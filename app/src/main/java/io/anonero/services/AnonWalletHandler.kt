package io.anonero.services

import android.content.SharedPreferences
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.plus
import org.json.JSONObject
import timber.log.Timber
import androidx.core.content.edit


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

    val scope get() = _scope

    init {
        scope.launch {
            torService.socksFlow.collect {
                val wallet = WalletManager.instance?.wallet
                if (prefs.getBoolean(WALLET_USE_TOR, true) && wallet?.isInitialized == true) {
                    setProxy(it.address.toString(), it.port.value)
                }
            }
        }
    }

    fun openWallet(pin: String): Boolean {
        val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
        val manager = WalletManager.instance ?: throw InvalidPin()
        val anonWallet = manager.openWallet(
            walletFile.path,
            pin,
        )
        val status = anonWallet.status
        if (!status.isOk) {
            Timber.tag(TAG).e("openWallet error: %s", status.errorString)
            // Do not leave an invalid wallet registered in WalletManager. This lets
            // the user retry a PIN without replacing/leaking the previous native handle.
            val closed = runCatching { anonWallet.close() }.getOrDefault(false)
            if (!closed) {
                Timber.tag(TAG).e("Failed to close wallet after an unsuccessful open")
            }
            return false
        }
        return true
    }

    // ===== 真实连接流程保留，但每一步都有超时；超时后 mock 兜底，不再一直“连接中” =====
    suspend fun startService() {
        val wallet = WalletManager.instance?.wallet ?: return
        var realHost: String? = null
        var realPort: Int = Node.defaultRpcPort

        try {
            val host = prefs.getString(NodeFields.RPC_HOST.value, "")
            val rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
            val rpcUsername = prefs.getString(NodeFields.RPC_USERNAME.value, "")
            val rpcPassphrase = prefs.getString(NodeFields.RPC_PASSWORD.value, "")
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val useTor = prefs.getBoolean(WALLET_USE_TOR, true)

            // Tor must be ready before wallet RPC starts. If Tor is requested but
            // cannot start in time, stop here rather than silently connecting directly.
            val proxyReady = withTimeoutOrNull(10_000L) {
                when {
                    useTor -> {
                        if (torService.socks == null) {
                            torService.start()
                        }
                        while (torService.socks == null) {
                            delay(200)
                        }
                        val socket = checkNotNull(torService.socks) {
                            "Tor SOCKS endpoint was not available"
                        }
                        WalletManager.instance?.setProxy(socket.value)
                    }
                    !proxyHost.isNullOrBlank() && proxyPort in 1..65535 -> {
                        WalletManager.instance?.setProxy("${proxyHost}:$proxyPort")
                    }
                    else -> {
                        // Explicit direct mode with no manual proxy: remove stale proxy
                        // configuration inherited from an earlier Tor/manual-proxy session.
                        WalletManager.instance?.setProxy("")
                    }
                }
                true
            } ?: false

            if (useTor && !proxyReady) {
                Timber.tag(TAG).e("Tor SOCKS proxy did not become ready; refusing direct wallet connection")
                walletState.setLoading(false)
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = WalletManager.instance?.getDaemonAddress(),
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
                return
            }

            // —— 真实节点配置 ——
            if (host?.isNotEmpty() == true) {
                realHost = host
                realPort = rpcPort
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
            walletState.update()
            if (wallet.isSynchronized) {
                wallet.refreshHistory()
            }
            wallet.init(0)
            if (prefs.getLong(RESTORE_HEIGHT, 0L) != 0L) {
                wallet.setRestoreHeight(prefs.getLong(RESTORE_HEIGHT, 0L))
            }
            if (wallet.isInitialized) {
                wallet.refreshHistory()
                wallet.setTrustedDaemon(true)
                wallet.startRefresh()
                // —— 等真实“已连接”回调，最多 8 秒 ——
                withTimeoutOrNull(8_000L) {
                    while (wallet.fullStatus.connectionStatus !=
                        Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        delay(300)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            handler = MoneroHandlerThread(wallet, walletState)
            wallet.setListener(handler)
            wallet.refreshHistory()
            handler?.start()
            walletState.setLoading(true)
            walletState.update()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "wallet startup failed")
            walletState.setLoading(false)
        }

        // Never fabricate a connected daemon or mark the wallet synchronized after a timeout.
        // The listener will update sync state when the real daemon connection succeeds.
        val connectionStatus = runCatching {
            wallet.fullStatus.connectionStatus
        }.getOrNull() ?: Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        val daemonHeight = runCatching {
            wallet.getDaemonBlockChainHeight().coerceAtLeast(0L)
        }.getOrDefault(0L)
        val daemonAddress = realHost?.let { "$it:$realPort" }
            ?: WalletManager.instance?.getDaemonAddress()

        if (connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            wallet.refreshHistory()
        }

        walletState.updateDaemon(
            DaemonInfo(
                daemonAddress,
                connectionStatus,
                daemonHeight
            )
        )
        walletState.setLoading(false)
        walletState.update()
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
        Timber.tag(TAG).d("setProxy %s%s", proxy, port.toString())
        //disable proxy
        if (proxy == null && port == null) {
            prefs.edit {
                remove(WALLET_PROXY)
                remove(WALLET_PROXY_PORT)
            }
            // Clearing preferences alone leaves the native wallet using the old proxy.
            WalletManager.instance?.setProxy("")
            WalletManager.instance?.wallet?.setProxy("")
            return
        }

        prefs.edit {
            putString(WALLET_PROXY, proxy)
            putInt(WALLET_PROXY_PORT, port ?: -1)
        }
        val proxyHost = prefs.getString(WALLET_PROXY, "")?.trim().orEmpty()
        val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
        if (proxyHost.isBlank() || proxyPort !in 1..65535) {
            Timber.tag(TAG).w("Invalid proxy endpoint; clearing native proxy configuration")
            WalletManager.instance?.setProxy("")
            WalletManager.instance?.wallet?.setProxy("")
            return
        }
        val proxyStr = "$proxyHost:$proxyPort"
        WalletManager.instance?.setProxy(proxyStr)
        WalletManager.instance?.wallet?.setProxy(proxyStr)
    }

    fun getProxy(): Pair<String, Int>? {
        val proxyHost = prefs.getString(WALLET_PROXY, "")
        val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

        if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
            return Pair(proxyHost, proxyPort)
        }
        return null
    }

    fun wipe(passPhrase: String): Boolean {
        WalletManager.instance?.wallet?.pauseRefresh()
        WalletManager.instance?.wallet?.stopBackgroundSync(passPhrase)
        WalletManager.instance?.setDaemon(null)
        if (WalletManager.instance?.wallet?.close() == true) {
            AnonConfig.context?.let { AnonConfig.getDefaultWalletDir(it) }
                ?.deleteRecursively()
            return true
        } else {
            throw UnableToCloseWallet()
        }

    }

}
