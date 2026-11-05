package io.anonero.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPassphraseStoreTest {
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
