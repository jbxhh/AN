package io.anonero

import android.content.Context
import io.anonero.model.NetworkType
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.pow

val jsonDecoder = Json { ignoreUnknownKeys = true }

object AnonConfig {

    const val NERO_KEY_PAYLOAD_VERSION = 1
    const val XMR_DECIMALS = 12
    val ONE_XMR = Math.round(10.0.pow(XMR_DECIMALS.toDouble()))
    const val MAX_LOG_SIZE = 250
    const val PREFS = "anonPref"
    const val APP_PREFERENCES = "anonero_prefs"
    const val PREF_WALLET_WIPE_PENDING = "wallet_wipe_pending"
    const val PREF_WALLET_WIPE_REQUESTED = "wallet_wipe_requested"
    const val PREF_ACTIVE_WALLET_NAME = "active_wallet_name"
    var context: AnonApplication? = null
    private var walletFound: Boolean = false

    const val EXPORT_OUTPUT_FILE = "export_wallet_outputs.out"
    const val IMPORT_OUTPUT_FILE = "import_wallet_outputs"

    const val EXPORT_KEY_IMAGE_FILE = "export_key_images.imgs"
    const val IMPORT_KEY_IMAGE_FILE = "import_key_images"

    const val EXPORT_UNSIGNED_TX_FILE = "export_unsigned_tx.utx"
    const val IMPORT_UNSIGNED_TX_FILE = "import_unsigned_tx"

    const val EXPORT_SIGNED_TX_FILE = "export_signed_tx.stx"
    const val IMPORT_SIGNED_TX_FILE = "import_signed_tx"


    fun getNetworkType(): NetworkType {
        if (BuildConfig.APPLICATION_ID.lowercase().contains("stagenet")) {
            return NetworkType.NetworkType_Stagenet
        }
        return NetworkType.NetworkType_Mainnet
    }

    val viewOnly: Boolean get() = BuildConfig.VIEW_ONLY

    fun getActiveWalletName(context: Context): String {
        return context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE_WALLET_NAME, "anon")
            ?.takeIf { it.isNotBlank() && File(it).name == it && it != "." && it != ".." }
            ?: "anon"
    }

    fun walletPreferenceKey(context: Context, key: String): String {
        return "${getActiveWalletName(context)}::$key"
    }

    fun getDefaultWalletFile(context: Context): File {
        return File(getDefaultWalletDir(context), getActiveWalletName(context))
    }

    fun setActiveWallet(context: Context, walletName: String) {
        require(walletName.isNotBlank() && File(walletName).name == walletName &&
            walletName != "." && walletName != "..") {
            "Invalid wallet name"
        }
        context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ACTIVE_WALLET_NAME, walletName)
            .commit()
    }

    /**
     * Move the pre-multi-wallet authentication and scan preferences into the
     * default wallet namespace. All other wallet and network preferences remain
     * untouched.
     */
    fun migrateLegacyWalletPreferences(context: Context) {
        if (getActiveWalletName(context) != "anon") return

        val preferences = context.getSharedPreferences("walletPref", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        val keys = listOf(
            "pinHash",
            "passPhraseHash",
            "restoreHeight",
            "anonero_force_fresh_wallet_scan",
        )
        keys.forEach { key ->
            val scopedKey = walletPreferenceKey(context, key)
            if (!preferences.contains(key) || preferences.contains(scopedKey)) return@forEach

            when (key) {
                "pinHash", "passPhraseHash" -> {
                    preferences.getString(key, null)?.let { editor.putString(scopedKey, it) }
                }
                "restoreHeight" -> editor.putLong(scopedKey, preferences.getLong(key, 0L))
                "anonero_force_fresh_wallet_scan" -> {
                    editor.putBoolean(scopedKey, preferences.getBoolean(key, false))
                }
            }
        }
        editor.commit()
    }

    fun getWalletFiles(context: Context): List<File> {
        val walletDir = getDefaultWalletDir(context)
        return walletDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".keys") }
            .map { File(walletDir, it.name.removeSuffix(".keys")) }
            .filter { it.isFile }
            .sortedWith(
                compareBy<File> { if (it.name == "anon") 0 else 1 }
                    .thenBy { it.name.substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    fun createNewWalletFile(context: Context): File {
        val walletDir = getDefaultWalletDir(context)
        var index = 2
        var candidate: File
        do {
            candidate = File(walletDir, "wallet_$index")
            index++
        } while (candidate.exists() || File(candidate.path + ".keys").exists())

        setActiveWallet(context, candidate.name)
        return candidate
    }


    fun getTorConfig(scope: CoroutineScope): TorRuntime.Environment {
        val torDir = File(context?.filesDir, "tor")
        torDir.mkdirs()
        val cacheDir = File(context?.cacheDir, "tor")
        cacheDir.mkdirs()
        return TorRuntime.Environment.Builder(
            workDirectory = torDir,
            cacheDirectory = cacheDir,
            loader = ResourceLoaderTorExec::getOrCreate,
        )
    }


    fun isWalletFileExist(): Boolean {
        return walletFound
    }


    fun getLogFile(context: Context): File {
        val logDir = File(context.applicationContext.cacheDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs() // Create the directory if it doesn't exist
        }
        val logFile = File(logDir, "anon_log")
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        return logFile
    }

    fun getDefaultWalletDir(context: Context): File {
        val walletDir = File(context.applicationContext.filesDir, "wallets")
        if (!walletDir.exists()) {
            walletDir.mkdirs()
        }
        return walletDir
    }

    fun initWalletState() {
        MainScope().launch(Dispatchers.IO) {
            migrateLegacyWalletPreferences(context!!)
            walletFound = getWalletFiles(context!!).isNotEmpty()
        }
    }

    fun clearSpendCacheFiles(context: Context) {
        val files = arrayListOf(
            EXPORT_OUTPUT_FILE,
            IMPORT_OUTPUT_FILE,

            EXPORT_KEY_IMAGE_FILE,
            IMPORT_KEY_IMAGE_FILE,

            EXPORT_UNSIGNED_TX_FILE,
            IMPORT_UNSIGNED_TX_FILE,

            EXPORT_SIGNED_TX_FILE,
            IMPORT_SIGNED_TX_FILE
        );
        context.cacheDir.listFiles()?.forEach { file ->
            if (files.any { file.name.contains(it) }) {
                file.delete()
            }
        }
    }

    fun disposeState() {
        walletFound = false
    }
}