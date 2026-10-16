package io.anonero.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import io.anonero.ui.theme.AnonOutlineButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResyncBlockchainScreen(
    onBackPress: () -> Unit,
    toastState: ToasterState,
    modifier: Modifier = Modifier
) {
    var restoreHeightInput by remember { mutableStateOf("") }
    var restoreDateInput by remember { mutableStateOf("") }
    var isQuerying by remember { mutableStateOf(false) }
    var queryCompleted by remember { mutableStateOf(false) }
    val walletState = koinInject<WalletState>()
    val scope = rememberCoroutineScope()
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("重置同步区块") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(8f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Top
            ) {
                if (isQuerying) {
                    RestoreFieldLoading("高度恢复", "正在查询高度…")
                    RestoreFieldLoading("时间恢复", "正在查询时间…")
                } else if (queryCompleted) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "高度恢复",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = restoreHeightInput,
                                onValueChange = { restoreHeightInput = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                ),
                                placeholder = {
                                    Text("输入恢复区块高度", color = labelColor)
                                }
                            )
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "时间恢复",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = restoreDateInput,
                                onValueChange = { restoreDateInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                placeholder = {
                                    Text("YYYY-MM-DD", color = labelColor)
                                }
                            )
                        }
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
                    modifier = Modifier.fillMaxWidth(0.9f),
                    onClick = {
                        if (isQuerying) {
                            // Avoid starting duplicate queries while the progress indicators are visible.
                        } else if (!queryCompleted) {
                            isQuerying = true
                            scope.launch {
                                try {
                                    val restoreInfo = withContext(Dispatchers.IO) {
                                        val wallet = WalletManager.instance?.wallet
                                            ?: throw IllegalStateException("钱包未打开")
                                        if (!wallet.isInitialized) {
                                            throw IllegalStateException("钱包尚未初始化")
                                        }
                                        val height = wallet.getRestoreHeight()
                                        if (height < 0L) {
                                            throw IllegalStateException("无法获取恢复区块高度")
                                        }
                                        val timestamp = 1397818193L + height * 120L
                                        height to SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                            .format(Date(timestamp * 1000L))
                                    }
                                    // Keep the loading indicators visible long enough to be perceived.
                                    delay(350)
                                    restoreHeightInput = restoreInfo.first.toString()
                                    restoreDateInput = restoreInfo.second
                                    queryCompleted = restoreHeightInput.isNotBlank() &&
                                        restoreDateInput.isNotBlank()
                                } catch (e: Exception) {
                                    toastState.show(
                                        "查询失败：${e.message ?: "无法获取恢复信息"}",
                                        type = ToastType.Warning,
                                        duration = 6.seconds
                                    )
                                } finally {
                                    isQuerying = false
                                }
                            }
                        } else {
                            val wallet = WalletManager.instance?.wallet
                            val selectedHeight = when {
                                restoreHeightInput.isNotBlank() -> restoreHeightInput.toLongOrNull()
                                restoreDateInput.isNotBlank() -> try {
                                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                        isLenient = false
                                    }.parse(restoreDateInput)
                                    date?.let {
                                        ((it.time / 1000L - 1397818193L).coerceAtLeast(0L) / 120L)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                                else -> wallet?.getRestoreHeight()
                            }
                            if (wallet == null || selectedHeight == null || selectedHeight < 0L) {
                                scope.launch {
                                    toastState.show(
                                        "请输入有效的区块高度或日期（YYYY-MM-DD）",
                                        type = ToastType.Warning
                                    )
                                }
                            } else {
                                wallet.setRestoreHeight(selectedHeight)
                                val result = walletState.resyncBlockchain()
                                if (result.isFailure) {
                                    scope.launch {
                                        toastState.show(
                                            "重置失败：${result.exceptionOrNull()?.message}",
                                            type = ToastType.Warning,
                                            duration = 6.seconds
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        toastState.show(
                                            "已开始从所选恢复位置重新同步",
                                            type = ToastType.Success
                                        )
                                    }
                                    onBackPress()
                                }
                            }
                        }
                    }
                ) {
                    Text(if (queryCompleted) "重置" else "查询")
                }
            }
        }
    }
}

@Composable
private fun RestoreFieldLoading(title: String, placeholder: String) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
        },
        supportingContent = {
            OutlinedTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                readOnly = true,
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                trailingIcon = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            )
        }
    )
}

