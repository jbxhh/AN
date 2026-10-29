package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun abbreviatedValue(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    val available = (maxChars - 3).coerceAtLeast(2)
    val left = available / 2
    val right = available - left
    return value.take(left) + "..." + value.takeLast(right)
}

private fun abbreviatedValueToFit(
    value: String,
    maxWidthPx: Int,
    style: androidx.compose.ui.text.TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): String {
    if (value.length <= 5) return value
    fun fits(candidate: String): Boolean =
        textMeasurer.measure(AnnotatedString(candidate), style = style, softWrap = false).size.width <= maxWidthPx

    if (fits(value)) return value
    var low = 5
    var high = value.length - 1
    var best = abbreviatedValue(value, 5)
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = abbreviatedValue(value, mid)
        if (fits(candidate)) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun fittingPrefixLength(
    value: String,
    maxWidthPx: Int,
    style: androidx.compose.ui.text.TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): Int {
    var low = 0
    var high = value.length
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = value.take(mid)
        val width = textMeasurer.measure(
            AnnotatedString(candidate),
            style = style,
            softWrap = false
        ).size.width
        if (width <= maxWidthPx) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun fittingSuffixLength(
    value: String,
    maxWidthPx: Int,
    style: androidx.compose.ui.text.TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): Int {
    var low = 0
    var high = value.length
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = value.takeLast(mid)
        val width = textMeasurer.measure(
            AnnotatedString(candidate),
            style = style,
            softWrap = false
        ).size.width
        if (width <= maxWidthPx) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

@Composable
private fun IdentifierValueText(
    value: String?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayValue = value ?: "____"
    val style = MaterialTheme.typography.titleMedium
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val ellipsisWidthPx = textMeasurer.measure(
            AnnotatedString("..."),
            style = style,
            softWrap = false
        ).size.width
        val sideWidthPx = ((maxWidthPx - ellipsisWidthPx) / 2).coerceAtLeast(0)
        val rightWidthPx = (maxWidthPx - sideWidthPx - ellipsisWidthPx).coerceAtLeast(0)
        val fullValueFits = textMeasurer.measure(
            AnnotatedString(displayValue),
            style = style,
            softWrap = false
        ).size.width <= maxWidthPx

        if (expanded) {
            // Preserve the original expanded presentation.
            Text(
                text = displayValue,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                style = style
            )
        } else if (fullValueFits) {
            Text(
                text = displayValue,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.End,
                style = style
            )
        } else {
            val prefixLength = remember(displayValue, sideWidthPx, style, textMeasurer) {
                fittingPrefixLength(displayValue, sideWidthPx, style, textMeasurer)
            }
            val suffixLength = remember(displayValue, rightWidthPx, style, textMeasurer) {
                fittingSuffixLength(displayValue, rightWidthPx, style, textMeasurer)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = displayValue.take(prefixLength),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Start,
                    style = style
                )
                Text(
                    text = "...",
                    modifier = Modifier.width(with(density) { ellipsisWidthPx.toDp() }),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    style = style
                )
                Text(
                    text = displayValue.takeLast(suffixLength),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.End,
                    style = style
                )
            }
        }
    }
}

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
                        val outputLabel = stringResource(R.string.output_number, 0)
                            .substringBefore("0")
                            .trimEnd()
                        Text(
                            outputLabel,
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
                    Text("公钥", modifier = Modifier.height(24.dp), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    IdentifierValueText(
                        value = coin?.pub_key,
                        expanded = pubKeyState == 1,
                        modifier = Modifier.weight(1f)
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
                    Text("哈希", modifier = Modifier.height(24.dp), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    IdentifierValueText(
                        value = coin?.hash,
                        expanded = hashState == 1,
                        modifier = Modifier.weight(1f)
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
                    Text("地址", modifier = Modifier.height(24.dp), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    IdentifierValueText(
                        value = outputAddress,
                        expanded = addressState == 1,
                        modifier = Modifier.weight(1f)
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
