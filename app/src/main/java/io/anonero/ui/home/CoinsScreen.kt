package io.anonero.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.anonero.model.WalletManager
import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.anonero.ui.theme.Orange
import io.anonero.util.Formats
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CoinsScreen(
    modifier: Modifier = Modifier,
    navigateToSpend: (route: SendScreenRoute) -> Unit = {},
    onBackPress: () -> Unit = {},
    selected: Set<String> = setOf(),
    onCoinClick: (CoinsInfo) -> Unit = {},
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val coins by walletState.coins.collectAsState(arrayListOf())
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)

    var selectedCoins by rememberSaveable(
        selected,
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(selected) }

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

    // Match upstream behavior: selection state and the send route use output public keys.
    // CoinsInfo.key is the key image used later by transaction construction, not the UI ID.
    fun selectCoin(coin: CoinsInfo) {
        if (coin.frozen || coin.spent) return

        selectedCoins = if (selectedCoins.contains(coin.pub_key)) {
            selectedCoins - coin.pub_key
        } else {
            selectedCoins + coin.pub_key
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
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
                            XmrText(
                                modifier = Modifier.offset(y = (-2).dp),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 30.sp,
                                    color = Orange
                                )
                            )
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
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .clickable { onCoinClick(coin) },
                            headlineContent = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.output_number, index + 1),
                                        color = if (isFrozen || coin.spent) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Text(
                                        if (hideAmounts) Formats.maskAmount(coin.amount)
                                        else Formats.getDisplayAmount(coin.amount),
                                        modifier = Modifier.weight(1f),
                                        color = if (isFrozen || coin.spent) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            },
                            supportingContent = {
                                SelectionContainer {
                                    Text(
                                        coin.pub_key,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Justify
                                    )
                                }
                            },
                            trailingContent = {
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
                                        checked = selectedCoins.contains(coin.pub_key),
                                        onCheckedChange = { selectCoin(coin) }
                                    )
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

@Preview(device = "id:pixel")
@Composable
private fun ReceiveScreenPreview() {
    AnonNeroTheme {
        CoinsScreen()
    }
}
