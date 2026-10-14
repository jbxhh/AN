package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anonero.AnonConfig
import io.anonero.BuildConfig
import io.anonero.R
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SettingsDataRoute
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column {
                LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
                    item {
                        SectionHeader(stringResource(R.string.settings_connection))
                    }
                    item {
                        MenuItem(stringResource(R.string.settings_node_settings)) {
                            navigateTo(SettingsNodeRoute)
                        }
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                    item {
                        MenuItem(stringResource(R.string.settings_proxy_settings)) {
                            navigateTo(ProxySettingsRoute)
                        }
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                    item {
                        SectionHeader(stringResource(R.string.settings_security))
                    }
                    // 钱包 → 全新助记词页面
                    if (!AnonConfig.viewOnly) {
                        item {
                            MenuItem(stringResource(R.string.settings_wallet_section)) {
                                navigateTo(SettingsViewSeedRoute)
                            }
                            HorizontalDivider(thickness = 2.5.dp)
                        }
                    }
                    // 数据 → 全新数据页面（修改PIN + 导出备份）
                    item {
                        MenuItem(stringResource(R.string.settings_data_section)) {
                            navigateTo(SettingsDataRoute)
                        }
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                    // 删除 → 全新安全删除页面
                    item {
                        MenuItem(stringResource(R.string.settings_secure_wipe)) {
                            navigateTo(SecureWipeRoute)
                        }
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .padding(bottom = 64.dp)
            ) {
                Text(
                    stringResource(
                        R.string.settings_app_version,
                        stringResource(R.string.app_name),
                        BuildConfig.VERSION_NAME
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.6f)
                    ),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navigateTo(SettingsLogs) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
    )
}

@Composable
private fun MenuItem(title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) }
    )
}

@Preview(device = "id:pixel_5")
@Composable
private fun SettingsPagePrev() {
    AnonNeroTheme { SettingsPage() }
}
