package io.anonero.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPassphraseStoreTest {
    @Test
    fun legacyHashFromAnyWalletCanAuthorizeTheAppPassphrase() {
        assertTrue(
            matchesLegacyWalletPassphraseHash(
                listOf("wallet_one_hash", "primary_wallet_hash", "wallet_three_hash"),
                "primary_wallet_hash"
            )
        )
    }

    @Test
    fun unrelatedOrEmptyHashCannotAuthorizeTheAppPassphrase() {
        assertFalse(
            matchesLegacyWalletPassphraseHash(
                listOf("wallet_one_hash", "wallet_two_hash"),
                "wrong_hash"
            )
        )
        assertFalse(matchesLegacyWalletPassphraseHash(listOf("wallet_one_hash"), ""))
        assertFalse(matchesLegacyWalletPassphraseHash(emptyList(), "candidate_hash"))
    }
}
