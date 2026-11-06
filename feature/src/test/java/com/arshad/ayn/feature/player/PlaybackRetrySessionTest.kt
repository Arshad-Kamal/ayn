package com.arshad.ayn.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The service's D8 bookkeeping: budgets re-arm per resolve, the
 * kept position applies only to the video that failed, and it is consumed either way —
 * no state can leak into the next video.
 */
class PlaybackRetrySessionTest {

    @Test
    fun `same-video retry resumes from the kept position`() {
        val session = PlaybackRetrySession()
        session.spendRecoverable(3)
        session.recordFailure(videoId = "a", positionMs = 754_000)

        assertEquals(754_000L, session.begin(videoId = "a"))
        assertEquals(0, session.recoverableAttempts) // terminal Retry re-arms the budget
    }

    @Test
    fun `a different video never inherits the kept position or the budgets`() {
        val session = PlaybackRetrySession()
        session.spendRecoverable(5)
        session.recordFailure(videoId = "a", positionMs = 754_000)

        assertNull(session.begin(videoId = "b"))
        assertEquals(0, session.recoverableAttempts)
    }

    @Test
    fun `begin consumes the position — a second resolve starts clean`() {
        val session = PlaybackRetrySession()
        session.recordFailure(videoId = "a", positionMs = 60_000)
        session.begin("a")

        assertNull(session.begin("a"))
    }

    @Test
    fun `a non-positive failure position is not kept`() {
        val session = PlaybackRetrySession()
        session.recordFailure(videoId = "a", positionMs = 0)
        assertNull(session.begin("a"))

        session.recordFailure(videoId = "a", positionMs = -1)
        assertNull(session.begin("a"))
    }

    @Test
    fun `READY clears a pending position but spends survive until the next resolve`() {
        val session = PlaybackRetrySession()
        session.spendAttestation(2)
        session.recordFailure(videoId = "a", positionMs = 30_000)
        session.clearKeptPosition()

        assertEquals(2, session.attestationAttempts) // the spend is visible before the re-arm
        assertNull(session.begin("a"))
        assertEquals(0, session.attestationAttempts) // and re-armed by the fresh resolve
    }

    @Test
    fun `unknown failing video keeps its position only for a matching retry`() {
        val session = PlaybackRetrySession()
        session.recordFailure(videoId = null, positionMs = 90_000)
        assertNull(session.begin("a")) // a null owner never matches a concrete request
    }

    @Test
    fun `the kept position is read only for the video that failed`() {
        val session = PlaybackRetrySession()
        session.recordFailure(videoId = "a", positionMs = 30_000)

        assertEquals(0L, session.keptPositionFor("b"))
    }

    @Test
    fun `reading the kept position leaves it for the retry`() {
        val session = PlaybackRetrySession()
        session.recordFailure(videoId = "a", positionMs = 30_000)

        assertEquals(30_000L, session.keptPositionFor("a"))
        assertEquals(30_000L, session.begin("a"))
    }
}
