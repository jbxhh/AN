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
import io.anonero.ui.util.getLastUnusedIndex
import io.anonero.ui.home.LockScreenShortCut
import io.anonero.util.RESTORE_HEIGHT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
const val PREF_FORCE_FRESH_WALLET_SCAN = "anonero_force_fresh_wallet_scan"

class WalletState(private val prefs: SharedPreferences) {
    class ManualResyncReservation internal constructor(internal val id: Long)
    private data class ScanPreferenceIdentity(val wallet: Wallet, val value: String)

    // Resolve JNI-backed wallet identity once before refresh begins.
    private val scanPreferenceIdentity = AtomicReference<ScanPreferenceIdentity?>(null)
    // StateFlow snapshots belong to one open wallet handle. A failed/transient daemon
    // init must not make a still-open wallet look like a brand-new empty wallet.
    private val publishedWalletIdentity = AtomicReference<Wallet?>(null)
    private var _blockUpdates = AtomicBoolean(false)
    val hideAmountsFlow = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private var _isSyncing = AtomicBoolean(false)
    private val _backgroundSync = MutableStateFlow(false)
    // UI lock visibility is separate from the native background-sync operation state.
    // A valid PIN may dismiss the overlay while native shutdown/restart finishes on IO.
    private val _pinLockVisible = MutableStateFlow(false)
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
    private val _resyncErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val resyncErrors = _resyncErrors.asSharedFlow()
    private val _connectedDaemon = MutableStateFlow<DaemonInfo?>(null)
    // Cache the daemon's real target height once per refresh callback so block
    // callbacks can calculate progress without issuing an RPC for every block.
    private val _daemonTargetHeight = AtomicLong(0L)
    // Cache the latest real daemon block-count so native newBlock callbacks never
    // issue a blocking daemon-height JNI/RPC call from inside the refresh thread.
    private val _scanDaemonHeight = AtomicLong(0L)
    private val _connectionStatus = MutableStateFlow<Wallet.ConnectionStatus?>(null)
    private val _previousConnectionStatus = AtomicReference<Wallet.ConnectionStatus?>(null)
    private val _unlockShortcut = Channel<LockScreenShortCut>(capacity = 1)
    val unlockShortcut = _unlockShortcut.receiveAsFlow()
    private val bgSyncMutex = Mutex()
    private val subaddressMutex = Mutex()
    private val daemonHeightQueryMutex = Mutex()
    // Native operations already started before reset priority was raised must drain first.
    private val ordinaryWalletOperationsInFlight = AtomicLong(0L)
    private val walletWipeInProgress = AtomicBoolean(false)
    // Wallet scan progress needs the live daemon target as soon as a scan starts.
    // Keep this request off the UI thread and avoid duplicate startup height RPCs.
    private val scanHeightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val daemonHeightQueryInFlight = AtomicBoolean(false)
    private val forcedRestoreScanInFlight = AtomicBoolean(false)
    private val forcedRestoreScanStarted = AtomicBoolean(false)
    private val manualResyncInProgress = AtomicBoolean(false)
    private val refreshDeferredDuringResync = AtomicBoolean(false)
    private val manualResyncReservationSequence = AtomicLong(0L)
    private val activeManualResyncReservation =
        AtomicReference<ManualResyncReservation?>(null)
    private val manualResyncNativeWorkerStarted = AtomicBoolean(false)
    private val resetPriorityActive = AtomicBoolean(false)
    // Distinguishes a reset request that is still preparing native wallet state
    // from one that has actually published scan progress.
    private val manualResyncScanPrepared = AtomicBoolean(false)

    val transactions: Flow<List<TransactionInfo>> = _transactions

    val balanceInfo: Flow<Long?> = _balanceInfo
    val walletHeight: Flow<Long> = _walletHeight
    val unLockedBalance: Flow<Long?> = _unLockedBalance
    val isLoading: Flow<Boolean> = _isLoading
    val isSyncing get():Boolean = _isSyncing.get()
    val backgroundSync get():Boolean = _backgroundSync.value
    /** True from the user's reset click until scan completion; secure wipe overrides it. */
    val isResyncPriorityActive: Boolean
        get() = resetPriorityActive.get() && !walletWipeInProgress.get()
    val isWalletWipeInProgress: Boolean
        get() = walletWipeInProgress.get()
    /** True while a manual rescan request is being prepared/handed to native refresh. */
    val isManualResyncInProgress: Boolean
        get() = manualResyncInProgress.get()
    /**
     * A node switch must wait only for reset preflight, not for the whole scan.
     * The native rescan request is already queued when manualResyncScanPrepared
     * becomes true, so a switch can pause refresh and reconnect to another daemon
     * while startRefresh is still running. Once the worker exits, reset priority
     * remains retargetable until the real scan-completion callback releases it.
     */
    val canRetargetNodeDuringResync: Boolean
        get() = resetPriorityActive.get() &&
            (!manualResyncInProgress.get() || manualResyncScanPrepared.get()) &&
            !walletWipeInProgress.get()
    /** Scan start position cached from trusted app state; does not call JNI. */
    val currentScanStartHeight: Long
        get() = _scanStartHeight.get().coerceAtLeast(0L)
    /** True while listener-based scan tracking owns an unsynchronized wallet. */
    val isWalletScanActive: Boolean
        get() = _scanTracking.get() && getWallet?.isSynchronized != true

    // While the native worker prepares reset state, callback data is suppressed.
    // Once scan state is published, block/progress callbacks must be allowed through,
    // even if startRefresh() has not returned yet.
    private fun isManualResyncPreparing(): Boolean =
        manualResyncInProgress.get() && !manualResyncScanPrepared.get()
    val backgroundSyncFlow: Flow<Boolean> = _backgroundSync
    val pinLockVisibleFlow: Flow<Boolean> = _pinLockVisible
    val pinLockVisible get() = _pinLockVisible.value

    fun dismissPinLockScreen() {
        _pinLockVisible.value = false
    }

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

    /**
     * Drop cached UI data only when the actual open wallet object changes. A daemon
     * reconnect can temporarily flip isInitialized to false even though the same
     * local wallet file and its last-known balance/history are still valid to display.
     */
    @Synchronized
    private fun resetSnapshotForWalletChange(wallet: Wallet) {
        if (publishedWalletIdentity.get() === wallet) return
        publishedWalletIdentity.set(wallet)
        _transactions.value = emptyList()
        _subAddresses.value = emptyList()
        _balanceInfo.value = null
        _walletHeight.value = 0L
        _scanTracking.set(false)
        _scanStartHeight.set(0L)
        _unLockedBalance.value = null
        _walletStatus.value = null
        _nextAddress.value = null
        _coins.value = emptyList()
        _syncProgress.value = null
        _connectedDaemon.value = null
        _daemonTargetHeight.set(0L)
        _scanDaemonHeight.set(0L)
        _connectionStatus.value = null
        _previousConnectionStatus.set(null)
        _isLoading.value = false
        _isSyncing.set(false)
        _backgroundSync.value = false
        _pinLockVisible.value = false
        scanPreferenceIdentity.set(null)
    }

    @Synchronized
    fun update() {
        if (
            _blockUpdates.get() ||
            walletWipeInProgress.get() ||
            manualResyncInProgress.get() ||
            isResyncPriorityActive
        ) return
        getWallet?.let { wallet ->
            resetSnapshotForWalletChange(wallet)
            if (!wallet.isInitialized) {
                // An unsuccessful daemon init is not evidence that the local wallet lost
                // its history, balance, or last processed height. Publish local data only
                // if no snapshot exists yet; never replace known values with transient
                // zero/empty JNI results during a reconnect.
                if (_balanceInfo.value == null || _nextAddress.value == null) {
                    publishWalletSnapshot(wallet)
                }
                _walletStatus.value = null
                _coins.value = emptyList()
                _connectionStatus.value =
                    Wallet.ConnectionStatus.ConnectionStatus_Disconnected
                _connectedDaemon.update { current ->
                    current?.copy(
                        connectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected
                    )
                }
                return@let
            }

            updateBlockHeights(wallet)
            _walletStatus.update { wallet.fullStatus }
            if (wallet.status.errorString.isNotEmpty()) {
                Timber.tag(TAG).i("StatusError %s", wallet.status.errorString)
            }
            // A refresh callback may already have entered update() before reset began.
            // Do not let that in-flight callback republish stale transactions after reset.
            if (manualResyncInProgress.get()) return@let

            // Publish wallet data continuously, not only after the full scan completes.
            // Monero's history becomes available incrementally as refresh callbacks arrive.
            val previousTransactions = _transactions.value
            val updatedTransactions = (wallet.history?.all?.sortedWith(comparator = { o1, o2 ->
                o2.timestamp.compareTo(o1.timestamp)
            }) ?: emptyList()).fastDistinctBy { it.getListKey() }
            // Native history can briefly report an empty list while the final refresh
            // batch is unwinding. Reset/wipe and wallet-switch paths clear state explicitly,
            // so an empty snapshot for the same open wallet is not enough to erase data
            // that was just shown to the user.
            val preservePreviousSnapshot =
                updatedTransactions.isEmpty() && previousTransactions.isNotEmpty()
            val publishedTransactions =
                if (preservePreviousSnapshot) previousTransactions else updatedTransactions
            if (preservePreviousSnapshot) {
                Timber.tag(TAG).w(
                    "Ignoring transient empty history snapshot; preserving %d known transactions",
                    previousTransactions.size
                )
            } else {
                _transactions.value = updatedTransactions
            }

            // Remember the earliest confirmed transaction we've ever seen for this wallet.
            // A rescan clears Monero's cached transfers; this durable marker lets us reject
            // future restore heights that would skip known historical transactions.
            val earliestTransactionHeight = publishedTransactions.asSequence()
                .filter { !it.isPending && it.blockheight > 0L }
                .minOfOrNull { it.blockheight }
            if (earliestTransactionHeight != null) {
                val earliestHeightKey = scanPreferenceKey(wallet, "earliest_transaction_height")
                val savedEarliestHeight = prefs.getLong(earliestHeightKey, -1L)
                if (savedEarliestHeight < 0L || earliestTransactionHeight < savedEarliestHeight) {
                    prefs.edit(commit = true) {
                        putLong(earliestHeightKey, earliestTransactionHeight)
                    }
                    Timber.tag(TAG).i(
                        "Recorded earliest known transaction height=%d",
                        earliestTransactionHeight
                    )
                }
            }

            // Do not fire incoming-transaction notifications for every historical output
            // discovered during initial restore/rescan. Notifications resume once synced.
            if (wallet.isSynchronized && previousTransactions.isNotEmpty()) {
                val previousKeys = previousTransactions.map { it.getListKey() }.toSet()
                val hasNewIncoming = publishedTransactions.any { transaction ->
                    transaction.getListKey() !in previousKeys &&
                        transaction.direction == TransactionInfo.Direction.Direction_In
                }
                if (hasNewIncoming) _incomingTx.tryEmit(Unit)
            }

            // An empty history snapshot can coincide with a transient zero balance at
            // scan completion. Keep the last-known balance alongside the retained history;
            // explicit reset/wipe and wallet replacement clear the snapshot separately.
            if (!preservePreviousSnapshot) {
                updateBalanceFromWallet(wallet)
            }
            val outputInventoryAvailable = wallet.isSynchronized ||
                (
                    currentConnectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected &&
                        !_isLoading.value &&
                        !_isSyncing.get() &&
                        _syncProgress.value == null
                )
            if (!backgroundSync && outputInventoryAvailable) {
                // The output list is an inventory, not just a spendable-input picker.
                // Keep spent and frozen outputs in state so the UI can display their
                // status; selection logic already prevents spending them.
                val outputSnapshot = wallet.coins?.all ?: emptyList()
                _coins.value = outputSnapshot
                Timber.tag(TAG).i(
                    "Output snapshot: total=%d unspent=%d spent=%d frozen=%d synchronized=%s",
                    outputSnapshot.size,
                    outputSnapshot.count { !it.spent },
                    outputSnapshot.count { it.spent },
                    outputSnapshot.count { it.frozen },
                    wallet.isSynchronized
                )
            } else {
                // Never expose selectable outputs while a scan, reset, or wallet
                // initialization is active. The idle Outputs-screen refresh will
                // publish a fresh native inventory after those tasks finish.
                _coins.value = emptyList()
            }

            if (!backgroundSync) {
                _nextAddress.update { wallet.getLatestSubAddress() }
                _subAddresses.update { wallet.getAllUsedSubAddresses().reversed() }
            }

            // Do not start a forced blockchain rescan from generic state publication.
            // Restore scans are started by the wallet startup/connection lifecycle,
            // not by manual refreshes or unrelated balance/history updates.
        }
    }


    /**
     * Refresh and publish outputs when the wallet is connected and idle. This is a
     * dedicated inventory read for the Outputs screen: do not require the synchronized
     * flag, because balance/history may be available even when that flag is stale.
     * Never call this during scan progress, loading, background sync, resync, or wipe.
     */
    @Synchronized
    fun refreshOutputInventoryIfIdle(): List<CoinsInfo>? {
        val wallet = getWallet ?: return null
        if (!wallet.isInitialized) return null

        // Hard-priority operations always block output enumeration. The wallet's
        // synchronized flag is the authoritative scan-completion signal: beginWalletScan
        // clears it before scanning and completeWalletScan sets it only after the final
        // height checks pass. UI loading/progress flows can lag behind that transition,
        // so they must not keep the Outputs screen empty after a successful sync.
        if (
            _blockUpdates.get() ||
            walletWipeInProgress.get() ||
            manualResyncInProgress.get() ||
            isResyncPriorityActive ||
            backgroundSync
        ) return null

        val scanStillActive = !wallet.isSynchronized && (
            _isLoading.value ||
                _isSyncing.get() ||
                _syncProgress.value != null
            )
        if (scanStillActive || !beginOrdinaryWalletOperation()) return null

        return try {
            if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                return null
            }
            wallet.refreshCoins(allowUnsynchronized = !wallet.isSynchronized)
            val outputs = wallet.coins?.all ?: emptyList()
            _coins.value = outputs
            Timber.tag(TAG).i(
                "Output inventory refresh: total=%d unspent=%d spent=%d balance=%d synchronized=%s",
                outputs.size,
                outputs.count { !it.spent },
                outputs.count { it.spent },
                wallet.balance,
                wallet.isSynchronized
            )
            outputs
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to refresh output inventory while wallet is idle")
            null
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    fun getAddressForCoin(txHash: String?): String? {
        if (txHash.isNullOrEmpty() || walletWipeInProgress.get() || isResyncPriorityActive) return null
        val tx = _transactions.value.firstOrNull { it.hash == txHash } ?: return null
        if (!beginOrdinaryWalletOperation()) return null
        return try {
            getWallet?.getSubaddress(tx.accountIndex, tx.addressIndex)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to resolve address for output transaction")
            null
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    fun freezeCoin(publicKey: String): Result<Boolean> =
        setCoinFrozen(publicKey, frozen = true)

    fun thawCoin(publicKey: String): Result<Boolean> =
        setCoinFrozen(publicKey, frozen = false)

    /**
     * Output freeze state is local wallet data, not a blockchain mutation. Allow it
     * during an active scan once reset setup has handed off to the native scanner.
     * Wallet.setOutputFrozen() serializes the short mutation with native refresh.
     */
    private fun setCoinFrozen(publicKey: String, frozen: Boolean): Result<Boolean> {
        if (!beginCoinControlOperation()) {
            return Result.failure(
                Exception("钱包正在安全删除或区块重置准备中，请稍后再修改输出")
            )
        }
        return try {
            val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
            check(wallet.isInitialized) { "钱包尚未初始化" }
            val coins = wallet.coins ?: throw IllegalStateException("输出列表尚未初始化")
            val currentCoin = coins.all.firstOrNull {
                it.pub_key.equals(publicKey, ignoreCase = true)
            } ?: throw IllegalStateException("当前钱包输出列表中找不到该输出，请等待同步更新后重试")
            check(!frozen || !currentCoin.spent) { "已花费的输出不能冻结" }
            check(wallet.setOutputFrozen(publicKey, frozen)) {
                wallet.status.errorString.takeIf { it.isNotBlank() }
                    ?: if (frozen) "原生钱包未能冻结该输出" else "原生钱包未能解冻该输出"
            }

            coins.refresh()
            val refreshed = coins.all
            val changedCoin = refreshed.firstOrNull { it.pub_key.equals(publicKey, ignoreCase = true) }
                ?: throw IllegalStateException("当前钱包输出列表中找不到该输出，请等待同步更新后重试")
            check(changedCoin.frozen == frozen) {
                if (frozen) "钱包未确认输出已冻结" else "钱包未确认输出已解冻"
            }

            publishCoinControlSnapshot(refreshed)
            Timber.tag(TAG).i(
                "Output freeze state updated: frozen=%s outputIndex=%d",
                frozen,
                changedCoin.globalOutputIndex
            )
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unable to change output frozen state to %s", frozen)
            Result.failure(e)
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    /**
     * Do not republish a selectable output inventory during a scan. If this detail
     * screen was already open, update only rows that were already visible so its
     * current output can show the new frozen state without exposing new spend inputs.
     */
    private fun publishCoinControlSnapshot(refreshed: List<CoinsInfo>) {
        val scanOwnsInventory = isResyncPriorityActive ||
            manualResyncInProgress.get() ||
            _isLoading.value ||
            _isSyncing.get() ||
            _syncProgress.value != null ||
            backgroundSync

        if (!scanOwnsInventory) {
            _coins.value = refreshed
            return
        }

        val byPublicKey = refreshed.associateBy { it.pub_key.lowercase() }
        _coins.update { current ->
            if (current.isEmpty()) current
            else current.map { old -> byPublicKey[old.pub_key.lowercase()] ?: old }
        }
    }

    /**
     * SharedPreferences is written by the seed-restore flow before its temporary
     * wallet is closed. Treat that selected value as authoritative across native init.
     */
    fun pendingSelectedRestoreHeight(wallet: Wallet? = getWallet): Long? {
        val currentWallet = wallet ?: return null
        if (!prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) return null
        // SharedPreferences evaluates default arguments eagerly; avoid an unnecessary JNI call.
        val selectedHeight = if (prefs.contains(RESTORE_HEIGHT)) {
            prefs.getLong(RESTORE_HEIGHT, 0L)
        } else {
            currentWallet.getRestoreHeight()
        }
        return selectedHeight.coerceAtLeast(0L)
    }

    /**
     * Monero's native init can replace the refresh start with the daemon tip when the
     * wallet cache only contains genesis. Reapply the selected height after every init.
     */
    @Synchronized
    fun preservePendingRestoreHeightAfterInit(wallet: Wallet): Long? {
        if (walletWipeInProgress.get()) return null
        val selectedHeight = pendingSelectedRestoreHeight(wallet) ?: return null
        wallet.setRestoreHeight(selectedHeight)
        val actualHeight = wallet.getRestoreHeight().coerceAtLeast(0L)
        check(actualHeight == selectedHeight) {
            "Selected restore height was not preserved: expected $selectedHeight, got $actualHeight"
        }
        check(wallet.store()) { "Unable to persist the selected restore height" }
        _scanStartHeight.set(selectedHeight)
        _walletHeight.value = selectedHeight
        _scanTracking.set(true)
        return selectedHeight
    }

    /**
     * Starts the pending first scan for a wallet restored from a seed.
     *
     * The onboarding screen persists the selected restore height before closing the
     * temporary wallet object. On the next wallet open, force a native blockchain
     * rescan from that height instead of inheriting a previous scan marker/hash cache.
     * Queue the rescan as soon as the native wallet connection is usable. The native
     * patch preserves pending rescan requests while the daemon is still syncing, so we
     * must not run an ordinary refresh to completion and then start a second full pass.
     */
    fun maybeStartForcedRestoreScan(wallet: Wallet) {
        if (walletWipeInProgress.get() || manualResyncInProgress.get() || isResyncPriorityActive) return
        if (!prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) return
        if (forcedRestoreScanStarted.get()) return
        if (!forcedRestoreScanInFlight.compareAndSet(false, true)) return

        scanHeightScope.launch {
            var refreshPaused = false
            try {
                daemonHeightQueryMutex.withLock {
                    // A wipe closes the gate first, then waits for this critical section
                    // before releasing the native wallet handle.
                    if (walletWipeInProgress.get() || getWallet !== wallet || !wallet.isInitialized) {
                        return@withLock
                    }
                    if (wallet.fullStatus.connectionStatus !=
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) return@withLock

                    var daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                    var daemonTargetHeight = wallet.getDaemonBlockChainTargetHeight().coerceAtLeast(0L)
                    if (daemonHeight <= 1L) {
                        Timber.tag(TAG).i(
                            "Restore scan pending until daemon exposes a usable chain tip: height=%d target=%d",
                            daemonHeight, daemonTargetHeight
                        )
                        return@withLock
                    }
                    // Queue the real scan now even if the remote daemon is still syncing.
                    // The native API patch retains m_refreshShouldRescan until its daemon
                    // reaches target height; delaying this handoff made the preceding normal
                    // refresh look complete and then visibly restarted from the restore point.
                    if (daemonTargetHeight > daemonHeight) {
                        Timber.tag(TAG).i(
                            "Queueing restore rescan while daemon catches up: height=%d target=%d",
                            daemonHeight, daemonTargetHeight
                        )
                    }

                    // The regular refresh loop was started so connection state can settle.
                    // Drain any active native refresh before changing restore height/cache.
                    wallet.pauseRefreshAndWait()
                    refreshPaused = true
                    if (walletWipeInProgress.get() || getWallet !== wallet || !wallet.isInitialized) {
                        return@withLock
                    }
                    if (wallet.fullStatus.connectionStatus !=
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        Timber.tag(TAG).i("Restore scan deferred because native daemon disconnected while pausing")
                        return@withLock
                    }

                    // Refresh may have been active while pauseRefreshAndWait was draining it.
                    // Recheck connectivity and a usable tip, but do not wait for the daemon
                    // target: native code keeps the asynchronous rescan request pending.
                    daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                    daemonTargetHeight = wallet.getDaemonBlockChainTargetHeight().coerceAtLeast(0L)
                    if (daemonHeight <= 1L) {
                        Timber.tag(TAG).i(
                            "Restore scan deferred until daemon exposes a usable chain tip: height=%d target=%d",
                            daemonHeight, daemonTargetHeight
                        )
                        return@withLock
                    }

                    // The saved restore height, not the native cache tip, is authoritative.
                    val selectedRestoreHeight = pendingSelectedRestoreHeight(wallet)
                        ?: return@withLock
                    wallet.setRestoreHeight(selectedRestoreHeight)
                    check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedRestoreHeight) {
                        "Selected restore height changed before rescan"
                    }
                    check(wallet.store()) { "Unable to persist selected restore height before rescan" }

                    clearSyncProgress()
                    _transactions.value = emptyList()
                    _coins.value = emptyList()
                    setLoading(true)

                    beginWalletScan(
                        wallet = wallet,
                        scanStartHeight = selectedRestoreHeight,
                        forceStartAtSelectedHeight = true
                    )
                    updateBalanceFromWallet(wallet)

                    // Do not queue/resume native scanning after secure wipe has closed the gate.
                    if (walletWipeInProgress.get() || getWallet !== wallet) return@withLock
                    wallet.rescanBlockchainAsync()
                    forcedRestoreScanStarted.set(true)
                    wallet.startRefresh()
                    refreshPaused = false
                    Timber.tag(TAG).i(
                        "Started restored-wallet rescan from selected height %d",
                        selectedRestoreHeight
                    )
                }
            } catch (e: Exception) {
                // Keep the preference set so a later refresh/reconnect can retry, except
                // while secure wipe is deliberately shutting down the wallet.
                Timber.tag(TAG).e(e, "Unable to start restored-wallet scan")
                if (!walletWipeInProgress.get()) {
                    clearSyncProgress()
                    setLoading(false)
                }
            } finally {
                // Never leave normal refresh disabled if a reset/wipe or disconnect raced
                // the forced-restore handoff. A secure wipe owns the wallet close instead.
                if (refreshPaused && !walletWipeInProgress.get() &&
                    getWallet === wallet && wallet.isInitialized
                ) {
                    runCatching { wallet.startRefresh() }
                        .onFailure { Timber.tag(TAG).e(it, "Unable to resume refresh after deferred restore scan") }
                }
                forcedRestoreScanInFlight.set(false)
            }
        }
    }

    /**
     * Publish local wallet data independently from daemon startup and blockchain scanning.
     * The UI should not need a successful Tor/RPC connection just to show an address.
     */
    @Synchronized
    fun publishWalletSnapshot(wallet: Wallet? = getWallet) {
        if (walletWipeInProgress.get() || wallet == null) return
        resetSnapshotForWalletChange(wallet)
        prepareScanPreferenceIdentity(wallet)

        try {
            if (wallet.isInitialized ||
                (_balanceInfo.value == null && _unLockedBalance.value == null)
            ) {
                // On a brand-new wallet, publish the local balance before Tor/daemon init.
                // For the same wallet handle, do not let a transient failed init replace a
                // previously known balance with a zero JNI snapshot.
                updateBalanceFromWallet(wallet)
            } else {
                Timber.tag(TAG).d("Preserving last-known balance while wallet init is unavailable")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to read local wallet balance during startup")
        }

        var address = try {
            // Subaddresses are derived locally from the wallet keys; a daemon connection
            // is not required to read an already-generated receive subaddress.
            wallet.getLatestSubAddress().takeIf {
                it.addressIndex > 0 && it.address.isNotBlank()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to read the next receive subaddress during startup")
            null
        }

        // Keep a known subaddress if the wallet is temporarily not initialized. Never
        // replace it with the primary address during a disconnected-wallet state update.
        if (address == null) {
            address = _nextAddress.value?.takeIf {
                it.addressIndex > 0 && it.address.isNotBlank()
            }
        }

        // The primary address remains a local snapshot fallback for other wallet UI,
        // but the receive screen deliberately refuses to display index 0.
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
                _subAddresses.value = (_subAddresses.value + address)
                    .distinctBy { it.address }
                    .sortedByDescending { it.addressIndex }
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
        get() = _scanDaemonHeight.get().coerceAtLeast(0L)

    /**
     * Reserve reset priority immediately when the user presses Reset, before the node
     * height query. This makes normal refresh, history reads, and reconnects yield.
     */
    fun reserveManualResyncRequest(): ManualResyncReservation? {
        if (walletWipeInProgress.get() || resetPriorityActive.get()) return null
        if (!manualResyncInProgress.compareAndSet(false, true)) return null

        val reservation = ManualResyncReservation(manualResyncReservationSequence.incrementAndGet())
        if (!activeManualResyncReservation.compareAndSet(null, reservation)) {
            manualResyncInProgress.set(false)
            return null
        }
        resetPriorityActive.set(true)
        if (walletWipeInProgress.get()) {
            cancelManualResyncRequest(reservation)
            return null
        }
        return reservation
    }

    private fun beginOrdinaryWalletOperation(): Boolean {
        if (walletWipeInProgress.get() || isResyncPriorityActive) return false
        ordinaryWalletOperationsInFlight.incrementAndGet()
        if (walletWipeInProgress.get() || isResyncPriorityActive) {
            endOrdinaryWalletOperation()
            return false
        }
        return true
    }

    /**
     * Receive-address creation is lower priority than preparing a reset, but it need
     * not wait for the entire rescan to finish. Reject it while reset setup owns the
     * native wallet; once the async rescan has been queued, permit this short native
     * call during scanning. It still participates in the in-flight counter so secure
     * wallet deletion waits before closing the native handle.
     */
    private fun beginReceiveAddressOperation(): Boolean {
        if (walletWipeInProgress.get() || manualResyncInProgress.get()) return false
        ordinaryWalletOperationsInFlight.incrementAndGet()
        if (walletWipeInProgress.get() || manualResyncInProgress.get()) {
            endOrdinaryWalletOperation()
            return false
        }
        return true
    }

    /**
     * Freeze/thaw uses the same short native refresh lock as address creation.
     * Secure wipe and reset preparation retain priority; a queued scan does not block it.
     */
    private fun beginCoinControlOperation(): Boolean {
        if (walletWipeInProgress.get() || manualResyncInProgress.get()) return false
        ordinaryWalletOperationsInFlight.incrementAndGet()
        if (walletWipeInProgress.get() || manualResyncInProgress.get()) {
            endOrdinaryWalletOperation()
            return false
        }
        return true
    }

    private fun endOrdinaryWalletOperation() {
        ordinaryWalletOperationsInFlight.updateAndGet { current -> (current - 1L).coerceAtLeast(0L) }
    }

    /** Reset waits for finite native calls that had already started. */
    suspend fun awaitOrdinaryWalletOperationsIdle() {
        while (ordinaryWalletOperationsInFlight.get() > 0L) {
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            delay(25L)
        }
    }

    suspend fun awaitBackgroundSyncOperationIdle() {
        bgSyncMutex.withLock { }
    }

    /** Monitor barrier: wait for any callback already inside a synchronized state method. */
    @Synchronized
    fun awaitWalletStateCallbacksIdle() = Unit

    /** Release a preflight reservation when the node query or validation fails. */
    fun cancelManualResyncRequest(reservation: ManualResyncReservation) {
        if (manualResyncNativeWorkerStarted.get()) return
        if (activeManualResyncReservation.compareAndSet(reservation, null)) {
            resetPriorityActive.set(false)
            manualResyncScanPrepared.set(false)
            manualResyncInProgress.set(false)
            scheduleDeferredRefreshIfIdle()
        }
    }

    /** Secure deletion is the only operation above reset and can cancel its preflight. */
    @Synchronized
    fun beginWalletWipe(): Boolean {
        if (!walletWipeInProgress.compareAndSet(false, true)) return false
        if (!manualResyncNativeWorkerStarted.get()) {
            activeManualResyncReservation.set(null)
            resetPriorityActive.set(false)
            manualResyncScanPrepared.set(false)
            manualResyncInProgress.set(false)
            clearSyncProgress()
            setLoading(false)
        }
        return true
    }

    @Synchronized
    fun endWalletWipe() {
        walletWipeInProgress.set(false)
    }

    /** Wait until any daemon-height JNI/RPC query started by scan progress has left its critical section. */
    suspend fun awaitPendingNativeQueries() {
        daemonHeightQueryMutex.withLock { }
        bgSyncMutex.withLock { }
        while (ordinaryWalletOperationsInFlight.get() > 0L) {
            delay(25L)
        }
        // Secure wipe has priority over manual resync. It closes the native wallet,
        // so wait until an already-running reset has observed the wipe gate and exited.
        while (manualResyncInProgress.get()) {
            delay(25L)
        }
    }

    /** Cached state only; reading this never calls through JNI or performs an RPC. */
    val currentConnectionStatus: Wallet.ConnectionStatus?
        get() = _walletStatus.value?.connectionStatus ?: _connectionStatus.value

    @Synchronized
    fun beginWalletScan(
        wallet: Wallet? = getWallet,
        scanStartHeight: Long? = null,
        forceStartAtSelectedHeight: Boolean = false
    ): Boolean {
        if (walletWipeInProgress.get()) return false
        if (wallet == null || !wallet.isInitialized) {
            _scanTracking.set(false)
            _walletHeight.value = 0L
            clearSyncProgress()
            return false
        }
        resetSnapshotForWalletChange(wallet)

        val restoreHeight = (scanStartHeight ?: wallet.getRestoreHeight()).coerceAtLeast(0L)
        // A forced scan from a user-selected restore height must not wait for
        // the native cached chain height before recording the selected scan start.
        val nativeHeight = if (forceStartAtSelectedHeight) 0L
            else wallet.getBlockChainHeight().takeIf { it > 1L } ?: 0L
        val inProgressKey = scanPreferenceKey(wallet, "in_progress")
        val completedKey = scanPreferenceKey(wallet, "completed")
        val startKey = scanPreferenceKey(wallet, "start_height")
        val processedKey = scanPreferenceKey(wallet, "processed_height")
        // Older builds persisted the native callback's zero-based block index while the
        // progress model compares against the daemon's one-based block count. Migrate
        // that stored value once so a previously completed wallet is not left one block
        // behind after the callback height is normalized.
        val heightCountSemanticsKey = scanPreferenceKey(wallet, "height_count_semantics_v1")
        if (!prefs.getBoolean(heightCountSemanticsKey, false)) {
            val legacyProcessedHeight = prefs.getLong(processedKey, -1L)
            prefs.edit(commit = true) {
                if (legacyProcessedHeight >= 0L) {
                    putLong(processedKey, legacyProcessedHeight + 1L)
                }
                putBoolean(heightCountSemanticsKey, true)
            }
        }
        val wasInterrupted = prefs.getBoolean(inProgressKey, false)
        val hasCompletedScan = prefs.getBoolean(completedKey, false)
        val savedStartHeight = prefs.getLong(startKey, restoreHeight).coerceAtLeast(restoreHeight)
        val savedProcessedHeight = prefs.getLong(processedKey, -1L)
        val hasTrustedCompletedScan =
            hasCompletedScan && savedProcessedHeight >= restoreHeight

        // Only rebuild legacy data when there is no trusted completed scan. A wallet
        // that already completed a scan must continue from its confirmed processed
        // height; the app must not turn every new wallet-service startup into a new
        // "in progress" scan session.
        val legacyNeedsRescan = !hasTrustedCompletedScan && !wasInterrupted &&
            nativeHeight > restoreHeight + 1L
        val requiresRescan = forceStartAtSelectedHeight || wasInterrupted || legacyNeedsRescan
        val initialHeight = when {
            forceStartAtSelectedHeight -> restoreHeight
            wasInterrupted -> savedStartHeight
            legacyNeedsRescan -> restoreHeight
            hasTrustedCompletedScan -> savedProcessedHeight
            else -> restoreHeight
        }
        val resumeCompletedScan = hasTrustedCompletedScan &&
            !wasInterrupted && !forceStartAtSelectedHeight
        // Progress must begin at the real scan position, never the native hash-cache tip.
        val actualScanStart = initialHeight

        _scanStartHeight.set(actualScanStart)
        // Listener-derived height is authoritative: getBlockChainHeight() can include
        // cached hashes for blocks whose wallet transactions have not yet been scanned.
        _scanTracking.set(true)
        // Keep a previously completed scan marked synchronized when reopening the wallet.
        // refreshed() will clear this flag if the daemon has genuinely advanced beyond
        // the persisted processed height. Unconditionally clearing it here made every
        // app re-entry look like a new scan even when a trusted completion marker existed.
        wallet.isSynchronized = resumeCompletedScan
        _walletHeight.value = initialHeight
        prefs.edit {
            if (resumeCompletedScan) {
                // Preserve the successful-scan marker. A daemon refresh or service
                // restart must not make an already scanned wallet look interrupted.
                putBoolean(inProgressKey, false)
                putBoolean(completedKey, true)
                putLong(startKey, actualScanStart)
                putLong(processedKey, actualScanStart)
            } else {
                putBoolean(inProgressKey, true)
                putBoolean(completedKey, false)
                if (forceStartAtSelectedHeight || !wasInterrupted || !prefs.contains(startKey)) {
                    putLong(startKey, actualScanStart)
                }
                putLong(processedKey, initialHeight)
            }
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

    /**
     * Prepare scan markers for a manual reset without taking WalletState's monitor or
     * calling native height/balance APIs. Listener callbacks are gated until the native
     * rescan is queued, avoiding a lock cycle with update() during an active refresh.
     */
    private fun prepareManualResyncScanState(
        wallet: Wallet,
        selectedHeight: Long,
        progressDaemonHeight: Long,
        progressTargetHeight: Long
    ) {
        val height = selectedHeight.coerceAtLeast(0L)
        val inProgressKey = scanPreferenceKey(wallet, "in_progress")
        val completedKey = scanPreferenceKey(wallet, "completed")
        val startKey = scanPreferenceKey(wallet, "start_height")
        val processedKey = scanPreferenceKey(wallet, "processed_height")
        val heightCountSemanticsKey = scanPreferenceKey(wallet, "height_count_semantics_v1")

        prefs.edit {
            putBoolean(inProgressKey, true)
            putBoolean(completedKey, false)
            putLong(startKey, height)
            putLong(processedKey, height)
            putBoolean(heightCountSemanticsKey, true)
        }
        // The reset screen has already obtained these heights from a direct daemon RPC.
        // Seed them before reopening native refresh so the first newBlock callback can
        // update progress without waiting for a second RPC through the wallet JNI.
        _scanDaemonHeight.set(progressDaemonHeight.coerceAtLeast(0L))
        _daemonTargetHeight.set(progressTargetHeight.coerceAtLeast(0L))
        _connectedDaemon.update { current ->
            current?.copy(daemonHeight = progressDaemonHeight.coerceAtLeast(0L))
        }
        _scanStartHeight.set(height)
        _scanTracking.set(true)
        wallet.isSynchronized = false
        _walletHeight.value = height
        _transactions.value = emptyList()
        _coins.value = emptyList()

        // Keep the indeterminate loader during preparation. The remaining-block bar is
        // published only after runManualResyncWorker queues the native rescan and restarts
        // refresh, so determinate progress cannot precede scan startup.
    }

    fun prepareScanPreferenceIdentity(wallet: Wallet) {
        resetSnapshotForWalletChange(wallet)
        val current = scanPreferenceIdentity.get()
        if (current?.wallet === wallet) return
        // Read JNI-backed fields before refresh starts, never from the newBlock callback.
        val path = wallet.getPath()?.takeIf { it.isNotBlank() } ?: wallet.name
        scanPreferenceIdentity.set(ScanPreferenceIdentity(wallet, path + "|" + wallet.address))
    }

    private fun scanPreferenceKey(wallet: Wallet, suffix: String): String {
        val identity = scanPreferenceIdentity.get()
            ?.takeIf { it.wallet === wallet }
            ?.value
            ?: throw IllegalStateException("Scan preference identity was not prepared before native refresh started")
        val hash = identity.fold(0) { acc, char -> 31 * acc + char.code }
        // Keep the v4 key namespace; migrate stored callback heights as before.
        return "anonero_scan_v4_" + hash + "_" + suffix
    }

    /**
     * Consumes the one-based processed-block count emitted by the native JNI listener.
     * Fast-refresh hash-cache callbacks are suppressed in the Monero C++ build patch,
     * so this value advances only after native wallet block processing.
     */
    fun recordScannedBlockHeight(processedHeight: Long, wallet: Wallet? = getWallet) {
        if (walletWipeInProgress.get() || isManualResyncPreparing() ||
            wallet == null || !wallet.isInitialized || processedHeight <= 0L
        ) return

        // This is a native WalletListener callback. Do not call JNI/RPC height or
        // wallet-status APIs here: they can wait on the native refresh lock that is
        // currently invoking this callback, freezing the rescan at its first block.
        // refreshed() and the async daemon-height poll keep this cache current.
        val daemonHeight = currentDaemonHeight
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
        // Native refresh can coalesce callbacks or begin reporting after a batch of
        // blocks has already been processed. A forward callback still confirms that the
        // wallet processed that height; rejecting it leaves currentHeight frozen forever,
        // causing every later callback to be rejected as non-sequential too.
        if (processedHeight > currentHeight + 1L) {
            Timber.tag(TAG).w(
                "Scan callback skipped heights; advancing to confirmed processed height: walletHeight=%d callbackHeight=%d daemonHeight=%d",
                currentHeight, processedHeight, daemonHeight
            )
        }

        _walletHeight.value = processedHeight
        prefs.edit {
            putLong(scanPreferenceKey(wallet, "processed_height"), processedHeight)
        }
        // Keep this callback lightweight: wallet balance/history snapshots are already
        // published by updated() and the regular post-refresh path. Reading balance via
        // JNI here can contend with the native scan lock and stall subsequent block callbacks.
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
    /**
     * Re-check sync state whenever an already-open wallet is entered again.
     * This is intentionally asynchronous: reading live daemon heights must not block
     * navigation, but the loading indicator stays visible until the first real snapshot.
     */
    fun checkSyncProgressOnStartup(wallet: Wallet? = getWallet) {
        val currentWallet = wallet ?: return
        if (!currentWallet.isInitialized || !_scanTracking.get()) return
        if (_syncProgress.value == null) setLoading(true)
        requestLatestDaemonHeightAsync(currentWallet)
    }

    private fun requestLatestDaemonHeightAsync(wallet: Wallet) {
        if (!daemonHeightQueryInFlight.compareAndSet(false, true)) return

        scanHeightScope.launch {
            var attempts = 0
            var initialDaemonSnapshotResolved = false
            try {
                // Keep retrying while the restore/scan is active. One early RPC can
                // return zero while Tor or the daemon is still coming online; a one-shot
                // request leaves the UI stuck on the wrong side of the refresh/sync handoff.
                while (
                    !walletWipeInProgress.get() &&
                    getWallet === wallet &&
                    wallet.isInitialized &&
                    // Even a wallet previously marked synchronized must perform one fresh
                    // daemon-height check on entry. Otherwise blocks received while the app
                    // was closed are not detected until a later refreshed()/manual refresh.
                    (!wallet.isSynchronized || !initialDaemonSnapshotResolved) &&
                    !manualResyncInProgress.get() &&
                    (_scanTracking.get() || prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) &&
                    _syncProgress.value == null
                ) {
                    attempts++
                    try {
                        daemonHeightQueryMutex.withLock {
                            // Wipe waits for an in-flight query before native close. A queued
                            // query must never begin against a closed wallet handle.
                            if (walletWipeInProgress.get() ||
                                getWallet !== wallet ||
                                !wallet.isInitialized
                            ) {
                                return@withLock
                            }
                            // Don't hammer a disconnected daemon. Poll again after a short
                            // delay, retaining the loader until a real height can be read.
                            if (wallet.fullStatus.connectionStatus !=
                                Wallet.ConnectionStatus.ConnectionStatus_Connected
                            ) {
                                return@withLock
                            }

                            val latestDaemonHeight =
                                wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                            val latestTargetHeight =
                                wallet.getDaemonBlockChainTargetHeight().coerceAtLeast(0L)
                            if (walletWipeInProgress.get() ||
                                getWallet !== wallet ||
                                !wallet.isInitialized
                            ) {
                                return@withLock
                            }

                            updateDaemonTargetHeight(latestTargetHeight)
                            if (latestDaemonHeight > 0L) {
                                // Mark the startup snapshot resolved only after a real
                                // connected-daemon height is available. A disconnected wallet
                                // keeps retrying even if its persisted scan marker says complete.
                                initialDaemonSnapshotResolved = true
                                updateBlockHeights(wallet, latestDaemonHeight)
                                updateSyncProgressFromWallet(wallet, latestDaemonHeight)
                            }
                        }
                    } catch (e: Exception) {
                        // Avoid flooding the log if Tor/RPC is temporarily unavailable.
                        if (attempts == 1 || attempts % 10 == 0) {
                            Timber.tag(TAG).w(e, "Waiting for a live daemon height for sync progress")
                        }
                    }

                    if (_syncProgress.value != null) break
                    if (walletWipeInProgress.get() ||
                        getWallet !== wallet ||
                        !wallet.isInitialized ||
                        (wallet.isSynchronized && initialDaemonSnapshotResolved) ||
                        (!_scanTracking.get() &&
                            !prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false))
                    ) {
                        break
                    }

                    setLoading(true)
                    delay(1_000L)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Daemon-height sync progress polling stopped")
            } finally {
                daemonHeightQueryInFlight.set(false)
                if (!manualResyncInProgress.get()) {
                    val hasProgress = _syncProgress.value != null
                    val scanPending = _scanTracking.get() ||
                        prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
                    setLoading(
                        !hasProgress &&
                            scanPending &&
                            !walletWipeInProgress.get() &&
                            wallet.isInitialized &&
                            !wallet.isSynchronized
                    )
                }
            }
        }
    }

    @Synchronized
    private fun updateBalanceFromWallet(wallet: Wallet) {
        if (walletWipeInProgress.get()) return
        _balanceInfo.value = wallet.balance
        _unLockedBalance.value = if (AnonConfig.viewOnly) {
            wallet.viewOnlyBalance()
        } else {
            wallet.unlockedBalance
        }
    }

    @Synchronized
    fun completeWalletScan(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ): Boolean {
        if (walletWipeInProgress.get() || isManualResyncPreparing()) return false
        // A transient failed init/reconnect cannot complete a scan or clear progress.
        if (wallet != null && !wallet.isInitialized) return false
        if (wallet != null && wallet.isInitialized) {
            // A callback can report the daemon tip before the fresh restore's native
            // rescan is queued. Never treat that startup race as a completed wallet scan.
            val pendingRestoreHeight = pendingSelectedRestoreHeight(wallet)
            if (pendingRestoreHeight != null && !forcedRestoreScanStarted.get()) {
                val selectedHeight = pendingRestoreHeight.coerceAtLeast(0L)
                _scanStartHeight.set(selectedHeight)
                _walletHeight.value = selectedHeight
                _scanTracking.set(true)
                val daemonHeight =
                    (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
                updateSyncProgressFromWallet(wallet, daemonHeight)
                maybeStartForcedRestoreScan(wallet)
                return false
            }
            // Keep listener-based height tracking enabled after sync, and do not issue
            // another node-height RPC that might observe a different tip mid-callback.
            val daemonHeight =
                (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
            val targetHeight = maxOf(daemonHeight, _daemonTargetHeight.get())
            if (daemonHeight > 0L && targetHeight > daemonHeight) {
                // The wallet has caught up to the node's present tip, but the node
                // itself still has blocks left. Keep scanning instead of finalizing
                // and showing a second sync cycle when the daemon advances.
                wallet.isSynchronized = false
                updateSyncProgressFromWallet(wallet, daemonHeight)
                return false
            }
            // A native "refreshed" callback only means one refresh batch ended; it
            // does not prove that a manual rescan from the selected restore height has
            // reached the daemon tip. In particular, the first callback can race ahead
            // of the queued rescan and otherwise mark the scan complete at its start
            // height, clearing the already-known remaining-block progress forever.
            val processedHeight = _walletHeight.value.coerceAtLeast(0L)
            if (daemonHeight > 0L && processedHeight < daemonHeight) {
                wallet.isSynchronized = false
                updateSyncProgressFromWallet(wallet, daemonHeight)
                setLoading(false)
                Timber.tag(TAG).i(
                    "Keeping wallet scan active: processedHeight=%d daemonHeight=%d targetHeight=%d",
                    processedHeight, daemonHeight, targetHeight
                )
                return false
            }

            // A transient zero height cannot prove that an active scan completed.
            val scanPending = _syncProgress.value != null ||
                prefs.getBoolean(scanPreferenceKey(wallet, "in_progress"), false) ||
                prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
            if (daemonHeight <= 0L && scanPending) {
                wallet.isSynchronized = false
                setLoading(true)
                Timber.tag(TAG).w("Keeping wallet scan active because daemon height is unavailable")
                return false
            }

            updateBlockHeights(wallet, daemonHeight)
            // Once caught up, the next ordinary refresh must measure progress from
            // this completed wallet height, not from the original restore height.
            _scanStartHeight.set(_walletHeight.value)
            // Persist completion and the final processed height before clearing
            // the restore marker. Otherwise a fast service/process restart can read
            // stale in-progress state and queue the same rescan again.
            prefs.edit(commit = true) {
                putBoolean(scanPreferenceKey(wallet, "in_progress"), false)
                putBoolean(scanPreferenceKey(wallet, "completed"), true)
                putLong(scanPreferenceKey(wallet, "processed_height"), _walletHeight.value)
                putLong(scanPreferenceKey(wallet, "start_height"), _walletHeight.value)
                remove(PREF_FORCE_FRESH_WALLET_SCAN)
            }
            forcedRestoreScanStarted.set(false)
        }
        clearSyncProgress()
        if (resetPriorityActive.get()) {
            activeManualResyncReservation.set(null)
            resetPriorityActive.set(false)
        }
        scheduleDeferredRefreshIfIdle()
        return true
    }
    /**
     * Publishes the active daemon height and (only outside a scan) the native
     * wallet height. During a scan, WalletListener callbacks are the source of truth.
     */
    @Synchronized
    fun updateBlockHeights(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ) {
        if (walletWipeInProgress.get() || isManualResyncPreparing()) return
        if (wallet == null) {
            _walletHeight.value = 0L
            _connectedDaemon.update { current -> current?.copy(daemonHeight = 0L) }
            return
        }
        // Keep the last listener-confirmed height if daemon initialization temporarily
        // fails for the same wallet handle. A newly opened wallet was cleared by
        // resetSnapshotForWalletChange() before any height callback can reach here.
        if (!wallet.isInitialized) return

        val daemonHeight =
            (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
        _scanDaemonHeight.set(daemonHeight)
        if (!_scanTracking.get()) {
            // Never populate the displayed wallet height from blockChainHeight():
            // Monero may append hashes during fast refresh before wallet transactions
            // for those blocks have been scanned. Resume from trusted app scan state
            // when available, otherwise begin at the wallet's configured restore height.
            // While a seed restore is pending, native init may temporarily expose
            // the daemon tip as restore height. The restore form's persisted selection
            // remains authoritative and must be the initial wallet scan baseline.
            val pendingRestoreHeight = pendingSelectedRestoreHeight(wallet)
            val restoreHeight = (pendingRestoreHeight ?: wallet.getRestoreHeight()).coerceAtLeast(0L)
            val savedProcessedHeight = if (pendingRestoreHeight != null) {
                -1L
            } else {
                prefs.getLong(scanPreferenceKey(wallet, "processed_height"), -1L)
            }
            val baseline = if (pendingRestoreHeight != null) {
                restoreHeight
            } else if (savedProcessedHeight >= restoreHeight) {
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
        if (walletWipeInProgress.get() || isManualResyncPreparing()) return
        if (wallet == null) {
            clearSyncProgress()
            return
        }
        // A failed native daemon init is transient; only a wallet replacement or an
        // explicit reset/wipe should clear the existing progress snapshot.
        if (!wallet.isInitialized) return
        // Only listener-confirmed processed height is valid scan progress.
        // Never substitute the native hash-chain cache height here.
        val chainHeight = _walletHeight.value.coerceAtLeast(0L)
        val daemonHeight =
            (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
        if (daemonHeight <= 0L) {
            // A transient zero from the RPC is not proof that the scan finished.
            // Preserve any active scan/progress until a valid node height arrives.
            if (!_scanTracking.get()) clearSyncProgress()
            return
        }

        if (chainHeight > daemonHeight) {
            // A restore point beyond the node's current chain is not sync success.
            wallet.isSynchronized = false
            clearSyncProgress()
            Timber.tag(TAG).w(
                "Wallet scan height %d exceeds daemon height %d",
                chainHeight, daemonHeight
            )
            return
        }

        // A fresh seed restore can be left pending if the first connection check
        // finds the daemon still catching up. refreshed() keeps calling this method
        // while scanHeight < daemonHeight; retry the pending native rescan here so
        // it starts automatically once daemon height reaches its target. Without
        // this retry, the UI can show a backlog while no newBlock callbacks arrive.
        if (chainHeight < daemonHeight &&
            prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false) &&
            !forcedRestoreScanStarted.get()
        ) {
            maybeStartForcedRestoreScan(wallet)
        }

        // Do not declare the wallet finished merely because it caught the daemon's
        // current tip while that daemon is still syncing. Keep the displayed backlog
        // tied to the live target to avoid a completed bar restarting from zero.
        val progressTargetHeight = maxOf(daemonHeight, _daemonTargetHeight.get())
        val left = progressTargetHeight - chainHeight
        if (left == 0L) {
            syncUpdate(SyncProgress(progress = 1f, left = 0L))
            return
        }

        wallet.isSynchronized = false
        // Progress is relative to this scan's start: restores begin at the selected
        // restore height; ordinary refreshes begin at the last processed wallet height.
        // The target follows the daemon's actual target while its chain is catching up.
        val scanStartHeight = _scanStartHeight.get().coerceAtMost(progressTargetHeight)
        val scanRange = (progressTargetHeight - scanStartHeight).coerceAtLeast(1L)
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
        if (walletWipeInProgress.get()) return false
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized) return false
        // If native background sync is still active after a prior resume failure,
        // re-lock the UI on the next app-background transition without starting a second worker.
        if (backgroundSync) {
            _pinLockVisible.value = true
            return true
        }
        if (isResyncPriorityActive) return false
        emitUnlockShortcut(LockScreenShortCut.HOME)
        _backgroundSync.value = true
        _pinLockVisible.value = true
        if (AnonConfig.viewOnly) return true
        _blockUpdates.set(true)
        try {
            if (wallet.startBackgroundSync()) {
                Timber.tag(TAG).i("Entered background sync")
                return true
            }
            _backgroundSync.value = false
            _pinLockVisible.value = false
            Timber.tag(TAG).e("startBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            _backgroundSync.value = false
            _pinLockVisible.value = false
            Timber.tag(TAG).e(e, "startBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
    }

    suspend fun exitBackgroundSync(pin: String): Boolean = bgSyncMutex.withLock {
        if (walletWipeInProgress.get() || isResyncPriorityActive) return false
        val wallet = getWallet ?: return false
        if (AnonConfig.viewOnly) {
            _backgroundSync.value = false
            _pinLockVisible.value = false
            update()
            return true
        }
        _blockUpdates.set(true)
        var refreshRestarted = false
        try {
            if (wallet.stopBackgroundSync(pin)) {
                // Unlock the app as soon as the native background worker has stopped.
                // Refresh startup must not keep the PIN overlay blocking navigation.
                _backgroundSync.value = false
                _pinLockVisible.value = false
                wallet.startRefresh()
                refreshRestarted = true
                Timber.tag(TAG).i("Exited background sync; normal refresh restarted")
                return true
            }
            Timber.tag(TAG).e("stopBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "stopBackgroundSync error")
            return false
        } finally {
            // update() intentionally ignores calls while _blockUpdates is set. Publish the
            // wallet snapshot only after releasing the guard rather than silently doing no work.
            _blockUpdates.set(false)
            if (refreshRestarted) {
                runCatching { update() }
                    .onFailure { Timber.tag(TAG).w(it, "Unable to publish state after background sync exit") }
            }
        }
    }

    /**
     * The native wallet is about to be reinitialized after a real refresh failure.
     * Only this lifecycle event may release the queued-restore guard for retry.
     */
    fun prepareForcedRestoreScanRetry() {
        if (prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) {
            forcedRestoreScanStarted.set(false)
        }
    }

    @Synchronized
    fun setConnectionStatus(status: Wallet.ConnectionStatus) {
        if (walletWipeInProgress.get()) return
        val previous = _previousConnectionStatus.getAndSet(status)
        _connectionStatus.update { status }
        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected) {
            _daemonTargetHeight.set(0L)
            val restoreScanPending = prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
            // During mnemonic restore or an active scan, keep the temporary loader
            // visible until we have a real remaining-block count. A transient disconnect
            // must not create a blank gap between refresh and the sync progress bar.
            if ((!_scanTracking.get() && !restoreScanPending) || _syncProgress.value == null) {
                clearSyncProgress()
            }
            val scanPending = _scanTracking.get() || restoreScanPending
            setLoading(
                scanPending &&
                    getWallet?.isSynchronized != true &&
                    _syncProgress.value == null
            )
        }
        // Do not reset forcedRestoreScanStarted merely because a status probe
        // briefly reports Disconnected. That probe can race an already queued native
        // rescan and cause the same restore scan to be queued again. Reset is done
        // explicitly by the native reinitialization path below.
        if (previous == Wallet.ConnectionStatus.ConnectionStatus_Disconnected &&
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            setLoading(true)
            getWallet?.startRefresh()
        }
        if (status == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            getWallet?.let { maybeStartForcedRestoreScan(it) }
        }
    }

    fun updateDaemon(daemonInfo: DaemonInfo) {
        _scanDaemonHeight.set(daemonInfo.daemonHeight.coerceAtLeast(0L))
        this._connectedDaemon.update { daemonInfo }
        if (daemonInfo.daemonHeight <= 0L ||
            daemonInfo.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        ) {
            _daemonTargetHeight.set(0L)
        }
    }

    fun updateDaemonTargetHeight(height: Long) {
        _daemonTargetHeight.set(height.coerceAtLeast(0L))
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

    /** Clear process-wide UI state once the wallet file has been safely deleted. */
    fun resetAfterWalletWipe() {
        scanPreferenceIdentity.set(null)
        publishedWalletIdentity.set(null)
        refreshDeferredDuringResync.set(false)
        _blockUpdates.set(false)
        _isLoading.value = false
        _backgroundSync.value = false
        _pinLockVisible.value = false
        _transactions.value = emptyList()
        _subAddresses.value = emptyList()
        _balanceInfo.value = null
        _walletHeight.value = 0L
        _scanTracking.set(false)
        _scanStartHeight.set(0L)
        _unLockedBalance.value = null
        _walletStatus.value = null
        _nextAddress.value = null
        _coins.value = emptyList()
        _connectedDaemon.value = null
        _daemonTargetHeight.set(0L)
        _scanDaemonHeight.set(0L)
        _connectionStatus.value = null
        _previousConnectionStatus.set(null)
        daemonHeightQueryInFlight.set(false)
        forcedRestoreScanInFlight.set(false)
        forcedRestoreScanStarted.set(false)
        manualResyncNativeWorkerStarted.set(false)
        manualResyncInProgress.set(false)
        manualResyncScanPrepared.set(false)
        activeManualResyncReservation.set(null)
        resetPriorityActive.set(false)
        clearSyncProgress()
        walletWipeInProgress.set(false)
    }

    fun toggleHideAmounts() {
        hideAmountsFlow.update { !it }
    }

    fun setBackGroundSync(startBackgroundSync: Boolean) {
        _backgroundSync.update { startBackgroundSync }
        if (!startBackgroundSync) _pinLockVisible.value = false
    }

    /**
     * Ensure a correct unused receive address when the user opens the receive screen.
     *
     * The UI-driven operation never runs while reset setup is changing native wallet
     * state. After the asynchronous scan is queued, address creation is allowed as a
     * short, low-priority native operation so the QR can appear during synchronization.
     * It is counted so secure deletion cannot close the wallet underneath this call.
     */
    suspend fun ensureNextReceiveAddress(): Subaddress = withContext(Dispatchers.IO) {
        subaddressMutex.withLock {
            if (walletWipeInProgress.get()) throw IllegalStateException("钱包正在安全删除")
            check(beginReceiveAddressOperation()) {
                if (walletWipeInProgress.get()) "钱包正在安全删除"
                else "区块重置正在准备，请稍后生成收款地址"
            }
            try {
                val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
                val accountIndex = wallet.getAccountIndex()
                val lastUsedIndex = wallet.getLastUnusedIndex()
                val existingAddress = (
                    _subAddresses.value.asSequence() + listOfNotNull(_nextAddress.value).asSequence()
                )
                    .filter {
                        it.addressIndex > 0 &&
                            it.addressIndex > lastUsedIndex &&
                            it.address.isNotBlank()
                    }
                    .minByOrNull { it.addressIndex }

                if (existingAddress != null) {
                    _nextAddress.value = existingAddress
                    return@withLock existingAddress
                }
                if (walletWipeInProgress.get() || getWallet !== wallet) {
                    throw IllegalStateException("钱包状态已改变")
                }

                val created = wallet.addSubaddressAndGetSubaddress(accountIndex)
                check(created.addressIndex > 0 && created.address.isNotBlank()) {
                    "原生钱包未能生成有效子地址"
                }
                _nextAddress.value = created
                _subAddresses.update { current ->
                    (current + created)
                        .distinctBy { it.address }
                        .sortedByDescending { it.addressIndex }
                }
                created
            } finally {
                endOrdinaryWalletOperation()
            }
        }
    }

    suspend fun getNewAddress(): Subaddress = subaddressMutex.withLock {
        if (walletWipeInProgress.get()) throw IllegalStateException("钱包正在安全删除")
        check(beginReceiveAddressOperation()) {
            if (walletWipeInProgress.get()) "钱包正在安全删除"
            else "区块重置正在准备，请稍后再生成收款地址"
        }
        try {
            val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
            val accountIndex = wallet.getAccountIndex()
            val addedAddress = wallet.addSubaddressAndGetSubaddress(accountIndex)

            _subAddresses.update { current ->
                (current + addedAddress)
                    .distinctBy { it.address }
                    .sortedByDescending { it.addressIndex }
            }
            _nextAddress.update { current ->
                if (current == null || current.addressIndex == addedAddress.addressIndex) {
                    addedAddress
                } else {
                    current
                }
            }
            addedAddress
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    fun setTransactionNote(note: String, transactionInfo: TransactionInfo) {
        if (!beginOrdinaryWalletOperation()) return
        try {
            val wallet = getWallet ?: return
            wallet.setUserNote(transactionInfo.hash, note)
            wallet.store()
            wallet.refreshHistory()
            update()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to update transaction note")
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    fun updateAddressLabel(label: String, addressIndex: Int) {
        if (!beginOrdinaryWalletOperation()) return
        try {
            val wallet = getWallet ?: return
            wallet.setSubaddressLabel(addressIndex, label)
            wallet.store()
            wallet.refreshHistory()
            wallet.getAllUsedSubAddresses().let { allItems ->
                _subAddresses.update { allItems.reversed() }
            }
            wallet.getLatestSubAddress().let { latest ->
                _nextAddress.update { latest }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to update address label")
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    /**
     * Refresh native transaction history and publish it without toggling the loading UI.
     * Called when the history screen becomes ready and when a blockchain scan finishes,
     * because the final native scan callback can arrive before its history snapshot is stable.
     *
     * @return number of transaction records visible for the current wallet account.
     */
    suspend fun refreshTransactionHistory(): Int = withContext(Dispatchers.IO) {
        val wallet = getWallet ?: return@withContext 0
        if (walletWipeInProgress.get() || isResyncPriorityActive || !wallet.isInitialized) return@withContext 0
        if (!beginOrdinaryWalletOperation()) return@withContext 0

        try {
            wallet.refreshHistory()
            if (walletWipeInProgress.get() || isResyncPriorityActive ||
                getWallet !== wallet || !wallet.isInitialized
            ) return@withContext 0

            val transactionCount = wallet.history?.all?.size ?: 0
            update()
            Timber.tag(TAG).i(
                "Explicit transaction history refresh: transactions=%d balance=%d",
                transactionCount,
                wallet.balance
            )
            transactionCount
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Explicit transaction history refresh failed")
            0
        } finally {
            endOrdinaryWalletOperation()
        }
    }

    private fun scheduleDeferredRefreshIfIdle() {
        if (walletWipeInProgress.get() || isResyncPriorityActive) return
        if (!refreshDeferredDuringResync.compareAndSet(true, false)) return
        scanHeightScope.launch {
            delay(250L)
            if (walletWipeInProgress.get()) return@launch
            if (isResyncPriorityActive) {
                refreshDeferredDuringResync.set(true)
                return@launch
            }
            refresh()
        }
    }

    suspend fun refresh() {
        if (walletWipeInProgress.get()) return
        if (isResyncPriorityActive) {
            // Keep explicit reset scanning exclusive; replay a manual refresh after it finishes.
            refreshDeferredDuringResync.set(true)
            Timber.tag(TAG).i("Wallet refresh queued until the priority rescan completes")
            return
        }
        val wallet = getWallet
        if (wallet == null || !wallet.isInitialized || !beginOrdinaryWalletOperation()) return

        setLoading(true)
        try {
            withContext(Dispatchers.IO) {
                if (walletWipeInProgress.get() || isResyncPriorityActive) return@withContext
                val connected = wallet.fullStatus.connectionStatus ==
                    Wallet.ConnectionStatus.ConnectionStatus_Connected
                if (!connected) return@withContext

                val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (daemonHeight > 0L) updateBlockHeights(wallet, daemonHeight)
                wallet.refreshHistory()
                update()

                val latestDaemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (latestDaemonHeight > 0L) updateBlockHeights(wallet, latestDaemonHeight)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet data refresh failed")
        } finally {
            if (!isResyncPriorityActive && !walletWipeInProgress.get()) {
                val waitingForScanProgress = runCatching {
                    wallet.isInitialized &&
                        !wallet.isSynchronized &&
                        (_scanTracking.get() ||
                            prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) &&
                        _syncProgress.value == null
                }.getOrDefault(false)
                setLoading(waitingForScanProgress)
            }
            endOrdinaryWalletOperation()
        }
    }

    fun resyncBlockchain(
        selectedRestoreHeight: Long? = null,
        knownDaemonHeight: Long? = null,
        knownDaemonTargetHeight: Long? = null,
        reservation: ManualResyncReservation? = null,
        awaitOtherWalletWork: suspend () -> Unit = {}
    ): Result<Boolean> {
        if (walletWipeInProgress.get()) {
            reservation?.let(::cancelManualResyncRequest)
            return Result.failure(Exception("钱包正在安全删除，已取消区块重置"))
        }
        val request = reservation ?: reserveManualResyncRequest()
            ?: return Result.failure(
                Exception(
                    if (walletWipeInProgress.get()) "钱包正在安全删除，已取消区块重置"
                    else "已有更高优先级的钱包操作正在执行，请稍后再试"
                )
            )
        if (activeManualResyncReservation.get() !== request) {
            return Result.failure(Exception("区块重置请求已失效"))
        }

        var handedOffToWorker = false
        return try {
            if (walletWipeInProgress.get()) {
                Result.failure(Exception("钱包正在安全删除，已取消区块重置"))
            } else {
                val result = validateAndScheduleManualResync(
                    selectedRestoreHeight = selectedRestoreHeight,
                    knownDaemonHeight = knownDaemonHeight,
                    knownDaemonTargetHeight = knownDaemonTargetHeight,
                    reservation = request,
                    awaitOtherWalletWork = awaitOtherWalletWork
                )
                handedOffToWorker = result.isSuccess
                result
            }
        } finally {
            if (!handedOffToWorker) cancelManualResyncRequest(request)
        }
    }

    /**
     * Validate only from the already-known node heights and in-memory wallet snapshot.
     * Once accepted, publish the determinate progress before making any blocking native
     * wallet calls. The independent WalletState scope keeps the reset alive while the
     * screen navigates back to the transaction page.
     */
    private fun validateAndScheduleManualResync(
        selectedRestoreHeight: Long?,
        knownDaemonHeight: Long?,
        knownDaemonTargetHeight: Long?,
        reservation: ManualResyncReservation,
        awaitOtherWalletWork: suspend () -> Unit
    ): Result<Boolean> {
        val wallet = getWallet
            ?: return Result.failure(Exception("Wallet is not initialized"))
        if (!wallet.isInitialized) {
            return Result.failure(Exception("Wallet is not initialized"))
        }
        if (walletWipeInProgress.get()) {
            return Result.failure(Exception("钱包正在安全删除，已取消区块重置"))
        }

        // The reset screen can query the node through an independent RPC connection.
        // That does NOT mean Monero's native wallet connection is ready to rescan:
        // WalletImpl::doRefresh() skips rescans while its own daemon connection is down.
        // Require the native wallet to be connected even when external RPC heights are fresh.
        val nativeConnectionStatus = runCatching {
            wallet.fullStatus.connectionStatus
        }.getOrNull()
        if (nativeConnectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            return Result.failure(
                Exception("钱包原生节点尚未连接成功。独立 RPC 能查询高度不代表钱包扫描已连接，请先连接节点成功后再重置。")
            )
        }
        if (backgroundSync) {
            return Result.failure(Exception("Exit background sync before resetting the scan"))
        }

        // The reset screen supplies these direct-RPC heights. Avoid querying native
        // daemon height again after that snapshot is already known.
        val daemonHeight = knownDaemonHeight?.takeIf { it > 0L }
            ?: runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L).coerceAtLeast(0L)
        val daemonTargetHeight = knownDaemonTargetHeight?.takeIf { it > 0L }
            ?: runCatching { wallet.getDaemonBlockChainTargetHeight() }.getOrDefault(0L).coerceAtLeast(0L)
        if (daemonHeight <= 1L || daemonTargetHeight <= 0L || daemonHeight < daemonTargetHeight) {
            return Result.failure(
                Exception("节点尚未同步完成，请等待节点同步完成后再重置同步")
            )
        }

        val selectedHeight = selectedRestoreHeight ?: runCatching {
            wallet.getRestoreHeight()
        }.getOrDefault(-1L)
        if (selectedHeight < 0L) {
            return Result.failure(Exception("恢复区块高度无效"))
        }
        if (selectedHeight > daemonHeight) {
            return Result.failure(
                Exception("恢复高度不能高于当前节点高度（$daemonHeight）")
            )
        }

        // A rescan discards Monero's cached transfers, so never start later than the
        // earliest transaction already known by this wallet.
        val earliestFromCurrentHistory = _transactions.value.asSequence()
            .filter { !it.isPending && it.blockheight > 0L }
            .minOfOrNull { it.blockheight }
        val savedEarliestHeight = prefs.getLong(
            scanPreferenceKey(wallet, "earliest_transaction_height"),
            -1L
        ).takeIf { it >= 0L }
        val earliestKnownTransactionHeight = listOfNotNull(
            earliestFromCurrentHistory,
            savedEarliestHeight
        ).minOrNull()
        if (earliestKnownTransactionHeight != null &&
            selectedHeight > earliestKnownTransactionHeight
        ) {
            return Result.failure(
                Exception(
                    "恢复高度 $selectedHeight 高于钱包最早已知交易区块 " +
                        "$earliestKnownTransactionHeight。继续重扫会跳过更早交易并导致余额缺失，" +
                        "请将高度调低至 $earliestKnownTransactionHeight 或更早。"
                )
            )
        }

        // Do not call wallet.balance/unlockedBalance here: those JNI calls can block
        // before the progress bar appears. Use the current published snapshot instead.
        val cachedBalances = listOfNotNull(_balanceInfo.value, _unLockedBalance.value)
        val cachedBalanceKnown = cachedBalances.isNotEmpty()
        val cachedWalletHasFunds = cachedBalances.any { it > 0L }
        if (earliestKnownTransactionHeight == null && selectedHeight > 0L &&
            (!cachedBalanceKnown || cachedWalletHasFunds)
        ) {
            return Result.failure(
                Exception(
                    "当前无法可靠确认钱包最早交易区块和余额。为避免重扫后旧交易和余额缺失，" +
                        "请将恢复高度设为 0，或先确保交易历史已加载。"
                )
            )
        }

        if (walletWipeInProgress.get()) {
            return Result.failure(Exception("钱包正在安全删除，已取消区块重置"))
        }

        // Direct get_info heights are zero-based tip indices; the scan tracker uses
        // one-based block counts. Publish the real remaining-block estimate before
        // native setup; native wallet height APIs already return counts.
        val progressDaemonHeight = if (knownDaemonHeight != null && knownDaemonHeight >= 0L) {
            knownDaemonHeight + 1L
        } else {
            daemonHeight
        }
        val progressTargetHeight = if (knownDaemonHeight != null && knownDaemonHeight >= 0L) {
            maxOf(
                knownDaemonHeight,
                knownDaemonTargetHeight ?: knownDaemonHeight
            ) + 1L
        } else {
            maxOf(daemonHeight, daemonTargetHeight)
        }
        // Do not show determinate remaining-block progress during reset preflight.
        // The native scan has not started yet; keep the existing loading indicator until
        // the selected height is committed and the native refresh worker is restarted.
        clearSyncProgress()
        setLoading(true)

        // Hand the native setup to the independent worker so navigation does not block.
        // Determinate progress is published only after the chosen height is applied and
        // the native refresh worker has been restarted.
        if (walletWipeInProgress.get() ||
            activeManualResyncReservation.get() !== reservation
        ) {
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("钱包安全删除优先，已取消区块重置"))
        }
        if (!manualResyncNativeWorkerStarted.compareAndSet(false, true)) {
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("已有原生钱包任务正在执行，请稍后重试重置"))
        }
        if (walletWipeInProgress.get() ||
            activeManualResyncReservation.get() !== reservation
        ) {
            manualResyncNativeWorkerStarted.set(false)
            clearSyncProgress()
            setLoading(false)
            return Result.failure(Exception("钱包安全删除优先，已取消区块重置"))
        }
        scanHeightScope.launch {
            runManualResyncWorker(
                wallet,
                selectedHeight,
                progressDaemonHeight,
                progressTargetHeight,
                reservation,
                awaitOtherWalletWork
            )
        }
        return Result.success(true)
    }

    private suspend fun runManualResyncWorker(
        wallet: Wallet,
        selectedHeight: Long,
        progressDaemonHeight: Long,
        progressTargetHeight: Long,
        reservation: ManualResyncReservation,
        awaitOtherWalletWork: suspend () -> Unit
    ) {
        var previousRestoreHeight: Long? = null
        var rescanQueued = false
        try {
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            // Give a node connect/disconnect that already holds the shared native
            // initialization lock time to exit. New ones are rejected by the reset gate.
            // Progress has already been published, so the UI does not wait on this lock.
            awaitOtherWalletWork()
            awaitBackgroundSyncOperationIdle()
            awaitOrdinaryWalletOperationsIdle()
            awaitWalletStateCallbacksIdle()
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            // The reset owns priority now. pauseRefresh() only disables future cycles;
            // drain any already-running native refresh before mutating restore height or
            // cached transfers, then queue the selected-height rescan as a separate step.
            wallet.pauseRefreshAndWait()
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            previousRestoreHeight = wallet.getRestoreHeight()
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            wallet.setRestoreHeight(selectedHeight)
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }
            check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedHeight) {
                "Selected restore height was not applied"
            }
            check(wallet.store()) { "Unable to persist selected restore height" }
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }

            Timber.tag(TAG).i(
                "Manual rescan: requesting native scan from height %d with %d blocks remaining",
                selectedHeight,
                (progressTargetHeight - selectedHeight).coerceAtLeast(0L)
            )
            // Refresh is paused. Queue the explicit native rescan first and check whether
            // Monero accepted it; only then publish the selected-height scan state.
            wallet.rescanBlockchainAsync()
            rescanQueued = true
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }

            prepareManualResyncScanState(
                wallet,
                selectedHeight,
                progressDaemonHeight,
                progressTargetHeight
            )
            manualResyncScanPrepared.set(true)
            check(!walletWipeInProgress.get()) {
                "钱包安全删除优先，已取消区块重置"
            }

            if (!walletWipeInProgress.get()) {
                wallet.startRefresh()
                // Publish remaining-block progress after the accepted request is handed to
                // native refresh, using the selected-height baseline rather than node tip.
                updateSyncProgressFromWallet(wallet, progressDaemonHeight)
                val hasRemainingBlocks = _syncProgress.value?.left?.let { it > 0L } == true
                setLoading(!hasRemainingBlocks && !wallet.isSynchronized)
                Timber.tag(TAG).i(
                    "Manual rescan started: selectedHeight=%d walletHeight=%d daemonHeight=%d targetHeight=%d remaining=%d",
                    selectedHeight,
                    _walletHeight.value,
                    progressDaemonHeight,
                    progressTargetHeight,
                    _syncProgress.value?.left ?: 0L
                )
            } else {
                Timber.tag(TAG).i("Secure wipe requested after rescan queue; skipping refresh restart")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unable to start manual wallet rescan")
            if (!rescanQueued && !walletWipeInProgress.get()) {
                previousRestoreHeight?.let { restoreHeight ->
                    runCatching {
                        wallet.setRestoreHeight(restoreHeight)
                        check(wallet.store()) { "Unable to restore previous restore height" }
                    }.onFailure { rollbackError ->
                        Timber.tag(TAG).e(rollbackError, "Restore-height rollback failed")
                    }
                }
                runCatching { wallet.startRefresh() }.onFailure { restartError ->
                    Timber.tag(TAG).e(restartError, "Unable to resume refresh after rejected rescan")
                }
                clearSyncProgress()
                val scanPending = _scanTracking.get() ||
                    prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
                setLoading(
                    scanPending && runCatching {
                        getWallet?.isInitialized == true && getWallet?.isSynchronized != true
                    }.getOrDefault(false)
                )
                _resyncErrors.tryEmit(e.message ?: "无法启动区块扫描")
            } else if (!rescanQueued && walletWipeInProgress.get()) {
                Timber.tag(TAG).i("Reset setup cancelled because secure wipe has priority")
            } else if (rescanQueued && !walletWipeInProgress.get()) {
                // Scan is already queued. Keep its real progress state and report only
                // if the refresh trigger could not be recovered.
                val restart = runCatching { wallet.startRefresh() }
                restart.onSuccess {
                    updateSyncProgressFromWallet(wallet, progressDaemonHeight)
                    val hasRemainingBlocks = _syncProgress.value?.left?.let { it > 0L } == true
                    setLoading(!hasRemainingBlocks && !wallet.isSynchronized)
                }.onFailure {
                    clearSyncProgress()
                    setLoading(false)
                    _resyncErrors.tryEmit(it.message ?: "扫描已提交，但钱包刷新未能启动")
                }
            }
        } finally {
            manualResyncNativeWorkerStarted.set(false)
            manualResyncInProgress.set(false)
            manualResyncScanPrepared.set(false)
            // Reset remains the top-priority wallet operation while native block
            // processing continues. Release the priority only on failure, wipe takeover,
            // or a real completed-scan callback.
            if (!rescanQueued || walletWipeInProgress.get()) {
                if (activeManualResyncReservation.compareAndSet(reservation, null)) {
                    resetPriorityActive.set(false)
                }
            }
            scheduleDeferredRefreshIfIdle()
        }
    }

    private val getWallet get() = WalletManager.instance?.wallet
}
