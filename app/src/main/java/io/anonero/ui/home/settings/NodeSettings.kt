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
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.services.AnonWalletHandler
import io.anonero.services.TorService
import io.anonero.services.WalletState
import io.anonero.store.NodesRepository
import io.anonero.ui.components.DaemonStatus
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.ResyncBlockchainRoute
import io.anonero.ui.theme.DangerColor
import kotlinx.coroutines.Dispatchers
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
    val nodes: StateFlow<List<Node>> = _nodes


    val activeNode: StateFlow<Node?> = combine(nodes, daemonInfo) { nodeList, daemonInfo ->
        if (daemonInfo?.daemon == null) {
            return@combine null
        }
        val uri = "http://${daemonInfo.daemon}".toUri()
        nodeList.find { node ->
            node.host == uri.host &&
                    node.rpcPort == uri.port
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = null
    )

    init {
        // Observe items from the repository
        viewModelScope.launch {
            nodesRepository.nodesFlow.collect { itemList ->
                _nodes.value = itemList
            }
        }
    }


    fun getCurrentDaemonLive() = walletState.daemonInfo.asLiveData()

    val connectionError: LiveData<String?> get() = uriValidationError

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
            val nodeJson = JSONObject()
                .apply {
                    put(NodeFields.RPC_HOST.value, validatedUrl.host)
                    put(
                        NodeFields.RPC_PORT.value,
                        if (validatedUrl.port == -1) Node.defaultRpcPort else validatedUrl.port
                    )
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
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            try {
                WalletManager.instance?.wallet?.pauseRefresh()
                editor.putString(
                    NodeFields.RPC_HOST.value,
                    node.host
                )
                editor.putInt(
                    NodeFields.RPC_PORT.value,
                    node.rpcPort
                )
                editor.putString(
                    NodeFields.RPC_USERNAME.value,
                    node.username
                )
                editor.putString(
                    NodeFields.RPC_PASSWORD.value,
                    node.password
                )

                editor.apply()
                torService.start()
                walletHandler.updateDaemon(node)
                walletState.setLoading(false)
                walletState.update()
                WalletManager.instance?.wallet?.init(0)
                WalletManager.instance?.wallet?.setTrustedDaemon(
                    true
                )
                WalletManager.instance?.wallet?.startRefresh()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
                uriValidationError.postValue(e.message)
            }
        }
    }

    // Add a new node at the top of the available-node list.
    fun addItemFirst(item: Node) {
        viewModelScope.launch {
            nodesRepository.addItemToTop(item)
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            WalletManager.instance?.wallet?.pauseRefresh()
            prefs.edit {
                putString(
                    NodeFields.RPC_HOST.value,
                    null
                )
                putInt(
                    NodeFields.RPC_PORT.value, 0

                )
                putString(
                    NodeFields.RPC_USERNAME.value,
                    null

                )
                putString(
                    NodeFields.RPC_PASSWORD.value,
                    null
                )
            }
            walletHandler.updateDaemon(null)
            walletState.update()
            walletState.setLoading(false)
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
                                nodeSettingsVM.removeItem(oldNode.toNodeString())
                                nodeSettingsVM.addItem(updatedNode)
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
    val walletStatus by walletState.walletStatus.collectAsState(initial = null)
    val walletHeight = remember(walletStatus) {
        WalletManager.instance?.wallet?.getBlockChainHeight() ?: 0L
    }

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
                        "区块高度:${daemonStatus?.daemonHeight?.toString() ?: ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "钱包高度:${if (walletStatus == null) "" else walletHeight.toString()}",
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
                        text = { Text(if (active) stringResource(R.string.disconnect) else stringResource(R.string.connect)) },
                        onClick = {
                            if (active) {
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
                Text(
                    if (initialNode == null) "添加"
                    else stringResource(R.string.save)
                )
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