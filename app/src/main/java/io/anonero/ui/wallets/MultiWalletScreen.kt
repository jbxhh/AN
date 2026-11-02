package io.anonero.ui.wallets

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.ui.theme.AnonOutlineButton
import io.anonero.util.WALLET_PREFERENCES
import java.io.File

private const val WALLET_DISPLAY_NAME_KEY = "wallet_display_name"

/**
 * Independent multi-wallet landing page.
 *
 * Wallet rows show only the wallet name. A saved custom name takes priority;
 * otherwise the stable default name derived from the wallet file is shown.
 * Long-press a row to set a custom name or restore its default name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiWalletScreen(
    onWalletSelected: (File) -> Unit,
    onProxySettings: () -> Unit,
    onNewWallet: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val walletPrefs = remember(context) {
        context.getSharedPreferences(WALLET_PREFERENCES, Context.MODE_PRIVATE)
    }
    var wallets by remember(context) {
        mutableStateOf(AnonConfig.getWalletFiles(context))
    }
    var walletToRename by remember { mutableStateOf<File?>(null) }
    var editedName by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                wallets = AnonConfig.getWalletFiles(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    walletToRename?.let { walletFile ->
        AlertDialog(
            onDismissRequest = { walletToRename = null },
            title = { Text("自定义钱包名称") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    label = { Text("钱包名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val customName = editedName.trim()
                        walletPrefs.edit {
                            if (customName.isEmpty()) {
                                remove(walletDisplayNamePreferenceKey(walletFile))
                            } else {
                                putString(walletDisplayNamePreferenceKey(walletFile), customName)
                            }
                        }
                        walletToRename = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            walletPrefs.edit {
                                remove(walletDisplayNamePreferenceKey(walletFile))
                            }
                            walletToRename = null
                        }
                    ) {
                        Text("恢复默认")
                    }
                    TextButton(onClick = { walletToRename = null }) {
                        Text("取消")
                    }
                }
            },
        )
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.size(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_anon),
                    contentDescription = "Anon nero icon",
                    modifier = Modifier.size(120.dp)
                )
            }

            // Proxy settings gear remains above the wallet list, at the upper right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                IconButton(
                    onClick = onProxySettings,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Proxy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .weight(1.2f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (wallets.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            // One wallet row is 68 dp plus its divider. Keep a
                            // five-row viewport; additional wallets scroll here.
                            .height((wallets.size.coerceAtMost(5) * 69).dp),
                    ) {
                        items(wallets, key = { it.name }) { walletFile ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 68.dp)
                                        .combinedClickable(
                                            onClick = { onWalletSelected(walletFile) },
                                            onLongClick = {
                                                editedName = walletPrefs.getString(
                                                    walletDisplayNamePreferenceKey(walletFile),
                                                    ""
                                                ).orEmpty()
                                                walletToRename = walletFile
                                            }
                                        )
                                        .padding(horizontal = 16.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = walletDisplayName(
                                            walletName = walletFile.name,
                                            customName = walletPrefs.getString(
                                                walletDisplayNamePreferenceKey(walletFile),
                                                null
                                            )
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 1.dp,
                                )
                            }
                        }
                    }
                }

                AnonOutlineButton(
                    onClick = onNewWallet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Text("新建钱包")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun walletDisplayNamePreferenceKey(walletFile: File): String =
    "${walletFile.name}::$WALLET_DISPLAY_NAME_KEY"

/**
 * Use a non-blank custom display name when present; otherwise use the
 * wallet's stable default name.
 */
internal fun walletDisplayName(walletName: String, customName: String?): String {
    customName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    if (walletName == "anon") return "Monero Wallet (1)"
    val walletNumber = walletName.removePrefix("wallet_").toIntOrNull()
    return if (walletNumber != null) {
        "Monero Wallet ($walletNumber)"
    } else {
        walletName
    }
}
