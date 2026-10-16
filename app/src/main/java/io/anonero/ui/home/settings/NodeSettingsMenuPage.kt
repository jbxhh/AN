package io.anonero.ui.home.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.ui.home.graph.routes.NodeManagementRoute
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.theme.AnonNeroTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSettingsMenuPage(
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
                title = { Text("节点") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            item(key = "node-settings") {
                SettingsMenuItem(stringResource(R.string.settings_node_settings)) {
                    navigateTo(NodeManagementRoute)
                }
                SettingsMenuDivider()
            }
            item(key = "proxy-settings") {
                SettingsMenuItem(stringResource(R.string.settings_proxy_settings)) {
                    navigateTo(ProxySettingsRoute)
                }
                SettingsMenuDivider()
            }
        }
    }
}

@Composable
private fun SettingsMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 4.dp),
        thickness = 2.5.dp
    )
}

@Composable
private fun SettingsMenuItem(title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        headlineContent = { Text(title, textAlign = TextAlign.Start) }
    )
}

@Preview(device = "id:pixel_5")
@Composable
private fun NodeSettingsMenuPagePreview() {
    AnonNeroTheme { NodeSettingsMenuPage() }
}
