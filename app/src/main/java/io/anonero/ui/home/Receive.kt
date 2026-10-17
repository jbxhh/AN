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
import io.anonero.icons.AnonIcons
import io.anonero.services.WalletState
import io.anonero.ui.components.SubAddressLabelDialog
import io.anonero.ui.components.qr.QrCodeColors
import io.anonero.ui.components.qr.QrCodeView
import org.koin.java.KoinJavaComponent.inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    modifier: Modifier = Modifier,
    navigateToSubAddresses: () -> Unit = {}
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val nextAddress by walletState.nextAddress.collectAsState(null)
    var labelDialog by remember { mutableStateOf(false) }
    var addressLoadAttempt by remember { mutableStateOf(0) }
    var isPreparingAddress by remember { mutableStateOf(true) }
    var addressLoadError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(
                        onClick = navigateToSubAddresses
                    ) {
                        Icon(AnonIcons.History, contentDescription = stringResource(R.string.subaddress_label_list))
                    }
                }
            )
        }) {
        if (isPreparingAddress || addressLoadError != null || nextAddress == null) {
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
            val receiveAddress = nextAddress!!
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
