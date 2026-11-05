package io.anonero.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPassphraseStoreTest {
    @Test
    fun importedWalletPassphraseMatchesItsOwnWalletHash() {
        assertTrue(
            matchesWalletPassphraseHash(
                walletPassphraseHash = "wallet_b_hash",
                candidateHash = "wallet_b_hash"
            )
        )
    }

    @Test
    fun firstWalletPassphraseCannotUnlockImportedWallet() {
        assertFalse(
            matchesWalletPassphraseHash(
                walletPassphraseHash = "wallet_b_hash",
                candidateHash = "wallet_a_hash"
            )
        )
    }

    @Test
    fun importedWalletPassphraseCannotUnlockFirstWallet() {
        assertFalse(
            matchesWalletPassphraseHash(
                walletPassphraseHash = "wallet_a_hash",
                candidateHash = "wallet_b_hash"
            )
        )
    }

    @Test
    fun walletWithoutPassphraseAcceptsOnlyItsEmptyPassphraseHash() {
        assertTrue(
            matchesWalletPassphraseHash(
                walletPassphraseHash = "empty_offset_wallet_hash",
                candidateHash = "empty_offset_wallet_hash"
            )
        )
        assertFalse(
            matchesWalletPassphraseHash(
                walletPassphraseHash = "empty_offset_wallet_hash",
                candidateHash = "different_passphrase_hash"
            )
        )
    }

    @Test
    fun missingOrEmptyWalletHashCannotAuthorizePassphrase() {
        assertFalse(matchesWalletPassphraseHash(null, "candidate_hash"))
        assertFalse(matchesWalletPassphraseHash("", "candidate_hash"))
        assertFalse(matchesWalletPassphraseHash("wallet_hash", ""))
    }

    @Test
    fun onlyThePrimaryWalletHashCanMigrateLegacyPassphrase() {
        assertTrue(
            matchesLegacyWalletPassphraseHash(
                "primary_wallet_hash",
                "primary_wallet_hash"
            )
        )
        assertFalse(
            matchesLegacyWalletPassphraseHash(
                "primary_wallet_hash",
                "secondary_wallet_hash"
            )
        )
        assertFalse(matchesLegacyWalletPassphraseHash(null, "candidate_hash"))
    }
}
