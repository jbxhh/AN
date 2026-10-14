package io.anonero.ui.home.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.ui.home.graph.routes.CoinsScreenRoute

/**
 * 交易 全新页面 — 与节点/代理/数据相同的全页列表结构，不改 UI 风格。
 * 功能入口：Coins（UTXO 选择，发交易依赖）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTradePage(
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
                title = {
                    Text(stringResource(R.string.settings_trade_section))
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                MenuItem(stringResource(R.string.coins)) {
                    navigateTo(CoinsScreenRoute)
                }
                HorizontalDivider(thickness = 2.5.dp)
            }
        }
    }
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
