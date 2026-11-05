package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anonero.AnonConfig
import io.anonero.BuildConfig
import io.anonero.R
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SettingsDataRoute
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.SettingsTradeRoute
import io.anonero.ui.home.graph.routes.SettingsWalletRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute
import io.anonero.util.AppPassphraseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accessGranted by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var passwordIncorrect by remember { mutableStateOf(false) }

    if (accessGranted) {
        SettingsPageContent(modifier, onBackPress, navigateTo)
    } else {
        AlertDialog(
            onDismissRequest = onBackPress,
            title = { Text("验证设置密码") },
            text = {
                Column {
                    Text("请输入首次打开 APP 时设置的外加密码")
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            passwordIncorrect = false
                        },
                        singleLine = true,
                        label = { Text("外加密码") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (passwordIncorrect) {
                        Text(
                            text = "外加密码错误",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty() && !verifying,
                    onClick = {
                        if (verifying) return@TextButton
                        verifying = true
                        scope.launch {
                            val valid = withContext(Dispatchers.IO) {
                                AppPassphraseStore.verify(context, passphrase)
                            }
                            verifying = false
                            if (valid) {
                                passphrase = ""
                                accessGranted = true
                            } else {
                                passwordIncorrect = true
                            }
                        }
                    },
                ) {
                    Text(if (verifying) "验证中…" else "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = onBackPress) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageContent(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {

    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                },
                title = { Text("ΛИ0И", modifier = Modifier.offset(x = (-6).dp)) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                item(key = "node") {
                    MenuItem(stringResource(R.string.settings_node_settings)) {
                        navigateTo(SettingsNodeRoute)
                    }
                    SettingsDivider()
                }
                if (!AnonConfig.viewOnly) {
                    item(key = "wallet") {
                        MenuItem(stringResource(R.string.settings_wallet_section)) {
                            navigateTo(SettingsWalletRoute)
                        }
                        SettingsDivider()
                    }
                }
                item(key = "trade") {
                    MenuItem(stringResource(R.string.settings_trade_section)) {
                        navigateTo(SettingsTradeRoute)
                    }
                    SettingsDivider()
                }
                item(key = "data") {
                    MenuItem(stringResource(R.string.settings_security)) {
                        navigateTo(SettingsDataRoute)
                    }
                    SettingsDivider()
                }
                item(key = "delete") {
                    MenuItem(stringResource(R.string.settings_secure_wipe)) {
                        navigateTo(SecureWipeRoute)
                    }
                    SettingsDivider()
                }
                item(key = "delete-footer-divider") {
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(56.dp)
                    )
                    SettingsDivider()
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
                        color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    ),
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
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 4.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
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
