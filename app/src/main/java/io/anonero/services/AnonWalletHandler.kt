package io.anonero.services

import android.content.SharedPreferences
import android.util.Log
import io.anonero.AnonConfig
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.DaemonInfo
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean


class InvalidPin : Exception("invalid pin")

private const val TAG = "AnonWalletHandler"

class UnableToCloseWallet : Exception()

class AnonWalletHandler(
    private val prefs: SharedPreferences,
    private val walletState: WalletState,
    private val torService: TorService
) {

    private val _scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    private var handler: MoneroHandlerThread? = null
    private val wipeInProgress = AtomicBoolean(false)

    val scope get() = _scope

    init {
        scope.launch {
            torService.socksFlow.collect {
                val wallet = WalletManager.instance?.wallet
                if (!wipeInProgress.get() && prefs.getBoolean(WALLET_USE_TOR, false) && wallet?.isInitialized == true) {
                    setProxy(it.address.toString(), it.port.value)
                }
            }
        }
    }

    fun openWallet(pin: String): Boolean {
        val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
        val anonWallet = WalletManager.instance?.openWallet(
            walletFile.path,
            pin,
        )
        if (anonWallet?.status?.isOk != true) {
            Timber.tag(TAG).e("openWallet error: %s", anonWallet?.status?.errorString)
        } else {
            // Publish balance/address from the opened wallet immediately. The asynchronous
            // service still has to configure Tor and initialize the daemon connection.
            walletState.publishWalletSnapshot(anonWallet)
        }
        return anonWallet?.status?.isOk ?: throw InvalidPin()
    }

    suspend fun startService() {
        if (wipeInProgress.get()) return
        val wallet = WalletManager.instance?.wallet ?: return
        if (wipeInProgress.get()) return
        handler = MoneroHandlerThread(
            wallet,
            walletState
        )

        wallet.setListener(handler)
        handler?.start()
        walletState.setLoading(true)
        try {
            val host = prefs.getString(NodeFields.RPC_HOST.value, "")
            val rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
            val rpcUsername = prefs.getString(NodeFields.RPC_USERNAME.value, "")
            val rpcPassphrase = prefs.getString(NodeFields.RPC_PASSWORD.value, "")
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
            if (useTor || proxyHost.isNullOrBlank()) {
                torService.start()
                val socket = withTimeoutOrNull(30_000L) {
                    while (torService.socks == null) {
                        delay(200)
                    }
                    torService.socks
                } ?: throw IllegalStateException("Timed out waiting for Tor SOCKS proxy")
                if (wipeInProgress.get()) return
                WalletManager.instance?.setProxy("${socket.value}")
            } else if (proxyHost.isNotEmpty() && proxyPort != -1) {
                WalletManager.instance?.setProxy("${proxyHost}:$proxyPort")
            } else {
                throw Exception("no proxy")
            }
            if (host?.isNotEmpty() == true) {
                val nodeObj = JSONObject()
                    .apply {
                        put(NodeFields.RPC_HOST.value, host)
                        put(NodeFields.RPC_PORT.value, rpcPort)
                        put(NodeFields.RPC_USERNAME.value, rpcUsername)
                        put(NodeFields.RPC_PASSWORD.value, rpcPassphrase)
                        put(NodeFields.RPC_NETWORK.value, AnonConfig.getNetworkType().toString())
                        put(NodeFields.NODE_NAME.value, "anon")
                    }
                val node = Node.fromJson(nodeObj)
                updateDaemon(node)
                walletState.setLoading(true)
            }
            if (wipeInProgress.get()) return
            // Monero's WalletImpl::doInit() treats an opened wallet with only
            // the genesis cache as a brand-new wallet and replaces refresh_height with
            // the daemon tip. That destroys the user-selected restore height because the
            // native "recovering from seed" flag is not retained after this wallet is
            // closed and reopened by the service. Capture the stored value before init.
            val forceFreshScan = prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
            val selectedRestoreHeightBeforeInit =
                if (forceFreshScan) walletState.pendingSelectedRestoreHeight(wallet) else null

            val initialized = wallet.init(0)
            if (initialized && wallet.isInitialized) {
                if (forceFreshScan && selectedRestoreHeightBeforeInit != null) {
                    // The height entered on the restore screen is authoritative;
                    // native init can replace the wallet's refresh start with the daemon tip.
                    val reappliedHeight = walletState.preservePendingRestoreHeightAfterInit(wallet)
                    check(reappliedHeight == selectedRestoreHeightBeforeInit) {
                        "Selected restore height changed during native init"
                    }
                    Timber.tag(TAG).i(
                        "Preserved selected restore height after wallet init: %d",
                        reappliedHeight
                    )
                }

                val actualRestoreHeight = wallet.getRestoreHeight().coerceAtLeast(0L)
                prefs.edit(commit = true) { putLong(RESTORE_HEIGHT, actualRestoreHeight) }
                wallet.setTrustedDaemon(true)
                // Recover an interrupted or legacy scan from the selected restore point.
                // Native hash-chain height alone is not proof transactions were scanned.
                val resumeScan = walletState.beginWalletScan(
                    wallet = wallet,
                    scanStartHeight = actualRestoreHeight,
                    forceStartAtSelectedHeight = forceFreshScan
                )
                // Publish local data and start the refresh/rescan immediately after the
                // selected restore height is recorded. Never wait for refreshHistory()
                // before starting the scan and allowing refreshed() to publish the live tip.
                walletState.publishWalletSnapshot(wallet)
                if (forceFreshScan) {
                    // Keep a normal refresh running so we can monitor daemon sync,
                    // but leave the fresh-restore marker set. WalletState will queue a
                    // native rescan from the chosen height after the daemon fully syncs;
                    // init may already have advanced the native hash cache to the node tip.
                    Timber.tag(TAG).i(
                        "Starting fresh wallet scan from selected restore height: %d",
                        actualRestoreHeight
                    )
                    wallet.startRefresh()
                } else {
                    if (resumeScan) {
                        // Recover an interrupted or legacy scan by clearing stale scan state.
                        // Enable the worker after queuing the asynchronous rescan.
                        wallet.rescanBlockchainAsync()
                    }
                    wallet.startRefresh()
                }
                try {
                    wallet.refreshHistory()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Initial history refresh failed; continuing wallet startup")
                }
                try {
                    walletState.update()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Initial wallet state refresh failed; continuing wallet startup")
                }
            } else {
                Timber.tag(TAG).e("Wallet initialization failed: %s", wallet.status.errorString)
                walletState.clearSyncProgress()
                walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
                walletState.setLoading(false)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet service startup failed")
            walletState.clearSyncProgress()
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
        }

    }

    fun updateDaemon(node: Node?) {
        walletState.setLoading(true)
        val wallet = WalletManager.instance?.wallet
        val walletManager = WalletManager.instance
        wallet?.pauseRefresh()
        try {
            if (node != null) {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = "${node.host}:${node.rpcPort}",
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            } else {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = null,
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            }
            walletManager?.setDaemon(node)
            wallet?.setTrustedDaemon(true)

            // Changing the daemon pauses the native refresh worker above. If this
            // wallet was already initialized, restart it here so daemon-height and
            // block-processing callbacks resume without requiring a manual refresh.
            // During initial startup isInitialized is false; startService() starts
            // the worker after wallet.init() succeeds.
            if (node != null && wallet?.isInitialized == true) {
                wallet.startRefresh()
            }

            walletState.setLoading(false)
        } catch (e: Exception) {
            walletState.updateDaemon(
                DaemonInfo(
                    daemon = null,
                    Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    daemonHeight = 0L
                )
            )
            e.printStackTrace()
        }

    }

    fun setProxy(proxy: String?, port: Int?) {
        if (wipeInProgress.get()) return
        Timber.tag(TAG).d("setProxy %s%s", proxy, port.toString())
        //disable proxy
        if (proxy == null && port == null) {
            prefs.edit {
                remove(WALLET_PROXY)
                remove(WALLET_PROXY_PORT)
            }
        } else {
            prefs.edit {
                putString(WALLET_PROXY, proxy)
                putInt(WALLET_PROXY_PORT, port ?: -1)
            }
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val proxyStr = if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
                "${proxyHost}:$proxyPort"
            } else {
                return
            }
            WalletManager.instance?.setProxy(proxyStr)
            WalletManager.instance?.wallet?.setProxy(proxyStr)
        }
    }

    fun getProxy(): Pair<String, Int>? {
        val proxyHost = prefs.getString(WALLET_PROXY, "")
        val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

        if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
            return Pair(proxyHost, proxyPort)
        }
        return null
    }

    suspend fun wipe(passPhrase: String): Boolean {
        check(wipeInProgress.compareAndSet(false, true)) {
            "Wallet wipe is already in progress"
        }

        val wallet = WalletManager.instance?.wallet
            ?: run {
                wipeInProgress.set(false)
                throw IllegalStateException("Wallet is not open; cannot safely close it")
            }
        if (!walletState.beginWalletWipe()) {
            wipeInProgress.set(false)
            throw IllegalStateException("Wallet wipe is already in progress")
        }
        var refreshPaused = false
        var listenerDetached = false
        var walletClosed = false

        try {
            // Stop background sync only if the app actually entered that mode.
            // Calling the native stop routine unconditionally can stall normal wipes.
            if (walletState.backgroundSync) {
                check(wallet.stopBackgroundSync(passPhrase)) {
                    "Unable to stop background sync; wallet was not deleted"
                }
                walletState.setBackGroundSync(false)
            }

            Timber.tag(TAG).i("Secure wipe: pausing wallet refresh")
            wallet.pauseRefresh()
            refreshPaused = true
            // Drain the app's explicitly tracked daemon-height JNI query before closing.
            walletState.awaitPendingNativeQueries()

            // No native listener may call into WalletState while closeWallet() releases
            // the native wallet and its JNI listener reference.
            Timber.tag(TAG).i("Secure wipe: detaching wallet listener")
            wallet.setListener(null)
            listenerDetached = true

            Timber.tag(TAG).i("Secure wipe: closing native wallet")
            check(wallet.close()) { throw UnableToCloseWallet() }
            walletClosed = true

            WalletManager.instance?.setDaemon(null)
            // The native object is closed now. Clear all in-memory references even if
            // filesystem deletion later needs recovery on the next process start.
            walletState.resetAfterWalletWipe()

            val context = AnonConfig.context
                ?: throw IllegalStateException("Application context unavailable")
            val walletDir = java.io.File(context.filesDir, "wallets")
            val removed = !walletDir.exists() || walletDir.deleteRecursively()
            check(removed && !java.io.File(walletDir, "anon").exists()) {
                "Native wallet closed, but wallet files could not be deleted"
            }

            Timber.tag(TAG).i("Secure wipe: wallet files deleted")
            return true
        } catch (e: Exception) {
            if (!walletClosed) {
                // Closing was rejected: reconnect the old listener and resume the
                // existing wallet rather than leaving it open but inert.
                if (listenerDetached) {
                    runCatching { wallet.setListener(handler) }
                        .onFailure { Timber.tag(TAG).e(it, "Unable to restore wallet listener after failed wipe") }
                }
                if (refreshPaused) {
                    runCatching { wallet.startRefresh() }
                        .onFailure { Timber.tag(TAG).e(it, "Unable to resume wallet refresh after failed wipe") }
                }
            }
            throw e
        } finally {
            if (!walletClosed) walletState.endWalletWipe()
            wipeInProgress.set(false)
        }
    }

}
