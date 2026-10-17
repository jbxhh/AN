package io.anonero.ui.home.settings

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.anonero.R
import io.anonero.model.WalletManager
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.graph.routes.SettingsExportBackUp
import io.anonero.ui.onboard.PinSetup
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.WALLET_PREFERENCES
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber

private const val TAG = "SettingsDataPage"

/**
 * 数据页面：修改PIN + 导出备份（全新整页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataPage(
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {
    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val toastState = rememberToasterState()
    var showLockScreen by remember { mutableStateOf(false) }
    var newPinDialog by remember { mutableStateOf(false) }

    if (showLockScreen) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            ),
            onDismissRequest = { showLockScreen = false }
        ) {
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
            onDismissRequest = { newPinDialog = false }
        ) {
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
                title = {
                    Text(stringResource(R.string.settings_security))
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
                item {
                    MenuItem(stringResource(R.string.settings_change_pin)) {
                        showLockScreen = true
                    }
                    HorizontalDivider(thickness = 1.dp)
                }
                item {
                    MenuItem(stringResource(R.string.settings_export_backup)) {
                        navigateTo(SettingsExportBackUp)
                    }
                    HorizontalDivider(thickness = 1.dp)
                }
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
