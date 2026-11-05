package io.anonero.util

import android.content.Context
import io.anonero.AnonConfig
import java.security.MessageDigest

/**
 * Stores and verifies the one extra passphrase configured during the app's
 * first wallet setup. It is app-scoped, not tied to the currently selected wallet.
 */
object AppPassphraseStore {
    private const val PRIMARY_PASSPHRASE_STORE_ID = "__anonero_primary_app_passphrase__"

    fun get(context: Context): String? = runCatching {
        KeyStoreHelper.loadWalletUserPass(
            context.applicationContext,
            PRIMARY_PASSPHRASE_STORE_ID
        )
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Save the first configured passphrase; later wallets must reuse it. */
    fun rememberIfMissing(context: Context, passphrase: String) {
        if (passphrase.isBlank() || get(context) != null) return
        runCatching {
            KeyStoreHelper.saveWalletUserPass(
                context.applicationContext,
                PRIMARY_PASSPHRASE_STORE_ID,
                passphrase
            )
        }
    }

    /**
     * Verify against the app-wide encrypted value. Existing installations may
     * not have that value yet, so migrate only from the original primary
     * wallet's legacy passphrase hash, never from a secondary wallet.
     */
    fun verify(context: Context, passphrase: String): Boolean {
        val appContext = context.applicationContext
        val candidateHash = runCatching {
            KeyStoreHelper.getCrazyPass(appContext, passphrase)
        }.getOrNull() ?: return false
        val walletPreferences = appContext.getSharedPreferences(
            WALLET_PREFERENCES,
            Context.MODE_PRIVATE
        )
        // Secure deletion applies to the complete wallet list, so any passphrase
        // belonging to any wallet in that list may authorize the wipe. Do not scope
        // this check to the currently active wallet.
        val walletFiles = AnonConfig.getWalletFiles(appContext)
        val walletHashes = walletFiles.map { walletFile ->
            walletPreferences.getString(
                "${walletFile.name}::$PREFS_PASSPHRASE_HASH",
                null
            )
        }
        if (matchesAnyWalletPassphraseHash(walletHashes, candidateHash)) return true

        // Older single-wallet installations may still have the original unscoped
        // hash. It remains associated with the primary "anon" wallet even when a
        // secondary wallet is currently selected.
        if (walletFiles.none { it.name == "anon" }) return false
        val legacyWalletHash = walletPreferences.getString(PREFS_PASSPHRASE_HASH, null)
        if (!matchesLegacyWalletPassphraseHash(legacyWalletHash, candidateHash)) return false

        rememberIfMissing(appContext, passphrase)
        return true
    }

    fun clear(context: Context) {
        KeyStoreHelper.removeWalletUserPass(
            context.applicationContext,
            PRIMARY_PASSPHRASE_STORE_ID
        )
    }
}

internal fun matchesWalletPassphraseHash(
    walletPassphraseHash: String?,
    candidateHash: String
): Boolean {
    if (candidateHash.isBlank() || walletPassphraseHash.isNullOrBlank()) return false
    return constantTimeEquals(walletPassphraseHash, candidateHash)
}

internal fun matchesAnyWalletPassphraseHash(
    walletPassphraseHashes: Iterable<String?>,
    candidateHash: String
): Boolean {
    if (candidateHash.isBlank()) return false
    return walletPassphraseHashes.any { matchesWalletPassphraseHash(it, candidateHash) }
}

internal fun matchesLegacyWalletPassphraseHash(
    primaryWalletHash: String?,
    candidateHash: String
): Boolean {
    if (candidateHash.isBlank() || primaryWalletHash.isNullOrBlank()) return false
    return constantTimeEquals(primaryWalletHash, candidateHash)
}

private fun constantTimeEquals(first: String, second: String): Boolean =
    MessageDigest.isEqual(
        first.toByteArray(Charsets.UTF_8),
        second.toByteArray(Charsets.UTF_8)
    )
