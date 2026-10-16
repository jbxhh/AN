package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import timber.log.Timber

/**
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 * The started Thread has a stck size of STACK_SIZE (=5MB)
 */

class MoneroHandlerThread(private val wallet: Wallet, private val walletState: WalletState) :
    Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {

    @Volatile
    private var lastReconnectAttemptMs = 0L

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
        WalletManager.instance?.wallet?.store()
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {}

    override fun newBlock(height: Long) {
        Timber.tag(name).i("newBlock: %s", height)
        updateSyncProgress()
    }

    private fun updateSyncProgress() {
        val status = wallet.fullStatus.connectionStatus
        if (status != Wallet.ConnectionStatus.ConnectionStatus_Connected) return

        val syncHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        if (syncHeight < 0L || daemonHeight <= 0L) return

        val left = daemonHeight - syncHeight
        if (left <= 1L) {
            walletState.syncUpdate(SyncProgress(1f, 0L))
            // A final new-block callback can arrive without a following refreshed()
            // callback. Complete the wallet refresh here too, so loading, balance,
            // coins, and transaction history cannot remain stale at the tip.
            if (!wallet.isSynchronized) {
                wallet.setSynchronized()
                wallet.store()
                refresh(true)
            } else {
                walletState.setLoading(false)
                walletState.update()
            }
            return
        }

        val reportedTarget = wallet.getDaemonBlockChainTargetHeight()
        val targetHeight = if (reportedTarget > 0L) reportedTarget else daemonHeight
        val progress = (syncHeight.toDouble() / targetHeight.toDouble())
            .toFloat()
            .coerceIn(0f, 0.999f)
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val chainHeight = wallet.getBlockChainHeight()
        Timber.tag(name)
            .i("refreshed() status:$status daemonHeight:$daemonHeight chainHeight:$chainHeight")

        if (status != null) {
            walletState.setConnectionStatus(status)
        }

        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            // Do not keep an indefinite spinner visible while the daemon is disconnected.
            // Retry without reinitializing an already initialized wallet on every callback.
            walletState.setLoading(false)
            tryRestartConnection()
            walletState.update()
            return
        }

        if (status != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            walletState.setLoading(false)
            walletState.clearSyncProgress()
            walletState.update()
            return
        }

        if (daemonHeight <= 0L || chainHeight < 0L) {
            Timber.tag(name).w(
                "Cannot validate sync heights yet: daemonHeight=%s chainHeight=%s",
                daemonHeight,
                chainHeight
            )
            walletState.clearSyncProgress()
            walletState.setLoading(false)
            walletState.update()
            return
        }

        val heightDiff = daemonHeight - chainHeight
        updateSyncProgress()
        if (heightDiff >= 2L) {
            // Keep the refresh worker running and allow history/balance snapshots
            // to update while the wallet catches up.
            wallet.refreshHistory()
            walletState.update()
        } else {
            if (!wallet.isSynchronized) {
                wallet.setSynchronized()
                wallet.store()
                refresh(true)
            } else {
                refresh(true)
            }
            walletState.setLoading(false)
        }
        walletState.update()
    }

    @Synchronized
    private fun tryRestartConnection() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectAttemptMs < RECONNECT_RETRY_INTERVAL_MS) return
        lastReconnectAttemptMs = now

        if (!wallet.isInitialized && !wallet.init(0)) {
            Timber.tag(name).e("wallet.init(0) retry failed: %s", wallet.status.errorString)
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
            walletState.update()
            return
        }

        if (wallet.isInitialized) {
            wallet.startRefresh()
        }
        walletState.update()
    }

    private fun refresh(walletSynced: Boolean) {
        wallet.refreshHistory()
        if (walletSynced) {
            wallet.refreshCoins()
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
        private const val RECONNECT_RETRY_INTERVAL_MS = 15_000L
        // from src/cryptonote_config.h
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}