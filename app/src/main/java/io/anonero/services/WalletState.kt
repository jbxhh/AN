package io.anonero.services

import android.content.SharedPreferences
import androidx.core.content.edit
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SyncProgress(val progress: Float, val left: Long)

private const val TAG = "WalletState"

class WalletState(private val prefs: SharedPreferences) {
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
    private val _scanTracking = AtomicBoolean(false)
    private val _scanStartHeight = AtomicLong(0L)
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
    // Wallet scan progress needs the live daemon target as soon as a scan starts.
    // Keep this request off the UI thread and avoid duplicate startup height RPCs.
    private val scanHeightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val daemonHeightQueryInFlight = AtomicBoolean(false)

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
                // Wallet files are already open at this point. Publish locally available
                // balance/address data without waiting for Tor, daemon connection, or scan.
                publishWalletSnapshot(wallet)
                _walletHeight.value = 0L
                _walletStatus.value = null
                _transactions.value = emptyList()
                _coins.value = emptyList()
                _connectedDaemon.update { current -> current?.copy(daemonHeight = 0L) }
                return@let
            }

            updateBlockHeights(wallet)
            _walletStatus.update { wallet.fullStatus }
            if (wallet.status.errorString.isNotEmpty()) {
                Timber.tag(TAG).i("StatusError %s", wallet.status.errorString)
            }

            // Publish wallet data continuously, not only after the full scan completes.
            // Monero's history becomes available incrementally as refresh callbacks arrive.
            val previousTransactions = _transactions.value
            val updatedTransactions = (wallet.history?.all?.sortedWith(comparator = { o1, o2 ->
                o2.timestamp.compareTo(o1.timestamp)
            }) ?: emptyList()).fastDistinctBy { it.getListKey() }
            _transactions.value = updatedTransactions

            // Do not fire incoming-transaction notifications for every historical output
            // discovered during initial restore/rescan. Notifications resume once synced.
            if (wallet.isSynchronized && previousTransactions.isNotEmpty()) {
                val previousKeys = previousTransactions.map { it.getListKey() }.toSet()
                val hasNewIncoming = updatedTransactions.any { transaction ->
                    transaction.getListKey() !in previousKeys &&
                        transaction.direction == TransactionInfo.Direction.Direction_In
                }
                if (hasNewIncoming) _incomingTx.tryEmit(Unit)
            }

            // Balance can change during restore and must remain visible while scanning.
            updateBalanceFromWallet(wallet)
            if (wallet.isSynchronized && !backgroundSync) {
                _coins.update { (wallet.coins?.all ?: emptyList()).fastFilter { !it.spent } }
            } else {
                _coins.value = emptyList()
            }

            if (!backgroundSync) {
                _nextAddress.update { wallet.getLatestSubAddress() }
                _subAddresses.update { wallet.getAllUsedSubAddresses().reversed() }
            }
        }
    }

    /**
     * Publish local wallet data independently from daemon startup and blockchain scanning.
     * The UI should not need a successful Tor/RPC connection just to show an address.
     */
    fun publishWalletSnapshot(wallet: Wallet? = getWallet) {
        if (wallet == null) return

        try {
            updateBalanceFromWallet(wallet)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to read local wallet balance during startup")
        }

        var address = try {
            if (wallet.isInitialized) {
                wallet.getLatestSubAddress().takeIf { it.address.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to read the next receive subaddress during startup")
            null
        }

        // The primary address is derived directly from the opened wallet keys and is
        // a safe fallback when history/next-subaddress lookup isn't ready yet.
        if (address == null) {
            address = try {
                val accountIndex = wallet.getAccountIndex()
                wallet.getAddress(accountIndex).takeIf { it.isNotBlank() }?.let {
                    Subaddress(accountIndex, 0, it, "")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to read the primary receive address during startup")
                null
            }
        }

        if (address != null) {
            _nextAddress.value = address
            if (!wallet.isInitialized) {
                _subAddresses.value = listOf(address)
            }
        }

        if (wallet.isInitialized) {
            try {
                _subAddresses.value = wallet.getAllUsedSubAddresses().reversed()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to load the address label list during startup")
            }
        }
    }

    /**
     * Start tracking the real block-processing position. Monero's local chain
     * height can jump during fast refresh, so it must not be used as scan progress.
     */
    val currentWalletHeight: Long
        get() = _walletHeight.value

    /** Last known daemon tip; reading this never performs a blocking RPC. */
    val currentDaemonHeight: Long
        get() = _connectedDaemon.value?.daemonHeight?.coerceAtLeast(0L) ?: 0L

    fun beginWalletScan(
        wallet: Wallet? = getWallet,
        scanStartHeight: Long? = null,
        forceStartAtSelectedHeight: Boolean = false
    ): Boolean {
        if (wallet == null || !wallet.isInitialized) {
            _scanTracking.set(false)
            _walletHeight.value = 0L
            clearSyncProgress()
            return false
        }

        val restoreHeight = (scanStartHeight ?: wallet.getRestoreHeight()).coerceAtLeast(0L)
        // A forced scan from a user-selected restore height must not wait for
        // the native cached chain height before recording the selected scan start.
        val nativeHeight = if (forceStartAtSelectedHeight) 0L
            else wallet.getBlockChainHeight().takeIf { it > 1L } ?: 0L
        val inProgressKey = scanPreferenceKey(wallet, "in_progress")
        val completedKey = scanPreferenceKey(wallet, "completed")
        val startKey = scanPreferenceKey(wallet, "start_height")
        val processedKey = scanPreferenceKey(wallet, "processed_height")
        val wasInterrupted = prefs.getBoolean(inProgressKey, false)
        val hasCompletedScan = prefs.getBoolean(completedKey, false)
        val savedStartHeight = prefs.getLong(startKey, restoreHeight).coerceAtLeast(restoreHeight)
        val savedProcessedHeight = prefs.getLong(processedKey, -1L)

        // Existing installations have no trusted processed-height marker yet. If the
        // native hash-chain cache is already past the selected restore point, rebuild
        // once instead of trusting a fast-forwarded hash-cache height.
        val legacyNeedsRescan = !hasCompletedScan && !wasInterrupted &&
            nativeHeight > restoreHeight + 1L
        val requiresRescan = forceStartAtSelectedHeight || wasInterrupted || legacyNeedsRescan
        val initialHeight = when {
            forceStartAtSelectedHeight -> restoreHeight
            wasInterrupted -> savedStartHeight
            legacyNeedsRescan -> restoreHeight
            hasCompletedScan && savedProcessedHeight >= restoreHeight -> savedProcessedHeight
            else -> restoreHeight
        }
        // Progress must begin at the real scan position, never the native hash-cache tip.
        val actualScanStart = initialHeight

        _scanStartHeight.set(actualScanStart)
        // Listener-derived height is authoritative: getBlockChainHeight() can include
        // cached hashes for blocks whose wallet transactions have not yet been scanned.
        _scanTracking.set(true)
        wallet.isSynchronized = false
        _walletHeight.value = initialHeight
        prefs.edit {
            putBoolean(inProgressKey, true)
            putBoolean(completedKey, false)
            if (forceStartAtSelectedHeight || !wasInterrupted || !prefs.contains(startKey)) {
                putLong(startKey, actualScanStart)
            }
            putLong(processedKey, initialHeight)
        }
        // Start scanning immediately from the selected height. Never make the
        // start of a restore/rescan wait on a synchronous daemon-height RPC. Use the
        // latest already-published node tip for immediate remaining-blocks display;
        // refreshed()/newBlock callbacks will replace it with a live daemon snapshot.
        val daemonHeight = currentDaemonHeight
        updateBlockHeights(wallet, daemonHeight)
        if (daemonHeight > 0L) {
            updateSyncProgressFromWallet(wallet, daemonHeight)
        } else {
            // Keep the temporary refresh/loading indicator visible until the live
            // daemon tip arrives. Never invent a target or remaining-block count.
            setLoading(true)
        }
        // Always fetch a fresh target in parallel with the scan. When it returns,
        // switch from the temporary loading indicator to real remaining-blocks
        // progress using latest daemon height minus actual processed wallet height.
        requestLatestDaemonHeightAsync(wallet)
        return requiresRescan
    }

    private fun scanPreferenceKey(wallet: Wallet, suffix: String): String {
        val path = wallet.getPath()?.takeIf { it.isNotBlank() } ?: wallet.name
        val identity = path + "|" + wallet.address
        val hash = identity.fold(0) { acc, char -> 31 * acc + char.code }
        // v4 invalidates markers created before sequential scan-callback validation.
        // Only heights confirmed in order by transaction-processing callbacks are trusted.
        return "anonero_scan_v4_" + hash + "_" + suffix
    }

    /**
     * Consumes the one-based processed-block count emitted by the native JNI listener.
     * Fast-refresh hash-cache callbacks are suppressed in the Monero C++ build patch,
     * so this value advances only after native wallet block processing.
     */
    @Synchronized
    fun recordScannedBlockHeight(processedHeight: Long, wallet: Wallet? = getWallet) {
        if (wallet == null || !wallet.isInitialized || processedHeight <= 0L) return

        // Read the live node tip once per callback and use the same value throughout.
        val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
        updateBlockHeights(wallet, daemonHeight)
        if (_scanTracking.get() && processedHeight <= _scanStartHeight.get()) {
            updateSyncProgressFromWallet(wallet, daemonHeight)
            if (daemonHeight > 0L) setLoading(false)
            return
        }

        // The native callback means the wallet has processed this block. Never replace
        // this count with the daemon tip or the local hash-cache size.
        val currentHeight = _walletHeight.value.coerceAtLeast(0L)
        if (processedHeight <= currentHeight) {
            // Duplicate/out-of-order callbacks cannot roll progress backward or
            // imply any additional wallet scanning.
            updateSyncProgressFromWallet(wallet, daemonHeight)
            if (daemonHeight > 0L) setLoading(false)
            return
        }
        if (processedHeight != currentHeight + 1L) {
            Timber.tag(TAG).w(
                "Ignoring non-sequential scan callback: walletHeight=%d callbackHeight=%d daemonHeight=%d",
                currentHeight, processedHeight, daemonHeight
            )
            updateSyncProgressFromWallet(wallet, daemonHeight)
            if (daemonHeight > 0L) setLoading(false)
            return
        }

        _walletHeight.value = processedHeight
        prefs.edit {
            putLong(scanPreferenceKey(wallet, "processed_height"), processedHeight)
        }
        // Keep the existing home balance area populated from Monero's live wallet
        // balance as processed blocks add discovered outputs; this is not a UI change.
        updateBalanceFromWallet(wallet)
        updateSyncProgressFromWallet(wallet, daemonHeight)
        // Once the native listener has a valid node tip, switch away from the
        // indefinite refresh loader; WalletProgressIndicator prioritizes sync progress.
        if (daemonHeight > 0L) setLoading(false)
    }
    /**
     * Resolve the live daemon tip without holding up restore/rescan startup.
     * The wallet height is never copied from the daemon: progress is computed
     * from the listener-confirmed processed height against this real target.
     */
    private fun requestLatestDaemonHeightAsync(wallet: Wallet) {
        if (!daemonHeightQueryInFlight.compareAndSet(false, true)) return

        scanHeightScope.launch {
            try {
                if (!wallet.isInitialized) return@launch
                val latestDaemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (latestDaemonHeight > 0L && wallet.isInitialized) {
                    updateBlockHeights(wallet, latestDaemonHeight)
                    updateSyncProgressFromWallet(wallet, latestDaemonHeight)
                    // Once a real target exists, the UI should show the determinate
                    // sync bar (when blocks remain), not stay in the temporary loader.
                    setLoading(false)
                }
            } catch (e: Exception) {
                // Keep the temporary loader active; refreshed()/newBlock callbacks
                // continue trying to obtain a real daemon target.
                Timber.tag(TAG).w(e, "Unable to fetch live daemon height for scan progress")
            } finally {
                daemonHeightQueryInFlight.set(false)
            }
        }
    }

    private fun updateBalanceFromWallet(wallet: Wallet) {
        _balanceInfo.value = wallet.balance
        _unLockedBalance.value = if (AnonConfig.viewOnly) {
            wallet.viewOnlyBalance()
        } else {
            wallet.unlockedBalance
        }
    }

    fun completeWalletScan(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ) {
        if (wallet != null && wallet.isInitialized) {
            // Keep listener-based height tracking enabled after sync, and do not issue
            // another node-height RPC that might observe a different tip mid-callback.
            val daemonHeight = (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
            updateBlockHeights(wallet, daemonHeight)
            // Once caught up, the next ordinary refresh must measure progress from
            // this completed wallet height, not from the original restore height.
            _scanStartHeight.set(_walletHeight.value)
            prefs.edit {
                putBoolean(scanPreferenceKey(wallet, "in_progress"), false)
                putBoolean(scanPreferenceKey(wallet, "completed"), true)
                putLong(scanPreferenceKey(wallet, "processed_height"), _walletHeight.value)
                putLong(scanPreferenceKey(wallet, "start_height"), _walletHeight.value)
            }
        }
        clearSyncProgress()
    }
    /**
     * Publishes the active daemon height and (only outside a scan) the native
     * wallet height. During a scan, WalletListener callbacks are the source of truth.
     */
    fun updateBlockHeights(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ) {
        if (wallet == null || !wallet.isInitialized) {
            _walletHeight.value = 0L
            _connectedDaemon.update { current -> current?.copy(daemonHeight = 0L) }
            return
        }

        val daemonHeight = (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
        if (!_scanTracking.get()) {
            // Never populate the displayed wallet height from blockChainHeight():
            // Monero may append hashes during fast refresh before wallet transactions
            // for those blocks have been scanned. Resume from trusted app scan state
            // when available, otherwise begin at the wallet's configured restore height.
            val restoreHeight = wallet.getRestoreHeight().coerceAtLeast(0L)
            val savedProcessedHeight = prefs.getLong(
                scanPreferenceKey(wallet, "processed_height"),
                -1L
            )
            val baseline = if (savedProcessedHeight >= restoreHeight) {
                savedProcessedHeight
            } else {
                restoreHeight
            }
            _scanStartHeight.set(baseline)
            _walletHeight.value = baseline
            _scanTracking.set(true)
        }

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
     * Computes backlog from the actual processed scan height, not the local hash-chain size.
     */
    fun updateSyncProgressFromWallet(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ) {
        if (wallet == null || !wallet.isInitialized) {
            clearSyncProgress()
            return
        }
        // Only listener-confirmed processed height is valid scan progress.
        // Never substitute the native hash-chain cache height here.
        val chainHeight = _walletHeight.value.coerceAtLeast(0L)
        val daemonHeight = (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
        if (daemonHeight <= 0L) {
            // A transient zero from the RPC is not proof that the scan finished.
            // Preserve any active scan/progress until a valid node height arrives.
            if (!_scanTracking.get()) clearSyncProgress()
            return
        }

        val left = daemonHeight - chainHeight
        if (left < 0L) {
            // A restore point beyond the node tip is an invalid scan state, not sync success.
            wallet.isSynchronized = false
            clearSyncProgress()
            Timber.tag(TAG).w(
                "Wallet scan height %d exceeds daemon height %d",
                chainHeight, daemonHeight
            )
            return
        }
        if (left == 0L) {
            syncUpdate(SyncProgress(progress = 1f, left = 0L))
            return
        }

        wallet.isSynchronized = false
        // Progress is relative to this scan's start: restores begin at the selected
        // restore height; ordinary refreshes begin at the last processed wallet height.
        // The denominator is the latest live daemon height, so a moving node tip is tracked.
        val scanStartHeight = _scanStartHeight.get().coerceAtMost(daemonHeight)
        val scanRange = (daemonHeight - scanStartHeight).coerceAtLeast(1L)
        val scanned = (chainHeight - scanStartHeight).coerceAtLeast(0L)
        val progress = (scanned.toDouble() / scanRange.toDouble())
            .toFloat()
            .coerceIn(0f, 0.999f)
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
            // Do not make the progress UI disappear during a temporary daemon outage.
            if (!_scanTracking.get() || _syncProgress.value == null) clearSyncProgress()
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
        val done = syncProgress.left <= 0L
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

    suspend fun refresh() {
        val wallet = getWallet
        if (wallet == null || !wallet.isInitialized) {
            clearSyncProgress()
            setLoading(false)
            return
        }

        var connectionKnown = false
        var connected = false
        var daemonHeightKnown = false
        setLoading(true)
        try {
            // Native wallet/RPC calls run on IO so the main thread can render progress.
            withContext(Dispatchers.IO) {
                connected = wallet.fullStatus.connectionStatus ==
                    Wallet.ConnectionStatus.ConnectionStatus_Connected
                connectionKnown = true
                if (!connected) {
                    clearSyncProgress()
                    return@withContext
                }

                // Publish node height and remaining blocks from one snapshot before refresh.
                val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (daemonHeight > 0L) {
                    daemonHeightKnown = true
                    updateBlockHeights(wallet, daemonHeight)
                    updateSyncProgressFromWallet(wallet, daemonHeight)
                }

                wallet.startRefresh()
                wallet.refreshHistory()
                update()

                // The node tip may advance during refresh. Replace the target only when
                // the RPC returned a valid height; a temporary zero must not erase progress.
                val latestDaemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (latestDaemonHeight > 0L) {
                    daemonHeightKnown = true
                    updateBlockHeights(wallet, latestDaemonHeight)
                    updateSyncProgressFromWallet(wallet, latestDaemonHeight)
                }
            }
        } catch (e: Exception) {
            // Keep any progress already computed from a real node height. A history/RPC
            // exception after that point must not make the syncing bar disappear.
            Timber.tag(TAG).e(e, "Wallet refresh failed")
        } finally {
            when {
                connectionKnown && !connected -> setLoading(false)
                daemonHeightKnown -> setLoading(false)
                else -> setLoading(true)
            }
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
        _transactions.value = emptyList()
        _coins.value = emptyList()
        setLoading(true)
        beginWalletScan(
            wallet = wallet,
            scanStartHeight = wallet.getRestoreHeight(),
            forceStartAtSelectedHeight = true
        )
        // Publish the wallet's current balance immediately, then keep replacing it
        // with the live balance discovered by each processed-block callback.
        updateBalanceFromWallet(wallet)

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
