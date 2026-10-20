package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

fun Wallet.getLastUnusedIndex(): Int {
    // Only transaction history is needed here. Reading every address through JNI was
    // unnecessary and could race the native scanner during a restore.
    var lastUsedSubaddress = 0
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) lastUsedSubaddress = info.addressIndex
    }
    return lastUsedSubaddress
}

/**
 * Read the best already-generated receive address without mutating the native wallet.
 * This function is called from wallet refresh callbacks, so it must never wait for or
 * stop the native refresh thread.
 */
fun Wallet.getLatestSubAddress(): Subaddress = synchronized(this) {
    val addressCount = numSubAddresses
    check(addressCount > 0) { "钱包没有可用地址" }
    val nextIndex = getLastUnusedIndex() + 1
    val indexToRead = if (nextIndex in 0 until addressCount) nextIndex else addressCount - 1
    getSubaddressObject(indexToRead)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> = synchronized(this) {
    // A read-only snapshot: refreshing the label list must not create native addresses.
    val subAddresses = getSubaddressObjectsWithBalances()
    // Keep the primary address in the list even when it has received funds.
    // The UI decides whether to render its amount; filtering the funded primary address
    // here makes all non-zero amount labels disappear when child subaddresses are empty.
    subAddresses
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
