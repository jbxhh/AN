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
    // Cache the daemon's real target height once per refresh callback so block
    // callbacks can calculate progress without issuing an RPC for every block.
    private val _daemonTargetHeight = AtomicLong(0L)
    private val _connectionStatus = MutableStateFlow<Wallet.ConnectionStatus?>(null)
    private val _previousConnectionStatus = AtomicReference<Wallet.ConnectionStatus?>(null)
    private val _unlockShortcut = Channel<LockScreenShortCut>(capacity = 1)
    val unlockShortcut = _unlockShortcut.receiveAsFlow()
    private val bgSyncMutex = Mutex()
    private val subaddressMutex = Mutex()
    private val daemonHeightQueryMutex = Mutex()
    private val walletWipeInProgress = AtomicBoolean(false)
    // Wallet scan progress needs the live daemon target as soon as a scan starts.
    // Keep this request off the UI thread and avoid duplicate startup height RPCs.
    private val scanHeightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val daemonHeightQueryInFlight = AtomicBoolean(false)
    private val forcedRestoreScanInFlight = AtomicBoolean(false)
    private val forcedRestoreScanStarted = AtomicBoolean(false)

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

    @Synchronized
    fun update() {
        if (_blockUpdates.get() || walletWipeInProgress.get()) return
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

            // Do not start a forced blockchain rescan from generic state publication.
            // Restore scans are started by the wallet startup/connection lifecycle,
            // not by manual refreshes or unrelated balance/history updates.
        }
    }


    /**
     * SharedPreferences is written by the seed-restore flow before its temporary
     * wallet is closed. Treat that selected value as authoritative across native init.
     */
    fun pendingSelectedRestoreHeight(wallet: Wallet? = getWallet): Long? {
        val currentWallet = wallet ?: return null
        if (!prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) return null
        return prefs.getLong(RESTORE_HEIGHT, currentWallet.getRestoreHeight()).coerceAtLeast(0L)
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
     * Keep the request pending until the daemon is connected and fully synchronized.
     */
    fun maybeStartForcedRestoreScan(wallet: Wallet) {
        if (walletWipeInProgress.get()) return
        if (!prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) return
        if (forcedRestoreScanStarted.get()) return
        if (!forcedRestoreScanInFlight.compareAndSet(false, true)) return

        scanHeightScope.launch {
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

                    val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                    val daemonTargetHeight = wallet.getDaemonBlockChainTargetHeight().coerceAtLeast(0L)
                    if (daemonHeight <= 1L) {
                        Timber.tag(TAG).i(
                            "Restore scan pending until daemon exposes a usable chain tip: height=%d target=%d",
                            daemonHeight, daemonTargetHeight
                        )
                        return@withLock
                    }
                    // Do not wait for the remote daemon's entire chain to finish syncing.
                    // Scan every available block from the selected restore height now;
                    // the normal refresh worker will continue when the daemon advances.
                    if (daemonTargetHeight > daemonHeight) {
                        Timber.tag(TAG).i(
                            "Daemon is still syncing; starting restore scan against available tip: height=%d target=%d",
                            daemonHeight, daemonTargetHeight
                        )
                    }

                    // The saved restore height, not the native cache tip, is the source
                    // of truth. Reapply it before queuing the native rescan.
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
                    wallet.startRefresh()
                    forcedRestoreScanStarted.set(true)
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

    /** Close the gate before stopping native refresh so callbacks cannot start new work. */
    @Synchronized
    fun beginWalletWipe(): Boolean = walletWipeInProgress.compareAndSet(false, true)

    @Synchronized
    fun endWalletWipe() {
        walletWipeInProgress.set(false)
    }

    /** Wait until any daemon-height JNI/RPC query started by scan progress has left its critical section. */
    suspend fun awaitPendingNativeQueries() {
        daemonHeightQueryMutex.withLock { }
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
        wallet.isSynchronized = false
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
        if (walletWipeInProgress.get() || wallet == null || !wallet.isInitialized || processedHeight <= 0L) return

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
            var attempts = 0
            try {
                // Keep retrying while the restore/scan is active. One early RPC can
                // return zero while Tor or the daemon is still coming online; a one-shot
                // request leaves the UI stuck on the wrong side of the refresh/sync handoff.
                while (
                    !walletWipeInProgress.get() &&
                    getWallet === wallet &&
                    wallet.isInitialized &&
                    !wallet.isSynchronized &&
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
                        wallet.isSynchronized ||
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
        if (walletWipeInProgress.get()) return false
        if (wallet != null && wallet.isInitialized) {
            // A callback can report the daemon tip before the fresh restore's native
            // rescan is queued. Never treat that startup race as a completed wallet scan.
            val pendingRestoreHeight = pendingSelectedRestoreHeight(wallet)
            if (pendingRestoreHeight != null && !forcedRestoreScanStarted.get()) {
                val selectedHeight = pendingRestoreHeight.coerceAtLeast(0L)
                _scanStartHeight.set(selectedHeight)
                _walletHeight.value = selectedHeight
                _scanTracking.set(true)
                val daemonHeight = (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
                updateSyncProgressFromWallet(wallet, daemonHeight)
                maybeStartForcedRestoreScan(wallet)
                return false
            }
            // Keep listener-based height tracking enabled after sync, and do not issue
            // another node-height RPC that might observe a different tip mid-callback.
            val daemonHeight = (knownDaemonHeight ?: wallet.getDaemonBlockChainHeight()).coerceAtLeast(0L)
            val targetHeight = maxOf(daemonHeight, _daemonTargetHeight.get())
            if (daemonHeight > 0L && targetHeight > daemonHeight) {
                // The wallet has caught up to the node's present tip, but the node
                // itself still has blocks left. Keep scanning instead of finalizing
                // and showing a second sync cycle when the daemon advances.
                wallet.isSynchronized = false
                updateSyncProgressFromWallet(wallet, daemonHeight)
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
        if (walletWipeInProgress.get()) return
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
    @Synchronized
    fun updateSyncProgressFromWallet(
        wallet: Wallet? = getWallet,
        knownDaemonHeight: Long? = null
    ) {
        if (walletWipeInProgress.get()) return
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
            pendingSelectedRestoreHeight(wallet) != null &&
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
        _blockUpdates.set(false)
        _isLoading.value = false
        _backgroundSync.value = false
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
        _connectionStatus.value = null
        _previousConnectionStatus.set(null)
        daemonHeightQueryInFlight.set(false)
        forcedRestoreScanInFlight.set(false)
        forcedRestoreScanStarted.set(false)
        clearSyncProgress()
        walletWipeInProgress.set(false)
    }

    fun toggleHideAmounts() {
        hideAmountsFlow.update { !it }
    }

    fun setBackGroundSync(startBackgroundSync: Boolean) {
        _backgroundSync.update { startBackgroundSync }
    }

    /**
     * Ensure a correct unused receive address when the user opens the receive screen.
     *
     * WalletState.update() is also called from the native refresh callback. That callback
     * must only read already-generated addresses: creating an address there can deadlock
     * against the native refresh lock. This explicit UI-driven operation may safely wait
     * for a refresh batch boundary because it does not hold synchronized(wallet).
     */
    suspend fun ensureNextReceiveAddress(): Subaddress = subaddressMutex.withLock {
        if (walletWipeInProgress.get()) throw IllegalStateException("钱包正在安全删除")
        val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
        val accountIndex = wallet.getAccountIndex()

        if (!wallet.isInitialized) {
            val primaryAddress = wallet.getAddress(accountIndex)
            check(primaryAddress.isNotBlank()) { "主收款地址为空" }
            val primary = Subaddress(accountIndex, 0, primaryAddress, "")
            _nextAddress.value = primary
            _subAddresses.update { current ->
                (current + primary).distinctBy { it.address }.sortedByDescending { it.addressIndex }
            }
            return@withLock primary
        }

        // History-only lookup; this does not perform a series of native address reads.
        val nextIndex = synchronized(wallet) { wallet.getLastUnusedIndex() + 1 }
        var addressCount = wallet.numSubAddresses
        val createdAddresses = mutableListOf<Subaddress>()

        while (addressCount <= nextIndex) {
            if (walletWipeInProgress.get() || getWallet !== wallet) {
                throw IllegalStateException("钱包状态已改变")
            }
            // Important: don't wrap this call in synchronized(wallet). The native call
            // waits for the current refresh callback to return before mutating wallet2.
            val created = wallet.addSubaddressAndGetSubaddress(accountIndex)
            check(created.addressIndex == addressCount) {
                "添加子地址失败：原生地址索引不连续"
            }
            createdAddresses += created
            addressCount = created.addressIndex + 1
        }

        val address = createdAddresses.lastOrNull { it.addressIndex == nextIndex }
            ?: synchronized(wallet) { wallet.getSubaddressObject(nextIndex) }

        _nextAddress.value = address
        _subAddresses.update { current ->
            (createdAddresses + current)
                .distinctBy { it.address }
                .sortedByDescending { it.addressIndex }
        }
        address
    }

    suspend fun getNewAddress(): Subaddress = subaddressMutex.withLock {
        val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
        val accountIndex = wallet.getAccountIndex()

        // This native call pauses only at a refresh batch boundary. Never hold the JVM
        // wallet monitor while the native call waits for the refresh thread to unwind.
        val addedAddress = wallet.addSubaddressAndGetSubaddress(accountIndex)

        _subAddresses.update { current ->
            (current + addedAddress)
                .distinctBy { it.address }
                .sortedByDescending { it.addressIndex }
        }
        _nextAddress.update { current ->
            if (current?.addressIndex == addedAddress.addressIndex) addedAddress else current
        }
        addedAddress
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
            return
        }

        // Keep a visible refresh indicator from the moment the request starts.
        // If this wallet is already behind the daemon, keep the indicator active
        // until listener callbacks publish real scan progress; this avoids a blank
        // gap between the pull-to-refresh animation and the sync progress bar.
        setLoading(true)
        try {
            // Manual refresh is a data refresh, not a command to start/resume blockchain
            // scanning. The normal wallet lifecycle owns startRefresh(); this method only
            // reads the current node height and republishes wallet history/balance.
            withContext(Dispatchers.IO) {
                val connected = wallet.fullStatus.connectionStatus ==
                    Wallet.ConnectionStatus.ConnectionStatus_Connected
                if (!connected) return@withContext

                val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (daemonHeight > 0L) {
                    updateBlockHeights(wallet, daemonHeight)
                }

                wallet.refreshHistory()
                update()

                // Update the displayed node height without manufacturing a new
                // sync-progress state just because the user requested a refresh.
                val latestDaemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                if (latestDaemonHeight > 0L) {
                    updateBlockHeights(wallet, latestDaemonHeight)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet data refresh failed")
        } finally {
            // A refresh is complete when the wallet is caught up or no longer
            // connected. When it is connected but an active scan has not published
            // its first real progress sample yet, retain the indeterminate indicator.
            val waitingForScanProgress = runCatching {
                wallet.isInitialized &&
                    !wallet.isSynchronized &&
                    (_scanTracking.get() ||
                        prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)) &&
                    _syncProgress.value == null &&
                    !walletWipeInProgress.get()
            }.getOrDefault(false)
            // Preserve the loader even through a temporary disconnect or while Tor is
            // starting. It hands off to the determinate bar only after real heights arrive.
            setLoading(waitingForScanProgress)
        }
    }

    fun resyncBlockchain(selectedRestoreHeight: Long? = null): Result<Boolean> {
        val wallet = getWallet
            ?: return Result.failure(Exception("Wallet is not initialized"))
        if (!wallet.isInitialized) {
            return Result.failure(Exception("Wallet is not initialized"))
        }
        if (wallet.fullStatus.connectionStatus !=
            Wallet.ConnectionStatus.ConnectionStatus_Connected
        ) {
            // A rejected reset must not erase progress from the scan already in progress.
            return Result.failure(Exception("Please connect to daemon for resync"))
        }
        if (backgroundSync) {
            return Result.failure(Exception("Exit background sync before resetting the scan"))
        }

        // Validate the daemon before changing the active scan or its visible progress.
        val daemonHeight = runCatching { wallet.getDaemonBlockChainHeight() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
        val daemonTargetHeight = runCatching { wallet.getDaemonBlockChainTargetHeight() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
        if (daemonHeight <= 1L || daemonTargetHeight <= 0L || daemonHeight < daemonTargetHeight) {
            return Result.failure(
                Exception("节点尚未同步完成，请等待节点同步完成后再重置同步")
            )
        }

        val selectedHeight = selectedRestoreHeight ?: wallet.getRestoreHeight()
        if (selectedHeight < 0L) {
            return Result.failure(Exception("恢复区块高度无效"))
        }
        if (selectedHeight > daemonHeight) {
            return Result.failure(
                Exception("恢复高度不能高于当前节点高度（$daemonHeight）")
            )
        }

        val previousRestoreHeight = wallet.getRestoreHeight()
        var rescanQueued = false
        return try {
            // Pause native refresh before changing the restore point. The native rescan
            // is queued while paused, then consumed by the native refresh worker after
            // it resumes; this avoids asking a second scanner to run alongside it.
            wallet.pauseRefresh()
            wallet.setRestoreHeight(selectedHeight)
            check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedHeight) {
                "Selected restore height was not applied"
            }
            check(wallet.store()) { "Unable to persist selected restore height" }

            // Queue the native rescan before replacing the current UI scan state. If
            // queuing fails, the old scan/progress can remain intact after rollback.
            wallet.rescanBlockchainAsync()
            rescanQueued = true

            clearSyncProgress()
            _transactions.value = emptyList()
            _coins.value = emptyList()
            setLoading(true)
            beginWalletScan(
                wallet = wallet,
                scanStartHeight = selectedHeight,
                forceStartAtSelectedHeight = true
            )
            updateBalanceFromWallet(wallet)
            wallet.startRefresh()
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet rescan failed to start")
            if (!rescanQueued) {
                // Nothing was queued natively: restore the old setting and resume the
                // existing scanner without disturbing its current progress indicators.
                runCatching {
                    wallet.setRestoreHeight(previousRestoreHeight)
                    check(wallet.store()) { "Unable to restore previous restore height" }
                }.onFailure { rollbackError ->
                    Timber.tag(TAG).e(rollbackError, "Restore-height rollback failed")
                }
                runCatching { wallet.startRefresh() }.onFailure { restartError ->
                    Timber.tag(TAG).e(restartError, "Unable to resume refresh after rejected rescan")
                }
                Result.failure(e)
            } else {
                // The native rescan is already queued, so do not roll the restore height
                // back behind it. Re-establish progress state and make one more attempt
                // to wake the native refresh worker.
                runCatching {
                    clearSyncProgress()
                    _transactions.value = emptyList()
                    _coins.value = emptyList()
                    setLoading(true)
                    beginWalletScan(
                        wallet = wallet,
                        scanStartHeight = selectedHeight,
                        forceStartAtSelectedHeight = true
                    )
                    updateBalanceFromWallet(wallet)
                }.onFailure { stateError ->
                    Timber.tag(TAG).e(stateError, "Unable to publish rescan progress state")
                }
                val restart = runCatching { wallet.startRefresh() }
                if (restart.isSuccess) {
                    Result.success(true)
                } else {
                    Result.failure(restart.exceptionOrNull() ?: e)
                }
            }
        }
    }

    private val getWallet get() = WalletManager.instance?.wallet
}
