package io.anonero.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.anonero.model.WalletManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.asLiveData
import io.anonero.R
import io.anonero.model.CoinsInfo
import io.anonero.model.Wallet
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.util.Formats
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinsScreen(
    modifier: Modifier = Modifier,
    navigateToSpend: (route: SendScreenRoute) -> Unit = {},
    onBackPress: () -> Unit = {},
    selected: Set<String> = setOf(),
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val coins by walletState.coins.collectAsState(arrayListOf())
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)

    var selectedCoins by remember { mutableStateOf(selected) }
    var detailCoin by remember { mutableStateOf<CoinsInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val connectionStatus by walletState.walletConnectionStatus.asLiveData().observeAsState(
        Wallet.ConnectionStatus.ConnectionStatus_Disconnected
    )
    val loading by walletState.isLoading.asLiveData().observeAsState(false)
    val nodeConnected = connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected

    // Refresh the real native output inventory while the connected wallet is idle.
    // Do not rely only on wallet.isSynchronized: restore can publish balance/history
    // while that flag remains false, which previously left this screen permanently empty.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var emptyOutputRetries = 0
            while (currentCoroutineContext().isActive) {
                val wallet = WalletManager.instance?.wallet
                if (wallet == null || !wallet.isInitialized) {
                    delay(1_000L)
                    continue
                }

                val outputs = walletState.refreshOutputInventoryIfIdle()
                if (outputs != null) {
                    val hasBalance = wallet.balance > 0L || wallet.unlockedBalance > 0L
                    Timber.tag("CoinsScreen").i(
                        "Output screen refresh: total=%d unspent=%d spent=%d balance=%d synchronized=%s",
                        outputs.size,
                        outputs.count { !it.spent },
                        outputs.count { it.spent },
                        wallet.balance,
                        wallet.isSynchronized
                    )
                    // Outputs include spent entries too. Retry transient empty native
                    // snapshots for longer; if balance is still positive, leave an explicit
                    // diagnostic in the log instead of silently treating it as no outputs.
                    if (outputs.isNotEmpty() || !hasBalance) {
                        return@withContext
                    }
                    emptyOutputRetries++
                    if (emptyOutputRetries >= 10) {
                        Timber.tag("CoinsScreen").e(
                            "Output inventory is empty after 10 idle refreshes despite positive balance=%d",
                            wallet.balance
                        )
                        return@withContext
                    }
                }
                delay(1_000L)
            }
        }
    }

    var message: String? = null
    if (loading) {
        message = stringResource(R.string.wallet_loading)
    } else if (!nodeConnected) {
        message = stringResource(R.string.node_disconnected)
    } else if (coins.isEmpty()) {
        message = stringResource(R.string.no_coins_available)
    }

    fun selectCoin(coin: CoinsInfo) {
        if (coin.frozen || coin.spent) return

        selectedCoins = if (selectedCoins.contains(coin.key)) {
            selectedCoins - coin.key
        } else {
            selectedCoins + coin.key
        }
    }

    fun reportOutputError(message: String) {
        detailCoin = null
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun setOutputFrozen(coin: CoinsInfo, shouldFreeze: Boolean) {
        // The upstream wallet API exposes the native frozen state for reading,
        // but does not expose setters to freeze or thaw outputs. Do not simulate a
        // successful state change or mutate wallet data locally.
        reportOutputError(
            if (shouldFreeze) "当前上游钱包接口不支持冻结输出"
            else "当前上游钱包接口不支持解冻输出"
        )
    }

    // 外层包一层不透明背景，确保 detailCoin 切换时不会透出下层
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (detailCoin != null) {
            val coin = detailCoin!!
            val outputNumber = (coins.indexOfFirst { it.key == coin.key } + 1).coerceAtLeast(1)
            OutputDetailScreen(
                coin = coin,
                outputNumber = outputNumber,
                hideAmounts = hideAmounts,
                isFrozen = coin.frozen,
                onBackPress = { detailCoin = null },
                onFreeze = {
                    setOutputFrozen(coin, shouldFreeze = true)
                },
                onUnfreeze = {
                    setOutputFrozen(coin, shouldFreeze = false)
                }
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    if (selectedCoins.isNotEmpty()) {
                        OutlinedButton(
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            onClick = {
                                navigateToSpend(
                                    SendScreenRoute(
                                        coins = selectedCoins.toList(),
                                        address = ""
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Text(
                                if (selected.size != 0) {
                                    stringResource(R.string.confirm)
                                } else {
                                    stringResource(R.string.spend)
                                }
                            )
                        }
                    }
                },
                topBar = {
                    TopAppBar(
                        title = {
                            Box(modifier = Modifier.offset(y = (-3).dp)) {
                                XmrText(
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp)
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackPress) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .padding(it)
                        .padding(bottom = 44.dp)
                ) {
                    item {
                        WalletProgressIndicator()
                    }

                    items(coins.size) { index ->
                        val coin = coins[index]
                        val isFrozen = coin.frozen

                        ListItem(
                            modifier = Modifier
                                .padding(
                                    horizontal = 4.dp,
                                    vertical = 6.dp
                                )
                                .clickable {
                                    // Opening output details must not discard other selected outputs.
                                    detailCoin = coin
                                },
                            headlineContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.output_number, index + 1),
                                        color = if (isFrozen) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Text(
                                        Formats.getDisplayAmount(coin.amount),
                                        color = if (isFrozen) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isFrozen) {
                                        Text(
                                            "已冻结",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    } else if (coin.spent) {
                                        Text(
                                            "已花费",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    } else {
                                        Checkbox(
                                            checked = selectedCoins.contains(coin.key),
                                            onCheckedChange = {
                                                selectCoin(coin)
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }

                    if (message != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .height(280.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    message,
                                    modifier = Modifier
                                        .padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp
                                        )
                                        .align(Alignment.Center),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun ReceiveScreenPreview() {
    AnonNeroTheme {
        CoinsScreen()
    }
}

private fun abbreviatedValue(value: String, maxChars: Int = 32): String {
    if (value.length <= maxChars) return value
    val available = (maxChars - 3).coerceAtLeast(2)
    val left = available / 2
    val right = available - left
    return value.take(left) + "..." + value.takeLast(right)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputDetailScreen(
    coin: CoinsInfo,
    outputNumber: Int,
    hideAmounts: Boolean,
    isFrozen: Boolean,
    onBackPress: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
) {
    BackHandler(onBack = onBackPress)

    val walletState: WalletState by inject(WalletState::class.java)
    val transactions by walletState.transactions.collectAsState(emptyList())
    val clipboardManager = LocalClipboardManager.current
    var publicKeyExpanded by remember(coin.key) { mutableStateOf(false) }
    var hashExpanded by remember(coin.key) { mutableStateOf(false) }
    var addressExpanded by remember(coin.key) { mutableStateOf(false) }

    val outputAddress = remember(coin.hash, transactions) {
        transactions.firstOrNull { it.hash == coin.hash }?.let { transaction ->
            try {
                WalletManager.instance?.wallet?.getSubaddress(
                    transaction.accountIndex,
                    transaction.addressIndex
                )
            } catch (_: Exception) {
                null
            }
        }
    }
    val outputColor = if (isFrozen) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("冻结") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "输出 $outputNumber",
                        modifier = Modifier.width(76.dp),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        if (hideAmounts) Formats.maskAmount(coin.amount)
                        else Formats.getDisplayAmount(coin.amount),
                        modifier = Modifier.weight(1f),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.size(28.dp))
                }

                Text(
                    text = "状态  " + when {
                        isFrozen -> "已冻结"
                        coin.spent -> "已花费"
                        else -> "未冻结"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .combinedClickable(
                            onClick = { publicKeyExpanded = !publicKeyExpanded },
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(coin.pub_key))
                            }
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "公钥",
                        modifier = Modifier.width(48.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (publicKeyExpanded) coin.pub_key
                        else abbreviatedValue(coin.pub_key),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .combinedClickable(
                            onClick = { hashExpanded = !hashExpanded },
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(coin.hash))
                            }
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "哈希",
                        modifier = Modifier.width(48.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (hashExpanded) coin.hash else abbreviatedValue(coin.hash),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .combinedClickable(
                            onClick = { addressExpanded = !addressExpanded },
                            onLongClick = {
                                outputAddress?.let {
                                    clipboardManager.setText(AnnotatedString(it))
                                }
                            }
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "地址",
                        modifier = Modifier.width(48.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        outputAddress?.let { if (addressExpanded) it else abbreviatedValue(it) } ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    // Keep the existing wallet capability check; the UI does not fake a
                    // successful freeze/thaw when the upstream native API cannot perform it.
                    if (isFrozen) onUnfreeze() else onFreeze()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(12.dp)
            ) {
                Text(if (isFrozen) "解冻" else "冻结")
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun OutputDetailScreenPreview() {
    AnonNeroTheme {
        // The preview shows a static, unfrozen output layout.
        OutputDetailScreen(
            coin = CoinsInfo(
                unlockTime = 0L,
                spent = false,
                key = "",
                amount = 0L,
                hash = "",
                pub_key = "",
                frozen = false,
                creationTime = 0L
            ),
            outputNumber = 1,
            hideAmounts = false,
            isFrozen = false,
            onBackPress = {},
            onFreeze = {},
            onUnfreeze = {}
        )
    }
}
