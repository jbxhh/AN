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
        Timber.tag(name).i("newBlock processed scan block: %s", height)
        // Do not switch off the loading phase here. recordScannedBlockHeight publishes
        // the syncing bar first; the progress indicator prioritizes it over loading.
        walletState.recordScannedBlockHeight(height, wallet)
    }

    override fun updated() {
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        val status = wallet.fullStatus.connectionStatus
        // A single node-height snapshot prevents successive RPC calls from racing and
        // clearing progress between a valid height and a transient zero/different value.
        val daemonHeight = wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
        walletState.updateBlockHeights(wallet, daemonHeight)
        val scanHeight = walletState.currentWalletHeight
        Timber.tag(name).i(
            "refreshed() status:%s daemonHeight:%d scanHeight:%d nativeHeight:%d",
            status, daemonHeight, scanHeight, wallet.getBlockChainHeight()
        )

        if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            // Keep a visible loading phase while trying to restore the daemon connection.
            // The progress indicator will still prioritize any last known sync backlog.
            walletState.setLoading(true)
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
            // Keep the loading phase active until the sync-progress flow is established.
            // The existing progress UI switches to its determinate bar as soon as it sees it.
            wallet.isSynchronized = false
            walletState.updateSyncProgressFromWallet(wallet, daemonHeight)
            // A confirmed node tip ends the temporary loader; the determinate
            // bar now shows real remaining blocks from the selected scan height.
            walletState.setLoading(false)
        } else {
            walletState.completeWalletScan(wallet, daemonHeight)
            wallet.setSynchronized()
            wallet.store()
            refresh(true)
            walletState.setLoading(false)
        }

        walletState.update()
    }

    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
        walletState.update()
    }

    private fun refresh(walletSynced: Boolean) {
        // A transient history refresh failure must not prevent live balance/state
        // publication during restore or rescan.
        try {
            wallet.refreshHistory()
        } catch (e: Exception) {
            Timber.tag(name).w(e, "Wallet history refresh failed; publishing current wallet state")
        }
        if (walletSynced) {
            try {
                wallet.refreshCoins()
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Coin refresh failed")
            }
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