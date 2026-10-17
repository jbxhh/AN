package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    val subaddress = arrayListOf<Subaddress>()
    for (i in 0 until this.numSubAddresses) {
        subaddress.add(this.getSubaddressObject(i))
    }
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) lastUsedSubaddress = info.addressIndex
    }
    return lastUsedSubaddress
}


fun Wallet.getLatestSubAddress(): Subaddress = synchronized(this) {
    // Return the first address after the highest index seen in transaction history.
    // Any missing address is created and persisted by one native operation that yields
    // its exact index/address while refresh is temporarily quiesced.
    val nextIndex = getLastUnusedIndex() + 1
    var knownCount = numSubAddresses
    var lastCreated: Subaddress? = null
    while (knownCount <= nextIndex) {
        lastCreated = addSubaddressAndGetSubaddress(getAccountIndex())
        knownCount = lastCreated.addressIndex + 1
    }
    lastCreated?.takeIf { it.addressIndex == nextIndex } ?: getSubaddressObject(nextIndex)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> = synchronized(this) {
    // A read-only snapshot: refreshing the label list must not create native addresses.
    val subAddresses = (0 until numSubAddresses).map { getSubaddressObject(it) }
    subAddresses
        .filterNot { it.addressIndex == 0 && it.totalAmount != 0L }
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
