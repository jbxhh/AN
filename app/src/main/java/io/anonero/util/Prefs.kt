package io.anonero.util


const val WALLET_PREFERENCES = "walletPref"


const val PREFS_PASSPHRASE_HASH = "passPhraseHash"

const val PREFS_PIN_HASH = "pinHash"
const val RESTORE_HEIGHT = "restoreHeight"
const val WALLET_PROXY = "proxy"
const val WALLET_USE_TOR = "useTor"
const val WALLET_PROXY_PORT = "proxyPort"

/**
 * Hash a numeric wallet PIN using the same zero-padded 32-byte representation
 * for onboarding and unlock screens.
 */
fun encodePinHash(pin: String): String {
    val bytes = pin.toByteArray(Charsets.UTF_8)
    val paddedBytes = if (bytes.size < 32) {
        bytes + ByteArray(32 - bytes.size)
    } else {
        bytes
    }
    return CrazyPassEncoder.encode(paddedBytes)
}