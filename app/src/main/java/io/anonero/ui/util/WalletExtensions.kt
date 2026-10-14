package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) lastUsedSubaddress = info.addressIndex
    }
    return lastUsedSubaddress
}


fun Wallet.getLatestSubAddress(): Subaddress {
    val accountIndex = getAccountIndex()
    val nextIndex = (getLastUnusedIndex() + 1).coerceAtLeast(1)
    var changed = false

    // A wallet can have fewer subaddresses than the first unused index requested.
    // Create missing entries before asking native code for the address at that index.
    while (numSubAddresses <= nextIndex) {
        val indexToCreate = numSubAddresses
        addSubaddress(accountIndex, "Subaddress$indexToCreate")
        changed = true
    }

    if (getSubaddressLabel(nextIndex).isEmpty()) {
        setSubaddressLabel(nextIndex, "Subaddress$nextIndex")
        changed = true
    }
    if (changed) store()

    // Fetch after creation/label changes so callers receive the real latest address.
    return getSubaddressObject(nextIndex)
}


fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    val subAddresses = arrayListOf<Subaddress>()
    for (i in 0 until this.numSubAddresses) {
        subAddresses.add(this.getSubaddressObject(i))
    }
    this.getLatestSubAddress().let {
        if (subAddresses.indexOf(it) == -1) subAddresses.add(it)
    }
    return subAddresses
        .apply {
            this.removeIf { it.addressIndex == 0 && it.totalAmount != 0L }
        }
        .distinctBy { it.address }.sortedBy { it.addressIndex }
}
