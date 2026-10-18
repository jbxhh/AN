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

    var selectedCoins by remember { mutableStateOf(selected) }
    var detailCoin by remember { mutableStateOf<CoinsInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val connectionStatus by walletState.walletConnectionStatus.asLiveData().observeAsState(
        Wallet.ConnectionStatus.ConnectionStatus_Disconnected
    )
    val loading by walletState.isLoading.asLiveData().observeAsState(false)
    val nodeConnected = connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected

    // Output enumeration is only safe after native wallet scanning is synchronized.
    // Keep waiting while this screen is open: nodeConnected/loading can remain unchanged
    // when the native synchronized flag flips, so a one-shot effect can miss the refresh.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var emptyOutputRetries = 0
            while (currentCoroutineContext().isActive) {
                // Wallet startup may finish after the output screen is opened. Retry
                // readiness here instead of requiring another connection-status event.
                val wallet = WalletManager.instance?.wallet
                if (wallet == null || !wallet.isInitialized) {
                    delay(1_000L)
                    continue
                }

                if (wallet.isSynchronized) {
                    try {
                        wallet.refreshCoins()
                        walletState.update()
                        val outputs = wallet.coins?.all.orEmpty()
                        val visibleCount = outputs.count { !it.spent }
                        val hasBalance = wallet.balance > 0L || wallet.unlockedBalance > 0L
                        Timber.tag("CoinsScreen").i(
                            "Refreshed outputs for output list: total=%d visible=%d balance=%d synchronized=%s",
                            outputs.size,
                            visibleCount,
                            wallet.balance,
                            wallet.isSynchronized
                        )
                        // A positive balance with no enumerated outputs can be a transient
                        // native refresh result. Retry a few times, then stop if empty is real.
                        if (visibleCount > 0 || !hasBalance || emptyOutputRetries >= 3) {
                            return@withContext
                        }
                        emptyOutputRetries++
                    } catch (e: Exception) {
                        emptyOutputRetries++
                        Timber.tag("CoinsScreen").w(e, "Unable to refresh outputs for output list")
                        if (emptyOutputRetries > 3) return@withContext
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
            OutputDetailScreen(
                coin = coin,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputDetailScreen(
    coin: CoinsInfo,
    isFrozen: Boolean,
    onBackPress: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
) {
    BackHandler(onBack = onBackPress)

    var selectedAction by remember { mutableStateOf<String?>(if (isFrozen) "unfreeze" else null) }
    val selectionScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 140),
        label = "freezeSelectionScale"
    )

    val orange = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item {
                val selectedScale = selectionScale

                ListItem(
                    modifier = Modifier
                        .padding(
                            horizontal = 4.dp,
                            vertical = 6.dp
                        )
                        .clickable {
                            selectedAction = if (selectedAction == "freeze") null else "freeze"
                        },
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "输出",
                                color = if (isFrozen) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
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

                            Checkbox(
                                checked = selectedAction != null,
                                onCheckedChange = {
                                    selectedAction = if (it) {
                                        if (isFrozen) "unfreeze" else "freeze"
                                    } else {
                                        null
                                    }
                                },
                                modifier = Modifier.scale(selectedScale)
                            )
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val freezeSelected = selectedAction == "freeze"
            OutlinedButton(
                onClick = {
                    if (freezeSelected) {
                        onFreeze()
                        selectedAction = "unfreeze"
                    }
                },
                border = BorderStroke(
                    1.dp,
                    if (freezeSelected) orange else MaterialTheme.colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (freezeSelected) orange
                    else MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("冻结")
            }

            val unfreezeSelected = selectedAction == "unfreeze"
            OutlinedButton(
                onClick = {
                    if (unfreezeSelected) {
                        onUnfreeze()
                        selectedAction = "freeze"
                    }
                },
                border = BorderStroke(
                    1.dp,
                    if (unfreezeSelected) orange else MaterialTheme.colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (unfreezeSelected) orange
                    else MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("解冻")
            }
        }
    }
}
