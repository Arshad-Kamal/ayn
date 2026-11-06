package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FetchAttempt
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.FetchOutcome.Failed
import com.arshad.ayn.core.store.FetchOutcome.Fetched
import com.arshad.ayn.core.store.FetchOutcome.RateLimited
import com.arshad.ayn.feature.TestClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FY-D5's budget at a fixed [NOW]: 30 attempts an hour, an hour's stop after a rate-limit answer
 *  or three failures in a row. */
class FetchBudgetTest {

    @Test
    fun anEmptyLogAllows() {
        assertTrue(allowsAt(emptyList(), NOW))
    }

    @Test
    fun thirtyAttemptsWithinTheHourSpendIt() {
        val attempts = (59 downTo 30).map { attempt(Fetched, it.minutes) }

        assertFalse(allowsAt(attempts, NOW))
    }

    @Test
    fun theOldestOfThirtyLeavingTheHourFreesOne() {
        val attempts = listOf(attempt(Fetched, 60.minutes)) + (58 downTo 30).map { attempt(Fetched, it.minutes) }

        assertTrue(allowsAt(attempts, NOW))
    }

    @Test
    fun aRateLimitAnswerStopsRequestsForAnHour() {
        val attempts = listOf(attempt(RateLimited, 59.minutes), attempt(Fetched, 30.minutes))

        assertFalse(allowsAt(attempts, NOW))
    }

    @Test
    fun aRateLimitAnswerAnHourOldNoLongerStops() {
        assertTrue(allowsAt(listOf(attempt(RateLimited, 60.minutes)), NOW))
    }

    @Test
    fun threeFailuresInARowStopRequestsForAnHourFromTheLast() {
        val attempts = listOf(attempt(Failed, 3.hours), attempt(Failed, 2.hours), attempt(Failed, 59.minutes))

        assertFalse(allowsAt(attempts, NOW))
    }

    @Test
    fun aRateLimitAnswerCountsInTheRunOfFailures() {
        val attempts = listOf(attempt(RateLimited, 70.minutes), attempt(Failed, 30.minutes), attempt(Failed, 20.minutes))

        assertFalse(allowsAt(attempts, NOW))
    }

    @Test
    fun twoFailuresInARowDontStop() {
        assertTrue(allowsAt(listOf(attempt(Failed, 2.minutes), attempt(Failed, 1.minutes)), NOW))
    }

    @Test
    fun aSuccessEndsTheRun() {
        val attempts = listOf(attempt(Failed, 4.minutes), attempt(Failed, 3.minutes), attempt(Fetched, 2.minutes), attempt(Failed, 1.minutes))

        assertTrue(allowsAt(attempts, NOW))
    }

    /** After the stop one attempt is tried; its failure is the run's fourth and stops requests again. */
    @Test
    fun aFailureAfterTheStopStopsAgain() {
        val run = listOf(attempt(Failed, 3.hours), attempt(Failed, 3.hours - 1.minutes), attempt(Failed, 2.hours))
        assertTrue("an hour after the third failure", allowsAt(run, NOW))

        assertFalse(allowsAt(run + attempt(Failed, 1.minutes), NOW))
    }

    @Test
    fun theBudgetReadsAndRecordsThroughTheLog() = runTest {
        val clock = TestClock(NOW)
        val budget = FetchBudget(FakeFetchAttemptLog(clock), clock)

        repeat(FetchBudget.FAILURES_TO_STOP) { budget.record(Failed) }

        assertFalse(budget.allows())
        clock.current = NOW + FetchBudget.STOP
        assertTrue(budget.allows())
    }

    private fun attempt(outcome: FetchOutcome, ago: Duration) = FetchAttempt(NOW - ago, outcome)
}
