package io.anonero.ui.home.graph

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.ui.home.HomeScreenComposable
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.graph.routes.LockScreenRoute


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
            val walletIsOpen = WalletManager.instance?.wallet?.status?.isOk == true
            val unlockCompleted = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            val selectedShortcut = androidx.compose.runtime.remember(homeRoute.lockScreenShortCut) {
                androidx.compose.runtime.mutableStateOf(homeRoute.lockScreenShortCut)
            }

            // Navigation state can restore HomeScreenRoute after process death, bypassing
            // the graph start destination. Do not render Home until the wallet is open
            // again through the PIN flow (except while secure wipe is intentionally active).
            if (secureDeleteInProgress || walletIsOpen || unlockCompleted.value) {
                HomeScreenComposable(
                    mainNavController = navController,
                    initialShortcut = selectedShortcut.value,
                )
            } else {
                LockScreen(
                    mode = LockScreenMode.OPEN_WALLET,
                    onUnLocked = { _, shortCut ->
                        selectedShortcut.value = shortCut
                        unlockCompleted.value = true
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
                        navController.navigate(HomeScreenRoute(lockScreenShortCut = shortCut)) {
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
