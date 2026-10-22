package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import io.anonero.ui.theme.AnonOutlineButton
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.services.AnonWalletHandler
import io.anonero.services.PREF_FORCE_FRESH_WALLET_SCAN
import io.anonero.services.TorService
import io.anonero.services.WalletState
import io.anonero.store.NodesRepository
import io.anonero.ui.components.DaemonStatus
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.ResyncBlockchainRoute
import io.anonero.ui.theme.DangerColor
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NodeSettings"

class NodeSettingsViewModel(
    private val prefs: SharedPreferences,
    private val nodesRepository: NodesRepository
) : ViewModel() {

    private val walletState: WalletState by inject(WalletState::class.java)

    private val torService: TorService by inject(TorService::class.java)
    private val walletHandler: AnonWalletHandler by inject(AnonWalletHandler::class.java)
    private val uriValidationError = MutableLiveData<String?>()
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    private val daemonInfo = walletState.daemonInfo
    // Keeps the user's latest choice visible while native wallet work is serialized off the UI thread.
    private val requestedNode = MutableStateFlow<Node?>(null)
    private val nodeRequestGeneration = AtomicLong(0L)
    val nodes: StateFlow<List<Node>> = _nodes


    val activeNode: StateFlow<Node?> = combine(nodes, daemonInfo, requestedNode) { nodeList, daemonInfo, requestedNode ->
        requestedNode ?: if (daemonInfo?.daemon == null) {
            null
        } else {
            val uri = "http://${daemonInfo.daemon}".toUri()
            nodeList.find { node ->
                node.host == uri.host &&
                        node.rpcPort == uri.port
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = null
    )

    init {
        // A node configured in wallet preferences can be missing from the user-managed
        // node list (for example after an interrupted DataStore write). Reinsert that
        // configured endpoint before observing the list so the management screen does
        // not become blank while the wallet is still connected to it.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val configuredNode = configuredNodeFromPreferences() ?: return@runCatching
                val storedNodes = nodesRepository.getAll()
                val alreadySaved = storedNodes.any {
                    it.host.equals(configuredNode.host, ignoreCase = true) &&
                        it.rpcPort == configuredNode.rpcPort
                }
                if (!alreadySaved) nodesRepository.addItemToTop(configuredNode)
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Unable to restore the configured node to the management list")
            }
        }
        // Observe items from the repository
        viewModelScope.launch {
            nodesRepository.nodesFlow.collect { itemList ->
                _nodes.value = itemList
            }
        }
    }

    private fun configuredNodeFromPreferences(): Node? {
        val host = prefs.getString(NodeFields.RPC_HOST.value, "").orEmpty().trim()
        val port = prefs.getInt(NodeFields.RPC_PORT.value, 0)
        if (host.isBlank() || port !in 1..65535) return null
        return Node.fromJson(
            JSONObject()
                .put(NodeFields.RPC_HOST.value, host)
                .put(NodeFields.RPC_PORT.value, port)
                .put(
                    NodeFields.RPC_SSL.value,
                    prefs.getBoolean(NodeFields.RPC_SSL.value, Node.defaultUseSsl(host, port))
                )
                .put(
                    NodeFields.RPC_USERNAME.value,
                    prefs.getString(NodeFields.RPC_USERNAME.value, "").orEmpty()
                )
                .put(
                    NodeFields.RPC_PASSWORD.value,
                    prefs.getString(NodeFields.RPC_PASSWORD.value, "").orEmpty()
                )
                .put(NodeFields.RPC_NETWORK.value, AnonConfig.getNetworkType().toString())
                .put(NodeFields.NODE_NAME.value, "anon")
        )
    }

    fun getCurrentDaemonLive() = walletState.daemonInfo.asLiveData()

    val connectionError: LiveData<String?> get() = uriValidationError

    private fun canContinueNodeConnect(allowActiveResyncScan: Boolean): Boolean =
        !walletState.isWalletWipeInProgress &&
            (!walletState.isResyncPriorityActive ||
                (allowActiveResyncScan && walletState.canRetargetNodeDuringResync))

    fun validate(rpcUrl: String, rpcUsername: String, rpcPassPhrase: String): Node? {
        uriValidationError.postValue(null)
        if( rpcUrl.isBlank()) {
            uriValidationError.postValue("节点地址不能为空")
            return null
        }
        try {
            val cleanURL = rpcUrl.trim().lowercase()
            val urlForParsing = if (cleanURL.startsWith("http")) {
                cleanURL
            } else {
                if (cleanURL.startsWith("https")) {
                    cleanURL
                } else {
                    "http://$cleanURL"
                }
            }
            val validatedUrl = urlForParsing.toUri()
            if (validatedUrl.host == null) {
                uriValidationError.postValue("无效节点地址")
                return null
            }
            val rpcPort = if (validatedUrl.port == -1) Node.defaultRpcPort else validatedUrl.port
            val explicitScheme = cleanURL.startsWith("http://") || cleanURL.startsWith("https://")
            val useSsl = if (explicitScheme) {
                validatedUrl.scheme.equals("https", ignoreCase = true)
            } else {
                Node.defaultUseSsl(validatedUrl.host, rpcPort)
            }
            val nodeJson = JSONObject()
                .apply {
                    put(NodeFields.RPC_HOST.value, validatedUrl.host)
                    put(NodeFields.RPC_PORT.value, rpcPort)
                    put(NodeFields.RPC_SSL.value, useSsl)
                    put(NodeFields.RPC_USERNAME.value, rpcUsername)
                    put(NodeFields.RPC_PASSWORD.value, rpcPassPhrase)
                    put(
                        NodeFields.RPC_NETWORK.value,
                        AnonConfig.getNetworkType().toString()
                    )
                    put(NodeFields.NODE_NAME.value, "anon")
                }

            return Node.fromJson(nodeJson)

        } catch (e: Exception) {
            uriValidationError.postValue(e.message)
            return null
        }
    }

    suspend fun connect(node: Node) {
        val requestId = nodeRequestGeneration.incrementAndGet()
        requestedNode.value = node
        // Publish immediate visual feedback; all JNI and blocking coordination below stays on IO.
        walletState.setLoading(true)
        uriValidationError.postValue(null)

        // Wait only while reset setup owns the native wallet. After the native scan
        // request has been handed off, node switching is allowed during the scan.
        while (
            walletState.isManualResyncInProgress &&
            !walletState.canRetargetNodeDuringResync &&
            !walletState.isWalletWipeInProgress
        ) {
            delay(50L)
        }
        if (walletState.isWalletWipeInProgress) {
            if (nodeRequestGeneration.get() == requestId) requestedNode.value = null
            uriValidationError.postValue("钱包正在安全删除，节点连接已取消")
            return
        }

        val allowActiveResyncScan = walletState.canRetargetNodeDuringResync
        if (!walletHandler.beginManualNodeConnection(allowActiveResyncScan)) {
            if (nodeRequestGeneration.get() == requestId) requestedNode.value = null
            if (!walletState.isWalletWipeInProgress) walletState.setLoading(false)
            uriValidationError.postValue(
                if (walletState.isWalletWipeInProgress) {
                    "钱包正在安全删除，节点连接已取消"
                } else {
                    "区块重置仍在准备，请稍后再连接节点"
                }
            )
            return
        }
        var refreshPausedForConnect = false
        // Suppress automatic native reconnects from the wallet listener while this
        // explicit request waits for Tor and serializes wallet initialization.
        try {
            withContext(Dispatchers.IO) {
            if (requestId != nodeRequestGeneration.get()) return@withContext
            check(canContinueNodeConnect(allowActiveResyncScan)) {
                "区块重置优先执行，节点连接已让路"
            }
            val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
                val proxyHost = prefs.getString(WALLET_PROXY, "")
                val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

                var proxyAddress: String? = null
                if (useTor) {
                    // Match upstream manual node switching: start Tor and continue the
                    // daemon switch without blocking this request for SOCKS bootstrap.
                    // An already-ready SOCKS endpoint can be applied immediately; otherwise
                    // AnonWalletHandler applies it when Tor emits the ready socket.
                    torService.start()
                    proxyAddress = torService.socks?.let { socket ->
                        "${socket.address}:${socket.port.value}"
                    }
                } else if (!proxyHost.isNullOrBlank() && proxyPort in 1..65535) {
                    proxyAddress = "${proxyHost}:${proxyPort}"
                }

                val nodeSwitch = nodeSwitch@{
                    if (requestId != nodeRequestGeneration.get()) return@nodeSwitch
                    check(canContinueNodeConnect(allowActiveResyncScan)) {
                        "区块重置优先执行，节点连接已让路"
                    }
                    // Persist only the request that actually reaches the serialized native operation.
                    prefs.edit {
                        putString(NodeFields.RPC_HOST.value, node.host)
                        putInt(NodeFields.RPC_PORT.value, node.rpcPort)
                        putBoolean(NodeFields.RPC_SSL.value, node.usesSsl)
                        putString(NodeFields.RPC_USERNAME.value, node.username)
                        putString(NodeFields.RPC_PASSWORD.value, node.password)
                    }
                    val wallet = WalletManager.instance?.wallet
                        ?: throw IllegalStateException("钱包未打开")
                    val walletManager = WalletManager.instance

                    // JNI wallet status reads are not safe to race the refresh/scanner thread.
                    // This barrier runs on IO, so the screen keeps responding while native calls drain.
                    if (wallet.isInitialized) wallet.pauseRefreshAndWait() else wallet.pauseRefresh()
                    refreshPausedForConnect = true
                    check(canContinueNodeConnect(allowActiveResyncScan)) {
                        "区块重置优先执行，节点连接已让路"
                    }

                    // Reuse the current wallet only when the selected daemon is
                    // actually connected. Query fullStatus only after the refresh barrier above.
                    val sameNodeAlreadyInitialized = wallet.isInitialized &&
                        wallet.fullStatus.connectionStatus ==
                            Wallet.ConnectionStatus.ConnectionStatus_Connected &&
                        walletManager != null &&
                        walletManager.getDaemonAddress() == node.address &&
                        walletManager.daemonUseSsl == node.usesSsl &&
                        walletManager.daemonUsername == node.username &&
                        walletManager.daemonPassword == node.password

                    if (sameNodeAlreadyInitialized) {
                        // Startup may have connected the saved node while this manual
                        // request was being prepared. Reuse its listener/scan state.
                        check(canContinueNodeConnect(allowActiveResyncScan)) {
                            "区块重置优先执行，节点连接已让路"
                        }
                        wallet.startRefresh()
                        refreshPausedForConnect = false
                        walletState.update()
                        walletState.setLoading(!wallet.isSynchronized && !walletState.isSyncing)
                        // The refreshed callback from this explicit connect can be suppressed
                        // while the request owns the node-switch gate. Poll the live daemon
                        // height after restarting refresh so scan progress does not wait for
                        // another native callback or a manual Refresh tap.
                        walletState.checkSyncProgressOnStartup(wallet)
                        walletHandler.markWalletServiceStarted(wallet)
                        return@nodeSwitch
                    }

                    // Capture the existing scan before touching the native daemon. A node
                    // switch must continue the current scan, not rewind to its original start
                    // height and queue a new native rescan.
                    val continueActiveResync =
                        allowActiveResyncScan && walletState.isResyncPriorityActive
                    val scanStartHeightBeforeConnect =
                        if (walletState.isWalletScanActive || continueActiveResync) {
                            walletState.currentScanStartHeight
                        } else null
                    val forceFreshScan = prefs.getBoolean(PREF_FORCE_FRESH_WALLET_SCAN, false)
                    val continueExistingScan =
                        (walletState.isWalletScanActive || continueActiveResync) &&
                            (!forceFreshScan || walletState.isForcedRestoreScanStarted)

                    // Refresh was drained before the first native status read above.
                    // Keep the pause in effect through daemon replacement and init.
                    val resolvedProxyAddress = proxyAddress
                    if (resolvedProxyAddress != null) {
                        WalletManager.instance?.setProxy(resolvedProxyAddress)
                        wallet.setProxy(resolvedProxyAddress)
                    } else if (!useTor) {
                        // No Tor and no custom proxy: use a direct node connection.
                        walletHandler.setProxy(null, null, allowActiveResyncScan)
                    }
                    // When Tor is still bootstrapping, do not clear the existing proxy.
                    // The Tor SOCKS callback will update the proxy after this native
                    // initialization lock is released.
                    check(canContinueNodeConnect(allowActiveResyncScan)) {
                        "区块重置准备优先执行，节点连接已让路"
                    }
                    walletHandler.updateDaemon(
                        node,
                        restartRefresh = false,
                        allowActiveResyncScan = allowActiveResyncScan,
                        refreshAlreadyPaused = true
                    )
                    check(canContinueNodeConnect(allowActiveResyncScan)) {
                        "区块重置准备优先执行，节点连接已让路"
                    }
                    walletState.setLoading(true)

                    val selectedRestoreHeightBeforeInit = if (forceFreshScan) {
                        walletState.pendingSelectedRestoreHeight(wallet)
                    } else {
                        scanStartHeightBeforeConnect
                    }
                    val restartExistingScan = scanStartHeightBeforeConnect != null

                    // A daemon switch can make initJ() return false transiently even
                    // though this is an already-open native wallet. Do not leave its refresh
                    // worker paused in that case: preserve the existing handle and resume the
                    // native connection callbacks. Only treat it as a hard init failure if no
                    // open handle existed before this reconnect.
                    val hadInitializedWallet = wallet.isInitialized
                    val initialized = wallet.init(0)
                    check(canContinueNodeConnect(allowActiveResyncScan)) {
                        "区块重置准备优先执行，节点连接已让路"
                    }
                    if (!initialized || !wallet.isInitialized) {
                        if (!hadInitializedWallet) {
                            throw IllegalStateException(
                                wallet.status.errorString.ifBlank { "钱包初始化失败" }
                            )
                        }
                        Timber.tag(TAG).w(
                            "wallet.init returned false while switching to %s:%d; retaining the already-open wallet and continuing refresh",
                            node.host,
                            node.rpcPort
                        )
                        // init() mirrors its return value into this Kotlin flag. For an
                        // already-open wallet, a reconnect-time false result must not make
                        // the remaining scan/refresh flow treat the native handle as closed.
                        // Match the known-working node-switch path and let callbacks decide
                        // whether the newly selected daemon is reachable.
                        wallet.isInitialized = true
                    }

                    if (forceFreshScan && selectedRestoreHeightBeforeInit != null) {
                        // Native init can replace a new seed restore height with the daemon
                        // tip. Reapply that preference, but never rewind the live progress
                        // snapshot if the forced restore scan was already running.
                        wallet.setRestoreHeight(selectedRestoreHeightBeforeInit)
                        check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedRestoreHeightBeforeInit) {
                            "Selected restore height changed during node connection"
                        }
                        check(wallet.store()) { "Unable to preserve selected restore height after node switch" }
                        if (!continueExistingScan) {
                            val reappliedHeight =
                                walletState.preservePendingRestoreHeightAfterInit(wallet)
                            check(reappliedHeight == selectedRestoreHeightBeforeInit) {
                                "Selected restore height changed during node connection"
                            }
                        }
                    } else if (restartExistingScan && selectedRestoreHeightBeforeInit != null) {
                        wallet.setRestoreHeight(selectedRestoreHeightBeforeInit)
                        check(wallet.getRestoreHeight().coerceAtLeast(0L) == selectedRestoreHeightBeforeInit) {
                            "Wallet scan baseline changed while reconnecting to a node"
                        }
                        check(wallet.store()) { "Unable to preserve wallet scan baseline after node switch" }
                    }

                    val actualRestoreHeight = wallet.getRestoreHeight().coerceAtLeast(0L)
                    prefs.edit(commit = true) { putLong(RESTORE_HEIGHT, actualRestoreHeight) }
                    wallet.setTrustedDaemon(true)
                    var resumeScan = false
                    if (!continueExistingScan) {
                        val forceStartAtSelectedHeight = forceFreshScan ||
                            (restartExistingScan && !continueActiveResync)
                        resumeScan = walletState.beginWalletScan(
                            wallet = wallet,
                            scanStartHeight = actualRestoreHeight,
                            forceStartAtSelectedHeight = forceStartAtSelectedHeight
                        )
                        walletState.publishWalletSnapshot(wallet)
                    } else {
                        // Preserve _walletHeight, _scanStartHeight and persisted in-progress
                        // markers. Clearing only the displayed estimate lets the first live
                        // height from the new daemon rebuild progress without rewinding scanning.
                        walletState.clearSyncProgress()
                        walletState.setLoading(true)
                    }

                    if (canContinueNodeConnect(allowActiveResyncScan)) {
                        if (forceFreshScan) {
                            // A pending fresh restore queues its first scan once the daemon
                            // is ready. An already active forced scan must not be queued twice.
                            wallet.startRefresh()
                        } else {
                            if (!continueExistingScan &&
                                !continueActiveResync &&
                                (resumeScan || restartExistingScan)
                            ) {
                                wallet.rescanBlockchainAsync()
                            }
                            wallet.startRefresh()
                        }
                        refreshPausedForConnect = false
                    }

                    if (continueExistingScan) {
                        // Do not perform synchronous history/output reads while the native
                        // scanner is active. They contend with refresh and make node switching
                        // appear hung. The height poll recomputes progress against the new node.
                        walletState.checkSyncProgressOnStartup(wallet)
                    } else {
                        try {
                            if (canContinueNodeConnect(allowActiveResyncScan)) wallet.refreshHistory()
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Initial history refresh failed after node connection")
                        }
                        if (canContinueNodeConnect(allowActiveResyncScan)) {
                            walletState.update()
                            walletState.setLoading(!wallet.isSynchronized && !walletState.isSyncing)
                        }
                    }
                    walletHandler.markWalletServiceStarted(wallet)
                }

                // Always serialize native wallet access. The selected row and progress
                // indicator already update immediately; a newer queued tap supersedes an
                // older request that has not yet entered the native operation.
                walletHandler.withWalletInitializationLock {
                    if (requestId == nodeRequestGeneration.get()) {
                        nodeSwitch()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (refreshPausedForConnect &&
                    !walletState.isWalletWipeInProgress &&
                    (!walletState.isManualResyncInProgress ||
                        walletState.canRetargetNodeDuringResync)
                ) {
                    runCatching {
                        WalletManager.instance?.wallet
                            ?.takeIf { it.isInitialized }
                            ?.startRefresh()
                    }.onFailure { restartError ->
                        Timber.tag(TAG).e(restartError, "Unable to resume refresh after node switch failure")
                    }
                    refreshPausedForConnect = false
                }
                if (canContinueNodeConnect(allowActiveResyncScan)) {
                    Timber.tag(TAG).e(e, "Failed to connect to node")
                    walletState.setLoading(false)
                    uriValidationError.postValue(e.message)
                } else {
                    Timber.tag(TAG).i("Node connection yielded to reset preparation priority")
                }
            }
            }
        } finally {
            if (nodeRequestGeneration.get() == requestId) {
                requestedNode.value = null
            }
            walletHandler.endManualNodeConnection()
        }
    }

    // Add a new node at the top of the available-node list.
    fun addItemFirst(item: Node) {
        viewModelScope.launch {
            nodesRepository.addItemToTop(item)
        }
    }

    // Replace a saved node before connecting to the edited configuration.
    suspend fun replaceItem(oldNodeString: String, item: Node) {
        nodesRepository.removeItemByNodeString(oldNodeString)
        nodesRepository.addItem(item)
    }

    // Add an item (used when replacing an existing node).
    fun addItem(item: Node) {
        viewModelScope.launch {
            nodesRepository.addItem(item)
        }
    }

    // Remove an item
    fun removeItem(nodeString: String) {
        viewModelScope.launch {
            nodesRepository.removeItemByNodeString(nodeString)
        }
    }

    fun disconnect() {
        if (walletState.isResyncPriorityActive) return
        viewModelScope.launch(Dispatchers.IO) {
            if (walletState.isResyncPriorityActive) return@launch
            // Share the native initialization lock with connect/startup. If disconnect
            // already owns it, a later reset waits for this finite operation to finish;
            // if reset owns priority first, disconnect never touches the wallet.
            walletHandler.withWalletInitializationLock {
                if (walletState.isResyncPriorityActive) return@withWalletInitializationLock
                WalletManager.instance?.wallet?.pauseRefresh()
                if (walletState.isResyncPriorityActive) return@withWalletInitializationLock
                prefs.edit {
                    putString(NodeFields.RPC_HOST.value, null)
                    putInt(NodeFields.RPC_PORT.value, 0)
                    putString(NodeFields.RPC_USERNAME.value, null)
                    putString(NodeFields.RPC_PASSWORD.value, null)
                }
                walletHandler.updateDaemon(null)
                if (!walletState.isResyncPriorityActive) {
                    walletState.update()
                    walletState.setLoading(false)
                }
            }
        }
    }

}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSettings(onBackPress: () -> Unit = {}, navigateTo: (param: Any) -> Unit = {}) {
    val nodeSettingsVM = koinViewModel<NodeSettingsViewModel>()
    var showNodeDetails by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<Node?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val availableNodes by nodeSettingsVM.nodes.collectAsState(arrayListOf())
    val activeNode by nodeSettingsVM.activeNode.collectAsState(null)
    val scope = rememberCoroutineScope()
    val toastState = rememberToasterState()
    val connectionError by nodeSettingsVM.connectionError.observeAsState(null)

    // Surface connection failures without changing the existing node-list layout.
    LaunchedEffect(connectionError) {
        connectionError?.takeIf { it.isNotBlank() }?.let { message ->
            toastState.show(message, type = ToastType.Warning, duration = 6.seconds)
        }
    }

    if (showNodeDetails || editingNode != null)
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false,
            ),
            onDismissRequest = {
                showNodeDetails = false
                editingNode = null
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (editingNode != null) {
                                        editingNode = null
                                    } else {
                                        showNodeDetails = false
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    )
                    NodeForm(
                        onBackPress = {
                            if (editingNode != null) {
                                editingNode = null
                            } else {
                                showNodeDetails = false
                            }
                        },
                        onAdd = { node ->
                            nodeSettingsVM.addItemFirst(node)
                        },
                        nodeSettingsVM = nodeSettingsVM,
                        initialNode = editingNode,
                        onSave = { updatedNode ->
                            editingNode?.let { oldNode ->
                                scope.launch {
                                    nodeSettingsVM.replaceItem(oldNode.toNodeString(), updatedNode)
                                    nodeSettingsVM.connect(updatedNode)
                                }
                            }
                        }
                    )
                }
            }
        }


    if (editingNode == null) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = onBackPress
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                showNodeDetails = true
                            }
                        ) { Text("添加节点") }
                        IconButton(
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color.White
                            ),
                            onClick = {
                                showMenu = !showMenu
                            }
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                            DropdownMenu(
                                expanded = showMenu,
                                containerColor = MaterialTheme.colorScheme.background,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        color = MaterialTheme.colorScheme.onSecondary.copy(
                                            alpha = .2f
                                        ),
                                        shape = MaterialTheme.shapes.medium,
                                    ),
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重置同步区块") },
                                    onClick = {
                                        showMenu = false
                                        navigateTo(ResyncBlockchainRoute)
                                    }
                                )
                            }
                        }

                    },
                    title = {
                        Text("节点")
                    },
                )
                    WalletProgressIndicator()
            }
        }
    ) {

        LazyColumn(modifier = Modifier.padding(it)) {
            if (activeNode != null)
                item(key = activeNode?.toNodeString() ?: "active") {
                    NodeListItem(
                        activeNode!!,
                        modifier = Modifier.animateItem(),
                        active = true,
                        nodeSettingsVM = nodeSettingsVM,
                        onDisconnect = {
                            nodeSettingsVM.disconnect()
                        },
                        onConnect = {
                            nodeSettingsVM.viewModelScope.launch {
                                try {
                                    nodeSettingsVM.connect(activeNode!!)
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (ex: Exception) {
                                    Timber.tag(TAG).e(ex, "Failed to reconnect the selected node")
                                }
                            }
                        },
                        onEdit = { editingNode = it },
                        onRemove = { node ->
                            nodeSettingsVM.disconnect()
                            nodeSettingsVM.removeItem(node.toNodeString())
                        }
                    )
                }
            if (availableNodes.isNotEmpty())
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .padding(
                                vertical = 12.dp,
                                horizontal = 12.dp
                            )
                            .background(color = Color.Black)
                    ) {
                        Text("可用节点", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.padding(4.dp))
                        HorizontalDivider()
                    }
                }
            if (availableNodes.isNotEmpty())
                items(
                    availableNodes.size,
                    key = { i -> "${availableNodes[i].toNodeString()} :${i}" }) { index ->
                    val node = availableNodes[index]
                    if (activeNode?.toNodeString() != node.toNodeString()) {
                        NodeListItem(
                            node,
                            modifier = Modifier.animateItem(),
                            active = false,
                            nodeSettingsVM = nodeSettingsVM,
                            onConnect = {
                                nodeSettingsVM.viewModelScope
                                    .launch {
                                        try {
                                            nodeSettingsVM.connect(node)
                                        } catch (ex: CancellationException) {
                                            throw ex
                                        } catch (ex: Exception) {
                                            Timber.tag(TAG).e(ex)
                                        }
                                    }
                            },
                            onRemove = { nodeSettingsVM.removeItem(node.toNodeString()) },
                            onEdit = { editingNode = it })
                    }
                }
        }
    }
    }

    Toaster(
        state = toastState,
        maxVisibleToasts = 4,
        alignment = Alignment.BottomCenter,
        darkTheme = true,
        richColors = true,
        containerPadding = PaddingValues(
            bottom = 64.dp,
        ),
    )
}

@Composable
fun NodeListItem(
    node: Node,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    nodeSettingsVM: NodeSettingsViewModel,
    onDisconnect: (node: Node) -> Unit = {},
    onConnect: (node: Node) -> Unit = {},
    onRemove: (node: Node) -> Unit = {},
    onEdit: (node: Node) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val daemonStatus by nodeSettingsVM.getCurrentDaemonLive().observeAsState(null)
    val connectionError by nodeSettingsVM.connectionError.observeAsState(null)
    val walletState = koinInject<WalletState>()
    val walletHeight by walletState.walletHeight.asLiveData().observeAsState(0L)
    // A selected node is not necessarily connected: startup may still be waiting for Tor.
    // Offer Connect until the daemon actually reports a live connection.
    val isConnected = active &&
        daemonStatus?.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected

    ListItem(
        modifier = modifier
            .padding(
                vertical = 8.dp,
                horizontal = 8.dp
            )
            .border(
                1.dp,
                color = MaterialTheme.colorScheme.onSecondary.copy(
                    alpha = .2f
                ),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable {
                menu = !menu
            },
        headlineContent = {
            Text(
                node.toNodeString(), style = MaterialTheme
                    .typography.bodySmall
            )
        },
        supportingContent = {
            if (active) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "区块高度：${daemonStatus?.daemonHeight?.toString() ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "钱包高度：${walletHeight}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                }
            }
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (active) {
                    DaemonStatus(daemonStatus?.connectionStatus)
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color.Gray, shape = CircleShape)
                    )

                }
                DropdownMenu(
                    expanded = menu,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onSecondary.copy(
                            alpha = .2f
                        ),
                    ),
                    containerColor = Color.Black,
                    onDismissRequest = { menu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isConnected) stringResource(R.string.disconnect)
                                else stringResource(R.string.connect)
                            )
                        },
                        onClick = {
                            if (isConnected) {
                                onDisconnect(node)
                            } else {
                                onConnect(node)
                            }
                            menu = false
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            menu = false
                            onEdit(node)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove)) },
                        onClick = {
                            onRemove(node)
                            menu = false
                        },
                    )
                }
            }
        }
    )


}


@Composable
fun NodeForm(
    onBackPress: () -> Unit = {},
    onAdd: (node: Node) -> Unit = {},
    nodeSettingsVM: NodeSettingsViewModel,
    initialNode: Node? = null,
    onSave: (node: Node) -> Unit = {},
) {
    val connectionError by nodeSettingsVM.connectionError.observeAsState(null)
    var rpcHost by remember(initialNode) { mutableStateOf(initialNode?.toNodeString().orEmpty()) }
    var rpcUsername by remember(initialNode) { mutableStateOf(initialNode?.username.orEmpty()) }
    var rpcPassPhrase by remember(initialNode) { mutableStateOf(initialNode?.password.orEmpty()) }
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(8f)
                .verticalScroll(
                    rememberScrollState()
                ),
            verticalArrangement = Arrangement.Top,
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.node),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                supportingContent = {
                    OutlinedTextField(
                        value = rpcHost,
                        shape = MaterialTheme.shapes.medium,
                        onValueChange = {
                            rpcHost = it
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next,
                        ),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.onion_address_example),
                                color = labelColor
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusable()
                    )
                },
            )
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.username),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                supportingContent = {
                    OutlinedTextField(
                        value = rpcUsername,
                        shape = MaterialTheme.shapes.medium,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.hint_optional),
                                color = labelColor
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next,
                        ),
                        onValueChange = {
                            rpcUsername = it
                        },

                    )
                },
            )
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.password),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                supportingContent = {
                    OutlinedTextField(
                        value = rpcPassPhrase,
                        shape = MaterialTheme.shapes.medium,
                        onValueChange = {
                            rpcPassPhrase = it
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                        ),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.hint_optional),
                                color = labelColor
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (connectionError != null)
                    Text(
                        "连接服务器时出错",
                        modifier = Modifier.padding(
                            vertical = 12.dp,
                            horizontal = 8.dp
                        ),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = DangerColor
                        )
                    )

            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.5f)
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            AnonOutlineButton(
                modifier = Modifier
                    .fillMaxWidth(
                        .9f
                    ),
                onClick = {
                    val node = nodeSettingsVM.validate(rpcHost, rpcUsername, rpcPassPhrase)
                    if (node != null) {
                        if (initialNode == null) {
                            onAdd(node)
                        } else {
                            onSave(node)
                        }
                        onBackPress.invoke()
                    }
                },
            ) {
                Text(if (initialNode == null) "添加" else "连接")
            }
        }
    }
}

@Preview(device = "id:pixel_5")
@Composable
private fun SeedSettingsPre() {
    AnonNeroTheme {
        NodeSettings()
    }
}