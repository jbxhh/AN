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
import java.io.IOException
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
        if (tmpBackupDir.exists()) {
            tmpBackupDir.deleteRecursively()
        }
        val date = Date()
        val sdf = SimpleDateFormat("dd_MM_yyyy' 'HH_mm_a", Locale.getDefault())
        val timeStamp: String = sdf.format(date)
        val backupFile = File(context.cacheDir, "${context.getString(R.string.app_name)}_backup_$timeStamp.zip")
        tmpBackupDir.mkdirs()
        val tmpBackupFile = File(tmpBackupDir, "anon.json")
        tmpBackupFile.writeText(json)
        val walletDir = File(context.filesDir, "wallets")
        walletDir.copyRecursively(tmpBackupDir, true)
        val files = tmpBackupDir.walkTopDown()
            .filter { it.isFile }
            .toList()
            .toTypedArray()
        if (files.isEmpty()) {
            tmpBackupDir.deleteRecursively()
            throw IOException("No wallet files available for backup")
        }

        val backupCacheDir = File(context.cacheDir, "backup")
        if (!backupCacheDir.exists() && !backupCacheDir.mkdirs()) {
            throw IOException("Unable to create encrypted backup directory")
        }
        val backupFileEncrypted =
            File(backupCacheDir, "${context.getString(R.string.app_name)}_backup_$timeStamp.anon")

        try {
            zip(files, backupFile.absolutePath, tmpBackupDir)
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

    private fun zip(files: Array<File>, zipFileName: String, baseDir: File) {
        val basePath = baseDir.canonicalFile.toPath()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFileName))).use { output ->
                val buffer = ByteArray(BUFFER)
                files.forEach { file ->
                    val relativePath = basePath.relativize(file.canonicalFile.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                    if (relativePath.isBlank() || relativePath.startsWith("../")) {
                        throw IOException("Invalid wallet backup path")
                    }
                    output.putNextEntry(ZipEntry(relativePath))
                    BufferedInputStream(FileInputStream(file), BUFFER).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.closeEntry()
                }
            }
        } catch (e: Exception) {
            File(zipFileName).delete()
            throw e
        }
    }

    fun unZip(toUnzip: File, destinationDir: File) {
        val root = destinationDir.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create backup extraction directory")
        }
        ZipFile(toUnzip).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val output = File(root, entry.name).canonicalFile
                val rootPrefix = root.path + File.separator
                if (output.path == root.path || !output.path.startsWith(rootPrefix)) {
                    throw IOException("Invalid backup archive path")
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw IOException("Unable to create backup directory")
                    }
                } else {
                    output.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Unable to create backup directory")
                        }
                    }
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { stream -> input.copyTo(stream) }
                    }
                }
            }
        }
    }

    fun extractBackUp(backupPath: String, passPhrase: String): BackupPayload? {
        val context = AnonConfig.context ?: throw IOException("Application context unavailable")
        val destFile = File(context.cacheDir, "backup.anon")
        val decryptedDestFile = File(context.cacheDir, "backup.zip")
        val extractDestination = File(context.cacheDir, "tmp_extract")
        val inputStream = context.contentResolver.openInputStream(backupPath.toUri())
            ?: throw IOException("Unable to open backup file")
        inputStream.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        val backup: BackupPayload
        try {
            EncryptUtil.decryptFile(passPhrase, destFile, decryptedDestFile)
            extractDestination.deleteRecursively()
            unZip(decryptedDestFile, extractDestination)

            val anonJson = File(extractDestination, "anon.json")
            val json = anonJson.readText()
            backup = Json.decodeFromString<BackupPayload>(json)
            if(backup.backup.meta.network != AnonConfig.getNetworkType().toStringForBackUp()){
                 throw NetworkMismatchException()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            throw e;
        }
        return backup;
    }

    suspend fun restoreBackUp(backupPayload: BackupPayload, passPhrase: String): Boolean {
        try {

            val extractDestination = File(AnonConfig.context?.cacheDir, "tmp_extract")
            if (!extractDestination.exists() || !testBackUp(extractDestination)) {
                return false
            }

            val anonDir = AnonConfig.getDefaultWalletDir(AnonConfig.context!!)

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
        AnonConfig.context?.cacheDir?.deleteRecursively()
    }
}