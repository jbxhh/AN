package io.anonero.util


const val WALLET_PREFERENCES = "walletPref"


const val PREFS_PASSPHRASE_HASH = "passPhraseHash"

const val PREFS_PIN_HASH = "pinHash"
const val RESTORE_HEIGHT = "restoreHeight"
const val WALLET_PROXY = "proxy"
const val WALLET_USE_TOR = "useTor"
const val WALLET_PROXY_PORT = "proxyPort"

/**
 * Keep the PIN fingerprint identical across wallet creation, restore, and lock-screen checks.
 * Monero's wallet PIN is padded to 32 bytes before passing through the existing encoder.
 */
fun encodePinHash(pin: String): String {
    val bytes = pin.toByteArray()
    val padded = if (bytes.size < 32) bytes + ByteArray(32 - bytes.size) else bytes
    return CrazyPassEncoder.encode(padded)
}
