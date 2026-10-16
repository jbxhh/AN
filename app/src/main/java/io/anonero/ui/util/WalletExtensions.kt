package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

/**
 * Returns the highest subaddress index that has appeared in wallet history.
 * Subaddress indices are zero-based; index 0 is the primary address.
 */
fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    for (info in history?.all ?: emptyList()) {
        if (info.addressIndex > lastUsedSubaddress) {
            lastUsedSubaddress = info.addressIndex
        }
    }
    return lastUsedSubaddress
}

/**
 * Gets the next receiving address, creating it only when that index does not
 * exist yet. Never ask the native wallet for an out-of-range address index:
 * Monero's native address() call can reject an index >= numSubaddresses.
 */
fun Wallet.getLatestSubAddress(): Subaddress {
    val requestedIndex = (getLastUnusedIndex() + 1).coerceAtLeast(1)
    val existingCount = numSubAddresses

    // The wallet normally always has primary address index 0.
    check(existingCount > 0) { "Wallet has no primary address" }

    if (requestedIndex < existingCount) {
        val existingAddress = getSubaddressObject(requestedIndex)
        if (existingAddress.label.isBlank()) {
            setSubaddressLabel(requestedIndex, "Subaddress #$requestedIndex")
            store()
            return getSubaddressObject(requestedIndex)
        }
        return existingAddress
    }

    // Only append at the current count; do not read the address before it exists.
    val newIndex = existingCount
    addSubaddress(getAccountIndex(), "Subaddress #$newIndex")
    check(numSubAddresses > newIndex) {
        "Native wallet did not create subaddress index $newIndex"
    }
    store()
    return getSubaddressObject(newIndex)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    val subAddresses = arrayListOf<Subaddress>()
    for (i in 0 until numSubAddresses) {
        subAddresses.add(getSubaddressObject(i))
    }
    getLatestSubAddress().let {
        if (subAddresses.none { existing -> existing.address == it.address }) {
            subAddresses.add(it)
        }
    }
    return subAddresses
        .apply {
            removeIf { it.addressIndex == 0 && it.totalAmount != 0L }
        }
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
