package io.anonero.ui.home

import io.anonero.ui.theme.AnonNeroTheme
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import io.anonero.R
import io.anonero.model.Subaddress
import io.anonero.model.node.NodeFields
import io.anonero.ui.MainActivity
import io.anonero.ui.components.MainBottomNavigation
import io.anonero.ui.home.addresses.SubAddressDetailScreen
import io.anonero.ui.home.addresses.SubAddressesScreen
import io.anonero.ui.home.graph.routes.CoinsScreenRoute
import io.anonero.ui.home.graph.routes.CoinDetailRoute
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.home.graph.routes.ReceiveRoute
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.ui.home.graph.routes.SettingsDataRoute
import io.anonero.ui.home.graph.routes.SettingsTradeRoute
import io.anonero.ui.home.graph.routes.SettingsExportBackUp
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.NodeManagementRoute
import io.anonero.ui.home.graph.routes.ResyncBlockchainRoute
import io.anonero.ui.home.graph.routes.SettingsRoute
import io.anonero.ui.home.graph.routes.SettingsWalletRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute
import io.anonero.ui.home.graph.routes.SubAddressesRoute
import io.anonero.ui.home.graph.routes.TransactionDetailRoute
import io.anonero.ui.home.graph.routes.TransactionsRoute
import io.anonero.ui.home.settings.ExportBackUp
import io.anonero.ui.home.settings.LogViewer
import io.anonero.ui.home.settings.NodeSettings
import io.anonero.ui.home.settings.NodeSettingsMenuPage
import io.anonero.ui.home.settings.ProxySettings
import io.anonero.ui.home.settings.SecureWipe
import io.anonero.ui.home.settings.SeedSettingsPage
import io.anonero.ui.home.settings.SettingsDataPage
import io.anonero.ui.home.settings.SettingsTradePage
import io.anonero.ui.home.settings.SettingsPage
import io.anonero.ui.home.settings.WalletSettingsPage
import io.anonero.ui.home.spend.ReviewTransactionScreen
import io.anonero.ui.onboard.graph.LandingScreenRoute
import io.anonero.util.isIgnoringBatteryOptimizations
import org.koin.compose.koinInject
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.anonero.services.WalletState
import io.anonero.AnonConfig

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("BatteryLife")
@Composable
fun HomeScreenComposable(
    modifier: Modifier = Modifier,
    mainNavController: NavHostController?,
    initialShortcut: LockScreenShortCut = LockScreenShortCut.HOME,
) {
    val context = LocalContext.current
    val appPreferences = remember(context) {
        context.getSharedPreferences(
            AnonConfig.APP_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
    }
    val wipeRequestedAtStart = remember(context) {
        appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false) ||
            appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
    }
    var showBottomNavigation by remember { mutableStateOf(!wipeRequestedAtStart) }
    var resyncReturnBottomNavigation by remember { mutableStateOf(true) }
    val bottomNavController = rememberNavController()
    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TransactionsRoute
    var showBatteryManagerDialog by remember { mutableStateOf(false) }
    val walletState = koinInject<WalletState>()
    val appViewModel = koinInject<io.anonero.ui.viewmodels.AppViewModel>()
    val toastState = rememberToasterState()
    val prefs = context.getSharedPreferences("anonero_prefs", android.content.Context.MODE_PRIVATE)
    val startDestination: Any = when {
        wipeRequestedAtStart -> SecureWipeRoute
        initialShortcut == LockScreenShortCut.RECEIVE -> ReceiveRoute
        initialShortcut == LockScreenShortCut.SEND -> SendScreenRoute(address = "")
        else -> TransactionsRoute
    }

    // A restored wallet can reach Home while the first background connection attempt
    // is still bootstrapping Tor. Recheck startup from Home so an initialized wallet
    // gets the same refresh kick as the manual same-node Connect action.
    LaunchedEffect(Unit) {
        val appPreferences = context.getSharedPreferences(
            AnonConfig.APP_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
        val wipeRequested = appPreferences.getBoolean(
            AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
        )
        val wipePending = appPreferences.getBoolean(
            AnonConfig.PREF_WALLET_WIPE_PENDING, false
        )
        val configuredNode = context.getSharedPreferences(
            AnonConfig.PREFS,
            android.content.Context.MODE_PRIVATE
        ).getString(NodeFields.RPC_HOST.value, "")
        if (!wipeRequested && !wipePending && !configuredNode.isNullOrBlank()) {
            appViewModel.startService()
        }
    }

    val pinLockVisible by walletState.pinLockVisibleFlow.collectAsState(
        initial = walletState.pinLockVisible
    )
    var notificationPromptAttempted by remember { mutableStateOf(false) }

    // Wait until the unlocked Home screen is actually visible before asking.
    // The initial Home composition can happen during onboarding/navigation transitions.
    LaunchedEffect(context, pinLockVisible) {
        if (notificationPromptAttempted || pinLockVisible) return@LaunchedEffect

        val preferences = context.getSharedPreferences(
            AnonConfig.APP_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
        val wipeInProgress = preferences.getBoolean(
            AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
        ) || preferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
        if (wipeInProgress || !AnonConfig.getDefaultWalletFile(context).exists()) {
            return@LaunchedEffect
        }

        // Let the first Home frame become visible before opening the system dialog.
        withFrameNanos { }
        delay(1_200L)

        val wipeStillInProgress = preferences.getBoolean(
            AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
        ) || preferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
        if (
            !wipeStillInProgress &&
            !walletState.pinLockVisible &&
            AnonConfig.getDefaultWalletFile(context).exists()
        ) {
            val activity = context as? MainActivity ?: return@LaunchedEffect
            notificationPromptAttempted = true
            activity.startNotificationService()
        }
    }

    LaunchedEffect(Unit) {
        walletState.unlockShortcut.collect { shortcut ->
            val wipeRequested = appPreferences.getBoolean(
                AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
            ) || appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
            if (wipeRequested) return@collect
            when (shortcut) {
                LockScreenShortCut.HOME -> bottomNavController.navigate(TransactionsRoute) {
                    popUpTo(TransactionsRoute) { inclusive = true }
                    launchSingleTop = true
                }
                LockScreenShortCut.RECEIVE -> bottomNavController.navigate(ReceiveRoute) {
                    popUpTo(TransactionsRoute) { inclusive = false }
                    launchSingleTop = true
                }
                LockScreenShortCut.SEND -> bottomNavController.navigate(SendScreenRoute(address = "")) {
                    popUpTo(TransactionsRoute) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        walletState.resyncErrors.collect { message ->
            toastState.show("重置失败：$message")
        }
    }

    LaunchedEffect(currentRoute) {
        if (getRoutes().find { it.getRouteAsString == currentRoute } != null) {
            showBottomNavigation = true
        }
    }

    LaunchedEffect(true) {
        // Do not put unrelated permission dialogs over an active secure-delete flow.
        if (!wipeRequestedAtStart && !context.isIgnoringBatteryOptimizations()) {
            showBatteryManagerDialog = true
        }
    }

    if (showBatteryManagerDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryManagerDialog = false },
            title = { Text(stringResource(R.string.battery_optimization_title)) },
            text = {
                Text(stringResource(R.string.battery_optimization_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent()
                        intent.action = ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = "package:${context.packageName}".toUri()
                        context.startActivity(intent)
                        showBatteryManagerDialog = false
                    }
                ) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryManagerDialog = false }) {
                    Text(
                        stringResource(R.string.cancel),
                        style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSecondary)
                    )
                }
            },
            modifier = Modifier.border(
                1.dp,
                color = MaterialTheme.colorScheme.onSecondary.copy(alpha = .2f),
                shape = MaterialTheme.shapes.medium,
            ),
            containerColor = MaterialTheme.colorScheme.secondary,
        )
    }

    SharedTransitionLayout {
        Scaffold(
            contentWindowInsets = WindowInsets(top = 0.dp, bottom = 0.dp),
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBottomNavigation,
                    enter = androidx.compose.animation.slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(durationMillis = 150)
                    ),
                    exit = androidx.compose.animation.slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(durationMillis = 150)
                    )
                ) {
                    MainBottomNavigation(
                        currentRoute = currentRoute,
                        onTabSelected = {
                            bottomNavController.navigate(it.route) {
                                popUpTo(bottomNavController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = modifier.padding(0.dp)) {
                NavHost(
                    bottomNavController,
                    startDestination = startDestination,
                    exitTransition = { fadeOut(animationSpec = tween(340)) },
                    enterTransition = { fadeIn(animationSpec = tween(340)) }
                ) {
                    composable<TransactionsRoute> {
                        TransactionScreen(
                            modifier = Modifier.padding(paddingValues),
                            onItemClick = {
                                showBottomNavigation = false
                                it.hash?.let { paymentId ->
                                    bottomNavController.navigate(TransactionDetailRoute(transactionId = paymentId))
                                }
                            },
                            navigateToShortCut = {
                                when (it) {
                                    LockScreenShortCut.HOME -> {}
                                    LockScreenShortCut.RECEIVE -> bottomNavController.navigate(ReceiveRoute)
                                    LockScreenShortCut.SEND -> bottomNavController.navigate(SendScreenRoute(""))
                                }
                            },
                            navigateTo = {
                                if (it is ResyncBlockchainRoute) resyncReturnBottomNavigation = showBottomNavigation
                                if (it is CoinsScreenRoute || it is ResyncBlockchainRoute) showBottomNavigation = false
                                bottomNavController.navigate(it)
                            },
                            toastState = toastState,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                    composable<ResyncBlockchainRoute> {
                        ResyncBlockchainScreen(
                            onBackPress = {
                                showBottomNavigation = resyncReturnBottomNavigation
                                bottomNavController.popBackStack()
                            },
                            onResyncStarted = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack(
                                    route = TransactionsRoute,
                                    inclusive = false
                                )
                            },
                            toastState = toastState
                        )
                    }
                    composable<ReceiveRoute> {
                        ReceiveScreen(
                            modifier = Modifier.padding(paddingValues),
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateToSubAddresses = {
                                showBottomNavigation = false
                                bottomNavController.navigate(SubAddressesRoute)
                            }
                        )
                    }
                    composable<TransactionDetailRoute> { navBackStackEntry ->
                        val txDetailRoute = navBackStackEntry.toRoute<TransactionDetailRoute>()
                        TransactionDetailScreen(
                            transactionId = txDetailRoute.transactionId,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable,
                            onBackPress = { bottomNavController.popBackStack() },
                            modifier = Modifier.padding(paddingValues),
                        )
                    }
                    composable<SendScreenRoute> { backStackEntry ->
                        val params = backStackEntry.toRoute<SendScreenRoute>()
                        SendScreen(
                            navigateToHome = {
                                bottomNavController.popBackStack(route = TransactionsRoute, inclusive = false)
                            },
                            navigateToReview = { bottomNavController.navigate(it) },
                            modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
                            onBackPress = {
                                // Returning from a spend flow opened by the output list should
                                // restore that list directly, without showing the main bottom bar.
                                val previousRoute = bottomNavController.previousBackStackEntry
                                    ?.destination?.route
                                showBottomNavigation =
                                    previousRoute != CoinsScreenRoute::class.qualifiedName
                                bottomNavController.popBackStack()
                            },
                            paymentUri = params,
                        )
                    }
                    composable<ReviewTransactionRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<ReviewTransactionRoute>()
                        ReviewTransactionScreen(
                            route,
                            modifier = Modifier.padding(paddingValues),
                            navigateToHome = {
                                bottomNavController.popBackStack(route = TransactionsRoute, inclusive = false)
                            },
                            onFinished = {
                                bottomNavController.popBackStack(route = TransactionsRoute, inclusive = false)
                            },
                            onBackPressed = { bottomNavController.popBackStack() },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsPage(
                            modifier = Modifier.padding(paddingValues),
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateTo = {
                                showBottomNavigation = false
                                bottomNavController.navigate(it)
                            }
                        )
                    }
                    composable<SettingsWalletRoute> {
                        WalletSettingsPage(
                            onBackPress = { bottomNavController.popBackStack() },
                            onViewSeed = { bottomNavController.navigate(SettingsViewSeedRoute) }
                        )
                    }
                    composable<SettingsViewSeedRoute> {
                        SeedSettingsPage(onBackPress = { bottomNavController.popBackStack() })
                    }
                    composable<SettingsTradeRoute> {
                        SettingsTradePage(
                            onBackPress = { bottomNavController.popBackStack() },
                            navigateTo = { bottomNavController.navigate(it) }
                        )
                    }
                    composable<SettingsDataRoute> {
                        SettingsDataPage(
                            onBackPress = { bottomNavController.popBackStack() },
                            navigateTo = { bottomNavController.navigate(it) }
                        )
                    }
                    composable<SettingsExportBackUp> {
                        ExportBackUp(onBackPress = { bottomNavController.popBackStack() })
                    }
                    composable<SettingsLogs> {
                        LogViewer(onBackPress = { bottomNavController.popBackStack() })
                    }
                    composable<CoinsScreenRoute> {
                        CoinsScreen(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateToSpend = {
                                showBottomNavigation = true
                                bottomNavController.navigate(it)
                            },
                            onCoinClick = { coin ->
                                showBottomNavigation = false
                                bottomNavController.navigate(CoinDetailRoute(coin.key))
                            }
                        )
                    }
                    composable<CoinDetailRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<CoinDetailRoute>()
                        CoinDetailScreen(
                            coinKey = route.coinKey,
                            onBackPress = { bottomNavController.popBackStack() }
                        )
                    }
                    composable<ProxySettingsRoute> {
                        ProxySettings(onBackPress = { bottomNavController.popBackStack() })
                    }
                    composable<SecureWipeRoute> {
                        SecureWipe(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            requestClearScreen = { showBottomNavigation = it },
                            goToHome = {
                                mainNavController?.navigate(LandingScreenRoute) {
                                    popUpTo(LandingScreenRoute) { inclusive = false }
                                }
                            }
                        )
                    }
                    composable<SettingsNodeRoute> {
                        NodeSettingsMenuPage(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateTo = {
                                showBottomNavigation = false
                                bottomNavController.navigate(it)
                            }
                        )
                    }
                    composable<NodeManagementRoute> {
                        NodeSettings(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateTo = {
                                if (it is ResyncBlockchainRoute) resyncReturnBottomNavigation = showBottomNavigation
                                showBottomNavigation = false
                                bottomNavController.navigate(it)
                            }
                        )
                    }
                    composable<SubAddressesRoute> {
                        SubAddressesScreen(
                            onBackPress = {
                                showBottomNavigation = true
                                bottomNavController.popBackStack()
                            },
                            navigateToDetails = { bottomNavController.navigate(it) },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                    composable<Subaddress> { backStackEntry ->
                        val subAddress = backStackEntry.toRoute<Subaddress>()
                        SubAddressDetailScreen(
                            subAddress = subAddress,
                            onBackPress = { bottomNavController.popBackStack() },
                            onTransactionClick = {
                                it.hash?.let { paymentId ->
                                    bottomNavController.navigate(TransactionDetailRoute(transactionId = paymentId))
                                }
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedContentScope = this@composable
                        )
                    }
                }
                Toaster(
                    state = toastState,
                    maxVisibleToasts = 1,
                    alignment = Alignment.BottomCenter,
                    darkTheme = true,
                    richColors = true,
                    containerPadding = PaddingValues(bottom = 84.dp),
                )
            }
        }
    }
}

@Preview(device = "id:pixel_8")
@Composable
private fun HomeScreenComposablePrev() {
    AnonNeroTheme {
        HomeScreenComposable(mainNavController = null)
    }
}
