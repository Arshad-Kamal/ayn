package com.arshad.ayn.core.backup

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** [readAtMost]: the stream cap that bounds memory when a provider doesn't report a length. */
class ReadAtMostTest {

    @Test
    fun aStreamOfUnknownLengthStopsAtTheLimit() {
        val stream = ByteArrayInputStream(ByteArray(200_000))

        assertEquals(100_001, stream.readAtMost(limit = 100_001, expected = -1).size)
    }

    @Test
    fun aStreamShorterThanTheLimitIsReadWhole() {
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readAtMost(limit = 100, expected = 10).size)
    }
}
