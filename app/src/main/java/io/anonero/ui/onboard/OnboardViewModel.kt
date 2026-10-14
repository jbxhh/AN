package io.anonero.ui.onboard

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.NeroKeyPayload
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.RESTORE_HEIGHT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException


enum class Mode {
    RESTORE,
    CREATE
}

private const val TAG = "OnboardViewModel"

class OnboardViewModel(private val prefs: SharedPreferences) : ViewModel() {

    private var mode = Mode.CREATE
    private var passPhrase = ""
    private var walllet: Wallet? = null
    private var neroKeyPayload: NeroKeyPayload? = null
    private var restorePayload: RestorePayload? = null

    fun setMode(mode: Mode) = run { this.mode = mode }

    fun getMode() = mode

    fun getSeed() = walllet?.getSeed(passPhrase) ?: ""


    suspend fun create(pin: String) {
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            val walletFile = AnonConfig.getDefaultWalletFile(context)
            AnonConfig.getDefaultWalletDir(context).deleteRecursively()
            AnonConfig.getDefaultWalletDir(context).mkdirs()
            val anonWallet = WalletManager.instance?.createWallet(
                walletFile,
                pin,
                passPhrase,
                "English",
                1,
            )
            anonWallet?.store()
            delay(100)
            if (anonWallet?.status?.isOk != true) {
                walletFile.delete()
                throw CancellationException(context.getString(R.string.unable_to_create_wallet))
            }
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit {
                putString(PREFS_PASSPHRASE_HASH, crazyPass)
            }

            //adds padding to the pin if it's less than 32 bytes
            val pinHash = CrazyPassEncoder.encode(
                pin.toByteArray().let { bytes ->
                    if (bytes.size < 32) {
                        bytes + ByteArray(32 - bytes.size)
                    } else bytes
                }
            )
            prefs.edit {
                putString(PREFS_PIN_HASH, pinHash)
            }
            walllet = anonWallet
            delay(500)
        }
    }


    suspend fun createViewOnly(pin: String) {
        if (neroKeyPayload == null) return
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            val walletFile = AnonConfig.getDefaultWalletFile(context)
            AnonConfig.getDefaultWalletDir(context).deleteRecursively()
            AnonConfig.getDefaultWalletDir(context).mkdirs()
            val anonWallet = WalletManager.instance?.createWalletWithKeys(
                walletFile,
                pin,
                language = "English",
                restoreHeight = neroKeyPayload!!.restoreHeight,
                viewKeyString = neroKeyPayload!!.privateViewKey,
                addressString = neroKeyPayload!!.primaryAddress,
                spendKeyString = ""
            )
            anonWallet?.store()
            delay(100)
            if (anonWallet?.status?.isOk != true) {
                walletFile.delete()
                throw CancellationException(context.getString(R.string.unable_to_create_wallet))
            }
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit {
                putString(PREFS_PASSPHRASE_HASH, crazyPass)
            }
            walllet = anonWallet
            delay(500)
        }
    }

    fun setPassPhrase(it: String) {
        this.passPhrase = it
    }

    fun setNeroPayload(it: NeroKeyPayload) {
        neroKeyPayload = it
    }

    fun setRestorePayload(it: RestorePayload) {
        restorePayload = it
    }

    fun getRestorePayload(): RestorePayload? {
        return restorePayload
    }

    suspend fun restoreFromSeed(pin: String) {
        val payload = restorePayload ?: return
        if (payload.seed.size != 16 && payload.seed.size != 25) {
            throw CancellationException("Invalid recovery phrase length: ${payload.seed.size}")
        }

        val context = AnonConfig.context?.applicationContext
            ?: throw IllegalStateException("Application context unavailable")
        val activeWallet = WalletManager.instance?.wallet
        if (activeWallet != null) {
            throw CancellationException("Close the current wallet before restoring another wallet")
        }

        val walletDir = File(context.filesDir, "wallets")
        val stagingDir = File(context.filesDir, "wallets.restore-staging")
        val previousDir = File(context.filesDir, "wallets.restore-backup")

        // Recover a previous transaction if the process was interrupted between
        // renaming the old and newly restored wallet directories.
        if (!walletDir.exists() && previousDir.exists()) {
            if (!previousDir.renameTo(walletDir)) {
                throw IOException("Unable to recover the existing wallet directory")
            }
        }

        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            throw IOException("Unable to create a temporary wallet directory")
        }

        val candidateFile = File(stagingDir, "anon")
        var candidateWallet: Wallet? = null
        try {
            candidateWallet = when (payload.seed.size) {
                16 -> WalletManager.instance?.recoveryWalletPolyseed(
                    candidateFile,
                    pin,
                    payload.seed.joinToString(" "),
                    passPhrase
                )
                25 -> WalletManager.instance?.recoveryWallet(
                    candidateFile,
                    pin,
                    payload.seed.joinToString(" "),
                    passPhrase,
                    payload.restoreHeight ?: 0L
                )
                else -> null
            } ?: throw CancellationException("Unable to create wallet from recovery phrase")

            payload.restoreHeight?.let { candidateWallet.setRestoreHeight(it) }
            if (!candidateWallet.store() || !candidateWallet.store(candidateFile.path)) {
                throw CancellationException("Unable to save the restored wallet")
            }
            val status = candidateWallet.status
            if (!status.isOk) {
                throw CancellationException("Unable to restore wallet: ${status.errorString}")
            }
            if (!candidateWallet.close()) {
                candidateWallet = null
                throw CancellationException("Unable to close the temporary restored wallet")
            }
            candidateWallet = null

            // Only replace the current wallet after the candidate has recovered,
            // passed native wallet status checks, been saved and closed.
            previousDir.deleteRecursively()
            val hadPreviousWallet = walletDir.exists()
            if (hadPreviousWallet && !walletDir.renameTo(previousDir)) {
                throw IOException("Unable to preserve the existing wallet before restore")
            }
            if (!stagingDir.renameTo(walletDir)) {
                if (hadPreviousWallet && previousDir.exists()) previousDir.renameTo(walletDir)
                throw IOException("Unable to install the restored wallet")
            }
            previousDir.deleteRecursively()

            val crazyPass = KeyStoreHelper.getCrazyPass(context, passPhrase)
            prefs.edit(commit = true) {
                putString(PREFS_PASSPHRASE_HASH, crazyPass)
                payload.restoreHeight?.let { putLong(RESTORE_HEIGHT, it) }
            }
        } catch (e: Exception) {
            candidateWallet?.let { runCatching { it.close() } }
            throw e
        } finally {
            stagingDir.deleteRecursively()
        }
    }

}
