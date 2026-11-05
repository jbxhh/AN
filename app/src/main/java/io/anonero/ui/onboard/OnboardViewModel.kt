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
import io.anonero.services.PREF_NEW_WALLET_SCAN_FROM_NODE_HEIGHT
import io.anonero.services.WalletState
import io.anonero.util.AppPinStore
import io.anonero.util.AppPassphraseStore
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.PREFS_PINLESS_WALLET
import io.anonero.util.encodePinHash
import io.anonero.util.RESTORE_HEIGHT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID


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


    suspend fun create(
        pin: String,
        prepareInitialReceiveAddress: Boolean = true
    ) {
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            passPhrase = AppPassphraseStore.get(context) ?: passPhrase
            val walletFile = AnonConfig.getDefaultWalletFile(context)
            AnonConfig.getDefaultWalletDir(context).mkdirs()
            // Reuse an already-unlocked wallet PIN without prompting. If none is
            // available, use a temporary random password protected by Android Keystore;
            // the user chooses a PIN the first time they open it from the wallet list.
            val walletPassword = pin.ifEmpty { UUID.randomUUID().toString() }
            val anonWallet = WalletManager.instance?.createWallet(
                walletFile,
                walletPassword,
                passPhrase,
                "English",
                1,
            )
            if (anonWallet?.status?.isOk != true) {
                walletFile.delete()
                throw CancellationException(context.getString(R.string.unable_to_create_wallet))
            }
            AppPinStore.rememberIfMissing(context, pin)
            AppPassphraseStore.rememberIfMissing(context, passPhrase)
            // A newly created wallet has no historical activity. Wait for its selected
            // daemon to report the live chain height, then start scanning from that tip.
            val initialRestoreHeight = anonWallet.getRestoreHeight().coerceAtLeast(0L)
            anonWallet.setRestoreHeight(initialRestoreHeight)
            check(anonWallet.store()) { "Unable to store new wallet restore height" }
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit(commit = true) {
                putString(AnonConfig.walletPreferenceKey(context, PREFS_PASSPHRASE_HASH), crazyPass)
                if (pin.isEmpty()) {
                    val walletName = AnonConfig.getActiveWalletName(context)
                    KeyStoreHelper.saveWalletUserPass(context, walletName, walletPassword)
                    check(
                        runCatching { KeyStoreHelper.loadWalletUserPass(context, walletName) == walletPassword }
                            .getOrDefault(false)
                    ) { "Unable to securely store temporary wallet password" }
                    remove(AnonConfig.walletPreferenceKey(context, PREFS_PIN_HASH))
                    putBoolean(AnonConfig.walletPreferenceKey(context, PREFS_PINLESS_WALLET), true)
                } else {
                    putString(AnonConfig.walletPreferenceKey(context, PREFS_PIN_HASH), encodePinHash(pin))
                    remove(AnonConfig.walletPreferenceKey(context, PREFS_PINLESS_WALLET))
                }
                putLong(AnonConfig.walletPreferenceKey(context, RESTORE_HEIGHT), initialRestoreHeight)
                remove(AnonConfig.walletPreferenceKey(context, PREF_FORCE_FRESH_WALLET_SCAN))
                putBoolean(
                    AnonConfig.walletPreferenceKey(context, PREF_NEW_WALLET_SCAN_FROM_NODE_HEIGHT),
                    true
                )
            }
            walllet = anonWallet
            if (prepareInitialReceiveAddress) {
                prepareFirstReceiveAddress(anonWallet)
            }
        }
    }


    suspend fun createViewOnly(pin: String) {
        if (neroKeyPayload == null) return
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            passPhrase = AppPassphraseStore.get(context) ?: passPhrase
            val walletFile = AnonConfig.getDefaultWalletFile(context)
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
            AppPinStore.rememberIfMissing(context, pin)
            AppPassphraseStore.rememberIfMissing(context, passPhrase)
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit(commit = true) {
                putString(AnonConfig.walletPreferenceKey(context, "passPhraseHash"), crazyPass)
                putString(AnonConfig.walletPreferenceKey(context, PREFS_PIN_HASH), encodePinHash(pin))
                remove(AnonConfig.walletPreferenceKey(context, PREFS_PINLESS_WALLET))
                putLong(AnonConfig.walletPreferenceKey(context, RESTORE_HEIGHT), neroKeyPayload!!.restoreHeight)
            }
            walllet = anonWallet
            prepareFirstReceiveAddress(anonWallet)
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

    suspend fun restoreFromSeed(
        pin: String,
        passphraseOverride: String? = null,
        rememberPassphraseGlobally: Boolean = true
    ) {
        if (restorePayload == null) return

        val context = AnonConfig.context!!.applicationContext
        passPhrase = passphraseOverride ?: AppPassphraseStore.get(context) ?: passPhrase
        val walletFile = AnonConfig.getDefaultWalletFile(context)
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
        AppPinStore.rememberIfMissing(context, pin)
        if (rememberPassphraseGlobally) {
            AppPassphraseStore.rememberIfMissing(context, passPhrase)
        }
        val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
        // For a standard Monero seed, persist the exact height chosen on the restore
        // screen. Polyseed has no height field, so fall back to the native wallet value.
        val actualRestoreHeight = (
            restorePayload?.restoreHeight ?: anonWallet.getRestoreHeight()
        ).coerceAtLeast(0L)
        prefs.edit(commit = true) {
            putString(AnonConfig.walletPreferenceKey(context, PREFS_PASSPHRASE_HASH), crazyPass)
            putString(AnonConfig.walletPreferenceKey(context, PREFS_PIN_HASH), encodePinHash(pin))
            remove(AnonConfig.walletPreferenceKey(context, PREFS_PINLESS_WALLET))
            putLong(AnonConfig.walletPreferenceKey(context, RESTORE_HEIGHT), actualRestoreHeight)
            // Mark this seed restore so wallet startup preserves the selected height
            // and queues its first native scan from that height after the daemon is synced.
            putBoolean(AnonConfig.walletPreferenceKey(context, PREF_FORCE_FRESH_WALLET_SCAN), true)
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
