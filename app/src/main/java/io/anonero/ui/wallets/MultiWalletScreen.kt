package io.anonero.ui.wallets

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.ui.home.xqzpFontFamily
import io.anonero.ui.theme.AnonOutlineButton
import io.anonero.util.WALLET_PREFERENCES
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onDeleteWallet: suspend (File) -> Boolean,
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
    var editFieldHadFocus by remember { mutableStateOf(false) }
    val editFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val screenScope = rememberCoroutineScope()
    val xmrFontFamily = xqzpFontFamily()
    val xmrVisualTransformation = remember(xmrFontFamily) {
        VisualTransformation { original ->
            TransformedText(
                text = original.withXmrFont(xmrFontFamily),
                offsetMapping = OffsetMapping.Identity,
            )
        }
    }
    val deleteActionWidth = 80.dp
    val deleteActionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        deleteActionWidth.toPx()
    }
    val focusManager = LocalFocusManager.current
    var screenCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var editingFieldBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    fun saveWalletName(walletFile: File) {
        val customName = customWalletDisplayNameForSave(walletFile.name, editedName)
        walletPrefs.edit {
            if (customName == null) {
                remove(walletDisplayNamePreferenceKey(walletFile))
            } else {
                putString(walletDisplayNamePreferenceKey(walletFile), customName)
            }
        }
    }

    fun finishEditing() {
        val walletFile = walletToRename ?: return
        editFieldHadFocus = false
        saveWalletName(walletFile)
        walletToRename = null
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        editingFieldBounds = null
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

    LaunchedEffect(walletToRename?.name) {
        if (walletToRename != null) {
            editFieldHadFocus = false
            editFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenCoordinates = it }
            .pointerInput(walletToRename?.name, editingFieldBounds) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull {
                            it.pressed && !it.previousPressed
                        } ?: continue
                        val fieldBounds = editingFieldBounds ?: continue
                        val screenOrigin = screenCoordinates?.positionInRoot() ?: Offset.Zero
                        if (!fieldBounds.contains(down.position + screenOrigin)) {
                            finishEditing()
                        }
                    }
                }
            }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp)
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            IconButton(
                                onClick = onProxySettings,
                                modifier = if (wallets.isNotEmpty()) Modifier.offset(y = 6.dp).size(32.dp) else Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Proxy",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                    if (wallets.isNotEmpty()) {
                        LazyColumn(
                            reverseLayout = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Show at most five rows; additional wallets scroll here.
                                .height((5 * 68).dp),
                        ) {
                            items(wallets, key = { it.name }) { walletFile ->
                                val isEditing = walletToRename?.name == walletFile.name
                                var swipeOffsetPx by remember(walletFile.name) { mutableStateOf(0f) }
                                val rowOffsetPx = swipeOffsetPx.coerceIn(-deleteActionWidthPx, 0f)
                                val customDisplayName = walletPrefs.getString(
                                    walletDisplayNamePreferenceKey(walletFile),
                                    null
                                )
                                val defaultDisplayName = walletDisplayName(
                                    walletName = walletFile.name,
                                    customName = customDisplayName
                                )
                                val usesDefaultXmrLabel =
                                    customDisplayName.isNullOrBlank() &&
                                        defaultDisplayName.startsWith("XMR ")

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 68.dp),
                                ) {
                                    if (swipeOffsetPx < 0f) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .width(deleteActionWidth)
                                                .heightIn(min = 68.dp)
                                                .clickable(
                                                    interactionSource = remember(walletFile.name) {
                                                        androidx.compose.foundation.interaction.MutableInteractionSource()
                                                    },
                                                    indication = null,
                                                ) {
                                                    screenScope.launch {
                                                        onDeleteWallet(walletFile)
                                                        wallets = AnonConfig.getWalletFiles(context)
                                                        swipeOffsetPx = 0f
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "删除",
                                                color = io.anonero.ui.theme.Orange,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 68.dp)
                                            .offset { IntOffset(rowOffsetPx.roundToInt(), 0) }
                                            .then(
                                                if (isEditing) Modifier else Modifier.pointerInput(walletFile.name) {
                                                    detectHorizontalDragGestures(
                                                        onHorizontalDrag = { change, dragAmount ->
                                                            change.consume()
                                                            swipeOffsetPx = (swipeOffsetPx + dragAmount)
                                                                .coerceIn(-deleteActionWidthPx, 0f)
                                                        },
                                                        onDragEnd = {
                                                            swipeOffsetPx = if (swipeOffsetPx <= -deleteActionWidthPx / 2f) {
                                                                -deleteActionWidthPx
                                                            } else {
                                                                0f
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            swipeOffsetPx = if (swipeOffsetPx <= -deleteActionWidthPx / 2f) {
                                                                -deleteActionWidthPx
                                                            } else {
                                                                0f
                                                            }
                                                        },
                                                    )
                                                }
                                            )
                                            .then(
                                                if (isEditing) Modifier else Modifier.combinedClickable(
                                                    onClick = { onWalletSelected(walletFile) },
                                                    onLongClick = {
                                                        swipeOffsetPx = 0f
                                                        editedName = defaultDisplayName
                                                        editFieldHadFocus = false
                                                        walletToRename = walletFile
                                                    }
                                                )
                                            )
                                            .padding(horizontal = 16.dp, vertical = 18.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                    if (isEditing) {
                                        BasicTextField(
                                            value = editedName,
                                            onValueChange = { editedName = it },
                                            singleLine = true,
                                            visualTransformation = xmrVisualTransformation,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            keyboardOptions = KeyboardOptions(
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = { finishEditing() }
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .weight(1f)
                                                .focusRequester(editFocusRequester)
                                                .onGloballyPositioned { editingFieldBounds = it.boundsInRoot() }
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        editFieldHadFocus = true
                                                    } else if (editFieldHadFocus) {
                                                        finishEditing()
                                                    }
                                                },
                                        )
                                    } else if (usesDefaultXmrLabel) {
                                        Text(
                                            text = AnnotatedString(defaultDisplayName).withXmrFont(
                                                fontFamily = xmrFontFamily,
                                                xmrFontSize = 22.sp,
                                            ),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            maxLines = 1,
                                        )
                                    } else {
                                        Text(
                                            text = AnnotatedString(defaultDisplayName).withXmrFont(xmrFontFamily),
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                    }
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 1.dp,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.height((5 * 68).dp))
                    }
                }
            }

            // Match the onboarding "Next" button placement: the button is
            // the final direct child of the page column, at the bottom inset.
            AnonOutlineButton(
                onClick = {
                    onNewWallet()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp
                    ),
            ) {
                Text("新建钱包")
            }
        }
    }
}

private fun walletDisplayNamePreferenceKey(walletFile: File): String =
    "${walletFile.name}::$WALLET_DISPLAY_NAME_KEY"


/**
 * Returns null when the edited text should continue using the wallet's default name.
 * This avoids converting an unchanged default such as "XMR 1" into a custom name
 * when the rename field loses focus without an edit.
 */
internal fun customWalletDisplayNameForSave(walletName: String, editedName: String): String? {
    val normalized = editedName.trim()
    if (normalized.isEmpty()) return null
    return normalized.takeUnless { it == walletDisplayName(walletName, null) }
}

/** Finds every visible XMR ticker occurrence, regardless of case. */
internal fun xmrRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var searchFrom = 0
    while (searchFrom <= text.length - 3) {
        val start = text.indexOf("XMR", startIndex = searchFrom, ignoreCase = true)
        if (start < 0) break
        ranges += start until (start + 3)
        searchFrom = start + 3
    }
    return ranges
}

/** Applies the same embedded typeface used by the Home-screen XMR ticker. */
private fun AnnotatedString.withXmrFont(
    fontFamily: FontFamily,
    xmrFontSize: TextUnit = TextUnit.Unspecified,
): AnnotatedString {
    val source = this
    val ranges = xmrRanges(source.text)
    if (ranges.isEmpty()) return source

    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            append(source.subSequence(cursor, range.first))
            withStyle(SpanStyle(fontFamily = fontFamily, fontSize = xmrFontSize)) {
                append(source.subSequence(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        append(source.subSequence(cursor, source.length))
    }
}

/**
 * Use a non-blank custom display name when present; otherwise use the
 * wallet's stable default name.
 */
internal fun walletDisplayName(walletName: String, customName: String?): String {
    customName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    if (walletName == "anon") return "XMR 1"
    val walletNumber = walletName.removePrefix("wallet_").toIntOrNull()
    return if (walletNumber != null) {
        "XMR $walletNumber"
    } else {
        walletName
    }
}
