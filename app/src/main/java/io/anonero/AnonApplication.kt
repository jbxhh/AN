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
        val detectedCrash = capturePreviousProcessExitInfo()
        val crashPrefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
        val diagnosticMode = detectedCrash || crashPrefs.getBoolean("diagnostic_mode", false)

        AnonConfig.context = this
        val interruptedWalletWipe = recoverInterruptedWalletWipeFiles()
        AnonConfig.initWalletState()
        startKoin {
            androidContext(this@AnonApplication)
            modules(appModule)
        }

        // Install persistent logging before wallet/JNI initialization, which may itself crash.
        plantLog()
        if (interruptedWalletWipe) finishInterruptedWalletWipeRecovery()
        if (diagnosticMode) {
            // Do not re-enter a native crash loop before the user can copy the report.
            initNotificationChannels()
            Timber.tag(TAG).e("Crash diagnostic mode active; skipping wallet and Tor initialization")
            return
        }

        initConfigs()
        val torService: TorService = get()
        val prefs: SharedPreferences = get(named(WALLET_PREFERENCES))
        val host = prefs.getString(NodeFields.RPC_HOST.value, "")
        if (!host.isNullOrEmpty()) {
            torService.start()
        }
        AnonConfig.clearSpendCacheFiles(this)
    }


    /**
     * A force-stop can interrupt the native wallet close before the regular wipe
     * reaches preference cleanup. A new process has no live native wallet handle,
     * so finish removing the wallet files and authentication preferences first.
     */
    private fun recoverInterruptedWalletWipeFiles(): Boolean {
        val recoveryPrefs = getSharedPreferences(AnonConfig.APP_PREFERENCES, MODE_PRIVATE)
        if (!recoveryPrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)) {
            return false
        }

        val walletDir = java.io.File(filesDir, "wallets")
        val removed = runCatching {
            !walletDir.exists() || walletDir.deleteRecursively()
        }.getOrDefault(false)
        val preferencesCleared = runCatching {
            getSharedPreferences(AnonConfig.PREFS, MODE_PRIVATE).edit().clear().commit()
        }.getOrDefault(false)

        if (!removed || java.io.File(walletDir, "anon").exists() || !preferencesCleared) {
            android.util.Log.e(TAG, "Interrupted wallet wipe is still pending; startup must not expose the old PIN screen")
        }
        // Keep the marker until nodes/logs are cleaned later in onCreate.
        return true
    }

    private fun finishInterruptedWalletWipeRecovery() {
        val recoveryPrefs = getSharedPreferences(AnonConfig.APP_PREFERENCES, MODE_PRIVATE)
        if (!recoveryPrefs.getBoolean(AnonConfig.PREF_WALLET_WIPE_PENDING, false)) return

        val walletFile = java.io.File(filesDir, "wallets/anon")
        if (walletFile.exists()) {
            Timber.tag(TAG).e("Wallet file remains after interrupted wipe; keeping recovery marker")
            return
        }

        try {
            get<NodesRepository>().clearAll()
            get<LogRepository>().clear()
            AnonConfig.disposeState()
            val walletPreferences = getSharedPreferences(AnonConfig.PREFS, MODE_PRIVATE)
            check(walletPreferences.all.isEmpty()) {
                "Wallet authentication preferences were not cleared during recovery"
            }
            check(recoveryPrefs.edit().remove(AnonConfig.PREF_WALLET_WIPE_PENDING).commit()) {
                "Unable to clear interrupted wallet wipe marker"
            }
            Timber.tag(TAG).i("Completed interrupted wallet wipe during startup")
        } catch (e: Exception) {
            // Keep the marker so the next startup retries instead of allowing the old PIN flow.
            Timber.tag(TAG).e(e, "Interrupted wallet wipe cleanup failed; recovery will retry on next startup")
        }
    }

    /**
     * On Android 11+, read the previous process exit reason and native tombstone when
     * available. Enter recovery mode after a detected crash so startup does not crash
     * again before the report can be shown.
     */
    private fun capturePreviousProcessExitInfo(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return try {
            val prefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val latest = activityManager
                .getHistoricalProcessExitReasons(packageName, 0, 10)
                .maxByOrNull { it.timestamp } ?: return false

            if (latest.timestamp <= prefs.getLong("last_exit_info_timestamp", 0L)) return false

            val reasonText = when (latest.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (SIGSEGV/SIGABRT)"
                ApplicationExitInfo.REASON_CRASH -> "Java crash"
                ApplicationExitInfo.REASON_ANR -> "Application Not Responding (ANR)"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Application initialization failure"
                else -> null
            }

            if (reasonText == null) {
                prefs.edit().putLong("last_exit_info_timestamp", latest.timestamp).commit()
                return false
            }

            val previousReport = prefs.getString("last_crash", null)
            val previousReportTimestamp = prefs.getLong("last_crash_timestamp", 0L)
            val javaReportAlreadyCaptured = latest.reason == ApplicationExitInfo.REASON_CRASH &&
                !previousReport.isNullOrBlank() &&
                (previousReportTimestamp == 0L ||
                    kotlin.math.abs(latest.timestamp - previousReportTimestamp) < 60_000L)

            if (javaReportAlreadyCaptured) {
                prefs.edit()
                    .putLong("last_exit_info_timestamp", latest.timestamp)
                    .putBoolean("diagnostic_mode", true)
                    .commit()
                return true
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
                .putBoolean("diagnostic_mode", true)
                .commit()
            true
        } catch (_: Throwable) {
            // Diagnostic collection must never prevent the app from starting.
            false
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
            val recentLogs = try {
                val logFile = AnonConfig.getLogFile(this)
                if (logFile.isFile) logFile.readBytes().takeLast(8_000).toByteArray().decodeToString()
                else ""
            } catch (_: Throwable) {
                ""
            }
            val report = buildString {
                appendLine("ANONERO crash report")
                appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
                appendLine("Thread: ${t.name}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine()
                append(sw.toString())
                if (recentLogs.isNotBlank()) {
                    appendLine()
                    appendLine("RECENT APP LOGS")
                    appendLine(recentLogs)
                }
            }
            val crashPrefs = getSharedPreferences("crash_prefs", 0)
            crashPrefs.edit()
                .putString("last_crash", report)
                .putLong("last_crash_timestamp", System.currentTimeMillis())
                .putBoolean("diagnostic_mode", true)
                .commit()
        } catch (_: Throwable) {}
        defaultHandler?.uncaughtException(t, e)
    }

}
