package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
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
    val walletStatus by walletState.walletStatus.collectAsState(null)
    val walletReady = walletStatus != null
    var labelDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // The local read-only wallet snapshot can temporarily publish only address index 0
    // while a new wallet is initializing or prioritized reset work is active.
    // Never encode the primary address: retry until a real child address is available.
    LaunchedEffect(walletState, walletReady, nextAddress?.addressIndex) {
        var retryCount = 0
        while (true) {
            if (walletState.isWalletWipeInProgress) return@LaunchedEffect
            if (nextAddress?.let { it.addressIndex > 0 && it.address.isNotBlank() } == true) {
                return@LaunchedEffect
            }

            try {
                // WalletState blocks this during reset setup/wipe, but allows the
                // short address-generation call once the async rescan has been queued.
                val address = walletState.ensureNextReceiveAddress()
                if (address.addressIndex > 0 && address.address.isNotBlank()) {
                    return@LaunchedEffect
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep retrying while startup/reset setup settles, but avoid flooding the log.
                if (retryCount == 0 || retryCount % 10 == 0) {
                    Timber.tag("ReceiveScreen").w(
                        e,
                        "Waiting for a usable receive subaddress"
                    )
                }
            }

            retryCount++
            delay(1_000L)
        }
    }
    if (labelDialog)
        SubAddressLabelDialog(
            label = nextAddress!!.label,
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
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = navigateToSubAddresses
                    ) {
                        Icon(AnonIcons.History, contentDescription = stringResource(R.string.subaddress_label_list))
                    }
                }
            )
        }) {
        // A primary address (index 0) must never appear on the Receive screen.
        // Wait for the local wallet to return a real child subaddress; this is independent
        // of daemon connection state and prevents the QR from encoding the primary address.
        if (nextAddress != null &&
            nextAddress!!.addressIndex > 0 &&
            nextAddress!!.address.isNotBlank()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = it,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Spacer(Modifier.height(24.dp))
                }
                item {
                    QrCodeView(
                        data = nextAddress!!.address,
                        colors = QrCodeColors(
                            background = MaterialTheme.colorScheme.background,
                            foreground = MaterialTheme.colorScheme.onBackground,
                        ),
                        modifier = Modifier
                            .size(300.dp)
                            .padding(18.dp),
                    )
                }
                item {
                    SelectionContainer(
                        modifier = Modifier.clickable {
                            nextAddress?.let {
                                clipboardManager.setText(AnnotatedString(text = it.address))
                                Toast.makeText(context,
                                    context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    ) {
                        Text(
                            nextAddress!!.address,
                            modifier = Modifier.width(300.dp)
                        )
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
        ReceiveScreen()
    }
}
