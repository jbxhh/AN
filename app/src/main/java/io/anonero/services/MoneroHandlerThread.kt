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
        walletState.updateBlockHeights(wallet)
        walletState.updateSyncProgressFromWallet(wallet)
        walletState.setLoading(false)
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
        Timber.tag(name).i(
            "refreshed() status:%s daemonHeight:%d chainHeight:%d",
            status, daemonHeight, chainHeight
        )

        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            walletState.clearSyncProgress()
            walletState.setLoading(false)
            tryRestartConnection()
        } else if (daemonHeight <= 0L || chainHeight < 0L) {
            // Unknown heights are not proof that the wallet is synchronized.
            walletState.clearSyncProgress()
            walletState.setLoading(false)
        } else {
            val heightDiff = daemonHeight - chainHeight
            if (heightDiff > 0L) {
                // Don't publish partial balance/history while the wallet is behind.
                wallet.isSynchronized = false
                // Keep the wallet's existing refresh loop running. Re-running init(0)
                // during synchronization can reset state and leave loading stuck.
                walletState.updateBlockHeights(wallet)
                walletState.updateSyncProgressFromWallet(wallet)
                walletState.setLoading(false)
            } else {
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