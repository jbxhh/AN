package io.anonero.ui.home.graph

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.services.AnonWalletHandler
import io.anonero.services.WalletState
import io.anonero.ui.home.HomeScreenComposable
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.graph.routes.LockScreenRoute
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber


fun NavGraphBuilder.homeGraph(navController: NavHostController) {
    val walletStatus = WalletManager.instance?.wallet?.status?.isOk
    // After process death there is no live WalletManager handle, so walletStatus
    // is false even if a secure wipe was already accepted. The durable wipe marker
    // must override the ordinary PIN route and return directly to the wipe workflow.
    val appPreferences = AnonConfig.context?.getSharedPreferences(
        AnonConfig.APP_PREFERENCES,
        android.content.Context.MODE_PRIVATE
    )
    val secureDeleteInProgress = appPreferences?.getBoolean(
        AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
    ) == true || appPreferences?.getBoolean(
        AnonConfig.PREF_WALLET_WIPE_PENDING, false
    ) == true

    navigation<Home>(
        startDestination = if (secureDeleteInProgress) {
            HomeScreenRoute()
        } else {
            LockScreenRoute
        }
    ) {
        composable<HomeScreenRoute> { backStackEntry ->
            val homeRoute = backStackEntry.toRoute<HomeScreenRoute>()
            val walletHandler = koinInject<AnonWalletHandler>()
            val walletState = koinInject<WalletState>()
            val immediateWalletCreationPending =
                walletHandler.isImmediateWalletCreationHomePending()
            val immediateWalletSelectionPending =
                walletHandler.isImmediateWalletSelectionPending()
            // Avoid querying the displaced native wallet while the immediate Home
            // transition is pending; the pending flag itself authorizes this route.
            val walletIsOpen = immediateWalletCreationPending ||
                WalletManager.instance?.wallet?.status?.isOk == true
            val unlockCompleted = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            val selectedShortcut = androidx.compose.runtime.remember(homeRoute.lockScreenShortCut) {
                androidx.compose.runtime.mutableStateOf(homeRoute.lockScreenShortCut)
            }

            // Navigation state can restore HomeScreenRoute after process death, bypassing
            // the graph start destination. Do not render Home until the wallet is open
            // again through the PIN flow (except while secure wipe is intentionally active).
            if (
                secureDeleteInProgress ||
                (homeRoute.pinVerified && (walletIsOpen || immediateWalletCreationPending)) ||
                unlockCompleted.value
            ) {
                HomeScreenComposable(
                    mainNavController = navController,
                    initialShortcut = selectedShortcut.value,
                )
            } else {
                if (immediateWalletSelectionPending) {
                    DisposableEffect(walletHandler) {
                        onDispose { walletHandler.endImmediateWalletSelection() }
                    }
                }
                LockScreen(
                    mode = if (walletIsOpen) LockScreenMode.LOCK_SCREEN else LockScreenMode.OPEN_WALLET,
                    onUnLocked = { _, shortCut ->
                        selectedShortcut.value = shortCut
                        unlockCompleted.value = true
                        // Finish native replacement and publish the selected wallet's
                        // local data off the navigation path. A wallet-list switch skips
                        // this work during open so Home is not held behind snapshot reads.
                        walletHandler.scope.launch(Dispatchers.IO) {
                            walletHandler.finishPendingWalletReplacement()
                            if (immediateWalletSelectionPending) {
                                val activeWallet = WalletManager.instance?.wallet
                                if (activeWallet != null) {
                                    runCatching {
                                        walletState.publishWalletSnapshot(activeWallet)
                                    }.onFailure {
                                        Timber.tag("HomeGraph").w(it, "Unable to publish selected wallet snapshot")
                                    }
                                    runCatching {
                                        walletState.ensureNextReceiveAddress()
                                    }.onFailure {
                                        Timber.tag("HomeGraph").w(it, "Unable to prepare selected wallet receive address")
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
        composable<LockScreenRoute> {
            // A restored navigation back stack may still point to LockScreenRoute.
            // Never display PIN over a persisted secure-delete request, even then.
            if (secureDeleteInProgress) {
                LaunchedEffect(Unit) {
                    navController.navigate(HomeScreenRoute()) {
                        popUpTo(LockScreenRoute) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            } else {
                LockScreen(
                    mode = if (walletStatus == true) {
                        LockScreenMode.LOCK_SCREEN
                    } else {
                        LockScreenMode.OPEN_WALLET
                    },
                    onUnLocked = { _, shortCut ->
                        navController.navigate(HomeScreenRoute(lockScreenShortCut = shortCut, pinVerified = true)) {
                            popUpTo(LockScreenRoute) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
