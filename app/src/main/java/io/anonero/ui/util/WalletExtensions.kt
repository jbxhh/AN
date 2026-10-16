package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    // History determines which subaddress was last used; don't query native
    // addresses while searching because the next index might not exist yet.
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) lastUsedSubaddress = info.addressIndex
    }
    return lastUsedSubaddress
}

fun Wallet.getLatestSubAddress(): Subaddress {
    val nextIndex = (getLastUnusedIndex() + 1).coerceAtLeast(1)
    var createdAddress = false

    // Freshly restored wallets often contain only index 0. Create the next
    // subaddress before asking the native wallet for its address/label.
    while (numSubAddresses <= nextIndex) {
        val previousCount = numSubAddresses
        addSubaddress(getAccountIndex(), "Subaddress #$previousCount")
        if (numSubAddresses <= previousCount) {
            throw IllegalStateException("无法创建子地址 $previousCount")
        }
        createdAddress = true
    }

    if (createdAddress) {
        store()
    }

    // Read only after the index is guaranteed to exist.
    return getSubaddressObject(nextIndex)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    // Ensure the next receive subaddress exists before enumerating. Otherwise
    // a freshly-created address could be omitted from the list snapshot.
    getLatestSubAddress()

    val subAddresses = arrayListOf<Subaddress>()
    for (i in 0 until numSubAddresses) {
        subAddresses.add(getSubaddressObject(i))
    }

    return subAddresses
        .apply {
            removeIf { it.addressIndex == 0 && it.totalAmount != 0L }
        }
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
