package io.anonero.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressTest {
    @Test
    fun unknownDaemonHeightDoesNotReportCompletion() {
        assertNull(calculateSyncProgress(chainHeight = 0L, daemonHeight = 0L, targetHeight = 0L))
    }

    @Test
    fun targetHeightDeterminesRemainingBlocksWhenHigherThanDaemonHeight() {
        val result = calculateSyncProgress(chainHeight = 100L, daemonHeight = 120L, targetHeight = 150L)
        requireNotNull(result)
        assertEquals(50L, result.left)
        assertEquals(2f / 3f, result.progress, 0.0001f)
    }

    @Test
    fun chainAtTargetReportsComplete() {
        val result = calculateSyncProgress(chainHeight = 150L, daemonHeight = 120L, targetHeight = 150L)
        requireNotNull(result)
        assertEquals(0L, result.left)
        assertEquals(1f, result.progress, 0f)
    }

    @Test
    fun remainingBlocksNeverBecomeNegative() {
        val result = calculateSyncProgress(chainHeight = 160L, daemonHeight = 120L, targetHeight = 150L)
        requireNotNull(result)
        assertEquals(0L, result.left)
        assertEquals(1f, result.progress, 0f)
    }
}
