package io.anonero.services

import io.anonero.model.CoinsInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputInventoryPolicyTest {
    private fun output(pubKey: String) = CoinsInfo(
        globalOutputIndex = 1L,
        spent = false,
        key = "key-$pubKey",
        amount = 1L,
        hash = "hash-$pubKey",
        pub_key = pubKey,
        frozen = false,
        creationTime = 1L
    )

    @Test
    fun retainsPreviousOutputsWhenNativeRefreshIsDeferred() {
        val cached = listOf(output("cached-output"))

        assertEquals(cached, retainOutputSnapshot(cached, null))
    }

    @Test
    fun replacesPreviousOutputsWhenFreshSnapshotIsAvailable() {
        val cached = listOf(output("cached-output"))
        val refreshed = listOf(output("fresh-output"))

        assertEquals(refreshed, retainOutputSnapshot(cached, refreshed))
    }

    @Test
    fun acceptsAnExplicitEmptySnapshotAfterARealRefresh() {
        val cached = listOf(output("cached-output"))

        assertTrue(retainOutputSnapshot(cached, emptyList()).isEmpty())
    }

    @Test
    fun retriesWhenBalanceExistsButOutputInventoryIsEmpty() {
        assertTrue(shouldRetryOutputSnapshot(outputCount = 0, balance = 10L, unlockedBalance = 5L))
        assertTrue(shouldRetryOutputSnapshot(outputCount = 0, balance = 0L, unlockedBalance = 5L))
    }

    @Test
    fun retriesWhenCachedWalletBalanceIsPositiveButNativeBalanceIsTemporarilyZero() {
        assertTrue(
            shouldRetryOutputSnapshot(
                outputCount = 0,
                balance = 0L,
                unlockedBalance = 0L,
                cachedBalance = 50L
            )
        )
        assertTrue(
            shouldRetryOutputSnapshot(
                outputCount = 0,
                balance = 0L,
                unlockedBalance = 0L,
                cachedUnlockedBalance = 25L
            )
        )
    }

    @Test
    fun stopsRetryingWhenOutputsExistOrWalletHasNoBalance() {
        assertFalse(shouldRetryOutputSnapshot(outputCount = 1, balance = 10L, unlockedBalance = 5L))
        assertFalse(shouldRetryOutputSnapshot(outputCount = 1, balance = 0L, unlockedBalance = 0L, cachedBalance = 50L))
        assertFalse(shouldRetryOutputSnapshot(outputCount = 0, balance = 0L, unlockedBalance = 0L))
    }
}
