package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.model.Subaddress
import io.anonero.icons.AnonIcons
import io.anonero.services.WalletState
import io.anonero.ui.components.SubAddressLabelDialog
import io.anonero.ui.components.qr.QrCodeColors
import io.anonero.ui.components.qr.QrCodeView
import org.koin.java.KoinJavaComponent.inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateToSubAddresses: () -> Unit = {}
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val nextAddress by walletState.nextAddress.collectAsState(null)
    var labelDialog by remember { mutableStateOf(false) }
    var addressLoadAttempt by remember { mutableStateOf(0) }
    var isPreparingAddress by remember { mutableStateOf(true) }
    var addressLoadError by remember { mutableStateOf<String?>(null) }
    var displayedAddress by remember { mutableStateOf<Subaddress?>(null) }
    var isAddingAddress by remember { mutableStateOf(false) }
    val addAddressScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(nextAddress, isPreparingAddress) {
        // Do not latch the fallback snapshot while address preparation is still running.
        // Otherwise a newly generated unused address can never replace the fallback.
        if (!isPreparingAddress && displayedAddress == null && nextAddress != null) {
            displayedAddress = nextAddress
        }
    }

    LaunchedEffect(walletState, addressLoadAttempt) {
        isPreparingAddress = true
        addressLoadError = null
        try {
            withContext(Dispatchers.IO) {
                walletState.publishWalletSnapshot()
                walletState.ensureNextReceiveAddress()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Receive").e(e, "Failed to prepare receive address")
            addressLoadError = "无法加载收款地址，请重试"
        } finally {
            isPreparingAddress = false
        }
    }
    if (labelDialog)
        SubAddressLabelDialog(
            label = nextAddress!!.displayLabel,
            onSave = { label ->
                walletState.updateAddressLabel(label, nextAddress!!.addressIndex)
                labelDialog = false
            }, onCancel = {
                labelDialog = false
            })

    val activeReceiveAddress = displayedAddress ?: nextAddress

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.primary_address))
                },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isAddingAddress) {
                                addAddressScope.launch {
                                    isAddingAddress = true
                                    try {
                                        val createdAddress = withContext(Dispatchers.IO) {
                                            walletState.getNewAddress()
                                        }
                                        displayedAddress = createdAddress
                                        Toast.makeText(
                                            context,
                                            "已添加子地址 #" + createdAddress.addressIndex,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Timber.tag("Receive").e(e, "Failed to add subaddress")
                                        Toast.makeText(
                                            context,
                                            e.message?.takeIf(String::isNotBlank) ?: "添加子地址失败",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        isAddingAddress = false
                                    }
                                }
                            }
                        },
                        enabled = !isAddingAddress
                    ) {
                        if (isAddingAddress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_subaddress_label)
                            )
                        }
                    }
                    IconButton(
                        onClick = navigateToSubAddresses
                    ) {
                        Icon(AnonIcons.History, contentDescription = stringResource(R.string.subaddress_label_list))
                    }
                }
            )
        }) {
        if (activeReceiveAddress == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (addressLoadError == null) {
                        CircularProgressIndicator()
                        Text("正在准备收款地址…")
                    } else {
                        Text(addressLoadError!!)
                        TextButton(onClick = { addressLoadAttempt += 1 }) {
                            Text("重试")
                        }
                    }
                }
            }
        } else {
            val receiveAddress = activeReceiveAddress!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = it,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    QrCodeView(
                        data = receiveAddress.address,
                        colors = QrCodeColors(
                            background = MaterialTheme.colorScheme.background,
                            foreground = MaterialTheme.colorScheme.onBackground,
                        ),
                        modifier = Modifier.size(300.dp).padding(18.dp),
                    )
                }
                item {
                    SelectionContainer(
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(text = receiveAddress.address))
                            Toast.makeText(
                                context,
                                context.getString(R.string.copied_to_clipboard),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text(receiveAddress.address, modifier = Modifier.width(300.dp))
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun ReceiveScreenPreview() {
    AnonNeroTheme {
        ReceiveScreen()
    }
}
