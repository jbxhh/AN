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
        assertEquals("XMR 1", walletDisplayName("anon", null))
        assertEquals("XMR 2", walletDisplayName("wallet_2", "  "))
    }

    @Test
    fun usesWalletFileNameAsFallbackForUnnumberedWallet() {
        assertEquals("cold-storage", walletDisplayName("cold-storage", null))
    }

    @Test
    fun unchangedDefaultNameIsNotPersistedAsCustomName() {
        assertEquals(null, customWalletDisplayNameForSave("anon", "XMR 1"))
        assertEquals(null, customWalletDisplayNameForSave("wallet_2", "XMR 2"))
    }

    @Test
    fun customNameIsTrimmedAndPersistedOnlyWhenDifferentFromDefault() {
        assertEquals("主钱包", customWalletDisplayNameForSave("wallet_2", "  主钱包  "))
        assertEquals(null, customWalletDisplayNameForSave("wallet_2", "  "))
    }

    @Test
    fun findsEveryXmrOccurrenceForConsistentFontStyling() {
        assertEquals(
            listOf(0..2, 9..11),
            xmrRanges("XMR main xmr"),
        )
        assertEquals(emptyList<IntRange>(), xmrRanges("wallet"))
    }
}
