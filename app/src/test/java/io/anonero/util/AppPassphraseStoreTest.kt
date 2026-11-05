package io.anonero.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPassphraseStoreTest {

    @Test
    fun importedWalletPassphraseWorksEvenWhenAppPassphraseIsDifferent() {
        assertTrue(
            matchesCurrentWalletPassphrase(
                savedAppPassphrase = "first-app-passphrase",
                activeWalletHash = "imported_wallet_hash",
                candidatePassphrase = "imported-wallet-passphrase",
                candidateHash = "imported_wallet_hash"
            )
        )
    }

    @Test
    fun wrongPassphraseDoesNotMatchImportedWalletHash() {
        assertFalse(
            matchesCurrentWalletPassphrase(
                savedAppPassphrase = "first-app-passphrase",
                activeWalletHash = "imported_wallet_hash",
                candidatePassphrase = "wrong-passphrase",
                candidateHash = "wrong_wallet_hash"
            )
        )
    }

    @Test
    fun originalAppPassphraseStillWorks() {
        assertTrue(
            matchesCurrentWalletPassphrase(
                savedAppPassphrase = "first-app-passphrase",
                activeWalletHash = "imported_wallet_hash",
                candidatePassphrase = "first-app-passphrase",
                candidateHash = "first_wallet_hash"
            )
        )
    }

    @Test
    fun onlyThePrimaryWalletHashCanMigrateTheAppPassphrase() {
        assertTrue(
            matchesLegacyWalletPassphraseHash(
                "primary_wallet_hash",
                "primary_wallet_hash"
            )
        )
    }

    @Test
    fun secondaryWalletHashCannotAuthorizeTheAppPassphrase() {
        assertFalse(
            matchesLegacyWalletPassphraseHash(
                "primary_wallet_hash",
                "secondary_wallet_hash"
            )
        )
    }

    @Test
    fun unrelatedOrEmptyHashCannotAuthorizeTheAppPassphrase() {
        assertFalse(matchesLegacyWalletPassphraseHash("primary_wallet_hash", "wrong_hash"))
        assertFalse(matchesLegacyWalletPassphraseHash("primary_wallet_hash", ""))
        assertFalse(matchesLegacyWalletPassphraseHash(null, "candidate_hash"))
    }
}
