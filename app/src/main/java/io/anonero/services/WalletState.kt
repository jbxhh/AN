package io.anonero.services

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import io.anonero.AnonConfig
import io.anonero.model.CoinsInfo
import io.anonero.model.Subaddress
import io.anonero.model.TransactionInfo
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.DaemonInfo
import io.anonero.ui.util.getAllUsedSubAddresses
import io.anonero.ui.util.getLatestSubAddress
import io.anonero.ui.home.LockScreenShortCut
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SyncProgress(val progress: Float, val left: Long)

private const val TAG = "WalletState"

class WalletState {
    private var _blockUpdates = AtomicBoolean(false)
    val hideAmountsFlow = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private var _isSyncing = AtomicBoolean(false)
    private val _backgroundSync = MutableStateFlow(false)
    private val _incomingTx = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val _transactions = MutableStateFlow<List<TransactionInfo>>(listOf())
    private val _subAddresses = MutableStateFlow<List<Subaddress>>(listOf())
    private val _balanceInfo = MutableStateFlow<Long?>(null)
    private val _walletHeight = MutableStateFlow(0L)
    private val _unLockedBalance = MutableStateFlow<Long?>(null)
    private val _walletStatus = MutableStateFlow<Wallet.Status?>(null)
    private val _nextAddress = MutableStateFlow<Subaddress?>(null)
    private val _coins = MutableStateFlow<List<CoinsInfo>>(arrayListOf())
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    private val _connectedDaemon = MutableStateFlow<DaemonInfo?>(null)
    private val _connectionStatus = MutableStateFlow<Wallet.ConnectionStatus?>(null)
    private val _previousConnectionStatus = AtomicReference<Wallet.ConnectionStatus?>(null)
    private val _unlockShortcut = Channel<LockScreenShortCut>(capacity = 1)
    val unlockShortcut = _unlockShortcut.receiveAsFlow()
    private val bgSyncMutex = Mutex()

    val transactions: Flow<List<TransactionInfo>> = _transactions

    val balanceInfo: Flow<Long?> = _balanceInfo
    val walletHeight: Flow<Long> = _walletHeight
    val unLockedBalance: Flow<Long?> = _unLockedBalance
    val isLoading: Flow<Boolean> = _isLoading
    val isSyncing get():Boolean = _isSyncing.get()
    val backgroundSync get():Boolean = _backgroundSync.value
    val backgroundSyncFlow: Flow<Boolean> = _backgroundSync

    val walletStatus: Flow<Wallet.Status?> = _walletStatus
    val syncProgress: Flow<SyncProgress?> = _syncProgress

    val nextAddress: Flow<Subaddress?> = _nextAddress
    val coins: Flow<List<CoinsInfo>> = _coins
    val subAddresses: Flow<List<Subaddress>> = _subAddresses

    val walletConnectionStatus: Flow<Wallet.ConnectionStatus?> = _walletStatus.map {
        it?.connectionStatus
    }

    val daemonInfo: Flow<DaemonInfo?> = _connectedDaemon
    val connectionStatus: Flow<Wallet.ConnectionStatus?> = _connectionStatus
    val incomingTx = _incomingTx.asSharedFlow()

    fun update() {
        if (_blockUpdates.get()) return
        getWallet?.let { wallet ->
            if (!wallet.isInitialized) {
                _walletHeight.value = 0L
                _balanceInfo.value = null
                _unLockedBalance.value = null
                _walletStatus.value = null
                _transactions.value = emptyList()
                _nextAddress.value = null
                _subAddresses.value = emptyList()
                _coins.value = emptyList()
                _connectedDaemon.update { current -> current?.copy(daemonHeight = 0L) }
                return@let
            }

            updateBlockHeights(wallet)
            _walletStatus.update { wallet.fullStatus }
            if (wallet.status.errorString.isNotEmpty()) {
                Timber.tag(TAG).i("StatusError %s", wallet.status.errorString)
            }

            if (wallet.isSynchronized) {
                _balanceInfo.update { wallet.balance }
                _unLockedBalance.update {
                    if (AnonConfig.viewOnly) wallet.viewOnlyBalance() else wallet.unlockedBalance
                }

                val oldTxCount = _transactions.value.size
                val updatedTxs = (wallet.history?.all?.sortedWith(comparator = { o1, o2 ->
                    o2.timestamp.compareTo(o1.timestamp)
                }) ?: emptyList()).fastDistinctBy {
                    it.getListKey()
                }
                _transactions.update { updatedTxs }
                if (oldTxCount > 0 && updatedTxs.size > oldTxCount) {
                    val hasNewIncoming = updatedTxs.take(updatedTxs.size - oldTxCount).any {
                        it.direction == TransactionInfo.Direction.Direction_In
                    }
                    if (hasNewIncoming) {
                        _incomingTx.tryEmit(Unit)
                    }
                }
                if (!backgroundSync) {
                    _coins.update { (wallet.coins?.all ?: emptyList()).fastFilter { !it.spent } }
                }
            } else {
                // Do not present partial balances or history as final while rescanning.
                _balanceInfo.value = null
                _unLockedBalance.value = null
                _transactions.value = emptyList()
                _coins.value = emptyList()
            }

            if (!backgroundSync) {
                _nextAddress.update { wallet.getLatestSubAddress() }
                _subAddresses.update { wallet.getAllUsedSubAddresses().reversed() }
            }
        }
    }

    /**
     * Publishes both heights from the wallet's active daemon connection.
     * The UI and sync progress must not use unrelated/stale height snapshots.
     */
    fun updateBlockHeights(wallet: Wallet? = getWallet) {
        if (wallet == null || !wallet.isInitialized) {
            _walletHeight.value = 0L
            _connectedDaemon.update { current -> current?.copy(daemonHeight = 0L) }
            return
        }

        val chainHeight = wallet.getBlockChainHeight().takeIf { it > 1L } ?: 0L
        val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
        _walletHeight.update { chainHeight }

        val address = try {
            WalletManager.instance?.getDaemonAddress()
        } catch (_: Exception) {
            null
        }
        val status = wallet.fullStatus.connectionStatus
            ?: Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        if (address != null) {
            _connectedDaemon.update { DaemonInfo(address, status, daemonHeight) }
        } else {
            _connectedDaemon.update { current ->
                current?.copy(connectionStatus = status, daemonHeight = daemonHeight)
            }
        }
    }

    /**
     * Refreshes the sync indicator from the same heights shown in node settings.
     */
    fun updateSyncProgressFromWallet(wallet: Wallet? = getWallet) {
        if (wallet == null || !wallet.isInitialized) {
            clearSyncProgress()
            return
        }
        val chainHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        if (chainHeight < 0L || daemonHeight <= 0L) {
            clearSyncProgress()
            return
        }

        val left = (daemonHeight - chainHeight).coerceAtLeast(0L)
        if (left <= 0L) {
            syncUpdate(SyncProgress(progress = 1f, left = 0L))
            return
        }

        // The latest real wallet height is still behind the daemon.
        // Do not expose partial balance/history until refreshed() confirms completion.
        wallet.isSynchronized = false
        val targetHeight = wallet.getDaemonBlockChainTargetHeight()
        val progress = if (targetHeight > 0L) {
            (chainHeight.toDouble() / targetHeight.toDouble()).toFloat().coerceIn(0f, 0.999f)
        } else {
            0f
        }
        syncUpdate(SyncProgress(progress, left))
    }

    fun setLoading(b: Boolean) {
        this._isLoading.update { b }
    }

    fun emitUnlockShortcut(shortcut: LockScreenShortCut) {
        _unlockShortcut.trySend(shortcut)
    }
    
    suspend fun enterBackgroundSync(): Boolean = bgSyncMutex.withLock {
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized || backgroundSync) return false
        emitUnlockShortcut(LockScreenShortCut.HOME)
        _backgroundSync.value = true
        if (AnonConfig.viewOnly) return true
        _blockUpdates.set(true)
        try {
            if (wallet.startBackgroundSync()) {
                Timber.tag(TAG).i("Entered background sync")
                return true
            }
            _backgroundSync.value = false
            Timber.tag(TAG).e("startBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            _backgroundSync.value = false
            Timber.tag(TAG).e(e, "startBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
    }

    suspend fun exitBackgroundSync(pin: String): Boolean = bgSyncMutex.withLock {
        val wallet = getWallet ?: return false
        if (AnonConfig.viewOnly) {
            _backgroundSync.value = false
            update()
            return true
        }
        _blockUpdates.set(true)
        try {
            if (wallet.stopBackgroundSync(pin)) {
                _backgroundSync.value = false
                update()
                wallet.startRefresh()
                Timber.tag(TAG).i("Exited background sync")
                return true
            }
            Timber.tag(TAG).e("stopBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "stopBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
    }

    fun setConnectionStatus(status: Wallet.ConnectionStatus) {
        val previous = _previousConnectionStatus.getAndSet(status)
        _connectionStatus.update { status }
        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected) {
            clearSyncProgress()
            setLoading(false)
        }
        if (previous == Wallet.ConnectionStatus.ConnectionStatus_Disconnected &&
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            setLoading(true)
            getWallet?.startRefresh()
        }
    }

    fun updateDaemon(daemonInfo: DaemonInfo) {
        this._connectedDaemon.update { daemonInfo }
    }

    fun syncUpdate(syncProgress: SyncProgress) {
        val progress = syncProgress.progress
        val done = syncProgress.left <= 1L
        if (done) {
            _syncProgress.value = null
            _isSyncing.set(false)
        } else {
            val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 0.999f) else 0f
            _syncProgress.value = syncProgress.copy(progress = safeProgress)
            _isSyncing.set(true)
        }
    }

    fun clearSyncProgress() {
        _syncProgress.value = null
        _isSyncing.set(false)
    }

    fun toggleHideAmounts() {
        hideAmountsFlow.update { !it }
    }

    fun setBackGroundSync(startBackgroundSync: Boolean) {
        _backgroundSync.update { startBackgroundSync }
    }

    fun getNewAddress() {
        getWallet?.let {
            it.addSubaddress(it.getAccountIndex(), "Subaddress #${it.numSubAddresses}")
            it.store()
            it.getLatestSubAddress().let { subAddresses ->
                _nextAddress.update { subAddresses }
            }
            it.getAllUsedSubAddresses().let { allItems ->
                _subAddresses.update { allItems.reversed() }
            }
            update()
        }
    }

    fun setTransactionNote(note: String, transactionInfo: TransactionInfo) {
        getWallet?.let {
            it.setUserNote(transactionInfo.hash, note)
            it.store()
            it.refreshHistory()
            update()
        }
    }

    fun updateAddressLabel(label: String, addressIndex: Int) {
        getWallet?.let {
            it.setSubaddressLabel(addressIndex, label)
            it.store()
            it.refreshHistory()
            it.getAllUsedSubAddresses().let { allItems ->
                _subAddresses.update { allItems.reversed() }
            }
            it.getLatestSubAddress().let { subAddresses ->
                _nextAddress.update { subAddresses }
            }
        }
    }

    fun refresh() {
        val wallet = getWallet
        if (wallet == null || !wallet.isInitialized) {
            clearSyncProgress()
            setLoading(false)
            return
        }

        val connected = wallet.fullStatus.connectionStatus ==
            Wallet.ConnectionStatus.ConnectionStatus_Connected
        if (connected) {
            setLoading(true)
            wallet.startRefresh()
        } else {
            clearSyncProgress()
        }

        try {
            wallet.refreshHistory()
            update()
            if (connected) {
                updateBlockHeights(wallet)
                updateSyncProgressFromWallet(wallet)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet refresh failed")
            clearSyncProgress()
        } finally {
            setLoading(false)
        }
    }

    fun resyncBlockchain(): Result<Boolean> {
        val wallet = getWallet
        if (wallet == null || !wallet.isInitialized) {
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("Wallet is not initialized"))
        }
        if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("Please connect to daemon for resync"))
        }
        if (backgroundSync) {
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("Exit background sync before resetting the scan"))
        }

        // Existing balances and history belong to the pre-rescan scan and must not
        // be presented as current while the wallet rebuilds them from the chosen height.
        clearSyncProgress()
        _balanceInfo.value = null
        _unLockedBalance.value = null
        _transactions.value = emptyList()
        _coins.value = emptyList()
        setLoading(true)

        return try {
            wallet.rescanBlockchainAsync()
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet rescan failed to start")
            clearSyncProgress()
            setLoading(false)
            Result.failure(e)
        }
    }

    private val getWallet get() = WalletManager.instance?.wallet
}
