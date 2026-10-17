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
    // Create it only when it does not exist yet, not merely because its label is empty.
    val nextIndex = getLastUnusedIndex() + 1
    while (numSubAddresses <= nextIndex) {
        val indexToCreate = numSubAddresses
        addSubaddress(getAccountIndex(), "Subaddress #$indexToCreate")
        check(numSubAddresses > indexToCreate) {
            "原生钱包未能创建收款子地址"
        }
    }
    check(store()) { "保存收款子地址失败" }
    getSubaddressObject(nextIndex)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> = synchronized(this) {
    // A read-only snapshot: refreshing the label list must not create native addresses.
    val subAddresses = (0 until numSubAddresses).map { getSubaddressObject(it) }
    subAddresses
        .filterNot { it.addressIndex == 0 && it.totalAmount != 0L }
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
