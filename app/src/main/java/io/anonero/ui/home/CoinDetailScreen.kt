package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    coinKey: String,
    onBackPress: () -> Unit = {},
) {
    val walletState = koinInject<WalletState>()
    val coins by walletState.coins.collectAsState(arrayListOf())
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)
    // The list hides spent outputs and numbers only the remaining visible entries.
    // Use the same filtered order for numbering while resolving details by stable output key.
    val visibleCoins = coins.filter { !it.spent }
    val coin = coins.firstOrNull { it.key == coinKey }
    val coinIndex = visibleCoins.indexOfFirst { it.key == coinKey }
    val scope = rememberCoroutineScope()
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
                    Row(
                        modifier = Modifier.size(width = 52.dp, height = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.output_label),
                            color = outputColor,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (coinIndex >= 0) (coinIndex + 1).toString() else "",
                            color = outputColor,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
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
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "全局索引",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = coin?.globalOutputIndex?.toString() ?: "____",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
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
                    Text("公钥", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = coin?.pub_key ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = if (pubKeyState == 1) TextAlign.End else TextAlign.Start,
                        maxLines = if (pubKeyState == 1) Int.MAX_VALUE else 1,
                        softWrap = pubKeyState == 1,
                        overflow = if (pubKeyState == 1) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.titleMedium
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
                    Text("哈希", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = coin?.hash ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = if (hashState == 1) TextAlign.End else TextAlign.Start,
                        maxLines = if (hashState == 1) Int.MAX_VALUE else 1,
                        softWrap = hashState == 1,
                        overflow = if (hashState == 1) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.titleMedium
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
                    Text("地址", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = outputAddress ?: "____",
                        modifier = Modifier.weight(1f),
                        textAlign = if (addressState == 1) TextAlign.End else TextAlign.Start,
                        maxLines = if (addressState == 1) Int.MAX_VALUE else 1,
                        softWrap = addressState == 1,
                        overflow = if (addressState == 1) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            AnonOutlineButton(
                enabled = coin != null && !busy && (!coin.spent || coin.frozen),
                onClick = {
                    val selectedCoin = coin ?: return@AnonOutlineButton
                    if (busy || (selectedCoin.spent && !selectedCoin.frozen)) return@AnonOutlineButton
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (selectedCoin.frozen) {
                                walletState.thawCoin(selectedCoin.pub_key)
                            } else if (!selectedCoin.spent) {
                                walletState.freezeCoin(selectedCoin.pub_key)
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    when {
                        coin?.spent == true && !coin.frozen -> "已花费"
                        coin?.frozen == true -> "解冻"
                        else -> "冻结"
                    }
                )
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
