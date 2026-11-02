package io.anonero.ui.wallets

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletDisplayNameTest {

    @Test
    fun displaysTrimmedCustomNameWhenPresent() {
        assertEquals(
            "我的主钱包",
            walletDisplayName("wallet_2", "  我的主钱包  ")
        )
    }

    @Test
    fun fallsBackToDefaultWhenCustomNameIsMissingOrBlank() {
        assertEquals("Monero Wallet (1)", walletDisplayName("anon", null))
        assertEquals("Monero Wallet (2)", walletDisplayName("wallet_2", "  "))
    }

    @Test
    fun usesWalletFileNameAsFallbackForUnnumberedWallet() {
        assertEquals("cold-storage", walletDisplayName("cold-storage", null))
    }
}
