package io.anonero

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Build
import io.anonero.di.appModule
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.services.TorService
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.ui.util.AnonLogTree
import io.anonero.util.WALLET_PREFERENCES
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.qualifier.named
import timber.log.Timber
import timber.log.Timber.DebugTree


const val FOREGROUND_CHANNEL = "anon_foreground"
const val TX_CHANNEL = "anon_transactions"
private const val TAG = "AnonApplication"

class AnonApplication : Application(), Thread.UncaughtExceptionHandler {

    private lateinit var anonLogTree: AnonLogTree
    private val defaultHandler =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        // Install crash capture before initializing Koin, wallet JNI, or services.
        Thread.setDefaultUncaughtExceptionHandler(this)
        AnonConfig.context = this
        AnonConfig.initWalletState()
        startKoin {
            androidContext(this@AnonApplication)
            modules(appModule)
        }
        initConfigs()
        plantLog()
        val torService: TorService = get()
        val prefs: SharedPreferences = get(named(WALLET_PREFERENCES))
        val host = prefs.getString(NodeFields.RPC_HOST.value, "")
        if(!host.isNullOrEmpty()){
            torService.start()
        }
        AnonConfig.clearSpendCacheFiles(this)
    }

    private fun plantLog() {

        anonLogTree = AnonLogTree(AnonConfig.getLogFile(applicationContext), get<LogRepository>())

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
            Timber.plant(anonLogTree)
        } else {
            Timber.plant(anonLogTree)
        }
        Timber.tag(TAG).i("BUILD_TYPE: %s", BuildConfig.BUILD_TYPE)
        Timber.tag(TAG).i("FLAVOR: %s", BuildConfig.FLAVOR)
        Timber.tag(TAG).i("APPLICATION_ID: %s", BuildConfig.APPLICATION_ID)
        Timber.tag(TAG).i("MONERO_NETWORK: %s", AnonConfig.getNetworkType())
        Timber.tag(TAG)
            .i("VERSION: ${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}\n\n")
    }

    private fun initConfigs() {
        //initialize wallet manager
        WalletManager.instance?.init()
        initNotificationChannels()
    }

    private fun initNotificationChannels() {
        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL,
            "AnonNero Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(foregroundChannel)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TX_CHANNEL,
                "Incoming Transactions",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    override fun onTerminate() {
        val torService: TorService = get()
        torService.dispose()
        anonLogTree.cleanup()
        super.onTerminate()
    }

        override fun uncaughtException(t: Thread, e: Throwable) {
        Timber.tag(TAG).e(e)
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val report = buildString {
                appendLine("ANONERO crash report")
                appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
                appendLine("Thread: ${t.name}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine()
                append(sw.toString())
            }
            getSharedPreferences("crash_prefs", 0).edit()
                .putString("last_crash", report).commit()
        } catch (_: Throwable) {}
        defaultHandler?.uncaughtException(t, e)
    }

}
