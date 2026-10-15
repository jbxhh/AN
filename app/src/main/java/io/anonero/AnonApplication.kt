package io.anonero

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
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
        capturePreviousProcessExitInfo()
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

    /**
     * Android 11+ keeps the previous process exit reason and, for native crashes,
     * often a tombstone/backtrace. Java uncaught exceptions are already captured by
     * uncaughtException(), so preserve that more specific stack when it matches.
     */
    private fun capturePreviousProcessExitInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val prefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
            val latest = activityManager
                .getHistoricalProcessExitReasons(packageName, 0, 10)
                .maxByOrNull { it.timestamp } ?: return

            if (latest.timestamp <= prefs.getLong("last_exit_info_timestamp", 0L)) return

            val reasonText = when (latest.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (SIGSEGV/SIGABRT)"
                ApplicationExitInfo.REASON_CRASH -> "Java crash"
                ApplicationExitInfo.REASON_ANR -> "Application Not Responding (ANR)"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Application initialization failure"
                else -> null
            }

            if (reasonText == null) {
                prefs.edit().putLong("last_exit_info_timestamp", latest.timestamp).commit()
                return
            }

            val previousReport = prefs.getString("last_crash", null)
            val previousReportTimestamp = prefs.getLong("last_crash_timestamp", 0L)
            val javaReportAlreadyCaptured = latest.reason == ApplicationExitInfo.REASON_CRASH &&
                !previousReport.isNullOrBlank() &&
                (previousReportTimestamp == 0L ||
                    kotlin.math.abs(latest.timestamp - previousReportTimestamp) < 60_000L)

            if (javaReportAlreadyCaptured) {
                prefs.edit().putLong("last_exit_info_timestamp", latest.timestamp).commit()
                return
            }

            val trace = try {
                latest.traceInputStream?.bufferedReader()?.use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(2048)
                    while (output.length < 12_000) {
                        val count = reader.read(
                            buffer,
                            0,
                            minOf(buffer.size, 12_000 - output.length)
                        )
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }.orEmpty()
            } catch (_: Throwable) {
                ""
            }

            val recentLogs = try {
                val logFile = AnonConfig.getLogFile(this)
                if (logFile.isFile) logFile.readBytes().takeLast(8_000).toByteArray().decodeToString()
                else ""
            } catch (_: Throwable) {
                ""
            }

            val report = buildString {
                appendLine("ANONERO previous process exit report")
                appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date(latest.timestamp))}")
                appendLine("Exit reason: $reasonText")
                appendLine("Process: ${latest.processName}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Exit status: ${latest.status}")
                latest.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
                if (trace.isNotBlank()) {
                    appendLine()
                    appendLine("SYSTEM TRACE")
                    appendLine(trace)
                }
                if (recentLogs.isNotBlank()) {
                    appendLine()
                    appendLine("RECENT APP LOGS")
                    appendLine(recentLogs)
                }
            }

            prefs.edit()
                .putString("last_crash", report)
                .putLong("last_crash_timestamp", latest.timestamp)
                .putLong("last_exit_info_timestamp", latest.timestamp)
                .commit()
        } catch (_: Throwable) {
            // Crash diagnostics must never prevent the app from starting.
        }
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
            val crashPrefs = getSharedPreferences("crash_prefs", 0)
            crashPrefs.edit()
                .putString("last_crash", report)
                .putLong("last_crash_timestamp", System.currentTimeMillis())
                .commit()
        } catch (_: Throwable) {}
        defaultHandler?.uncaughtException(t, e)
    }

}
