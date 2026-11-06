package com.arshad.ayn.core.lock

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wrong-try back-off and its clock rules (§ B3p). */
class LockBackoffTest {

    @Test
    fun theFirstFourWrongTriesStartNoWait() {
        (0..4).forEach { assertNull("try $it", waitStep(it)) }
    }

    @Test
    fun theFifthWrongTryWaits30SecondsAndEachFurtherOneDoublesUpTo15Minutes() {
        val waits = (5..12).map { waitStep(it) }

        assertEquals(listOf(30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes, 15.minutes, 15.minutes, 15.minutes), waits)
    }

    @Test
    fun aVeryLongRunOfWrongTriesStaysAt15Minutes() {
        assertEquals(15.minutes, waitStep(Int.MAX_VALUE))
    }

    @Test
    fun triesBeforeWaitCountsDownToTheFifth() {
        assertEquals(listOf(5, 4, 3, 2, 1, 0, 0), (0..6).map { triesBeforeWait(it) })
    }

    @Test
    fun aWaitWithinItsStepIsKept() {
        assertEquals(T0 + 20_000, effectiveWaitUntil(failedTries = 5, waitUntilMillis = T0 + 20_000, nowMillis = T0))
    }

    @Test
    fun aClockSetBackCantPushTheWaitPastItsStep() {
        val setBackAnHour = T0 - 3_600_000

        assertEquals(setBackAnHour + 60_000, effectiveWaitUntil(failedTries = 6, waitUntilMillis = T0 + 60_000, nowMillis = setBackAnHour))
    }

    @Test
    fun aPassedWaitIsGone() {
        assertNull(effectiveWaitUntil(failedTries = 5, waitUntilMillis = T0, nowMillis = T0))
        assertNull(effectiveWaitUntil(failedTries = 5, waitUntilMillis = T0, nowMillis = T0 + 86_400_000))
    }

    @Test
    fun noWaitStaysNoWait() {
        assertNull(effectiveWaitUntil(failedTries = 7, waitUntilMillis = null, nowMillis = T0))
    }
}

private const val T0 = 1_700_000_000_000L
