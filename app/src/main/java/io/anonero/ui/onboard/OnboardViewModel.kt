package io.anonero.ui.onboard

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.NeroKeyPayload
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.services.PREF_FORCE_FRESH_WALLET_SCAN
import io.anonero.services.WalletState
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.encodePinHash
import io.anonero.util.RESTORE_HEIGHT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber


enum class Mode {
    RESTORE,
    CREATE
}

private const val TAG = "OnboardViewModel"

class OnboardViewModel(
    private val prefs: SharedPreferences,
    private val walletState: WalletState
) : ViewModel() {

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
            prefs.edit(commit = true) {
                putString(PREFS_PIN_HASH, encodePinHash(pin))
                // A newly created wallet must not inherit another wallet's restore height.
                putLong(RESTORE_HEIGHT, 0L)
            }
            walllet = anonWallet
            prepareFirstReceiveAddress(anonWallet)
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
            prefs.edit(commit = true) {
                putString("passPhraseHash", crazyPass)
                putString(PREFS_PIN_HASH, encodePinHash(pin))
                putLong(RESTORE_HEIGHT, neroKeyPayload!!.restoreHeight)
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
        if (restorePayload == null) return

        val context = AnonConfig.context!!.applicationContext
        val walletFile = AnonConfig.getDefaultWalletFile(context)
        AnonConfig.getDefaultWalletDir(context).deleteRecursively()
        AnonConfig.getDefaultWalletDir(context).mkdirs()
        delay(100)
        val anonWallet = if (restorePayload!!.seed.size == 25) {
            WalletManager.instance?.recoveryWallet(
                walletFile,
                pin,
                restorePayload!!.seed.joinToString(" "),
                passPhrase,
                restorePayload!!.restoreHeight ?: 0
            )
        } else if (restorePayload!!.seed.size == 16) {
            WalletManager.instance?.recoveryWalletPolyseed(
                walletFile,
                pin,
                restorePayload!!.seed.joinToString(" "),
                passPhrase,
            )
        } else {
            null
        }
        if (anonWallet == null) {
            throw Exception("unable to create wallet from seed, invalid seed length ${restorePayload!!.seed.size}")
        }
        delay(1000)
        restorePayload?.restoreHeight?.let {
            anonWallet?.setRestoreHeight(it)
        }
        anonWallet?.store()
        anonWallet?.store(walletFile.path)
        delay(100)
        if (anonWallet?.status?.isOk != true) {
            walletFile.delete()
            throw CancellationException("unable to create wallet ${anonWallet?.status?.errorString}")
        }
        val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
        // For a standard Monero seed, persist the exact height chosen on the restore
        // screen. Polyseed has no height field, so fall back to the native wallet value.
        val actualRestoreHeight = (
            restorePayload?.restoreHeight ?: anonWallet.getRestoreHeight()
        ).coerceAtLeast(0L)
        prefs.edit(commit = true) {
            putString(PREFS_PASSPHRASE_HASH, crazyPass)
            putString(PREFS_PIN_HASH, encodePinHash(pin))
            putLong(RESTORE_HEIGHT, actualRestoreHeight)
            // Mark this seed restore so wallet startup preserves the selected height
            // and queues its first native scan from that height after the daemon is synced.
            putBoolean(PREF_FORCE_FRESH_WALLET_SCAN, true)
        }
        // Generate the local receive child address before Home starts node init/scan.
        prepareFirstReceiveAddress(anonWallet)
        // Keep the recovered wallet managed by WalletManager. Onboarding launches
        // AnonWalletHandler.startService() on Home; closing here clears WalletManager.wallet,
        // so startService() returns before it can apply the saved node and connect.
        delay(500)
    }

 
    /**
     * Prepare the first receive child locally before node initialization starts.
     * Subaddress creation does not need daemon sync; doing it here avoids racing
     * the native wallet initialization/refresh thread on the first wallet screen.
     */
    private suspend fun prepareFirstReceiveAddress(wallet: Wallet) {
        withContext(Dispatchers.IO) {
            try {
                walletState.publishWalletSnapshot(wallet)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to publish the initial local wallet snapshot")
            }
        }

        try {
            val address = walletState.ensureNextReceiveAddress()
            Timber.tag(TAG).i(
                "Prepared initial receive subaddress index=%d",
                address.addressIndex
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do not fail wallet creation/restoration for a transient address error.
            // ReceiveScreen retries until the usable child address is available.
            Timber.tag(TAG).w(e, "Initial receive subaddress not ready; screen will retry")
        }
    }

}
