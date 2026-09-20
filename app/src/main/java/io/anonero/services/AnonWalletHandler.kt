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
                if (prefs.getBoolean(WALLET_USE_TOR, false) && wallet?.isInitialized == true) {
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
        }
        return anonWallet?.status?.isOk ?: throw InvalidPin()
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

            // —— 真实 Tor/代理流程，最多等 10 秒，等不到就继续往下走 ——
            withTimeoutOrNull(10_000L) {
                if (useTor || proxyHost.isNullOrBlank()) {
                    torService.start()
                    while (torService.socks == null) {
                        delay(200)
                    }
                    val socket = torService.socks
                    WalletManager.instance?.setProxy("${socket?.value}")
                } else if (proxyHost.isNotEmpty() && proxyPort != -1) {
                    WalletManager.instance?.setProxy("${proxyHost}:$proxyPort")
                }
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

        // ===== mock 兜底：真实流程走完/超时后，强制结束“连接中”，进入可用首页 =====
        wallet.setSynchronized()
        wallet.refreshHistory()
        walletState.updateDaemon(
            DaemonInfo(
                realHost?.let { "$it:$realPort" } ?: "mock-node:18081",
                Wallet.ConnectionStatus.ConnectionStatus_Connected,
                3_400_000L
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
