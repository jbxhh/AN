package io.anonero.ui.home

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
import androidx.lifecycle.asLiveData
import io.anonero.R
import io.anonero.model.CoinsInfo
import io.anonero.model.Wallet
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.util.Formats
import org.koin.java.KoinJavaComponent.inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinsScreen(
    modifier: Modifier = Modifier,
    navigateToSpend: (route: SendScreenRoute) -> Unit = {},
    onBackPress: () -> Unit = {},
    onOpenDetail: (pubKey: String) -> Unit = {},
    frozenCoins: Set<String> = emptySet(),
    selected: Set<String> = setOf(),
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val coins by walletState.coins.collectAsState(arrayListOf())

    var selectedCoins by remember { mutableStateOf(selected) }

    val connectionStatus by walletState.walletConnectionStatus.asLiveData().observeAsState(
        Wallet.ConnectionStatus.ConnectionStatus_Disconnected
    )
    val loading by walletState.isLoading.asLiveData().observeAsState(false)
    val nodeConnected = connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected

    var message: String? = null
    if (loading) {
        message = stringResource(R.string.wallet_loading)
    } else if (!nodeConnected) {
        message = stringResource(R.string.node_disconnected)
    } else if (coins.isEmpty()) {
        message = stringResource(R.string.no_coins_available)
    }

    fun selectCoin(coin: CoinsInfo) {
        if (frozenCoins.contains(coin.pub_key)) return

        selectedCoins = if (selectedCoins.contains(coin.pub_key)) {
            selectedCoins - coin.pub_key
        } else {
            selectedCoins + coin.pub_key
        }
    }

    Scaffold(
        modifier = modifier,
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
                    Text("输出")
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
                val isFrozen = frozenCoins.contains(coin.pub_key)

                ListItem(
                    modifier = Modifier
                        .padding(
                            horizontal = 4.dp,
                            vertical = 6.dp
                        )
                        .clickable {
                            selectedCoins = emptySet()
                            onOpenDetail(coin.pub_key)
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
                            } else {
                                Checkbox(
                                    checked = selectedCoins.contains(coin.pub_key),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    pubKey: String,
    frozenCoins: Set<String> = emptySet(),
    onToggleFreeze: (pubKey: String, freeze: Boolean) -> Unit = { _, _ -> },
    onBackPress: () -> Unit = {},
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val coins by walletState.coins.collectAsState(arrayListOf())
    val coin = coins.find { it.pub_key == pubKey } ?: return

    val isFrozen = frozenCoins.contains(coin.pub_key)

    OutputDetailScreen(
        coin = coin,
        isFrozen = isFrozen,
        onBackPress = onBackPress,
        onFreeze = {
            onToggleFreeze(coin.pub_key, true)
        },
        onUnfreeze = {
            onToggleFreeze(coin.pub_key, false)
        }
    )
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
            title = { Text("输出") },
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
