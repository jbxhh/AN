package io.anonero.ui.home.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import io.anonero.R
import io.anonero.util.WALLET_PREFERENCES
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

private const val SEED_MENU_NAME_KEY = "settings_seed_menu_custom_name"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WalletSettingsPage(
    onBackPress: () -> Unit = {},
    onViewSeed: () -> Unit = {},
) {
    val prefs = koinInject<android.content.SharedPreferences>(named(WALLET_PREFERENCES))
    var seedMenuName by remember {
        mutableStateOf(prefs.getString(SEED_MENU_NAME_KEY, null) ?: "助记词")
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(seedMenuName) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("自定义名称") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    label = { Text("名称") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = editedName.trim()
                        if (name.isNotEmpty()) {
                            seedMenuName = name
                            prefs.edit { putString(SEED_MENU_NAME_KEY, name) }
                        }
                        showRenameDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                title = {
                    Text(stringResource(R.string.settings_wallet_section))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            ListItem(
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onViewSeed,
                    onLongClick = {
                        editedName = seedMenuName
                        showRenameDialog = true
                    }
                ),
                headlineContent = {
                    Text(
                        text = seedMenuName,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            )
        }
    }
}
