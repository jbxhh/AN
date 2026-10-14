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
        val left = daemonHeight - syncHeight
        if (syncHeight <= 0L || daemonHeight <= 0L || left < 0L) {
            walletState.clearSyncProgress()
            return
        }
        val targetHeight = wallet.getDaemonBlockChainTargetHeight()
        val progress = if (targetHeight > 0L) {
            (height.toDouble() / targetHeight.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            // An unavailable target height is not proof that synchronization is complete.
            0f
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
            tryRestartConnection()
        } else {
            val heightDiff = daemonHeight - chainHeight
            when {
                daemonHeight <= 0L || chainHeight <= 0L -> {
                    // Do not treat missing height data as a completed initial sync.
                    walletState.clearSyncProgress()
                    walletState.setLoading(false)
                }
                heightDiff >= 2L -> tryRestartConnection()
                heightDiff < 0L -> {
                    // A daemon behind the wallet cannot establish that the wallet is synced.
                    walletState.clearSyncProgress()
                    walletState.setLoading(false)
                }
                heightDiff == 1L -> {
                    // Keep the final outstanding block visible; do not mark the wallet
                    // synchronized while the remaining-block count is still non-zero.
                    updateSyncProgress(chainHeight)
                    walletState.setLoading(false)
                }
                else -> {
                    // Always emit the zero-remaining update, even if the wallet had
                    // previously been marked synchronized, so an old progress state clears.
                    updateSyncProgress(chainHeight)
                    wallet.setSynchronized()
                    wallet.store()
                    refresh(true)
                    walletState.setLoading(false)
                }
            }
        }
        walletState.update()
    }

    private fun tryRestartConnection() {
        try {
            if (wallet.init(0)) {
                wallet.startRefresh()
            } else {
                Timber.tag(name).w("Wallet initialization failed; refresh was not started")
            }
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Unable to restart wallet connection")
        } finally {
            // A failed reconnect must not leave the loading indicator stuck indefinitely.
            walletState.setLoading(false)
            walletState.update()
        }
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