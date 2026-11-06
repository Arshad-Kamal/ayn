package com.arshad.ayn.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hold in virtual time: report + 2 s settle, a 5 s cap on the report, carry-over. */
class DisplayModeHoldTest {

    private class FakePlayback(
        override var playWhenReady: Boolean,
        private val scope: TestScope,
        override var canResume: Boolean = true,
    ) : DisplayModeHold.Playback {
        val events = mutableListOf<String>()

        override fun pause() {
            playWhenReady = false
            events += "pause@${scope.currentTime}"
        }

        override fun play() {
            playWhenReady = true
            events += "play@${scope.currentTime}"
        }
    }

    private fun TestScope.holdOn(playback: FakePlayback, scope: CoroutineScope = backgroundScope) =
        DisplayModeHold(scope, playback)

    /** `advanceUntilIdle` ignores `backgroundScope` work, so step past every wait explicitly. */
    private fun TestScope.runPastEveryWait() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `a playing player pauses at the request and resumes two seconds after the report`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)

        holdOn(playback).onModeRequested { delay(300) }
        runPastEveryWait()

        assertEquals(listOf("pause@0", "play@2300"), playback.events)
    }

    @Test
    fun `a mode never reported resumes after the five second cap plus the settle`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)

        holdOn(playback).onModeRequested { awaitCancellation() }
        runPastEveryWait()

        assertEquals(listOf("pause@0", "play@7000"), playback.events)
    }

    @Test
    fun `a paused player is not held`() = runTest {
        val playback = FakePlayback(playWhenReady = false, scope = this)

        holdOn(playback).onModeRequested { }
        runPastEveryWait()

        assertEquals(emptyList<String>(), playback.events)
    }

    @Test
    fun `a hold ending in the background leaves playback paused`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this, canResume = false)

        holdOn(playback).onModeRequested { }
        runPastEveryWait()

        assertEquals(listOf("pause@0"), playback.events)
    }

    @Test
    fun `a second request during the hold carries it over and restarts the wait`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val hold = holdOn(playback)

        hold.onModeRequested { delay(300) }
        advanceTimeBy(1_000)
        hold.onModeRequested { delay(400) } // the player is paused by the hold by now
        runPastEveryWait()

        assertEquals(listOf("pause@0", "play@3400"), playback.events)
    }

    @Test
    fun `a kept mode during the hold still settles before resuming`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val hold = holdOn(playback)

        hold.onModeRequested { awaitCancellation() }
        advanceTimeBy(500)
        hold.onModeKept()
        runPastEveryWait()

        assertEquals(listOf("pause@0", "play@2500"), playback.events)
    }

    @Test
    fun `a kept mode without a hold does nothing`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)

        holdOn(playback).onModeKept()
        runPastEveryWait()

        assertEquals(emptyList<String>(), playback.events)
    }

    @Test
    fun `a finished hold never resumes a later user pause`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val hold = holdOn(playback)
        hold.onModeRequested { }
        runPastEveryWait()

        playback.playWhenReady = false // the user pauses after the hold resumed
        hold.onModeRequested { }
        hold.onModeKept()
        runPastEveryWait()

        assertEquals(listOf("pause@0", "play@2000"), playback.events)
    }

    /** The reconnect order: the request lands before the screen's start: the hold must not depend on it. */
    @Test
    fun `a start asked for while a switch settles waits for it`() = runTest {
        val playback = FakePlayback(playWhenReady = false, scope = this)
        val hold = holdOn(playback)

        hold.onModeRequested { delay(300) }
        hold.play()
        runPastEveryWait()

        assertEquals(listOf("play@2300"), playback.events)
    }

    @Test
    fun `a start with no switch settling plays at once`() = runTest {
        val playback = FakePlayback(playWhenReady = false, scope = this)

        holdOn(playback).play()

        assertEquals(listOf("play@0"), playback.events)
    }

    @Test
    fun `a pause during the hold drops the owed resume`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val hold = holdOn(playback)

        hold.onModeRequested { delay(300) }
        advanceTimeBy(1_000)
        hold.pause()
        runPastEveryWait()

        assertEquals(listOf("pause@0", "pause@1000"), playback.events)
    }

    @Test
    fun `leaving while awaiting the report never resumes`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val screenJob = Job(backgroundScope.coroutineContext.job)

        holdOn(playback, CoroutineScope(backgroundScope.coroutineContext + screenJob))
            .onModeRequested { awaitCancellation() }
        advanceTimeBy(1_000)
        screenJob.cancel()
        runPastEveryWait()

        assertEquals(listOf("pause@0"), playback.events)
    }

    @Test
    fun `leaving during the settle never resumes`() = runTest {
        val playback = FakePlayback(playWhenReady = true, scope = this)
        val screenJob = Job(backgroundScope.coroutineContext.job)

        holdOn(playback, CoroutineScope(backgroundScope.coroutineContext + screenJob))
            .onModeRequested { }
        advanceTimeBy(1_000)
        screenJob.cancel()
        runPastEveryWait()

        assertEquals(listOf("pause@0"), playback.events)
    }
}
