package io.anonero.util

import android.content.Context

/**
 * Stores the PIN chosen during the app's first wallet setup independently of
 * the selected wallet. The value is encrypted with Android Keystore through
 * KeyStoreHelper; it is never written to plaintext app preferences.
 */
object AppPinStore {
    private const val PRIMARY_PIN_STORE_ID = "__anonero_primary_app_pin__"

    fun get(context: Context): String? = runCatching {
        KeyStoreHelper.loadWalletUserPass(
            context.applicationContext,
            PRIMARY_PIN_STORE_ID
        )
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Preserve the first successfully configured app PIN. Later wallet-specific
     * PINs must not silently replace the app's primary PIN.
     */
    fun rememberIfMissing(context: Context, pin: String) {
        if (pin.isBlank() || get(context) != null) return
        runCatching {
            KeyStoreHelper.saveWalletUserPass(
                context.applicationContext,
                PRIMARY_PIN_STORE_ID,
                pin
            )
        }
    }
}
