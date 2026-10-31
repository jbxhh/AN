package io.anonero

import io.anonero.ui.home.ActualBlockHeader
import io.anonero.ui.home.firstBlockAtOrAfterTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun selectsLowestHeightAtOrAfterTargetEvenWhenHeadersAreOutOfOrder() {
        val headers = listOf(
            ActualBlockHeader(height = 12L, timestamp = 1_200L),
            ActualBlockHeader(height = 10L, timestamp = 1_000L),
            ActualBlockHeader(height = 11L, timestamp = 1_100L)
        )

        assertEquals(
            11L,
            firstBlockAtOrAfterTimestamp(headers, 1_050L)?.height
        )
    }

    @Test
    fun returnsNullWhenRangeHasNoBlockAtOrAfterTarget() {
        val headers = listOf(
            ActualBlockHeader(height = 10L, timestamp = 1_000L),
            ActualBlockHeader(height = 11L, timestamp = 1_100L)
        )

        assertNull(firstBlockAtOrAfterTimestamp(headers, 1_101L))
    }
}
