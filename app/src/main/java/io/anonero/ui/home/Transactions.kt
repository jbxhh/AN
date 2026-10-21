package io.anonero.ui.home
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.model.TransactionInfo
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.CoinsScreenRoute
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.ui.home.graph.routes.NodeManagementRoute
import io.anonero.ui.home.graph.routes.ResyncBlockchainRoute
import io.anonero.ui.home.graph.routes.TransactionsRoute
import io.anonero.ui.home.spend.qr.ExportType
import io.anonero.ui.home.spend.qr.ImportEvents
import io.anonero.ui.home.spend.qr.QRExchangeScreen
import io.anonero.ui.home.spend.qr.SpendQRExchangeParam
import io.anonero.ui.home.spend.qr.URQRScanner
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import io.anonero.services.TorService
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.WALLET_USE_TOR
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.res.painterResource


class TransactionsViewModel : ViewModel() {
    private val walletState: WalletState by inject(WalletState::class.java)

    val balance = walletState.balanceInfo.asLiveData()

    val transactions = walletState.transactions
        .asLiveData()

}

private const val TAG = "Transactions"

@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun TransactionScreen(
    modifier: Modifier = Modifier,
    onItemClick: (TransactionInfo) -> Unit = {},
    navigateTo: (route: Any) -> Unit = {},
    navigateToShortCut: (shortcut: LockScreenShortCut) -> Unit = {},
    toastState: ToasterState,
    animatedContentScope: AnimatedContentScope,
    sharedTransitionScope: SharedTransitionScope,
) {

    val transactionsViewModel = viewModel<TransactionsViewModel>()
    val balance by transactionsViewModel.balance.observeAsState()
    val transactions by transactionsViewModel.transactions.observeAsState(listOf())
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior { true }
    var showExitDialog by remember { mutableStateOf(false) }
    var scanFailure by remember { mutableStateOf<String?>(null) }
    var spendDialog by remember { mutableStateOf<String?>(null) }
    var broadcastSignedTxPath by remember { mutableStateOf<String?>(null) }
    var broadcastProgree by remember { mutableStateOf(false) }
    var settingBSync by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var qrScannerParam by remember { mutableStateOf<SpendQRExchangeParam?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val walletState = koinInject<WalletState>()
    val torService = koinInject<TorService>()
    val anonPrefs = koinInject<android.content.SharedPreferences>(named(WALLET_PREFERENCES))
    val showLockScreen by walletState.backgroundSyncFlow.asLiveData().observeAsState(walletState.backgroundSync)
    val hideAmounts by walletState.hideAmountsFlow.asLiveData().observeAsState(false)
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current;

    if (broadcastSignedTxPath != null) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text("Do you want to broadcast transaction?")
            },
            onDismissRequest = {
                scanFailure = null
            },
            dismissButton = {
                Button(
                    onClick = {
                        broadcastSignedTxPath = null
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
                        "Cancel",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
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
                        broadcastProgree = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                WalletManager.instance?.wallet?.submitTransaction(
                                    broadcastSignedTxPath ?: ""
                                )
                                WalletManager.instance?.wallet?.refreshHistory()
                                WalletManager.instance?.wallet?.store()
                            } catch (ex: Exception) {
                                Timber.tag(TAG).e(ex)
                            } finally {
                                broadcastProgree = false
                                broadcastSignedTxPath = null
                            }
                        }
                    }) { Text("Yes") }
            },
        )
    }
    if (showExitDialog) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                )
                .fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text(stringResource(R.string.exit_wallet_prompt, stringResource(R.string.app_name)))
            },
            onDismissRequest = {
                showExitDialog = false
            },
            dismissButton = {
                Button(
                    onClick = {
                        showExitDialog = false
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
                        "Cancel",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
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
                        activity?.finishAffinity()
                    }) { Text(stringResource(R.string.exit_wallet)) }
            },
        )
    }

    if (qrScannerParam != null) {
        QRExchangeScreen(
            params = qrScannerParam!!,
            onNavigateToHome = {
                navigateTo.invoke(TransactionsRoute)
            },
            onBackPressed = {
                qrScannerParam = null
            },
            onCtaCalled = {
                qrScannerParam = null
                showScanner = true
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showScanner)
        URQRScanner(
            onQRCodeScanned = {
                if (it.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        SendScreenRoute.parse(it)?.let { paymentUri ->
                            withContext(Dispatchers.Main) {
                                navigateTo(paymentUri)
                            }
                        }
                    }
                }
                showScanner = false
            },
            onDismiss = {
                showScanner = false
            },
            onURResult = {
                when (it.getOrNull()) {
                    ImportEvents.IMPORT_OUTPUTS -> {
                        showScanner = false
                        qrScannerParam = SpendQRExchangeParam(
                            exportType = ExportType.IMAGE,
                            title = "KEY IMAGES",
                            ctaText = "SCAN UNSIGNED TX",
                        )
                    }

                    null -> {

                    }

                    ImportEvents.IMPORT_KEY_IMAGES -> {
                        toastState.show(
                            "Key images imported",
                            type = ToastType.Success,
                            duration = 4.seconds
                        )
                    }

                    ImportEvents.IMPORT_UNSIGNED_TX -> {
                        if (!AnonConfig.viewOnly) {
                            navigateTo.invoke(
                                ReviewTransactionRoute(
                                    "",
                                )
                            );
                        }
                    }

                    ImportEvents.IMPORT_SIGNED_TX -> {}
                }
                showScanner = false
            },
            showScanner = showScanner
        )

    if (spendDialog != null) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text("${spendDialog}")
            },
            onDismissRequest = {
                spendDialog = null
            },
            dismissButton = {
                Button(
                    onClick = {
                        spendDialog = null
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
                        "Ok",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            },
            confirmButton = {

            },
        )
    }
    val showIndefiniteLoading by walletState.isLoading.asLiveData().observeAsState(false)
    val syncProgress by walletState.syncProgress.asLiveData().observeAsState(null)
    val walletStatus by walletState.walletStatus.asLiveData().observeAsState(null)
    val walletConnectionStatus by walletState.walletConnectionStatus.asLiveData().observeAsState(null)
    var syncBacklogSeen by remember { mutableStateOf(false) }
    var initialHistoryRefreshDone by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    val blockSyncProgressVisible = syncProgress?.left?.let { it > 0L } == true
    var manualPullRefreshInProgress by remember { mutableStateOf(false) }
    val view = LocalView.current
    val torConnected by torService.socksFlow.asLiveData().observeAsState(torService.socks != null)
    val useTor = anonPrefs.getBoolean(WALLET_USE_TOR, true)
    val hazeState = rememberHazeState()


    BackHandler {
        showExitDialog = true
    }

    LaunchedEffect(walletState) {
        withContext(Dispatchers.IO) {
            walletState.publishWalletSnapshot()
            // Re-publish balance, history, and status when Home is re-entered even if
            // the wallet service is already running and skips its initialization path.
            walletState.update()
        }
    }

    // Starting the wallet service and attaching this screen are asynchronous. Once the
    // native wallet is connected, explicitly check the live daemon height so a restored
    // wallet enters block-sync progress without requiring a pull-to-refresh gesture.
    LaunchedEffect(walletConnectionStatus) {
        if (walletConnectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            walletState.checkSyncProgressOnStartup()
        }
    }

    // Publish cached/native transaction history when this screen attaches to an open wallet.
    LaunchedEffect(walletStatus != null) {
        if (walletStatus != null && !initialHistoryRefreshDone) {
            initialHistoryRefreshDone = true
            walletState.refreshTransactionHistory()
        }
    }

    // The final native scan callback can precede the stable transaction-history snapshot.
    // Retry after sync has completed without changing any screen layout or interaction.
    LaunchedEffect(syncProgress?.left, showIndefiniteLoading) {
        val left = syncProgress?.left
        if (left != null && left > 0L) {
            syncBacklogSeen = true
        } else if (syncBacklogSeen && syncProgress == null && !showIndefiniteLoading) {
            syncBacklogSeen = false
            for (attempt in 0 until 5) {
                delay(if (attempt == 0) 600L else 1_200L)
                val transactionCount = walletState.refreshTransactionHistory()
                val wallet = WalletManager.instance?.wallet
                val walletHasBalance = wallet?.let { currentWallet ->
                    runCatching {
                        currentWallet.balance > 0L || currentWallet.unlockedBalance > 0L
                    }.getOrDefault(false)
                } ?: false
                Timber.tag(TAG).i(
                    "Post-sync transaction refresh attempt=%d count=%d hasBalance=%s",
                    attempt + 1,
                    transactionCount,
                    walletHasBalance
                )
                if (transactionCount > 0 || !walletHasBalance) break
            }
        }
    }

    LaunchedEffect(true) {
        refreshState.snapTo(0f)
    }

    // Block-sync progress takes priority over the manual pull indicator.
    // Clear any partial pull distance when a real scan begins to avoid stacked bars.
    LaunchedEffect(blockSyncProgressVisible) {
        if (blockSyncProgressVisible) refreshState.snapTo(0f)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors().copy(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .hazeEffect(
                        state = hazeState,
                        style = HazeDefaults.style(
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(
                                alpha = 0.95f
                            ),
                            blurRadius = 26.dp
                        ),
                    )
                    .fillMaxWidth(),
                title = {
                    Text(if (AnonConfig.viewOnly) "[ИΞR0]" else "[ΛИ0И]")
                },
                actions = {
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White
                        ),
                        onClick = {
                            showScanner = true
                        }
                    ) {
                        Icon(AnonIcons.Scan, contentDescription = "Scan")
                    }
                    IconButton(
                        onClick = {
                            navigateTo(NodeManagementRoute)
                        }
                    ) {
                        val torIconColor = if (useTor && torConnected == false) Color.Red else Color.White
                        Icon(
                            painterResource(R.drawable.ic_tor),
                            contentDescription = "Tor Status",
                            tint = torIconColor
                        )
                    }
                    val context = LocalContext.current
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White
                        ),
                        onClick = {
                            showMenu = !showMenu
                        }
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                        DropdownMenu(
                            expanded = showMenu,
                            containerColor = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    color = MaterialTheme.colorScheme.onSecondary.copy(
                                        alpha = .2f
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                ),
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    val coinControlLabel = stringResource(R.string.coin_control)
                                    val xmrIndex = coinControlLabel.indexOf("XMR")
                                    if (xmrIndex >= 0) {
                                        Text(
                                            buildAnnotatedString {
                                                append(coinControlLabel.substring(0, xmrIndex))
                                                withStyle(SpanStyle(fontFamily = xqzpFontFamily(), fontSize = 18.sp)) {
                                                    append("XMR")
                                                }
                                                append(coinControlLabel.substring(xmrIndex + 3))
                                            }
                                        )
                                    } else {
                                        Text(coinControlLabel)
                                    }
                                },
                                onClick = {
                                    // Dismiss the overflow menu immediately before navigation;
                                    // otherwise it remains visible over the destination during transition.
                                    showMenu = false
                                    navigateTo(CoinsScreenRoute)
                                }
                            )
                               DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh)) },
                                                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

                                            walletState.refresh()

                                        }
                                    }
                            )
                        
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.resync_blockchain)) },
                                onClick = {
                                    showMenu = false
                                    navigateTo(ResyncBlockchainRoute)
                                }
                            )
                            if (AnonConfig.viewOnly) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_outputs)) },
                                    onClick = {
                                        qrScannerParam =
                                            SpendQRExchangeParam(
                                                exportType = ExportType.OUTPUT,
                                                title = context.getString(R.string.outputs),
                                                ctaText = context.getString(R.string.scan_key_images),
                                            )
                                    }
                                )
                            }
                            if (!AnonConfig.viewOnly) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_key_images)) },
                                    onClick = {
                                        qrScannerParam =
                                            SpendQRExchangeParam(
                                                exportType = ExportType.IMAGE,
                                                title = context.getString(R.string.key_images),
                                                ctaText = "",
                                            )
                                    }
                                )
                            }
                        }
                    }
                }
            )

        }
    ) { contentPadding ->
        PullToRefreshBox(
          isRefreshing = false,
            state = refreshState,
            modifier = Modifier
                .hazeSource(
                    state = hazeState,
                ),
            onRefresh = {
                scope.launch {
                    manualPullRefreshInProgress = true
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        walletState.refresh()
                    } finally {
                        refreshState.animateToHidden()
                        manualPullRefreshInProgress = false
                    }
                }
            },
            // Match upstream: render pull progress in the list's existing progress area,
            // not as a separately offset overlay that follows a different animation.
            indicator = {}
        ) {
               LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                stickyHeader(key = "progress") {
                    Column {
                        WalletProgressIndicator(
                            refreshIndicatorProgress = if (
                                blockSyncProgressVisible
                            ) 0f else refreshState.distanceFraction
                        )
                        AnimatedVisibility(
                            visible = !manualPullRefreshInProgress &&
                                !blockSyncProgressVisible &&
                                refreshState.distanceFraction > 0.2f &&
                                !showIndefiniteLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "拉动开始刷新",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                            )
                        }
                    }
                }
                item(key = "balance") {
                    Box(
                        modifier = Modifier
                                        .padding(
                                      top = 32.dp,
                                   bottom = 16.dp
                                )
                            .combinedClickable(
                                onClick = { walletState.toggleHideAmounts() },
                                onLongClick = {
                                    navigateTo(CoinsScreenRoute)
                                }
                            )
                          .fillParentMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillParentMaxWidth()
                        ) {
                            XmrText(
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 50.sp),
                                modifier = Modifier
                                    .padding(start = 8.dp, bottom = 2.dp)
                                    .offset(y = (-12).dp)
                            )
                            Text(
                                balance?.let { amount ->
                                    if (hideAmounts) Formats.maskAmount(amount)
                                    else Formats.getDisplayAmountAdaptive(amount)
                                } ?: "—",
                                style = MaterialTheme.typography
                                    .displaySmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 23.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
                val visibleTransactions = transactions
                    item(key = "balance_space") {
                    Spacer(Modifier.height(50.dp))
                }
                items(visibleTransactions.size, key = { visibleTransactions[it].getListKey() }) {
                    with(sharedTransitionScope) {
                        TransactionItem(
                            visibleTransactions[it], hideAmounts = hideAmounts, modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable {
                                    onItemClick(visibleTransactions[it])
                                }
                                .sharedElement(
                                    sharedTransitionScope.rememberSharedContentState(
                                        key = "${visibleTransactions[it].hash}",
                                    ),
                                    animatedVisibilityScope = animatedContentScope
                                )
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun TransactionDirectionIndicator(
    tx: TransactionInfo,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val isIncoming = tx.direction == TransactionInfo.Direction.Direction_In
    val tint = if (isIncoming) MaterialTheme.colorScheme.primary else LocalContentColor.current
    val directionIcon = if (isIncoming) AnonIcons.ArrowDownLeft else AnonIcons.ArrowUpRight
    val confirmations = tx.confirmations
    val confirmationThreshold = TransactionInfo.CONFIRMATION.toLong()

    // Match the upstream transaction list: the confirmation ring (with its count)
    // replaces the direction arrow until 10 confirmations; then the arrow returns.
    Box(
        modifier = Modifier.size(iconSize).then(modifier),
        contentAlignment = Alignment.Center
    ) {
        if (confirmations < confirmationThreshold) {
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = tint,
                    strokeWidth = 1.5.dp,
                    progress = {
                        confirmations.coerceIn(0L, confirmationThreshold).toFloat() /
                            confirmationThreshold.toFloat()
                    }
                )
                Text(
                    text = confirmations.coerceAtLeast(0L).toString(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                    color = tint
                )
            }
        } else {
            Icon(
                imageVector = directionIcon,
                modifier = Modifier.size(iconSize),
                tint = tint,
                contentDescription = null
            )
        }
    }
}

@Composable
fun TransactionItem(tx: TransactionInfo, hideAmounts: Boolean = false, amountCenter: Boolean = false, modifier: Modifier = Modifier, showTime: Boolean = false, timePattern: String = "HH:mm\ndd/MM") {
    val isIncoming = tx.direction == TransactionInfo.Direction.Direction_In
    val amount = tx.amount
    val amountText = if (hideAmounts) Formats.maskAmount(amount)
    else Formats.getDisplayAmount(amount)

    if (!amountCenter && !showTime) {
        // Match q's home-list placement: pending confirmation ring at the left
        // edge, amount held at the right; after 10 confirmations, replace the ring
        // with the direction arrow beside the amount.
        val confirming = tx.confirmations < TransactionInfo.CONFIRMATION.toLong()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 28.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            if (confirming) {
                TransactionDirectionIndicator(
                    tx = tx,
                    iconSize = 28.dp,
                    modifier = Modifier.offset(x = (-8).dp, y = 2.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!confirming) {
                TransactionDirectionIndicator(
                    tx = tx,
                    iconSize = 25.dp,
                    modifier = Modifier.offset(x = 6.dp, y = 2.dp)
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                amountText,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleLarge
            )
        }
    } else if (amountCenter && showTime) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 28.dp, top = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TransactionDirectionIndicator(tx = tx, iconSize = 28.dp)
                Text(
                    Formats.formatTransactionTime(tx.timestamp, timePattern),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End
                )
            }
            Text(
                amountText,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 28.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransactionDirectionIndicator(
                    tx = tx,
                    iconSize = if (amountCenter) 28.dp else 18.dp
                )
                Text(
                    amountText,
                    textAlign = if (amountCenter) TextAlign.Center else TextAlign.End,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .then(if (amountCenter) Modifier.weight(1f) else Modifier),
                    style = MaterialTheme.typography.titleLarge
                )
                if (showTime) {
                    Text(
                        Formats.formatTransactionTime(tx.timestamp, timePattern),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun LockButton(onLock: () -> Unit, loading: Boolean = false) {
    val walletState = koinInject<WalletState>()
    val walletLoading by walletState.isLoading.asLiveData().observeAsState(false)
    IconButton(
        modifier = Modifier.alpha(if (walletLoading) 0.2f else 1.0f),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = Color.White
        ),
        onClick = {
            if (walletLoading || loading) {
                return@IconButton
            }
            onLock()
        }
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun TransactionScreenReview() {
    AnonNeroTheme {
    }


}