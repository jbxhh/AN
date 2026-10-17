package io.anonero.rust

/**
 * JNI bridge for the isolated Rust trial app.
 *
 * This module has no C++ fallback. A missing or incompatible Rust library is
 * surfaced in the test UI instead of silently switching backends.
 */
internal object RustNativeBridge {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    loaded = try {
                        System.loadLibrary("anonero_rust")
                        true
                    } catch (_: UnsatisfiedLinkError) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                    loadAttempted = true
                }
            }
        }
        return loaded
    }

    fun backendInfoOrNull(): String? =
        if (ensureLoaded()) runCatching { nativeBackendInfo() }.getOrNull() else null

    fun validateAddressOrNull(address: String, networkId: Int): Boolean? =
        if (ensureLoaded()) {
            runCatching { nativeValidateAddress(address, networkId) }.getOrNull()
        } else {
            null
        }

    @JvmStatic
    private external fun nativeBackendInfo(): String

    @JvmStatic
    private external fun nativeValidateAddress(address: String, networkId: Int): Boolean
}
