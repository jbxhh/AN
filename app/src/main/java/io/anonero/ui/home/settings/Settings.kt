package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.anonero.AnonConfig
import io.anonero.BuildConfig
import io.anonero.R
import io.anonero.model.WalletManager
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SettingsExportBackUp
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute
import io.anonero.ui.onboard.PinSetup
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.WALLET_PREFERENCES
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber

private const val TAG = "SettingsPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {

    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val toastState = rememberToasterState()
    var showLockScreen by remember { mutableStateOf(false) }
    var newPinDialog by remember { mutableStateOf(false) }
    var showDataMenu by remember { mutableStateOf(false) }

    if (showLockScreen) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            ),
            onDismissRequest = { showLockScreen = false }) {
            LockScreen(
                mode = LockScreenMode.VERYFY_PIN,
                modifier = Modifier.fillMaxHeight(0.95f),
                onUnLocked = { _, _ ->
                    showLockScreen = false
                    newPinDialog = true
                }
            )
        }
    }
    if (newPinDialog) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            ),
            onDismissRequest = { newPinDialog = false }) {
            PinSetup(
                changePin = true,
                onNext = {
                    try {
                        WalletManager.instance?.wallet?.setPassword(it)
                        toastState.show("PIN changed successfully", type = ToastType.Success)
                        prefs.edit(commit = true) {
                            putString(
                                PREFS_PIN_HASH,
                                CrazyPassEncoder.encode(
                                    it.toByteArray().let { bytes ->
                                        if (bytes.size < 32) bytes + ByteArray(32 - bytes.size)
                                        else bytes
                                    }
                                )
                            )
                        }
                    } catch (e: Exception) {
                        toastState.show("Error changing PIN", type = ToastType.Error)
                        Timber.tag(TAG).e(e)
                    } finally {
                        newPinDialog = false
                    }
                }
            )
        }
    }

    // 数据子菜单弹层
    if (showDataMenu) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
                dismissOnClickOutside = true,
                dismissOnBackPress = true
            ),
            onDismissRequest = { showDataMenu = false }
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
            ) {
                Text(
                    stringResource(R.string.settings_data_section),
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                MenuItem(stringResource(R.string.settings_change_pin)) {
                    showDataMenu = false
                    showLockScreen = true
                }
                HorizontalDivider(thickness = 1.dp)
                MenuItem(stringResource(R.string.settings_export_backup)) {
                    showDataMenu = false
                    navigateTo(SettingsExportBackUp)
                }
            }
        }
    }

    Toaster(
        state = toastState,
        maxVisibleToasts = 2,
        alignment = Alignment.TopCenter,
        darkTheme = true
    )
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
                    // 钱包 → 助记词
                    if (!AnonConfig.viewOnly) {
                        item {
                            MenuItem(stringResource(R.string.settings_wallet_section)) {
                                navigateTo(SettingsViewSeedRoute)
                            }
                            HorizontalDivider(thickness = 2.5.dp)
                        }
                    }
                    // 数据 → 弹出修改PIN + 导出备份
                    item {
                        MenuItem(stringResource(R.string.settings_data_section)) {
                            showDataMenu = true
                        }
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                    // 删除
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
