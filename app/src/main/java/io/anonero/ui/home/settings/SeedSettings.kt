package io.anonero.ui.home.settings

import io.anonero.ui.theme.AnonNeroTheme
import io.anonero.ui.theme.AnonOutlineButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.NeroKeyPayload
import io.anonero.model.WalletManager
import io.anonero.ui.components.qr.QrCodeColors
import io.anonero.ui.components.qr.QrCodeView
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.ShakeConfig
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import kotlin.random.Random


fun getWalletSeed(passPhrase: String): String? {
    return WalletManager.instance?.wallet?.getSeed(passPhrase)
}

fun getLegacySeed(passPhrase: String): String? {
    return WalletManager.instance?.wallet?.getLegacySeed(passPhrase)
}

fun generatePlaceHolderSeed(): List<String> {
    return (1..16).map {
        (1..Random.nextInt(6, 8)).joinToString("") { "*" }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeedSettingsPage(onBackPress: () -> Unit = {}) {

    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))

    var seedWords by remember {
        mutableStateOf(
            generatePlaceHolderSeed()
        )
    }
    var legacySeed by remember {
        mutableStateOf(
            generatePlaceHolderSeed()
        )
    }
    val focusRequester = remember { FocusRequester() }
    var passPhraseDialog by remember { mutableStateOf(true) }
    var passPhrase by remember { mutableStateOf("") }
    var neroPayload by remember { mutableStateOf("") }
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    val backgroundBlur: Float by animateFloatAsState(
        if (passPhraseDialog) 6f else 0f,
        label = "blur-radius"
    )


    fun exportForNero() {
        val wallet = WalletManager.instance?.wallet ?: return
        if (passPhrase.isEmpty()) {
            passPhraseDialog = true
            return
        }
        val payload = Json.encodeToString(NeroKeyPayload.fromWallet(wallet))
        neroPayload = payload.toString();
    }


    LaunchedEffect(true) {
        scope.launch {
            delay(100)
            focusRequester.requestFocus()
        }
    }
    fun getSeed() {
        scope.launch(Dispatchers.IO) {
            val hash = prefs.getString(PREFS_PASSPHRASE_HASH, "")
            val hashedPass = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            if (hash == hashedPass) {
                val seed = getWalletSeed(passPhrase)?.split(" ")
                val legacy = getLegacySeed(passPhrase)?.split(" ")
                if (seed != null) {
                    seedWords = seed
                    passPhraseDialog = false
                }
                if (legacy != null) {
                    legacySeed = legacy
                }
            } else {
                errorShake.shake(
                    ShakeConfig(
                        6, translateX = 5f
                    )
                )
                repeat(6) {
                    delay(50)
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
                delay(100)
            }

        }
    }
    if (neroPayload.isNotEmpty()) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
            scrimColor = MaterialTheme.colorScheme.background.copy(
                alpha = 0.5f
            ),
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = {
                neroPayload = ""
            }
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.padding(8.dp))
                Text(
                    stringResource(id = R.string.nero_keys),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                Box(modifier = Modifier.padding(8.dp))
                QrCodeView(
                    data = neroPayload,
                    colors = QrCodeColors(
                        background = MaterialTheme.colorScheme.background,
                        foreground = MaterialTheme.colorScheme.onBackground,
                    ),
                    modifier = Modifier
                        .size(320.dp)
                        .padding(20.dp)
                )
                Box(modifier = Modifier.padding(16.dp))
            }
        }
    }


    if (passPhraseDialog)
        AlertDialog(
            modifier = Modifier
                .shake(errorShake)
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = {
                Text(
                    text = stringResource(id = R.string.enter_seed_phrase),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                OutlinedTextField(
                    value = passPhrase,
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(
                            top = 8.dp
                        ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            getSeed()
                        }
                    ),
                    onValueChange = {
                        passPhrase = it
                    },

                    )
            },
            onDismissRequest = {
                passPhraseDialog = false
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,

                        ),
                    onClick = {
                        getSeed()
                    }) { Text(stringResource(id = R.string.view)) }
            },
            dismissButton = {
                Button(
                    onClick = {
                        passPhraseDialog = false
                        onBackPress.invoke()
                    },
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                ) {
                    Text(
                        stringResource(id = R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            }
        )

    var showLegacy by remember { mutableStateOf(false) }
    var allowSeedCopy by remember { mutableStateOf(false) }
    val selectedSeed = if (showLegacy) legacySeed else seedWords
    val canCopySeed = !passPhraseDialog &&
        selectedSeed.isNotEmpty() &&
        selectedSeed.none { it.contains("*") }

    val wallet = WalletManager.instance?.wallet
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBackPress
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                        Switch(
                            checked = allowSeedCopy,
                            onCheckedChange = { allowSeedCopy = it },
                            enabled = !passPhraseDialog
                        )
                    }
                },
                title = {
                    Text("")
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (allowSeedCopy && canCopySeed) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Mnemonic",
                                        selectedSeed.joinToString(" ")
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.copied_to_clipboard),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = allowSeedCopy && canCopySeed
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制助记词"
                        )
                    }
                }
            )
        },
    ) {
        Box(
            modifier = Modifier
                .padding(it)
                .blur(backgroundBlur.dp)
                .padding(
                    horizontal = 8.dp
                )
        ) {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(id = R.string.primary_address),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    text = "${wallet?.address ?: ""}",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    )
                }
                item {
                    HorizontalDivider(
                        thickness = 1.dp
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = if (showLegacy) stringResource(id = R.string.legacy_seed) else stringResource(id = R.string.polyseed),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .animateContentSize()
                            )
                        },
                        supportingContent = {
                            FlowRow(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .clickable {
                                        showLegacy = !showLegacy
                                    },
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                val seed = if (showLegacy) legacySeed else seedWords
                                seed.forEach {
                                    Text(
                                        it, modifier = Modifier
                                            .padding(
                                                4.dp
                                            )
                                            .background(
                                                Color.Gray.copy(
                                                    alpha = 0.3f
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(
                                                vertical = 4.dp,
                                                horizontal = 8.dp
                                            )
                                    )
                                }
                            }
                        }

                    )
                }

                item {
                    HorizontalDivider(
                        thickness = 1.dp
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(id = R.string.view_key),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    text = "${wallet?.getSecretViewKey()}",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    )
                }
                item {
                    HorizontalDivider(
                        thickness = 1.dp
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(id = R.string.spend_key),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    text = "${wallet?.getSecretSpendKey()}",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    )
                }

                item {
                    HorizontalDivider(
                        thickness = 1.dp
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(id = R.string.restore_height),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    text = "${wallet?.getRestoreHeight() ?: ""}",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    )
                }
                item {
                    HorizontalDivider(
                        thickness = 1.dp,
                        modifier = Modifier.padding(
                            bottom = 24.dp
                        )
                    )
                }
                item {
                    AnonOutlineButton(
                        modifier = Modifier
                            .padding(
                                horizontal = 8.dp,
                            )
                            .fillMaxWidth(),
                        onClick = {
                            exportForNero()
                        }) {
                        Text(
                            stringResource(id = R.string.export_nero_keys), style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSecondary.copy(
                                    alpha = 0.8f
                                )
                            )
                        )
                    }
                }
                item {
                    Box(
                        Modifier.height(
                            44.dp
                        )
                    )
                }
            }
        }
    }
}


@Preview(device = "id:pixel_5")
@Composable
private fun SeedSettingsPre() {
    AnonNeroTheme {
        SeedSettingsPage()
    }
}