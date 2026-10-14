package io.anonero.util.backup

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.SimpleDateFormat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.di.provideWalletSharedPrefs
import io.anonero.model.Backup
import io.anonero.model.BackupMeta
import io.anonero.model.BackupPayload
import io.anonero.model.NeroKeyPayload
import io.anonero.model.NodeBackup
import io.anonero.model.WalletBackup
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.store.NodesRepository
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PREFERENCES
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NetworkMismatchException : Exception()
object BackupHelper {
    private const val BACKUP_VERSION = 2
    const val BUFFER = 2048
    private const val TAG = "BackUpHelper"
    val walletPrefs: SharedPreferences by inject(
        SharedPreferences::class.java, named(WALLET_PREFERENCES)
    )
    val nodeRepository: NodesRepository by inject(NodesRepository::class.java)

    suspend fun createBackUp(seedPassphrase: String, context: Context): String {
        val wallet = WalletManager.instance?.wallet ?: throw Exception("Wallet not found")
        val prefs = provideWalletSharedPrefs(context)


        val walletPayload = WalletBackup(
            address = wallet.address,
            seed = wallet.getSeed(seedPassphrase),
            restoreHeight = wallet.getRestoreHeight(),
            balanceAll = wallet.getBalanceAll(),
            numSubaddresses = wallet.numSubAddresses,
            numAccounts = wallet.getNumAccounts(),
            primaryAddress = wallet.address,
            isWatchOnly = wallet.isWatchOnly(),
            isSynchronized = wallet.isSynchronized,
            neroPayload = NeroKeyPayload.fromWallet(wallet),
        );


        val nodeBackup = NodeBackup(
            host = prefs.getString(NodeFields.RPC_HOST.value, "") ?: "",
            password = prefs.getString(NodeFields.RPC_PASSWORD.value, "") ?: "",
            username = prefs.getString(NodeFields.RPC_USERNAME.value, "") ?: "",
            rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort),
            networkType = AnonConfig.getNetworkType().toString(),
            isOnion = false
        )
        val backup = Backup(
            meta = BackupMeta(
                timestamp = System.currentTimeMillis(),
                network = AnonConfig.getNetworkType().toStringForBackUp(),
            ), node = nodeBackup, wallet = walletPayload,
            nodes = nodeRepository.getAll()
        )

        val backUpPayload = BackupPayload(
            version = BACKUP_VERSION, backup = backup
        )
        val json = Json.encodeToString(backUpPayload)

        val tmpBackupDir = File(context.cacheDir, "tmp_backup")
        if (tmpBackupDir.exists() && !tmpBackupDir.deleteRecursively()) {
            throw java.io.IOException("Unable to clear temporary backup directory")
        }
        if (!tmpBackupDir.mkdirs() && !tmpBackupDir.isDirectory) {
            throw java.io.IOException("Unable to create temporary backup directory")
        }

        val date = Date()
        val sdf = SimpleDateFormat("dd_MM_yyyy' 'HH_mm_a", Locale.getDefault())
        val timeStamp: String = sdf.format(date)
        val appName = context.getString(R.string.app_name)
        val backupFile = File(context.cacheDir, "${appName}_backup_$timeStamp.zip")
        val tmpBackupFile = File(tmpBackupDir, "anon.json")
        tmpBackupFile.writeText(json)

        val walletDir = File(context.filesDir, "wallets")
        if (!walletDir.isDirectory) {
            throw java.io.IOException("Wallet backup directory does not exist")
        }
        walletDir.copyRecursively(tmpBackupDir, overwrite = true)

        val files = tmpBackupDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.absolutePath }
            ?.toTypedArray()
            ?: throw java.io.IOException("Unable to list backup files")
        if (files.isEmpty()) {
            throw java.io.IOException("No files available for backup")
        }

        val backupCacheDir = File(context.cacheDir, "backup")
        if (!backupCacheDir.exists() && !backupCacheDir.mkdirs()) {
            throw java.io.IOException("Unable to create encrypted backup directory")
        }
        val backupFileEncrypted = File(backupCacheDir, "${appName}_backup_$timeStamp.anon")

        try {
            zip(files, backupFile.absolutePath)
            EncryptUtil.encryptFile(seedPassphrase, backupFile, backupFileEncrypted)
            return backupFileEncrypted.absolutePath
        } finally {
            tmpBackupDir.deleteRecursively()
            backupFile.delete()
        }
    }

    fun testBackUp(destinationDir: File): Boolean {
        val items = destinationDir.listFiles()?.toList()?.filter {
            (it.name.endsWith(".keys") || it.name.endsWith(".json"))
        }
        return items?.size == 2
    }

    private fun zip(files: Array<String>, zipFileName: String) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFileName))).use { output ->
            files.forEach { filePath ->
                val source = File(filePath)
                require(source.isFile) { "Backup entry is not a file" }
                output.putNextEntry(ZipEntry(source.name))
                BufferedInputStream(FileInputStream(source), BUFFER).use { input ->
                    input.copyTo(output, BUFFER)
                }
                output.closeEntry()
            }
        }
    }

    fun unZip(toUnzip: File, destinationDir: File) {
        val root = destinationDir.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException("Unable to create backup extraction directory")
        }
        if (!root.isDirectory) {
            throw java.io.IOException("Backup extraction target is not a directory")
        }
        val rootPrefix = root.path + File.separator

        ZipFile(toUnzip).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name.replace('\\', '/')
                if (entryName.isBlank() || entryName.startsWith("/") ||
                    entryName.startsWith("//") ||
                    entryName.matches(Regex("^[A-Za-z]:.*"))
                ) {
                    throw java.io.IOException("Invalid backup archive entry path")
                }

                val outputFile = File(root, entryName).canonicalFile
                if (outputFile.path != root.path && !outputFile.path.startsWith(rootPrefix)) {
                    throw java.io.IOException("Backup archive entry escapes extraction directory")
                }
                if (outputFile == root && !entry.isDirectory) {
                    throw java.io.IOException("Invalid backup archive file path")
                }

                if (entry.isDirectory) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw java.io.IOException("Unable to create backup directory")
                    }
                    if (!outputFile.isDirectory) {
                        throw java.io.IOException("Backup entry conflicts with a file")
                    }
                } else {
                    val parent = outputFile.parentFile
                        ?: throw java.io.IOException("Backup entry has no parent directory")
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw java.io.IOException("Unable to create backup entry directory")
                    }
                    if (!parent.isDirectory) {
                        throw java.io.IOException("Backup entry parent is not a directory")
                    }
                    zip.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                            input.copyTo(output, BUFFER)
                        }
                    }
                }
            }
        }
    }

    fun extractBackUp(backupPath: String, passPhrase: String): BackupPayload? {
        val context = AnonConfig.context?.applicationContext
            ?: throw IllegalStateException("Application context unavailable")
        val destFile = File(context.cacheDir, "backup.anon")
        val decryptedDestFile = File(context.cacheDir, "backup.zip")
        val extractDestination = File(context.cacheDir, "tmp_extract")

        try {
            val source = context.contentResolver.openInputStream(backupPath.toUri())
                ?: throw java.io.IOException("Unable to open selected backup")
            source.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, BUFFER)
                }
            }

            // Do not let a previous extraction supply files missing from this archive.
            if (extractDestination.exists() && !extractDestination.deleteRecursively()) {
                throw java.io.IOException("Unable to clear previous backup extraction")
            }
            EncryptUtil.decryptFile(passPhrase, destFile, decryptedDestFile)
            unZip(decryptedDestFile, extractDestination)

            val anonJson = File(extractDestination, "anon.json")
            if (!anonJson.isFile) {
                throw java.io.IOException("Backup metadata is missing")
            }
            val backup = Json.decodeFromString<BackupPayload>(anonJson.readText())
            if (backup.backup.meta.network != AnonConfig.getNetworkType().toStringForBackUp()) {
                throw NetworkMismatchException()
            }
            return backup
        } catch (e: Exception) {
            extractDestination.deleteRecursively()
            Timber.tag(TAG).e(e, "Unable to extract backup")
            throw e
        } finally {
            destFile.delete()
            decryptedDestFile.delete()
        }
    }

    suspend fun restoreBackUp(backupPayload: BackupPayload, passPhrase: String): Boolean {
        try {

            val context = AnonConfig.context?.applicationContext ?: return false
            val extractDestination = File(context.cacheDir, "tmp_extract")
            if (!extractDestination.isDirectory || !testBackUp(extractDestination)) {
                return false
            }

            val anonDir = AnonConfig.getDefaultWalletDir(context)
            if (!anonDir.exists() && !anonDir.mkdirs()) {
                return false
            }

            val v1Node = backupPayload.backup.node
            walletPrefs.edit(commit = true) {
                putString(NodeFields.RPC_HOST.value, v1Node.host)
                putInt(NodeFields.RPC_PORT.value, v1Node.rpcPort)
                putString(NodeFields.RPC_USERNAME.value, v1Node.username)
                putString(NodeFields.RPC_PASSWORD.value, v1Node.password)
                putString(NodeFields.RPC_NETWORK.value, v1Node.networkType)
            }

            val wallet = backupPayload.backup.wallet
            val nodes = backupPayload.backup.nodes;

            nodes.forEach {
                nodeRepository.addItem(it)
            }
            if (nodes.isEmpty() && v1Node.host.isNotEmpty()) {
                val node = Node().apply {
                    host = v1Node.host
                    rpcPort = v1Node.rpcPort
                    username = v1Node.username
                    password = v1Node.password
                    networkType = AnonConfig.getNetworkType()
                }
                nodeRepository.addItem(node)
            }
            walletPrefs.edit(commit = true) {
                putString(
                    PREFS_PASSPHRASE_HASH,
                    KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
                )
                putLong(RESTORE_HEIGHT, wallet.restoreHeight ?: 0L)
            }
            extractDestination.listFiles()?.forEach { entry ->
                if (!entry.isDirectory && !entry.name.contains("anon.json")) {
                    entry.copyTo(File(anonDir, entry.name), true)
                }
            }
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            return false
        }
    }

    fun cleanCacheDir() {
        val cacheDir = AnonConfig.context?.cacheDir ?: return

        // Remove only transient backup/restore files. Deleting cacheDir itself also
        // removes Tor state, diagnostic logs, and unrelated app caches; deleting the
        // backup subdirectory would also destroy encrypted backups already created.
        listOf("tmp_backup", "tmp_extract", "backup.anon", "backup.zip").forEach { name ->
            File(cacheDir, name).deleteRecursively()
        }
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") && it.name.contains("_backup_") }
            ?.forEach { it.delete() }
    }
}