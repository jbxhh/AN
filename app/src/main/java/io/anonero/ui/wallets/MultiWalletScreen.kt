package io.anonero.ui.wallets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.ui.theme.AnonOutlineButton
import java.io.File

/**
 * Independent multi-wallet landing page.
 *
 * Its overall composition follows the existing onboarding welcome page:
 * same logo placement, content column alignment, button width/spacing,
 * and proxy settings placement. The wallet list replaces the two
 * create/restore actions; creating a wallet continues through the original
 * onboarding welcome route.
 */
@Composable
fun MultiWalletScreen(
    onWalletSelected: (File) -> Unit,
    onProxySettings: () -> Unit,
    onNewWallet: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var wallets by remember(context) {
        mutableStateOf(AnonConfig.getWalletFiles(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                wallets = AnonConfig.getWalletFiles(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                IconButton(
                    onClick = onProxySettings,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Proxy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
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
                            .width(220.dp)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(wallets, key = { it.name }) { walletFile ->
                            AnonOutlineButton(
                                onClick = { onWalletSelected(walletFile) },
                                modifier = Modifier.width(220.dp),
                            ) {
                                Text(
                                    text = walletDisplayName(walletFile),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                AnonOutlineButton(
                    onClick = onNewWallet,
                    modifier = Modifier.width(220.dp),
                ) {
                    Text("新建钱包")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun walletDisplayName(walletFile: File): String {
    if (walletFile.name == "anon") return "Monero Wallet"
    val walletNumber = walletFile.name.removePrefix("wallet_").toIntOrNull()
    return if (walletNumber != null) {
        "Monero Wallet ($walletNumber)"
    } else {
        walletFile.name
    }
}
