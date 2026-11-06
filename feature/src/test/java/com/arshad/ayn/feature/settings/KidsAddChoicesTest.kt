package com.arshad.ayn.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time sheets' choices (KP-D8): every one of them is a pick the rules take. */
class KidsAddChoicesTest {
    @Test
    fun aDayOffersNoLimitThenEveryQuarterHourUpToFourHours() {
        val choices = dayLimitChoices()

        assertEquals(listOf(null, 15, 30, 45, 60), choices.take(5))
        assertEquals(240, choices.last())
        assertEquals(17, choices.size)
        assertTrue(choices.all(::isPickableLimit))
    }

    @Test
    fun aBedtimeBoundOffersEveryHalfHourButTheOtherBound() {
        val choices = bedtimeChoices(other = 7 * 60)

        assertEquals(47, choices.size)
        assertFalse(7 * 60 in choices)
        assertEquals(listOf(0, 30), choices.take(2))
        assertEquals(23 * 60 + 30, choices.last())
        assertTrue(choices.all(::isPickableBedtimeMinute))
    }
}
