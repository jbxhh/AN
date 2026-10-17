package io.anonero.rust

/**
 * Optional bridge for the Rust core migration.
 *
 * The old native wallet remains the active backend until Rust parity tests pass. Callers must
 * handle [isAvailable] == false; this keeps current AN builds working before the Rust library is
 * packaged into an APK.
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

    fun isAvailable(): Boolean = ensureLoaded()

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
