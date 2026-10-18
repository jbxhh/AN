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
    private val manualResyncInProgress = AtomicBoolean(false)
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
        if (_blockUpdates.get() || walletWipeInProgress.get() || manualResyncInProgress.get()) return
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
            // A refresh callback may already have entered update() before reset began.
            // Do not let that in-flight callback republish stale transactions after reset.
            if (manualResyncInProgress.get()) return@let

            // Publish wallet data continuously, not only after the full scan completes.
            // Monero's history becomes available incrementally as refresh callbacks arrive.
            val previousTransactions = _transactions.value
            val updatedTransactions = (wallet.history?.all?.sortedWith(comparator = { o1, o2 ->
                o2.timestamp.compareTo(o1.timestamp)
            }) ?: emptyList()).fastDistinctBy { it.getListKey() }
            _transactions.value = updatedTransactions

            // Remember the earliest confirmed transaction we've ever seen for this wallet.
            // A rescan clears Monero's cached transfers; this durable marker lets us reject
            // future restore heights that would skip known historical transactions.
            val earliestTransactionHeight = updatedTransactions.asSequence()
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
                val hasNewIncoming = updatedTransactions.any { transaction ->
                    transaction.getListKey() !in previousKeys &&
                        transaction.direction == TransactionInfo.Direction.Direction_In
                }
                if (hasNewIncoming) _incomingTx.tryEmit(Unit)
            }

            // Balance can change during restore and must remain visible while scanning.
            updateBalanceFromWallet(wallet)
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
        if (
            _blockUpdates.get() ||
            walletWipeInProgress.get() ||
            manualResyncInProgress.get() ||
            _isLoading.value ||
            _isSyncing.get() ||
            _syncProgress.value != null ||
            backgroundSync
        ) return null

        val wallet = getWallet ?: return null
        if (!wallet.isInitialized) return null

        return try {
            if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                return null
            }
            wallet.refreshCoins(allowUnsynchronized = true)
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
        }
    }


    fun getAddressForCoin(txHash: String?): String? {
        if (txHash.isNullOrEmpty()) return null
        val tx = _transactions.value.firstOrNull { it.hash == txHash } ?: return null
        return try {
            getWallet?.getSubaddress(tx.accountIndex, tx.addressIndex)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to resolve address for output transaction")
            null
        }
    }

    fun freezeCoin(publicKey: String): Result<Boolean> = try {
        val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
        val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
        coins.setFrozen(publicKey)
        wallet.store()
        coins.refresh()
        update()
        Result.success(true)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Unable to freeze output")
        Result.failure(e)
    }

    fun thawCoin(publicKey: String): Result<Boolean> = try {
        val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
        val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
        coins.thaw(publicKey)
        wallet.store()
        coins.refresh()
        update()
        Result.success(true)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Unable to thaw output")
        Result.failure(e)
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
        _scanStartHeight.set(height)
        _scanTracking.set(true)
        wallet.isSynchronized = false
        _walletHeight.value = height
        clearSyncProgress()
        _transactions.value = emptyList()
        _coins.value = emptyList()

        // Publish the known remaining-block count immediately. The progress indicator
        // must not depend on the first native callback to switch away from the endless
        // refresh spinner after a manual reset.
        val remainingBlocks = (progressTargetHeight - height).coerceAtLeast(0L)
        if (remainingBlocks > 0L) {
            syncUpdate(SyncProgress(progress = 0f, left = remainingBlocks))
            setLoading(false)
        } else {
            // Keep loading only if the target cannot yet establish a positive backlog.
            setLoading(true)
        }
    }

    private fun scanPreferenceKey(wallet: Wallet, suffix: String): String {
        val path = wallet.getPath()?.takeIf { it.isNotBlank() } ?: wallet.name
        val identity = path + "|" + wallet.address
        val hash = identity.fold(0) { acc, char -> 31 * acc + char.code }
        // Keep the v4 key namespace; heightCountSemanticsKey migrates v4's stored
        // zero-based callback heights to the one-based processed-block count model.
        return "anonero_scan_v4_" + hash + "_" + suffix
    }

    /**
     * Consumes the one-based processed-block count emitted by the native JNI listener.
     * Fast-refresh hash-cache callbacks are suppressed in the Monero C++ build patch,
     * so this value advances only after native wallet block processing.
     */
    @Synchronized
    fun recordScannedBlockHeight(processedHeight: Long, wallet: Wallet? = getWallet) {
        if (walletWipeInProgress.get() || manualResyncInProgress.get() ||
            wallet == null || !wallet.isInitialized || processedHeight <= 0L
        ) return

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
        if (walletWipeInProgress.get() || manualResyncInProgress.get()) return false
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
    suspend fun ensureNextReceiveAddress(): Subaddress = withContext(Dispatchers.IO) {
        subaddressMutex.withLock {
        if (walletWipeInProgress.get()) throw IllegalStateException("钱包正在安全删除")
        val wallet = getWallet ?: throw IllegalStateException("钱包未打开")
        val accountIndex = wallet.getAccountIndex()

        // A receive subaddress is derived from local wallet keys and does not require
        // a connected daemon. Keep this path enabled before wallet.init() so restore
        // can display a child address while Tor/RPC is still starting.
        // Never return the primary address from this receive-address operation.

        // Reuse the lowest already-generated address above the highest used index.
        // This relies on cached app state/history rather than querying native address
        // vectors while the scanner may be mutating them.
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

        // Native code waits for a refresh batch boundary, then atomically appends,
        // persists, and returns the newly generated index/address. Do not hold
        // synchronized(wallet) while it waits for the callback to unwind.
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
        }
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
            if (current == null || current.addressIndex == addedAddress.addressIndex) {
                addedAddress
            } else {
                current
            }
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

    /**
     * Refresh native transaction history and publish it without toggling the loading UI.
     * Called when the history screen becomes ready and when a blockchain scan finishes,
     * because the final native scan callback can arrive before its history snapshot is stable.
     *
     * @return number of transaction records visible for the current wallet account.
     */
    suspend fun refreshTransactionHistory(): Int = withContext(Dispatchers.IO) {
        val wallet = getWallet ?: return@withContext 0
        if (walletWipeInProgress.get() || !wallet.isInitialized) return@withContext 0

        try {
            wallet.refreshHistory()
            if (walletWipeInProgress.get() || getWallet !== wallet || !wallet.isInitialized) {
                return@withContext 0
            }

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

    fun resyncBlockchain(
        selectedRestoreHeight: Long? = null,
        knownDaemonHeight: Long? = null,
        knownDaemonTargetHeight: Long? = null
    ): Result<Boolean> {
        val wallet = getWallet
            ?: return Result.failure(Exception("Wallet is not initialized"))
        if (!wallet.isInitialized) {
            return Result.failure(Exception("Wallet is not initialized"))
        }
        val hasFreshDaemonHeights =
            knownDaemonHeight != null && knownDaemonHeight > 0L &&
                knownDaemonTargetHeight != null && knownDaemonTargetHeight > 0L
        if (!hasFreshDaemonHeights &&
            wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected
        ) {
            // A rejected reset must not erase progress from the scan already in progress.
            return Result.failure(Exception("Please connect to daemon for resync"))
        }
        if (backgroundSync) {
            return Result.failure(Exception("Exit background sync before resetting the scan"))
        }

        // Validate the daemon before changing the active scan or its visible progress.
        // The reset screen can supply a fresh direct-RPC snapshot. Prefer it over
        // native daemon-height calls, which may stall when the wallet refresh worker
        // is idle even though the node itself is reachable.
        val daemonHeight = knownDaemonHeight?.takeIf { it > 0L }
            ?: runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L).coerceAtLeast(0L)
        val daemonTargetHeight = knownDaemonTargetHeight?.takeIf { it > 0L }
            ?: runCatching { wallet.getDaemonBlockChainTargetHeight() }.getOrDefault(0L).coerceAtLeast(0L)
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

        // A rescan discards Monero's current transfer cache and only rebuilds
        // transactions from the configured refresh height. Protect an existing wallet
        // from silently losing earlier transactions and their balance by refusing a
        // start height later than the earliest confirmed transaction already known.
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
        val walletHasFunds = runCatching {
            wallet.balance > 0L || wallet.unlockedBalance > 0L
        }.getOrDefault(false)
        if (earliestKnownTransactionHeight == null && walletHasFunds && selectedHeight > 0L) {
            return Result.failure(
                Exception(
                    "当前无法确认钱包最早交易区块，而钱包已有余额。为避免重扫后旧交易和余额缺失，" +
                        "请将恢复高度设为 0，或先确保交易历史已加载。"
                )
            )
        }

        if (!manualResyncInProgress.compareAndSet(false, true)) {
            val scanAlreadyPrepared = manualResyncScanPrepared.get() ||
                _syncProgress.value != null || _isSyncing.get()
            val message = if (scanAlreadyPrepared) {
                "区块重置请求已提交，请勿重复点击"
            } else {
                "上一次重置请求仍占用钱包接口，但尚未进入区块扫描；这不代表正在同步。请关闭并重新打开钱包后再试。"
            }
            return Result.failure(Exception(message))
        }
        manualResyncScanPrepared.set(false)
        // Expose the native reset's preparation phase too. Previously the guard
        // could stay held while native wallet calls were blocked before scan progress
        // was published, leaving the UI looking idle.
        setLoading(true)
        var previousRestoreHeight: Long? = null
        var rescanQueued = false
        return try {
            previousRestoreHeight = wallet.getRestoreHeight()
            // Finish all state and wallet reads before requesting native rescan.
            // Once rescan starts, JNI reads such as balance/history can contend with
            // the refresh worker and keep the reset coroutine suspended indefinitely.
            wallet.setRestoreHeight(selectedHeight)
            check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedHeight) {
                "Selected restore height was not applied"
            }
            check(wallet.store()) { "Unable to persist selected restore height" }

            // The reset screen already queried the real daemon tip directly. Seed the
            // determinate progress state before queuing the native rescan, rather than
            // leaving the UI on an endless spinner until a refreshed/newBlock callback arrives.
            val progressTargetHeight = if (knownDaemonHeight != null && knownDaemonHeight >= 0L) {
                maxOf(
                    knownDaemonHeight,
                    knownDaemonTargetHeight ?: knownDaemonHeight
                ) + 1L // direct get_info heights are zero-based tip indices; scan progress uses counts
            } else {
                maxOf(daemonHeight, daemonTargetHeight) // native wallet heights are already counts
            }
            prepareManualResyncScanState(wallet, selectedHeight, progressTargetHeight)
            manualResyncScanPrepared.set(true)

            // This is the last operation that schedules the native scan. Do not make
            // further JNI reads before the new listener callbacks publish scan progress.
            Timber.tag(TAG).i("Manual rescan: queuing native scan from height %d", selectedHeight)
            wallet.rescanBlockchainAsync()
            rescanQueued = true
            wallet.startRefresh()
            Timber.tag(TAG).i("Manual rescan: native scan queued and refresh signalled")
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet rescan failed to start")
            if (!rescanQueued) {
                // Nothing was queued natively: restore the old setting and resume the
                // existing scanner without disturbing its current progress indicators.
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
                Result.failure(e)
            } else {
                // The native rescan is already queued and scan state was prepared before
                // queueing. Do not issue more JNI reads while the new scan is starting.
                val restart = runCatching { wallet.startRefresh() }
                if (restart.isSuccess) {
                    Result.success(true)
                } else {
                    Result.failure(restart.exceptionOrNull() ?: e)
                }
            }
        } finally {
            if (!manualResyncScanPrepared.get()) {
                // If native setup failed before scan state was published, remove only
                // the temporary loader. Preserve a pre-existing/recovery scan indicator.
                val scanPending = _scanTracking.get() ||
                    _syncProgress.value != null ||
                    prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
                setLoading(
                    scanPending && runCatching {
                        getWallet?.isInitialized == true && getWallet?.isSynchronized != true
                    }.getOrDefault(false)
                )
            }
            manualResyncInProgress.set(false)
            manualResyncScanPrepared.set(false)
        }
    }

    private val getWallet get() = WalletManager.instance?.wallet
}
