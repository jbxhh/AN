package io.anonero.util

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Verifies the exact wallet passphrase against the device-bound value recorded
 * when the wallet was created or restored. A missing hash fails closed.
 */
fun isWalletPassphraseValid(
    context: Context,
    prefs: SharedPreferences,
    passPhrase: String
): Boolean {
    val expected = prefs.getString(PREFS_PASSPHRASE_HASH, null) ?: return false
    if (expected.isBlank()) return false

    val actual = try {
        KeyStoreHelper.getCrazyPass(context.applicationContext, passPhrase)
    } catch (_: Exception) {
        return false
    }

    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8)
    )
}
