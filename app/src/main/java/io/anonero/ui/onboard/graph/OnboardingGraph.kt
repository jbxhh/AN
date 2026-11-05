package io.anonero.ui.onboard.graph

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.services.AnonWalletHandler
import io.anonero.services.WalletReplacement
import io.anonero.services.WalletState
import io.anonero.ui.MainActivity
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.settings.LogViewer
import io.anonero.ui.home.settings.ProxySettings
import io.anonero.ui.onboard.Mode
import io.anonero.ui.onboard.OnboardLoadingComposable
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.onboard.OnboardingWelcome
import io.anonero.ui.onboard.PinSetup
import io.anonero.ui.onboard.RestoreWallet
import io.anonero.ui.onboard.SeedSetup
import io.anonero.ui.onboard.SetupNodeComposable
import io.anonero.ui.onboard.SetupPassphrase
import io.anonero.ui.onboard.restore.RestorePreview
import io.anonero.ui.onboard.viewonly.RestoreFromKeys
import io.anonero.ui.viewmodels.AppViewModel
import io.anonero.util.AppPinStore
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PINLESS_WALLET
import io.anonero.util.WALLET_PREFERENCES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import java.io.File
import timber.log.Timber


@Serializable
data object LandingScreenRoute

@Serializable
data object OnboardingWelcomeScreen

@Serializable
data object OnboardingProxyScreen

@Serializable
data object OnboardPassPhraseScreen

@Serializable
data object OnboardLogsScreen

@Serializable
data class OnboardLoading(val message: String)

@Serializable
data class OnboardSeedScreen(val seed: String)

@Serializable
data object OnboardPinScreen

@Serializable
data object OnboardMultiWalletCreatePinScreen

@Serializable
data object OnboardNodeSetupScreen

@Serializable
data object OnboardRestoreScreen

@Serializable
data class OnboardRestorePreviewScreen(val backUpPath: String)

@Serializable
data object OnboardImportKeysScreen

private fun startConfiguredNodeService(context: Context, appViewModel: AppViewModel) {
    val host = context.getSharedPreferences(AnonConfig.PREFS, Context.MODE_PRIVATE)
        .getString(NodeFields.RPC_HOST.value, "")
    if (!host.isNullOrBlank()) {
        appViewModel.startService()
    }
}

/**
 * The restore UI and restoreFromSeed implementation are shared with first-run
 * onboarding. This wrapper only reserves a new wallet file and safely replaces
 * the currently open native handle before invoking that same restore operation.
 */
private suspend fun restoreImportedWallet(
    context: Context,
    activity: MainActivity?,
    onboardViewModel: OnboardViewModel,
    walletHandler: AnonWalletHandler,
    walletState: WalletState,
    walletPreferences: SharedPreferences,
) {
    val appContext = context.applicationContext
    val previousWalletName = AnonConfig.getActiveWalletName(appContext)
    val managedWalletPin = WalletManager.instance?.getManagedWalletPassword().orEmpty()
    val globalPin = AppPinStore.get(appContext) ?: managedWalletPin.takeIf {
        it.isNotEmpty() && !walletPreferences.getBoolean(
            AnonConfig.walletPreferenceKey(appContext, PREFS_PINLESS_WALLET),
            false
        )
    }.orEmpty()
    check(globalPin.isNotBlank()) { "无法读取首次启动时设置的全局 PIN，无法导入钱包" }
    AppPinStore.rememberIfMissing(appContext, globalPin)

    check(walletHandler.tryBeginWalletTransition()) {
        "钱包正在执行其他操作，请稍后重试导入"
    }

    var previousReplacement: WalletReplacement? = null
    var createdWalletFile: File? = null
    var previousWalletDetached = false
    var transitionReleased = false

    try {
        walletState.preparePendingWalletHome()
        activity?.stopNotificationService()

        withContext(Dispatchers.IO) {
            previousReplacement = walletHandler.prepareWalletReplacement(managedWalletPin)
            walletHandler.detachWalletForReplacement(previousReplacement!!)
            previousWalletDetached = true

            createdWalletFile = if (AnonConfig.getWalletFiles(appContext).isEmpty()) {
                AnonConfig.setActiveWallet(appContext, "anon")
                AnonConfig.getDefaultWalletFile(appContext)
            } else {
                AnonConfig.createNewWalletFile(appContext)
            }

            // This is the same restore operation used by first-run recovery.
            // Only the app PIN prompt is bypassed; the primary app PIN is reused.
            onboardViewModel.restoreFromSeed(
                globalPin,
                rememberPassphraseGlobally = false
            )

            val restoredWallet = WalletManager.instance?.wallet
                ?: error("恢复完成后没有可用的钱包句柄")
            check(restoredWallet.name == createdWalletFile?.name) {
                "恢复的钱包与新建的钱包文件不匹配"
            }
            walletState.prepareImmediateWalletHome(restoredWallet)
            runCatching { walletState.publishWalletSnapshot(restoredWallet) }
                .onFailure {
                    Timber.tag("OnboardingGraph").w(it, "Unable to publish imported wallet snapshot")
                }
            runCatching { walletState.ensureNextReceiveAddress() }
                .onFailure {
                    Timber.tag("OnboardingGraph").w(it, "Unable to prepare imported wallet receive address")
                }
        }

        walletHandler.endWalletTransition()
        transitionReleased = true

        walletHandler.scope.launch(Dispatchers.IO) {
            runCatching { walletHandler.finishPendingWalletReplacement() }
                .onFailure {
                    Timber.tag("OnboardingGraph").w(it, "Unable to close previous wallet after import")
                }

            val configuredNode = appContext.getSharedPreferences(
                AnonConfig.PREFS,
                Context.MODE_PRIVATE
            ).getString(NodeFields.RPC_HOST.value, "")
            if (!configuredNode.isNullOrBlank()) {
                runCatching { walletHandler.startService() }
                    .onFailure {
                        Timber.tag("OnboardingGraph").w(it, "Unable to start service for imported wallet")
                    }
            }
            withContext(Dispatchers.Main) {
                runCatching { activity?.startNotificationService() }
                    .onFailure {
                        Timber.tag("OnboardingGraph").w(it, "Unable to start notifications for imported wallet")
                    }
            }
        }
    } catch (error: Exception) {
        withContext(Dispatchers.IO) {
            if (previousWalletDetached) {
                if (previousReplacement != null) {
                    runCatching {
                        walletHandler.restoreWalletAfterFailedCreation(previousReplacement!!)
                    }.onFailure {
                        Timber.tag("OnboardingGraph").e(it, "Unable to restore previous wallet after import failure")
                    }
                } else {
                    WalletManager.instance?.wallet?.let { failedWallet ->
                        runCatching { WalletManager.instance?.close(failedWallet) }
                            .onFailure {
                                Timber.tag("OnboardingGraph").w(it, "Unable to close incomplete imported wallet")
                            }
                    }
                }

                createdWalletFile?.let { walletFile ->
                    runCatching { walletFile.delete() }
                    runCatching { File("${walletFile.path}.keys").delete() }
                    val editor = walletPreferences.edit()
                    walletPreferences.all.keys
                        .filter { it.startsWith("${walletFile.name}::") }
                        .forEach(editor::remove)
                    editor.commit()
                    KeyStoreHelper.removeWalletUserPass(appContext, walletFile.name)
                }
                AnonConfig.setActiveWallet(appContext, previousWalletName)
            }

            WalletManager.instance?.wallet?.let { restoredWallet ->
                walletState.prepareImmediateWalletHome(restoredWallet)
                runCatching { walletState.publishWalletSnapshot(restoredWallet) }
                    .onFailure {
                        Timber.tag("OnboardingGraph").w(it, "Unable to republish previous wallet after import failure")
                    }
            }
        }

        walletHandler.endWalletTransition()
        transitionReleased = true

        if (previousWalletDetached) {
            walletHandler.scope.launch(Dispatchers.IO) {
                val configuredNode = appContext.getSharedPreferences(
                    AnonConfig.PREFS,
                    Context.MODE_PRIVATE
                ).getString(NodeFields.RPC_HOST.value, "")
                if (!configuredNode.isNullOrBlank()) {
                    runCatching { walletHandler.startService() }
                        .onFailure {
                            Timber.tag("OnboardingGraph").w(it, "Unable to restart previous wallet service")
                        }
                }
                withContext(Dispatchers.Main) {
                    runCatching { activity?.startNotificationService() }
                        .onFailure {
                            Timber.tag("OnboardingGraph").w(it, "Unable to restart previous wallet notifications")
                        }
                }
            }
        }
        throw error
    } finally {
        if (!transitionReleased) {
            walletHandler.endWalletTransition()
        }
    }
}

//onboarding
fun NavGraphBuilder.onboardingGraph(
    navController: NavHostController,
    onboardViewModel: OnboardViewModel
) {


    navigation<LandingScreenRoute>(
        startDestination = OnboardingWelcomeScreen,
    ) {

        dialog<OnboardLoading>(
            dialogProperties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            OnboardLoadingComposable(loadingState = it.toRoute())
        }
        composable<OnboardingWelcomeScreen> {
            OnboardingWelcome(
                onRestoreClick = {
                    onboardViewModel.setMode(Mode.RESTORE)
                    if (it != null) {
                        navController.navigate(OnboardRestorePreviewScreen(it))
                    } else {
                        navController.navigate(OnboardRestoreScreen)
                    }
                },
                onCreateClick = {
                    onboardViewModel.setMode(Mode.CREATE)
                    navController.navigate(OnboardNodeSetupScreen)
                },
                onRestoreFromKeys = {
                    navController.navigate(OnboardImportKeysScreen)
                },
                onProxySettings = {
                    navController.navigate(OnboardingProxyScreen)
                },
                onLogsScreen = {
                    navController.navigate(OnboardLogsScreen)
                }
            )
        }
        composable<OnboardingProxyScreen> {
            ProxySettings(
                onBackPress = {
                    navController.navigateUp()
                },
            )
        }
        composable<OnboardNodeSetupScreen> {
            SetupNodeComposable(
                onBackPressed = {
                    navController.navigateUp()
                },
                oNextPressed = {
                    if (AnonConfig.viewOnly) {
                        navController.navigate(OnboardPinScreen)
                    } else {
                        navController.navigate(OnboardPassPhraseScreen)
                    }
                }
            )
        }
        composable<OnboardRestoreScreen> {
            BackHandler(enabled = onboardViewModel.isWalletImport()) {
                onboardViewModel.cancelWalletImport()
                navController.navigateUp()
            }
            RestoreWallet(
                onboardViewModel = onboardViewModel,
                onBackPressed = {
                    if (onboardViewModel.isWalletImport()) {
                        onboardViewModel.cancelWalletImport()
                    }
                    navController.navigateUp()
                },
                oNextPressed = {
                    onboardViewModel.setRestorePayload(it)
                    navController.navigate(OnboardNodeSetupScreen)
                }
            )
        }
        composable<OnboardRestorePreviewScreen> { navBackStackEntry ->
            val activity = LocalActivity.current
            val backUpPath = navBackStackEntry.toRoute<OnboardRestorePreviewScreen>()
            RestorePreview(
                backUpPath = backUpPath.backUpPath,
                onBackPressed = {
                    navController.navigateUp()
                },
                navigateTo = {
                    navController.navigate(it)
                },
                oNextPressed = {
                    navController.navigate(
                        Home
                    ) {
                        popUpTo(LandingScreenRoute)
                    }
                }
            )
        }
        composable<OnboardPassPhraseScreen> {
            SetupPassphrase(
                onBackPressed = {
                    navController.navigateUp()
                },
                oNextPressed = {
                    onboardViewModel.setPassPhrase(it)
                    navController.navigate(OnboardPinScreen)
                }
            )
        }

        composable<OnboardLogsScreen>() {
            LogViewer(
                onBackPress = {
                    navController.navigateUp()
                }
            )
        }

        composable<OnboardPinScreen> {
            val appViewModel = koinInject<AppViewModel>()
            val walletState = koinInject<WalletState>()
            var showErrorMessage by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            val activity = LocalActivity.current
            val walletHandler = koinInject<AnonWalletHandler>()
            val walletPreferences = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
            OnboardErrorDialog(
                showErrorMessage = showErrorMessage,
                isRestoreMode = onboardViewModel.getMode() == Mode.RESTORE,
                onClose = {
                    showErrorMessage = null
                    navController.popBackStack()
                },
                onShowLogs = {
                    showErrorMessage = null
                    navController.popBackStack()
                    navController.navigate(OnboardLogsScreen)
                }
            )
            if (onboardViewModel.isWalletImport()) {
                LaunchedEffect(Unit) {
                    navController.navigate(
                        OnboardLoading(context.getString(R.string.restoring_wallet))
                    )
                    onboardViewModel.viewModelScope.launch {
                        restoreImportedWallet(
                            context = context,
                            activity = activity as? MainActivity,
                            onboardViewModel = onboardViewModel,
                            walletHandler = walletHandler,
                            walletState = walletState,
                            walletPreferences = walletPreferences,
                        )
                        delay(600)
                    }.invokeOnCompletion { error ->
                        if (error == null) {
                            onboardViewModel.finishWalletImport()
                            navController.navigate(HomeScreenRoute(pinVerified = true)) {
                                popUpTo(Home::class.qualifiedName!!) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack()
                            showErrorMessage = error.message ?: "Unknown error"
                        }
                    }
                }
            } else {
                PinSetup(
                    onNext = { pin ->
                        var loadingMessage = context.getString(R.string.creating_wallet)
                        if (onboardViewModel.getMode() == Mode.RESTORE) {
                            loadingMessage = context.getString(R.string.restoring_wallet)
                        }
                        navController.navigate(OnboardLoading(loadingMessage))
                        onboardViewModel.viewModelScope.launch {
                            if (AnonConfig.viewOnly) {
                                onboardViewModel.createViewOnly(pin)
                            } else {
                                if (onboardViewModel.getMode() == Mode.RESTORE) {
                                    withContext(Dispatchers.IO) {
                                        onboardViewModel.restoreFromSeed(pin)
                                    }
                                } else {
                                    onboardViewModel.create(pin)
                                }
                            }
                            // Seed restore/create paths reach Home without reopening the wallet.
                            // Publish local balance and primary address before Home's first frame;
                            // node/Tor initialization continues asynchronously afterward.
                            withContext(Dispatchers.IO) {
                                walletState.publishWalletSnapshot()
                            }
                            delay(600)
                        }.invokeOnCompletion {
                            if (it == null) {
                                if (AnonConfig.viewOnly) {
                                    startConfiguredNodeService(context, appViewModel)
                                    navController.navigate(HomeScreenRoute()) {
                                        popUpTo(LandingScreenRoute)
                                    }
                                } else {
                                    if (onboardViewModel.getMode() == Mode.RESTORE) {
                                        startConfiguredNodeService(context, appViewModel)
                                        navController.navigate(HomeScreenRoute()) {
                                            popUpTo(LandingScreenRoute)
                                        }
                                    } else {
                                        navController.navigate(OnboardSeedScreen(onboardViewModel.getSeed()))
                                    }
                                }
                            } else {
                                navController.popBackStack()
                                showErrorMessage = it.message ?: "Unknown error";
                            }
                        }
                    }
                )
            }
        }
        composable<OnboardMultiWalletCreatePinScreen> {
            val walletState = koinInject<WalletState>()
            val context = LocalContext.current
            val previousWalletName = remember(context) {
                AnonConfig.getActiveWalletName(context)
            }
            var showErrorMessage by remember { mutableStateOf<String?>(null) }

            OnboardErrorDialog(
                showErrorMessage = showErrorMessage,
                isRestoreMode = false,
                onClose = {
                    showErrorMessage = null
                    navController.popBackStack()
                },
                onShowLogs = {
                    showErrorMessage = null
                    navController.popBackStack()
                    navController.navigate(OnboardLogsScreen)
                }
            )

            PinSetup(
                onNext = { pin ->
                    try {
                        if (AnonConfig.getWalletFiles(context).isEmpty()) {
                            AnonConfig.setActiveWallet(context, "anon")
                        } else {
                            AnonConfig.createNewWalletFile(context)
                        }
                    } catch (error: Exception) {
                        showErrorMessage = error.message ?: "Unknown error"
                        return@PinSetup
                    }

                    onboardViewModel.setMode(Mode.CREATE)
                    onboardViewModel.setPassPhrase("")
                    navController.navigate(
                        OnboardLoading(context.getString(R.string.creating_wallet))
                    )
                    onboardViewModel.viewModelScope.launch {
                        onboardViewModel.create(pin)
                        withContext(Dispatchers.IO) {
                            walletState.publishWalletSnapshot()
                        }
                        delay(600)
                    }.invokeOnCompletion { error ->
                        if (error == null) {
                            navController.navigate(HomeScreenRoute(pinVerified = true)) {
                                popUpTo(OnboardMultiWalletCreatePinScreen) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        } else {
                            AnonConfig.setActiveWallet(context, previousWalletName)
                            navController.popBackStack()
                            showErrorMessage = error.message ?: "Unknown error"
                        }
                    }
                }
            )
        }

        composable<OnboardSeedScreen> { navBackStackEntry ->
            val state = navBackStackEntry.toRoute<OnboardSeedScreen>()
            val context = LocalContext.current
            val activity = LocalActivity.current
            val appViewModel = koinInject<AppViewModel>()
            val walletState = koinInject<WalletState>()
            val entryScope = rememberCoroutineScope()
            SeedSetup(
                seed = state.seed
                    .split(" ").toList(),
                oNextPressed = {
                    entryScope.launch {
                        withContext(Dispatchers.IO) {
                            walletState.publishWalletSnapshot()
                        }
                        startConfiguredNodeService(context, appViewModel)
                        navController.navigate(HomeScreenRoute()) {
                            popUpTo(LandingScreenRoute)
                        }
                    }
                },
                onBackPressed = {
                    navController.navigate("")
                }
            )
        }
        composable<OnboardImportKeysScreen> {
            RestoreFromKeys(
                oNextPressed = {
                    onboardViewModel.setNeroPayload(it)
                    navController.navigate(OnboardNodeSetupScreen)
                },
                onBackPressed = {
                    navController.navigateUp()
                }
            )
        }
    }
}


@Composable
fun OnboardErrorDialog(
    showErrorMessage: String?,
    isRestoreMode: Boolean,
    onClose: () -> Unit,
    onShowLogs: () -> Unit
) {
    if (showErrorMessage != null) {
        AlertDialog(
            onDismissRequest = onClose,
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
                    onClick = onClose
                ) { Text(stringResource(R.string.close)) }
            },
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(alpha = .2f),
                    shape = MaterialTheme.shapes.medium,
                ),
            dismissButton = {
                Button(
                    onClick = onShowLogs,
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
                        stringResource(R.string.logs),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                        )
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.secondary,
            title = {
                Text(
                    text = stringResource(
                        R.string.wallet,
                        stringResource(R.string.unable_to),
                        if (isRestoreMode) stringResource(
                            R.string.restore
                        ) else stringResource(R.string.create)
                    ),
                )
            },
            text = {
                Text(
                    text = showErrorMessage,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.error,
                    )
                )
            }
        )
    }
}