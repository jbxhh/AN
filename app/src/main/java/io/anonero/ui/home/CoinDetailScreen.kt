package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import io.anonero.ui.theme.AnonOutlineButton
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private fun breakableIdentifier(value: String, chunkSize: Int = 8): String {
    if (value.isEmpty()) return value
    return value.chunked(chunkSize).joinToString(separator = "\u200B")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    coinKey: String,
    onBackPress: () -> Unit = {},
) {
    BackHandler(onBack = onBackPress)

    val walletState = koinInject<WalletState>()
    val coins by walletState.coins.collectAsState(arrayListOf())
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)
    val coinIndex = coins.indexOfFirst { it.key == coinKey }
    val currentCoin = coins.getOrNull(coinIndex)
    // Keep the opened output visible while WalletState temporarily hides the output
    // inventory during a rescan. A successful freeze/thaw updates this local snapshot.
    var lastKnownCoin by remember(coinKey) { mutableStateOf(currentCoin) }
    LaunchedEffect(currentCoin) {
        if (currentCoin != null) lastKnownCoin = currentCoin
    }
    val coin = currentCoin ?: lastKnownCoin
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var pubKeyState by remember { mutableStateOf(0) }
    var hashState by remember { mutableStateOf(0) }
    var addressState by remember { mutableStateOf(0) }
    val outputAddress = coin?.let { walletState.getAddressForCoin(it.hash) }
    val outputColor = if (coin?.frozen == true) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("冻结") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "输出 " + if (coinIndex >= 0) coinIndex + 1 else "",
                        modifier = Modifier.size(width = 52.dp, height = 24.dp),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        coin?.let {
                            if (hideAmounts) Formats.maskAmount(it.amount)
                            else Formats.getDisplayAmount(it.amount)
                        } ?: "____",
                        modifier = Modifier.weight(1f),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Spacer(modifier = Modifier.size(28.dp))
                    }
                }
                Text(
                    text = "全局索引  " + (coin?.globalOutputIndex ?: "____"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .combinedClickable(
                        onClick = { pubKeyState = if (pubKeyState == 1) 0 else 1 },
                        onLongClick = { coin?.pub_key?.let { clipboardManager.setText(AnnotatedString(it)) } }
                    ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("公钥", modifier = Modifier.size(width = 40.dp, height = 24.dp), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = coin?.let {
                            if (pubKeyState == 1) breakableIdentifier(it.pub_key) else it.pub_key
                        } ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (pubKeyState == 1) Int.MAX_VALUE else 1,
                        softWrap = pubKeyState == 1,
                        overflow = if (pubKeyState == 1) TextOverflow.Clip else TextOverflow.Ellipsis
                    )
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .combinedClickable(
                        onClick = { hashState = if (hashState == 1) 0 else 1 },
                        onLongClick = { coin?.hash?.let { clipboardManager.setText(AnnotatedString(it)) } }
                    ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("哈希", modifier = Modifier.size(width = 40.dp, height = 24.dp), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = coin?.let {
                            if (hashState == 1) breakableIdentifier(it.hash) else it.hash
                        } ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (hashState == 1) Int.MAX_VALUE else 1,
                        softWrap = hashState == 1,
                        overflow = if (hashState == 1) TextOverflow.Clip else TextOverflow.Ellipsis
                    )
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .combinedClickable(
                        onClick = { addressState = if (addressState == 1) 0 else 1 },
                        onLongClick = { outputAddress?.let { clipboardManager.setText(AnnotatedString(it)) } }
                    ),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("地址", modifier = Modifier.size(width = 40.dp, height = 24.dp), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = outputAddress?.let {
                            if (addressState == 1) breakableIdentifier(it) else it
                        } ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (addressState == 1) Int.MAX_VALUE else 1,
                        softWrap = addressState == 1,
                        overflow = if (addressState == 1) TextOverflow.Clip else TextOverflow.Ellipsis
                    )
                }
            }
            AnonOutlineButton(
                onClick = {
                    val selectedCoin = coin ?: return@AnonOutlineButton
                    if (busy) return@AnonOutlineButton
                    busy = true
                    scope.launch {
                        val wasFrozen = selectedCoin.frozen
                        val error = try {
                            val result = withContext(Dispatchers.IO) {
                                if (wasFrozen) {
                                    walletState.thawCoin(selectedCoin.pub_key)
                                } else {
                                    walletState.freezeCoin(selectedCoin.pub_key)
                                }
                            }
                            result.exceptionOrNull()
                        } catch (e: Exception) {
                            e
                        } finally {
                            busy = false
                        }
                        if (error != null) {
                            snackbarHostState.showSnackbar(
                                (if (wasFrozen) "解冻失败：" else "冻结失败：") +
                                    (error.message ?: "未知错误")
                            )
                        } else {
                            lastKnownCoin = selectedCoin.copy(frozen = !wasFrozen)
                            snackbarHostState.showSnackbar(if (wasFrozen) "解冻成功" else "冻结成功")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(if (coin?.frozen == true) "解冻" else "冻结")
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun CoinDetailScreenPreview() {
    AnonNeroTheme {
        CoinDetailScreen(coinKey = "")
    }
}
