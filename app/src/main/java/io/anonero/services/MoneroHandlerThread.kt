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
        updateSyncProgress(height)
    }

    private fun updateSyncProgress(height: Long) {
        val syncHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        if (syncHeight < 0L || daemonHeight <= 0L) {
            walletState.clearSyncProgress()
            return
        }

        val left = (daemonHeight - syncHeight).coerceAtLeast(0L)
        if (left == 0L) {
            walletState.syncUpdate(SyncProgress(progress = 1f, left = 0L))
            return
        }

        val targetHeight = wallet.getDaemonBlockChainTargetHeight()
        val progress = if (targetHeight <= 0L) {
            0f
        } else {
            (maxOf(height, syncHeight).toDouble() / targetHeight.toDouble())
                .toFloat()
                .coerceIn(0f, 0.999f)
        }
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
        /// height
        Timber.tag(name)
            .i("refreshed() status:${status} daemonHeight:$daemonHeight chainHeight:$chainHeight ")
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            walletState.clearSyncProgress()
            walletState.setLoading(false)
            tryRestartConnection()
        } else {
            val heightDiff = daemonHeight - chainHeight
            if (heightDiff >= 2) {
                tryRestartConnection()
            } else {
                // The wallet has caught up (or is ahead of a stale daemon height).
                // Always clear any previous progress, even if isSynchronized was
                // already true before this callback.
                walletState.syncUpdate(SyncProgress(progress = 1f, left = 0L))
                wallet.setSynchronized()
                wallet.store()
                refresh(true)
                walletState.setLoading(false)
            }

        }
        walletState.update()
    }

    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
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
        // from src/cryptonote_config.h
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}