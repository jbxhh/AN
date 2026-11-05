package io.anonero.util

import android.content.Context
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
     * not have that value yet, so migrate it after matching any wallet's legacy
     * passphrase hash (not just the currently selected wallet).
     */
    fun verify(context: Context, passphrase: String): Boolean {
        if (passphrase.isBlank()) return false
        val appContext = context.applicationContext
        val savedPassphrase = get(appContext)
        if (savedPassphrase != null) {
            return constantTimeEquals(savedPassphrase, passphrase)
        }

        val candidateHash = runCatching {
            KeyStoreHelper.getCrazyPass(appContext, passphrase)
        }.getOrNull() ?: return false
        val walletPreferences = appContext.getSharedPreferences(
            WALLET_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val legacyHashes = walletPreferences.all.entries.mapNotNull { entry ->
            val isPassphraseHash = entry.key == PREFS_PASSPHRASE_HASH ||
                entry.key.endsWith("::$PREFS_PASSPHRASE_HASH")
            if (isPassphraseHash) entry.value as? String else null
        }
        if (!matchesLegacyWalletPassphraseHash(legacyHashes, candidateHash)) return false

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

internal fun matchesLegacyWalletPassphraseHash(
    storedHashes: Collection<String>,
    candidateHash: String
): Boolean {
    if (candidateHash.isBlank()) return false
    return storedHashes.any { storedHash ->
        storedHash.isNotBlank() && constantTimeEquals(storedHash, candidateHash)
    }
}

private fun constantTimeEquals(first: String, second: String): Boolean =
    MessageDigest.isEqual(
        first.toByteArray(Charsets.UTF_8),
        second.toByteArray(Charsets.UTF_8)
    )
