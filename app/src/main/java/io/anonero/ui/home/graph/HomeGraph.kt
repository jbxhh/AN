package io.anonero.ui.home.graph

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.ui.home.HomeScreenComposable
import io.anonero.ui.home.LockScreen
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
        startDestination = if (secureDeleteInProgress || walletStatus == true) {
            HomeScreenRoute()
        } else {
            LockScreenRoute
        }
    ) {
        composable<HomeScreenRoute> { backStackEntry ->
            val homeRoute = backStackEntry.toRoute<HomeScreenRoute>()
            HomeScreenComposable(
                mainNavController = navController,
                initialShortcut = homeRoute.lockScreenShortCut,
            )
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
