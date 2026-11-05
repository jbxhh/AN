package io.anonero.ui

import io.anonero.ui.theme.AnonNeroTheme
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.asLiveData
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.R
import io.anonero.services.AnonNeroService
import io.anonero.services.AnonWalletHandler
import io.anonero.services.TorService
import io.anonero.services.WalletState
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.services.startAnonService
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.LockScreenShortCut
import io.anonero.ui.home.graph.homeGraph
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.home.graph.routes.HomeScreenRoute
import io.anonero.ui.home.settings.ProxySettings
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.onboard.Mode
import io.anonero.ui.onboard.graph.LandingScreenRoute
import io.anonero.ui.onboard.graph.OnboardImportKeysScreen
import io.anonero.ui.onboard.graph.OnboardNodeSetupScreen
import io.anonero.ui.onboard.graph.OnboardMultiWalletCreatePinScreen
import io.anonero.ui.onboard.graph.onboardingGraph
import io.anonero.util.AppPinStore
import io.anonero.util.AppPassphraseStore
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PINLESS_WALLET
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.WALLET_USE_TOR
import io.anonero.ui.wallets.WalletListRoute
import io.anonero.ui.wallets.WalletListProxyRoute
import io.anonero.ui.wallets.MultiWalletScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.qualifier.named
import timber.log.Timber


private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_WALLET_CLEARED = "io.anonero.action.WALLET_CLEARED"
    }

    private val walletClearedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_WALLET_CLEARED && !isFinishing && !isDestroyed) {
                // Every open Activity instance must rebuild navigation from the on-disk wallet state.
                recreate()
            }
        }
    }
    private var walletClearedReceiverRegistered = false

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val walletState: WalletState by inject()
    private val anonWalletHandler: AnonWalletHandler by inject()
    private val torService: TorService by inject()
    val anonPrefs: SharedPreferences by inject(named(WALLET_PREFERENCES))
    private val nodesRepository: NodesRepository by inject()
    private val logRepository: LogRepository by inject()

    // Local prefs to record which wallet we've already prompted notification permission for
    private val localPrefs by lazy { getSharedPreferences("anonero_prefs", MODE_PRIVATE) }
    // Android's notification permission sheet can trigger onUserLeaveHint. Do not
    // interpret that system-owned UI as the user leaving the wallet and lock it again.
    @Volatile
    private var notificationPermissionRequestInProgress = false
    private val walletSelectionUnlockInProgress = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashscreen = installSplashScreen()
        var isAppReady by mutableStateOf(false)
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            walletClearedReceiver,
            IntentFilter(ACTION_WALLET_CLEARED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        walletClearedReceiverRegistered = true
        val crashPrefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
        if (crashPrefs.getBoolean("diagnostic_mode", false)) {
            val crash = crashPrefs.getString("last_crash", null)
                ?: "上次异常退出已被系统检测到，但没有可读取的详细堆栈。"
            val diagnosticDialog = android.app.AlertDialog.Builder(this)
                .setTitle("已进入闪退诊断模式")
                .setMessage(
                    "为了避免应用再次立即闪退，当前已暂时跳过钱包和 Tor 初始化。\n\n" +
                        crash.take(2500) +
                        if (crash.length > 2500) "\n\n……日志较长，请点“复制完整日志”获取全部内容。" else ""
                )
                .setNeutralButton("复制完整日志", null)
                .setPositiveButton("关闭应用，稍后重开", null)
                .setCancelable(false)
                .create()

            // Keep the diagnostic dialog visible after copying so the user can then exit.
            diagnosticDialog.setOnShowListener {
                diagnosticDialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                    .setOnClickListener {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("ANONERO 崩溃日志", crash)
                        )
                        android.widget.Toast.makeText(
                            this,
                            "完整日志已复制",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                diagnosticDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener {
                        crashPrefs.edit().putBoolean("diagnostic_mode", false).commit()
                        finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
            }
            diagnosticDialog.show()
            return
        }

        crashPrefs.getString("last_crash", null)?.let { crash ->
            android.app.AlertDialog.Builder(this)
                .setTitle("检测到上次异常退出")
                .setMessage(
                    crash.take(2500) +
                        if (crash.length > 2500) "\n\n……日志较长，点“复制完整日志”获取全部内容。" else ""
                )
                .setNeutralButton("复制完整日志") { _, _ ->
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("ANONERO 崩溃日志", crash)
                    )
                }
                .setPositiveButton("知道了") { _, _ ->
                    crashPrefs.edit()
                        .remove("last_crash")
                        .remove("last_crash_timestamp")
                        .apply()
                }
                .show()
        }

        val scrimColor = Color.Black.copy(alpha = 0.1f).toArgb()
        // Keep the splash visible only until wallet/preferences initialization finishes.
        splashscreen.setKeepOnScreenCondition { !isAppReady }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrimColor),
            navigationBarStyle = SystemBarStyle.dark(scrimColor),
        )
        setContent {
            val context = LocalContext.current
            var walletExist by remember {
                mutableStateOf(
                    context.getSharedPreferences(AnonConfig.APP_PREFERENCES, MODE_PRIVATE).let { appPreferences ->
                        appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false) ||
                            appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false) ||
                            runCatching { AnonConfig.getWalletFiles(context).isNotEmpty() }.getOrDefault(true)
                    }
                )
            }
            var startupInitializationFailed by remember { mutableStateOf(false) }
            var useTor by remember {
                mutableStateOf(
                    !context.getSharedPreferences(AnonConfig.APP_PREFERENCES, MODE_PRIVATE).let { appPreferences ->
                        appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false) ||
                            appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false)
                    } && anonPrefs.getBoolean(WALLET_USE_TOR, true)
                )
            }
            val onboardViewModel = koinViewModel<OnboardViewModel>()
            val scope = rememberCoroutineScope()

            LaunchedEffect(key1 = true) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val appPreferences = context.getSharedPreferences(
                            AnonConfig.APP_PREFERENCES, MODE_PRIVATE
                        )
                        // Keep an accepted deletion request visible to the SecureWipe progress
                        // screen. That screen resumes interrupted cleanup without asking for
                        // the deletion password again; startup must not silently skip progress.
                        val stillPending = appPreferences.getBoolean(
                            AnonConfig.PREF_WALLET_WIPE_PENDING, false
                        )
                        val wipeRequested = appPreferences.getBoolean(
                            AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
                        )
                        val storedWallets = AnonConfig.getWalletFiles(context)
                        if (storedWallets.isNotEmpty()) {
                            if (!AnonConfig.getDefaultWalletFile(context).exists()) {
                                AnonConfig.setActiveWallet(context, storedWallets.first().name)
                            }
                        } else if (!stillPending && !wipeRequested) {
                            AnonConfig.setActiveWallet(context, "anon")
                        }
                        walletExist = stillPending || wipeRequested || storedWallets.isNotEmpty()
                        useTor = if (stillPending || wipeRequested) {
                            false
                        } else {
                            anonPrefs.getBoolean(WALLET_USE_TOR, true)
                        }
                    } catch (e: Exception) {
                        // A corrupt/unavailable preference or wallet path must not leave
                        // the system splash screen waiting forever.
                        Timber.tag(TAG).e(e, "Startup initialization failed; continuing with safe defaults")
                        val appPreferences = context.getSharedPreferences(
                            AnonConfig.APP_PREFERENCES, MODE_PRIVATE
                        )
                        val wipeStillPending = appPreferences.getBoolean(
                            AnonConfig.PREF_WALLET_WIPE_PENDING, false
                        )
                        val wipeWasRequested = appPreferences.getBoolean(
                            AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
                        )
                        // Fail closed: do not route to onboarding or a usable wallet when recovery fails.
                        startupInitializationFailed = true
                        walletExist = wipeStillPending || wipeWasRequested
                        useTor = false
                    } finally {
                        isAppReady = true
                    }
                }
            }
            TorSplash(enableTor = useTor) {
                AnonNeroTheme {
                    val navController = rememberNavController()
                    var walletSelectedForUnlock by walletSelectionUnlockInProgress
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    val isHomeGraphVisible = currentBackStackEntry?.destination?.hierarchy
                        ?.any { it.hasRoute<Home>() } == true
                    navController.addOnDestinationChangedListener { x, destination, d ->
                        Timber.tag(TAG).d("destination changed to ${destination.route}")
                    }
                    val showLockScreen by walletState.pinLockVisibleFlow.asLiveData().observeAsState(walletState.pinLockVisible)
                    val deletionWorkflowActive = context.getSharedPreferences(
                        AnonConfig.APP_PREFERENCES, MODE_PRIVATE
                    ).let { appPreferences ->
                        appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false) ||
                            appPreferences.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)
                    }
                    LaunchedEffect(
                        showLockScreen,
                        isHomeGraphVisible,
                        walletSelectedForUnlock,
                        deletionWorkflowActive
                    ) {
                        if (
                            showLockScreen &&
                            isHomeGraphVisible &&
                            !walletSelectedForUnlock &&
                            !deletionWorkflowActive
                        ) {
                            navController.navigate(WalletListRoute) {
                                popUpTo(Home::class.qualifiedName!!) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController, popExitTransition = {
                            ExitTransition.None
                        }, popEnterTransition = {
                            EnterTransition.None
                        }, startDestination = if (deletionWorkflowActive) {
                            Home
                        } else if (walletExist || startupInitializationFailed) {
                            WalletListRoute
                        } else {
                            LandingScreenRoute
                        }
                        ) {
                            //landing screen and onboarding screens
                            onboardingGraph(navController, onboardViewModel)
                            //home bottom navigation
                            homeGraph(navController)
                            composable<WalletListRoute> {
                                MultiWalletScreen(
                                    onWalletSelected = { walletFile ->
                                        scope.launch {
                                            val previousWalletName = AnonConfig.getActiveWalletName(context)
                                            val isSameWallet = previousWalletName == walletFile.name
                                            if (isSameWallet) {
                                                val walletAlreadyOpen =
                                                    WalletManager.instance?.wallet?.let {
                                                        it.name == walletFile.name && it.status.isOk
                                                    } == true
                                                withContext(Dispatchers.Main) {
                                                    walletSelectedForUnlock =
                                                        walletState.pinLockVisible && walletAlreadyOpen
                                                    if (walletState.pinLockVisible && !walletAlreadyOpen) {
                                                        walletState.dismissPinLockScreen()
                                                    }
                                                    navController.navigate(
                                                        HomeScreenRoute(pinVerified = walletAlreadyOpen)
                                                    ) {
                                                        popUpTo(WalletListRoute) { inclusive = false }
                                                        launchSingleTop = true
                                                    }
                                                }
                                                return@launch
                                            }

                                            if (!anonWalletHandler.tryBeginWalletTransition()) return@launch
                                            try {
                                                val walletWasPinLocked = walletState.pinLockVisible
                                                walletSelectedForUnlock = false
                                                val managedWalletPin = WalletManager.instance
                                                    ?.getManagedWalletPassword().orEmpty()
                                                val primaryPin = AppPinStore.get(context)
                                                    ?: managedWalletPin.takeIf {
                                                        it.isNotEmpty() && !anonPrefs.getBoolean(
                                                            AnonConfig.walletPreferenceKey(context, PREFS_PINLESS_WALLET),
                                                            false
                                                        )
                                                    }.orEmpty().also {
                                                        AppPinStore.rememberIfMissing(context, it)
                                                    }
                                                val canReusePrimaryPin = primaryPin.isNotEmpty() &&
                                                    managedWalletPin.isNotEmpty() &&
                                                    !walletWasPinLocked
                                                val replacement = anonWalletHandler.prepareWalletReplacement(managedWalletPin)

                                                stopNotificationService()
                                                AnonConfig.setActiveWallet(context, walletFile.name)
                                                anonWalletHandler.detachWalletForReplacement(replacement)
                                                if (walletWasPinLocked) {
                                                    walletState.dismissPinLockScreen()
                                                }

                                                val targetWalletOpened = if (canReusePrimaryPin) {
                                                    runCatching {
                                                        anonWalletHandler.openWallet(
                                                            primaryPin,
                                                            publishLocalSnapshot = false,
                                                            prepareInitialReceiveAddress = false
                                                        )
                                                    }.onFailure {
                                                        Timber.tag(TAG).w(it, "Unable to auto-open selected wallet with the app's primary PIN")
                                                    }.getOrDefault(false)
                                                } else {
                                                    false
                                                }
                                                val targetWallet = if (targetWalletOpened) {
                                                    WalletManager.instance?.wallet
                                                } else {
                                                    null
                                                }
                                                targetWallet?.let { wallet ->
                                                    walletState.prepareImmediateWalletHome(wallet)
                                                    // Read the selected wallet's local balance before Home composes;
                                                    // old-wallet cleanup and node startup remain asynchronous.
                                                    withContext(Dispatchers.IO) {
                                                        walletState.publishWalletBalanceSnapshot(wallet)
                                                    }
                                                }

                                                withContext(Dispatchers.Main) {
                                                    navController.navigate(
                                                        HomeScreenRoute(pinVerified = targetWalletOpened)
                                                    ) {
                                                        popUpTo(WalletListRoute) { inclusive = false }
                                                        launchSingleTop = true
                                                    }
                                                }

                                                val configuredNode = context.getSharedPreferences(
                                                    AnonConfig.PREFS,
                                                    MODE_PRIVATE
                                                ).getString(NodeFields.RPC_HOST.value, "")
                                                if (targetWalletOpened) {
                                                    scope.launch(Dispatchers.IO) {
                                                        anonWalletHandler.finishPendingWalletReplacement()
                                                        val activeWallet = WalletManager.instance?.wallet
                                                        if (activeWallet === targetWallet) {
                                                            walletState.publishWalletSnapshot(activeWallet)
                                                        }
                                                        if (activeWallet === targetWallet && !configuredNode.isNullOrBlank()) {
                                                            anonWalletHandler.startService()
                                                        }
                                                    }
                                                }
                                            } finally {
                                                anonWalletHandler.endWalletTransition()
                                            }
                                        }
                                    },
                                    onProxySettings = {
                                        navController.navigate(WalletListProxyRoute)
                                    },
                                    onDeleteWallet = { walletFile ->
                                        if (!anonWalletHandler.tryBeginWalletTransition()) {
                                            Toast.makeText(
                                                context,
                                                "钱包正在切换，请稍后重试删除",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            false
                                        } else {
                                            try {
                                                val walletName = walletFile.name
                                                check(
                                                    walletName.isNotBlank() &&
                                                        java.io.File(walletName).name == walletName &&
                                                        walletName != "." && walletName != ".."
                                                ) { "无效的钱包文件名" }
                                                val activeWalletName = AnonConfig.getActiveWalletName(context)
                                                val managedWalletMatches =
                                                    WalletManager.instance?.wallet?.name == walletName
                                                val deletingActiveWallet =
                                                    activeWalletName == walletName || managedWalletMatches

                                                if (deletingActiveWallet) stopNotificationService()

                                                val nativeWalletClosed = withContext(Dispatchers.IO) {
                                                    anonWalletHandler.closeWalletForDeletion(walletName)
                                                }
                                                check(nativeWalletClosed) {
                                                    "钱包仍有原生操作未结束，请稍后重试删除"
                                                }

                                                val remainingWallets = withContext(Dispatchers.IO) {
                                                    val walletDirectory = AnonConfig.getDefaultWalletDir(context).canonicalFile
                                                    val walletDataFile = java.io.File(walletDirectory, walletName)
                                                    val walletKeysFile = java.io.File(walletDirectory, "$walletName.keys")
                                                    check(walletDataFile.canonicalFile.parentFile == walletDirectory) {
                                                        "钱包文件路径无效"
                                                    }
                                                    check(walletDataFile.isFile && walletKeysFile.isFile) {
                                                        "钱包文件不存在或已被删除"
                                                    }

                                                    val dataDeleted = walletDataFile.delete()
                                                    val keysDeleted = dataDeleted && walletKeysFile.delete()
                                                    check(dataDeleted && keysDeleted) {
                                                        "钱包文件删除失败，请检查文件状态"
                                                    }

                                                    val walletPreferenceEditor = anonPrefs.edit()
                                                    anonPrefs.all.keys
                                                        .filter { it.startsWith("$walletName::") }
                                                        .forEach(walletPreferenceEditor::remove)
                                                    check(walletPreferenceEditor.commit()) {
                                                        "钱包设置清理失败"
                                                    }
                                                    KeyStoreHelper.removeWalletUserPass(
                                                        context.applicationContext,
                                                        walletName
                                                    )

                                                    val notificationPreferences = context.getSharedPreferences(
                                                        "anonero_prefs", MODE_PRIVATE
                                                    )
                                                    if (notificationPreferences.getString("notif_prompt_wallet_id", null) == walletName) {
                                                        notificationPreferences.edit().remove("notif_prompt_wallet_id").apply()
                                                    }

                                                    val remaining = AnonConfig.getWalletFiles(context)
                                                    if (deletingActiveWallet) {
                                                        AnonConfig.setActiveWallet(
                                                            context,
                                                            remaining.firstOrNull()?.name ?: "anon"
                                                        )
                                                    }
                                                    remaining
                                                }
                                                walletExist = remainingWallets.isNotEmpty()
                                                true
                                            } catch (error: Exception) {
                                                Timber.tag(TAG).e(error, "Unable to delete wallet %s", walletFile.name)
                                                Toast.makeText(
                                                    context,
                                                    error.message ?: "删除钱包失败",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                false
                                            } finally {
                                                anonWalletHandler.endWalletTransition()
                                            }
                                        }
                                    },
                                    onNewWallet = {
                                        if (!anonWalletHandler.tryBeginWalletTransition()) {
                                            Timber.tag(TAG).w(
                                                "Ignoring new wallet request while another wallet transition is active"
                                            )
                                        } else {
                                            val previousWalletName = AnonConfig.getActiveWalletName(context)
                                            onboardViewModel.setMode(Mode.CREATE)
                                            anonWalletHandler.beginImmediateWalletCreationHome()

                                            // Publish a zero-balance pending wallet state and navigate
                                            // before touching the synchronized native WalletManager.
                                            walletState.preparePendingWalletHome()
                                            navController.navigate(HomeScreenRoute(pinVerified = true)) {
                                                popUpTo(WalletListRoute) { inclusive = true }
                                                launchSingleTop = true
                                            }

                                            anonWalletHandler.scope.launch(Dispatchers.IO) {
                                                var previousWalletReplacement: io.anonero.services.WalletReplacement? = null
                                                var transitionReleased = false
                                                try {
                                                    try {
                                                        // These operations can contend with native wallet calls.
                                                        // Keep them off the UI thread so the first tap navigates at once.
                                                        val managedWalletPin = WalletManager.instance
                                                            ?.getManagedWalletPassword().orEmpty()
                                                        previousWalletReplacement =
                                                            anonWalletHandler.prepareWalletReplacement(managedWalletPin)
                                                        previousWalletReplacement?.let {
                                                            anonWalletHandler.detachWalletForReplacement(it)
                                                        }

                                                        runCatching { stopNotificationService() }
                                                            .onFailure {
                                                                Timber.tag(TAG).w(it, "Unable to stop the previous notification service")
                                                            }

                                                        // Wallet-file and native wallet creation are local operations;
                                                        // neither requires a node, RPC connection, or network.
                                                        if (AnonConfig.getWalletFiles(context).isEmpty()) {
                                                            AnonConfig.setActiveWallet(context, "anon")
                                                        } else {
                                                            AnonConfig.createNewWalletFile(context)
                                                        }

                                                        onboardViewModel.setPassPhrase(
                                                            AppPassphraseStore.get(context).orEmpty()
                                                        )
                                                        val primaryPin = AppPinStore.get(context)
                                                            ?: managedWalletPin.takeIf {
                                                                it.isNotEmpty() && !anonPrefs.getBoolean(
                                                                    AnonConfig.walletPreferenceKey(
                                                                        context,
                                                                        PREFS_PINLESS_WALLET
                                                                    ),
                                                                    false
                                                                )
                                                            }.orEmpty().also {
                                                                AppPinStore.rememberIfMissing(context, it)
                                                            }

                                                        onboardViewModel.create(
                                                            primaryPin,
                                                            prepareInitialReceiveAddress = false
                                                        )
                                                        val createdWallet = WalletManager.instance?.wallet
                                                            ?: error("新钱包创建后没有可用的钱包句柄")
                                                        walletState.prepareImmediateNewWalletHome(createdWallet)
                                                    } catch (error: Exception) {
                                                        Timber.tag(TAG).e(error, "Local wallet creation failed")
                                                        if (previousWalletReplacement != null) {
                                                            runCatching {
                                                                anonWalletHandler.restoreWalletAfterFailedCreation(
                                                                    previousWalletReplacement
                                                                )
                                                            }.onFailure {
                                                                Timber.tag(TAG).e(it, "Unable to restore previous wallet after creation failure")
                                                            }
                                                        } else {
                                                            WalletManager.instance?.wallet?.let { wallet ->
                                                                runCatching { WalletManager.instance?.close(wallet) }
                                                            }
                                                        }
                                                        AnonConfig.setActiveWallet(context, previousWalletName)
                                                        val restoredWallet = WalletManager.instance?.wallet
                                                        if (restoredWallet != null) {
                                                            walletState.prepareImmediateWalletHome(restoredWallet)
                                                            runCatching {
                                                                walletState.publishWalletSnapshot(restoredWallet)
                                                            }.onFailure {
                                                                Timber.tag(TAG).w(it, "Unable to restore previous wallet snapshot")
                                                            }
                                                        }

                                                        // Unlock the list as soon as local rollback is complete;
                                                        // restarting the old node is background-only work.
                                                        anonWalletHandler.endWalletTransition()
                                                        transitionReleased = true
                                                        withContext(Dispatchers.Main) {
                                                            runCatching {
                                                                navController.navigate(WalletListRoute) {
                                                                    popUpTo(Home::class.qualifiedName!!) { inclusive = true }
                                                                    launchSingleTop = true
                                                                }
                                                            }.onFailure {
                                                                Timber.tag(TAG).w(it, "Unable to return to wallet list after local creation failure")
                                                            }
                                                            Toast.makeText(
                                                                context,
                                                                error.message ?: "新建钱包失败",
                                                                Toast.LENGTH_LONG,
                                                            ).show()
                                                        }

                                                        val configuredNode = context.getSharedPreferences(
                                                            AnonConfig.PREFS,
                                                            MODE_PRIVATE
                                                        ).getString(NodeFields.RPC_HOST.value, "")
                                                        if (restoredWallet != null && !configuredNode.isNullOrBlank()) {
                                                            runCatching { anonWalletHandler.startService() }
                                                                .onFailure {
                                                                    Timber.tag(TAG).w(it, "Unable to restart previous wallet service")
                                                                }
                                                        }
                                                        return@launch
                                                    }

                                                    // The new wallet handle and its zero balance are ready.
                                                    // Release the transition lock before slow old-wallet cleanup
                                                    // and optional node startup; Home stays visible throughout.
                                                    anonWalletHandler.endWalletTransition()
                                                    transitionReleased = true

                                                    runCatching {
                                                        anonWalletHandler.finishPendingWalletReplacement()
                                                    }.onFailure {
                                                        Timber.tag(TAG).w(it, "Unable to finish previous wallet cleanup")
                                                    }

                                                    val activeWallet = WalletManager.instance?.wallet
                                                    if (activeWallet != null &&
                                                        activeWallet.name == AnonConfig.getActiveWalletName(context)
                                                    ) {
                                                        runCatching {
                                                            walletState.publishWalletSnapshot(activeWallet)
                                                        }.onFailure {
                                                            Timber.tag(TAG).w(it, "Unable to publish new wallet snapshot")
                                                        }

                                                        val configuredNode = context.getSharedPreferences(
                                                            AnonConfig.PREFS,
                                                            MODE_PRIVATE
                                                        ).getString(NodeFields.RPC_HOST.value, "")
                                                        if (!configuredNode.isNullOrBlank()) {
                                                            runCatching { anonWalletHandler.startService() }
                                                                .onFailure {
                                                                    Timber.tag(TAG).w(it, "Unable to start wallet node service; wallet remains created")
                                                                }
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            if (!notificationPermissionRequestInProgress) {
                                                                runCatching { startNotificationService() }
                                                                    .onFailure {
                                                                        Timber.tag(TAG).w(it, "Unable to start notification service")
                                                                    }
                                                            }
                                                        }
                                                    }
                                                } finally {
                                                    anonWalletHandler.endImmediateWalletCreationHome()
                                                    if (!transitionReleased) {
                                                        anonWalletHandler.endWalletTransition()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            composable<WalletListProxyRoute> {
                                ProxySettings(
                                    onBackPress = { navController.popBackStack() }
                                )
                            }
                        }
                        if (showLockScreen && isHomeGraphVisible && !deletionWorkflowActive) {
                            LockScreen(
                                mode = LockScreenMode.LOCK_SCREEN,
                                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                                onUnLocked = { pin, shortCut ->
                                    // The PIN has already been verified by LockScreen. Dismiss
                                    // the overlay immediately; background-sync shutdown and refresh
                                    // recovery must never trap the user on the PIN screen.
                                    walletSelectedForUnlock = false
                                    val appPreferences = getSharedPreferences(
                                        AnonConfig.APP_PREFERENCES, MODE_PRIVATE
                                    )
                                    val wipeRequested = appPreferences.getBoolean(
                                        AnonConfig.PREF_WALLET_WIPE_REQUESTED, false
                                    ) || appPreferences.getBoolean(
                                        AnonConfig.PREF_WALLET_WIPE_PENDING, false
                                    )
                                    walletState.dismissPinLockScreen()
                                    if (!wipeRequested && shortCut != LockScreenShortCut.HOME) {
                                        walletState.emitUnlockShortcut(shortCut)
                                    }
                                    if (!wipeRequested) scope.launch(Dispatchers.IO) {
                                        val resumed = runCatching {
                                            walletState.exitBackgroundSync(pin)
                                        }.onFailure {
                                            Timber.tag(TAG).e(it, "Unable to restore wallet refresh after PIN unlock")
                                        }.getOrDefault(false)
                                        if (!resumed) {
                                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(
                                                    this@MainActivity,
                                                    "PIN 已验证，但钱包刷新未恢复；请重试或手动连接节点。",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionRequestInProgress = false
        // Mark that we've prompted for this wallet
        val walletId = AnonConfig.getDefaultWalletFile(this)?.name ?: ""
        if (walletId.isNotEmpty()) {
            localPrefs.edit().putString("notif_prompt_wallet_id", walletId).apply()
        }

        if (isGranted) {
            Intent(applicationContext, AnonNeroService::class.java).also {
                it.action = "start"
                ContextCompat.startForegroundService(applicationContext, it)
            }
        }
        //handle and show dialog
    }

    fun startNotificationService() {
        val walletId = AnonConfig.getDefaultWalletFile(this)?.name ?: ""
        if (walletId.isEmpty()) {
            // No wallet file; nothing to prompt for
            return
        }

        // If already have permission, just start service
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            startAnonService(applicationContext)
            return
        }

        // Check whether we've already prompted for this wallet
        val promptedWalletId = localPrefs.getString("notif_prompt_wallet_id", "") ?: ""
        if (promptedWalletId != walletId) {
            // Not prompted for this wallet yet — request runtime permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionRequestInProgress = true
                try {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (error: Exception) {
                    notificationPermissionRequestInProgress = false
                    throw error
                }
            } else {
                // Pre-Android 13 no runtime permission needed — start service and mark
                startAnonService(applicationContext)
                localPrefs.edit().putString("notif_prompt_wallet_id", walletId).apply()
            }
            return
        }

        // Already prompted for this wallet and still no permission — don't re-request system dialog.
        // Show explanation and guide user to app settings.
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.enable_notifications_title))
            .setMessage(getString(R.string.enable_notifications_explain))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    fun stopNotificationService() {
        // Stop directly; starting a foreground service with a "stop" action can race
        // native wallet close while the notification polling job is still running.
        stopService(Intent(applicationContext, AnonNeroService::class.java))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (notificationPermissionRequestInProgress) {
            Timber.tag(TAG).d("Skipping background wallet lock while notification permission is requested")
            return
        }
        walletSelectionUnlockInProgress.value = false
        val wipePrefs = getSharedPreferences(AnonConfig.APP_PREFERENCES, MODE_PRIVATE)
        if (
            wipePrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false) ||
            wipePrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false)
        ) return

        scope.launch {
            // Recheck after dispatching: a secure wipe may start between lifecycle callbacks.
            if (
                !wipePrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false) &&
                !wipePrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_REQUESTED, false)
            ) {
                walletState.enterBackgroundSync()
            }
        }
    }

    override fun onDestroy() {
        if (walletClearedReceiverRegistered) {
            unregisterReceiver(walletClearedReceiver)
            walletClearedReceiverRegistered = false
        }
        // Activity recreation/background transitions must not close the process-wide
        // wallet or dispose the shared Tor service. Persist/close wallet through its
        // own lifecycle instead of racing an IO coroutine against scope cancellation.
        scope.cancel()
        super.onDestroy()
    }
}