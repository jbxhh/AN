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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val serviceStartMutex = Mutex()
    private val walletInitializationMutex = Mutex()
    @Volatile private var serviceStartedWallet: Wallet? = null

    val scope get() = _scope

    init {
        scope.launch {
            torService.socksFlow.collect {
                val wallet = WalletManager.instance?.wallet
                if (!wipeInProgress.get() && prefs.getBoolean(WALLET_USE_TOR, false) && wallet?.isInitialized == true) {
                    val proxyAddress = "${it.address}:${it.port.value}"
                    WalletManager.instance?.setProxy(proxyAddress)
                    wallet.setProxy(proxyAddress)
                    Timber.tag(TAG).d("Applied Tor SOCKS proxy %s", proxyAddress)
                }
            }
        }
    }

    fun openWallet(pin: String): Boolean {
        val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
        val anonWallet = WalletManager.instance?.openWallet(
            walletFile.path,
            pin,
        ) ?: throw InvalidPin()
        val opened = anonWallet.status.isOk
        if (!opened) {
            val error = anonWallet.status.errorString
            Timber.tag(TAG).e("openWallet error: %s", error)
            // WalletManager manages the native handle even when the password was wrong.
            // Close it before returning so another correct PIN attempt can retry cleanly.
            runCatching { anonWallet.close() }
                .onFailure { closeError ->
                    Timber.tag(TAG).w(closeError, "Unable to close wallet after failed PIN attempt")
                }
            return false
        }

        // Publish balance/address from the opened wallet immediately. The asynchronous
        // service still has to configure Tor and initialize the daemon connection.
        walletState.publishWalletSnapshot(anonWallet)
        return true
    }

    suspend fun startService() {
        serviceStartMutex.lock()
        try {
            startServiceLocked()
        } finally {
            serviceStartMutex.unlock()
        }
    }

    private suspend fun startServiceLocked() {
        if (wipeInProgress.get()) return
        val wallet = WalletManager.instance?.wallet ?: return
        if (wipeInProgress.get()) return
        if (serviceStartedWallet === wallet && wallet.isInitialized) {
            walletState.update()
            return
        }

        handler = MoneroHandlerThread(wallet, walletState)
        wallet.setListener(handler)
        handler?.start()
        walletState.setLoading(true)

        try {
            // A single 30-second wait is not a failed wallet startup. Tor may still be
            // bootstrapping on a first install; keep the daemon alive and continue waiting.
            while (!wipeInProgress.get()) {
                val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
                val proxyHost = prefs.getString(WALLET_PROXY, "")
                val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

                if (useTor) {
                    val readySocket = torService.awaitSocks(30_000L)
                    if (readySocket == null) {
                        Timber.tag(TAG).w(
                            "Tor SOCKS is still starting; wallet initialization will retry"
                        )
                        walletState.setLoading(true)
                        delay(2_000L)
                        continue
                    }
                    val proxyAddress = "${readySocket.address}:${readySocket.port.value}"
                    WalletManager.instance?.setProxy(proxyAddress)
                    wallet.setProxy(proxyAddress)
                } else if (!proxyHost.isNullOrBlank() && proxyPort != -1) {
                    val proxyAddress = "${proxyHost}:$proxyPort"
                    WalletManager.instance?.setProxy(proxyAddress)
                    wallet.setProxy(proxyAddress)
                } else {
                    // Tor is disabled and no custom proxy is configured: use a direct node
                    // connection rather than trying a stale or unavailable SOCKS endpoint.
                    setProxy(null, null)
                }
                break
            }

            if (wipeInProgress.get()) return
            walletInitializationMutex.withLock {
                if (wipeInProgress.get()) return@withLock
                if (serviceStartedWallet === wallet && wallet.isInitialized) {
                    walletState.update()
                } else {
                    initializeWalletLocked(wallet)
                }
            }
        } catch (e: CancellationException) {
            if (wipeInProgress.get()) return
            throw e
        } catch (e: Exception) {
            if (wipeInProgress.get()) return
            Timber.tag(TAG).e(e, "Wallet service startup failed")
            walletState.clearSyncProgress()
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
        }
    }

    /**
     * Shared with the node settings flow so manual connects cannot race startup's
     * native wallet initialization while Tor finishes bootstrapping.
     */
    suspend fun <T> withWalletInitializationLock(block: () -> T): T =
        walletInitializationMutex.withLock { block() }

    fun markWalletServiceStarted(wallet: Wallet) {
        if (wallet.isInitialized) serviceStartedWallet = wallet
    }

    private fun initializeWalletLocked(wallet: Wallet) {
        val host = prefs.getString(NodeFields.RPC_HOST.value, "")
        val rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
        val rpcUsername = prefs.getString(NodeFields.RPC_USERNAME.value, "")
        val rpcPassphrase = prefs.getString(NodeFields.RPC_PASSWORD.value, "")

        if (host?.isNotEmpty() == true) {
            if (wipeInProgress.get()) return
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
        // Native init may replace a fresh seed restore's selected scan height with the
        // daemon tip. Capture it before init and reapply it before starting the scan.
        val forceFreshScan = prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
        val selectedRestoreHeightBeforeInit =
            if (forceFreshScan) walletState.pendingSelectedRestoreHeight(wallet) else null

        val initialized = wallet.init(0)
        if (wipeInProgress.get()) return
        if (initialized && wallet.isInitialized) {
            if (forceFreshScan && selectedRestoreHeightBeforeInit != null) {
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
            val resumeScan = walletState.beginWalletScan(
                wallet = wallet,
                scanStartHeight = actualRestoreHeight,
                forceStartAtSelectedHeight = forceFreshScan
            )
            walletState.publishWalletSnapshot(wallet)
            if (forceFreshScan) {
                Timber.tag(TAG).i(
                    "Starting fresh wallet scan from selected restore height: %d",
                    actualRestoreHeight
                )
                wallet.startRefresh()
            } else {
                if (resumeScan) wallet.rescanBlockchainAsync()
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
            serviceStartedWallet = wallet
        } else {
            Timber.tag(TAG).e("Wallet initialization failed: %s", wallet.status.errorString)
            walletState.clearSyncProgress()
            walletState.setConnectionStatus(Wallet.ConnectionStatus.ConnectionStatus_Disconnected)
            walletState.setLoading(false)
        }
    }

    fun updateDaemon(node: Node?, restartRefresh: Boolean = true) {
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
            if (restartRefresh && node != null && wallet?.isInitialized == true) {
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
            WalletManager.instance?.setProxy("")
            WalletManager.instance?.wallet?.setProxy("")
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

            // Gate and drain app-side JNI calls first; only then stop native refresh.
            // This prevents an in-flight scan task from restarting refresh after pause.
            walletState.awaitPendingNativeQueries()
            Timber.tag(TAG).i("Secure wipe: pausing wallet refresh")
            wallet.pauseRefresh()
            refreshPaused = true

            // No native listener may call into WalletState while closeWallet() releases
            // the native wallet and its JNI listener reference.
            Timber.tag(TAG).i("Secure wipe: detaching wallet listener")
            wallet.setListener(null)
            listenerDetached = true

            Timber.tag(TAG).i("Secure wipe: closing native wallet")
            check(wallet.close()) { throw UnableToCloseWallet() }
            walletClosed = true

            WalletManager.instance?.setDaemon(null)

            val context = AnonConfig.context
                ?: throw IllegalStateException("Application context unavailable")
            val walletDir = java.io.File(context.filesDir, "wallets")
            val removed = !walletDir.exists() || walletDir.deleteRecursively()
            check(removed && !java.io.File(walletDir, "anon").exists()) {
                "Native wallet closed, but wallet files could not be deleted"
            }
            walletState.resetAfterWalletWipe()

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
