package io.anonero.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs wallet-state operations that may wait for the native wallet monitor away from the UI thread.
 */
internal suspend fun <T> withWalletStateIo(block: () -> T): T =
    withContext(Dispatchers.IO) {
        block()
    }
