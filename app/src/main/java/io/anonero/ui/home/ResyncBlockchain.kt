package io.anonero.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
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
    val walletState = koinInject<WalletState>()
    val scope = rememberCoroutineScope()

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
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text("高度恢复", color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = restoreHeightInput,
                            onValueChange = { restoreHeightInput = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            placeholder = { Text("输入恢复区块高度") }
                        )
                    }
                )
                ListItem(
                    headlineContent = {
                        Text("时间恢复", color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = restoreDateInput,
                            onValueChange = { restoreDateInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            placeholder = { Text("YYYY-MM-DD") }
                        )
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    onClick = {
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
                            else -> wallet?.restoreHeight
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
                ) {
                    Text("重置")
                }
            }
        }
    }
}
