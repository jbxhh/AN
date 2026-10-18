package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 * The started Thread has a stck size of STACK_SIZE (=5MB)
 */

class MoneroHandlerThread(
    private val wallet: Wallet,
    private val walletState: WalletState,
    private val walletHandler: AnonWalletHandler
) : Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {

    private val postCompletionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val postCompletionRefreshInFlight = AtomicBoolean(false)
    private val lastPostCompletionRefreshHeight = AtomicLong(-1L)
    private val walletUpdateCallbackPending = AtomicBoolean(false)
    private val walletUpdateCallbackWorkerActive = AtomicBoolean(false)
    private val refreshedCallbackPending = AtomicBoolean(false)
    private val refreshedCallbackWorkerActive = AtomicBoolean(false)
    private val connectionRecoveryInFlight = AtomicBoolean(false)
    private val lastConnectionRecoveryAtMillis = AtomicLong(0L)

    @Synchronized
    override fun start() {
        super.start()
    }

    override fun run() {

    }

    override fun moneySpent(txId: String?, amount: Long) {

    }

    override fun moneyReceived(txId: String?, amount: Long) {
        Timber.tag(name).i("moneyReceived: %s", amount)
        postCompletionScope.launch {
            if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) return@launch
            runCatching { wallet.store() }
                .onFailure { Timber.tag(name).w(it, "Wallet store after received transaction failed") }
        }
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {}

    override fun newBlock(height: Long) {
        // anonero.cpp already converts Monero's zero-based block index to the
        // one-based processed-block count before calling this Java listener.
        // Do not increment here a second time; scan and daemon heights must use the
        // same block-count semantics or completion will be rejected by exactly one.
        val processedHeight = height
        Timber.tag(name).i("newBlock processed block count=%d", processedHeight)
        // Do not switch off the loading phase here. recordScannedBlockHeight publishes
        // the syncing bar first; the progress indicator prioritizes it over loading.
        walletState.recordScannedBlockHeight(processedHeight, wallet)
    }

    override fun updated() {
        // Listener callbacks run from native wallet code. Defer JNI history/status reads
        // so they cannot stall later newBlock callbacks during an ordinary initial scan.
        if (walletState.isResyncPriorityActive ||
            walletState.isWalletWipeInProgress ||
            walletHandler.isManualNodeConnectionInProgress()
        ) {
            Timber.tag(name).d("Skipping wallet update while a priority operation or node switch owns wallet")
            return
        }
        walletUpdateCallbackPending.set(true)
        scheduleWalletUpdateOutsideNativeCallback()
    }

    private fun scheduleWalletUpdateOutsideNativeCallback() {
        if (!walletUpdateCallbackWorkerActive.compareAndSet(false, true)) return
        postCompletionScope.launch {
            try {
                while (walletUpdateCallbackPending.getAndSet(false)) {
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) break
                    if (walletState.isResyncPriorityActive || walletState.isWalletWipeInProgress) continue
                    refresh()
                    Timber.tag(name).i("updated() processed outside native wallet callback")
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Deferred wallet update failed")
            } finally {
                walletUpdateCallbackWorkerActive.set(false)
                if (walletUpdateCallbackPending.get() &&
                    WalletManager.instance?.wallet === wallet &&
                    wallet.isInitialized &&
                    !walletState.isResyncPriorityActive &&
                    !walletState.isWalletWipeInProgress
                ) scheduleWalletUpdateOutsideNativeCallback()
            }
        }
    }

    override fun refreshed() {
        // Return immediately from native callback; perform JNI/status work after locks unwind.
        refreshedCallbackPending.set(true)
        scheduleRefreshedProcessingOutsideNativeCallback()
    }

    private fun scheduleRefreshedProcessingOutsideNativeCallback() {
        if (!refreshedCallbackWorkerActive.compareAndSet(false, true)) return
        postCompletionScope.launch {
            try {
                while (refreshedCallbackPending.getAndSet(false)) {
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) break
                    processRefreshedCallback()
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Deferred refreshed callback processing failed")
            } finally {
                refreshedCallbackWorkerActive.set(false)
                if (refreshedCallbackPending.get() &&
                    WalletManager.instance?.wallet === wallet &&
                    wallet.isInitialized
                ) scheduleRefreshedProcessingOutsideNativeCallback()
            }
        }
    }

    private fun processRefreshedCallback() {
        // The node-connect path pauses and drains native refresh before daemon/init changes.
        // Drop any queued callback from the old daemon; the new refresh will emit fresh data.
        if (walletHandler.isManualNodeConnectionInProgress() || walletState.isWalletWipeInProgress) return
        val status = wallet.fullStatus.connectionStatus
        // Use one live daemon snapshot plus its target. Reaching the current node tip
        // is not wallet sync completion while that node still has blocks to download.
        // Wallet API height methods return chain heights as counts in this build.
        // Only WalletListener.newBlock needs conversion, and anonero.cpp already applies it.
        val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
        val daemonTargetHeight = if (
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected
        ) {
            runCatching { wallet.getDaemonBlockChainTargetHeight() }
                .getOrDefault(0L).coerceAtLeast(0L)
        } else {
            0L
        }
        walletState.updateDaemonTargetHeight(daemonTargetHeight)
        walletState.updateBlockHeights(wallet, daemonHeight)
        // Do not wait for completeWalletScan(): a restored wallet starts below the daemon
        // tip by design, so the completion branch cannot be the first place that queues
        // its required rescan. This check retries until the connected daemon is synced.
        walletState.maybeStartForcedRestoreScan(wallet)
        val scanHeight = walletState.currentWalletHeight
        Timber.tag(name).i(
            "refreshed() status:%s daemonHeight:%d daemonTargetHeight:%d scanHeight:%d nativeHeight:%d",
            status, daemonHeight, daemonTargetHeight, scanHeight, wallet.getBlockChainHeight()
        )

        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            // During first restore, the connection-status callback can lag behind a
            // successful height RPC. If the node tip is already known, publish the
            // actual selected-height backlog before attempting native reinitialization.
            // Otherwise the UI remains stuck on the indeterminate refresh bar until
            // the user leaves and re-enters the wallet.
            val progressTargetHeight = maxOf(daemonHeight, daemonTargetHeight)
            val hasKnownBacklog = daemonHeight > 0L && scanHeight < progressTargetHeight
            if (hasKnownBacklog) {
                wallet.isSynchronized = false
                walletState.updateSyncProgressFromWallet(wallet, daemonHeight)
                walletState.setLoading(false)
            } else {
                walletState.setLoading(true)
            }
            tryRestartConnection()
        } else if (daemonHeight <= 0L) {
            // Unknown daemon height is not proof of synchronization or scan completion.
            walletState.setLoading(true)
        } else if (scanHeight > daemonHeight) {
            // A selected restore point beyond the daemon tip is not a completed scan.
            wallet.isSynchronized = false
            walletState.clearSyncProgress()
            walletState.setLoading(false)
            Timber.tag(name).e(
                "Refusing sync completion: processed height %d exceeds daemon height %d",
                scanHeight, daemonHeight
            )
        } else if (scanHeight < daemonHeight) {
            // A new backlog means a later caught-up transition may need a post-sync
            // history refresh, even if the wallet previously completed at the same tip.
            lastPostCompletionRefreshHeight.set(-1L)
            // Keep the loading phase active until the sync-progress flow is established.
            // The existing progress UI switches to its determinate bar as soon as it sees it.
            wallet.isSynchronized = false
            walletState.updateSyncProgressFromWallet(wallet, daemonHeight)
            // A confirmed node tip ends the temporary loader; the determinate
            // bar now shows real remaining blocks from the selected scan height.
            walletState.setLoading(false)
        } else {
            val scanCompleted = walletState.completeWalletScan(wallet, daemonHeight)
            if (scanCompleted) {
                wallet.setSynchronized()
                wallet.store()
                refresh()
                walletState.setLoading(false)
                // History refresh inside the native callback can run before the native
                // scan has published its final transaction list. Re-read it after the
                // callback unwinds so the user does not need to pull-to-refresh.
                schedulePostCompletionRefresh()
            } else {
                wallet.isSynchronized = false
                if (daemonTargetHeight > daemonHeight) {
                    // Keep showing real remaining blocks while the node catches up;
                    // do not finish and restart the wallet's progress at every tip.
                    walletState.updateSyncProgressFromWallet(wallet, daemonHeight)
                    walletState.setLoading(false)
                } else {
                    // If a selected-height rescan is queued at the current tip, keep
                    // the loader visible until the native scan starts processing.
                    walletState.setLoading(walletState.currentWalletHeight >= daemonHeight)
                }
            }
        }

        walletState.update()
    }


    /**
     * Refresh transaction history outside the native refreshed() callback. The first
     * read may observe an empty/stale history while the native scan is still unwinding.
     * If a wallet with funds still has no history, retry once after another short delay.
     */
    private fun schedulePostCompletionRefresh() {
        val completedHeight = walletState.currentWalletHeight
        if (lastPostCompletionRefreshHeight.get() == completedHeight) return
        if (!postCompletionRefreshInFlight.compareAndSet(false, true)) return
        lastPostCompletionRefreshHeight.set(completedHeight)

        postCompletionScope.launch {
            try {
                repeat(2) { attempt ->
                    delay(if (attempt == 0) 600L else 1_200L)
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) {
                        return@launch
                    }

                    try {
                        // Output enumeration can lag behind the scan-completion callback.
                        // Refresh it with history so outputs discovered during restore become
                        // visible without requiring the user to manually reconnect the node.
                        if (wallet.isSynchronized) {
                            wallet.refreshCoins()
                        }
                        wallet.refreshHistory()
                        walletState.update()
                    } catch (e: Exception) {
                        Timber.tag(name).w(e, "Deferred wallet output/history refresh failed")
                    }

                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) {
                        return@launch
                    }

                    // Re-check live heights after the native callback has returned.
                    // Only finish the progress state when the wallet really caught up
                    // and the daemon itself has no remaining target backlog.
                    if (wallet.fullStatus.connectionStatus ==
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        val daemonHeight = runCatching {
                            wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
                        }.getOrDefault(0L)
                        val daemonTargetHeight = runCatching {
                            wallet.getDaemonBlockChainTargetHeight().coerceAtLeast(0L)
                        }.getOrDefault(0L)
                        walletState.updateDaemonTargetHeight(daemonTargetHeight)
                        if (daemonHeight > 0L) {
                            walletState.updateBlockHeights(wallet, daemonHeight)
                            if (walletState.currentWalletHeight == daemonHeight &&
                                daemonTargetHeight <= daemonHeight
                            ) {
                                if (walletState.completeWalletScan(wallet, daemonHeight)) {
                                    wallet.setSynchronized()
                                    runCatching { wallet.store() }.onFailure { error ->
                                        Timber.tag(name).w(error, "Unable to store completed wallet scan")
                                    }
                                    walletState.clearSyncProgress()
                                    walletState.setLoading(false)
                                }
                            }
                        }
                    }

                    val transactionCount = wallet.history?.all?.size ?: 0
                    Timber.tag(name).i(
                        "Post-sync refresh attempt=%d transactions=%d walletHeight=%d daemonHeight=%d",
                        attempt + 1,
                        transactionCount,
                        walletState.currentWalletHeight,
                        walletState.currentDaemonHeight
                    )
                    if (transactionCount > 0 ||
                        (wallet.balance <= 0L && wallet.unlockedBalance <= 0L)
                    ) {
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Post-sync history refresh failed")
            } finally {
                postCompletionRefreshInFlight.set(false)
            }
        }
    }

    private fun tryRestartConnection() {
        // This method is called from WalletListener.refreshed(), which is itself a
        // native wallet callback. Never call wallet.init() inline here: re-entering
        // native initialization from the refresh callback can stall refresh and race
        // a manual node connection or a manual rescan handoff.
        if (
            walletHandler.isManualNodeConnectionInProgress() ||
            walletState.isManualResyncInProgress ||
            walletState.isWalletWipeInProgress
        ) return
        val now = System.currentTimeMillis()
        if (now - lastConnectionRecoveryAtMillis.get() < CONNECTION_RECOVERY_COOLDOWN_MILLIS) return
        if (!connectionRecoveryInFlight.compareAndSet(false, true)) return
        lastConnectionRecoveryAtMillis.set(now)

        postCompletionScope.launch {
            try {
                // Let the native refreshed() callback fully unwind before any JNI init.
                delay(750L)
                if (
                    walletHandler.isManualNodeConnectionInProgress() ||
                    walletState.isManualResyncInProgress ||
                    walletState.isWalletWipeInProgress
                ) return@launch
                walletHandler.withWalletInitializationLock {
                    // A user-initiated connection or reset takes priority over automatic
                    // recovery, including requests that began while this coroutine queued.
                    if (
                        walletHandler.isManualNodeConnectionInProgress() ||
                        walletState.isManualResyncInProgress ||
                        walletState.isWalletWipeInProgress
                    ) {
                        Timber.tag(name).i("Skipping automatic reconnect while a manual wallet operation is pending")
                        return@withWalletInitializationLock
                    }
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) {
                        return@withWalletInitializationLock
                    }
                    if (runCatching { wallet.fullStatus.connectionStatus }.getOrNull() ==
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        walletState.update()
                        return@withWalletInitializationLock
                    }

                    Timber.tag(name).w("Retrying daemon initialization outside native refresh callback")
                    // Preserve a pending restore's selected height across this real retry.
                    walletState.prepareForcedRestoreScanRetry()
                    val initialized = wallet.init(0)
                    if (initialized && wallet.isInitialized) {
                        walletState.preservePendingRestoreHeightAfterInit(wallet)
                    }
                    wallet.startRefresh()
                    walletState.update()
                }
            } catch (e: Exception) {
                Timber.tag(name).e(e, "Automatic daemon reconnect failed")
            } finally {
                connectionRecoveryInFlight.set(false)
            }
        }
    }

    private fun refresh() {
        // WalletListener callbacks run from native wallet code. Do not enumerate native
        // outputs here: Coins.refresh() can contend with the active scanner/native locks.
        // Output refresh is scheduled on Dispatchers.IO after the completion callback
        // unwinds, so a blocked output query cannot prevent that deferred work from starting.
        try {
            wallet.refreshHistory()
        } catch (e: Exception) {
            Timber.tag(name).w(e, "Wallet history refresh failed; publishing current wallet state")
        }
        walletState.update()
    }

    fun sendTx(pendingTx: PendingTransaction): Boolean {
        return pendingTx.commit("", true)
    }

    interface Listener {
        fun onRefresh(walletSynced: Boolean)
        fun onConnectionFail()
        fun onNewBlockFound(block: Long)
    }

    companion object {
        // Avoid repeated native init attempts when refreshed() reports Disconnected
        // several times during one failed connection attempt.
        private const val CONNECTION_RECOVERY_COOLDOWN_MILLIS = 15_000L

        // from src/cryptonote_config.h
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}