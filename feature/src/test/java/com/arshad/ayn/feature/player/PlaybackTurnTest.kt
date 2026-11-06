package com.arshad.ayn.feature.player

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.C
import com.arshad.ayn.core.access.VideoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The turn's request, start and leave rules both players share (M5-D8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTurnTest {

    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val lifecycle = owner.lifecycle
    private val player = ScriptedPlayer()
    private val asked = mutableListOf<Long?>()

    private suspend fun request(
        id: String,
        short: Boolean = false,
        startingOver: Boolean = false,
        afresh: Boolean = false,
        resumeMs: suspend () -> Long? = { 90_000 },
        away: Boolean = false,
    ) = player.requestTurn(lifecycle, VideoId(id), short = short, startingOver = startingOver, afresh = afresh, { resumeMs() }, { 150 }, away) { asked += it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The player's events reach the view, and the view's state its observers. */
    private fun settle() {
        idle()
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `a new video is asked for from its resume point at the profile's speed, once the screen is started`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.CREATED
        val job = launch { request("turnvideo01") }
        assertTrue(asked.isEmpty())
        lifecycle.currentState = Lifecycle.State.STARTED
        job.join()
        idle()
        assertEquals(listOf<Long?>(90_000), asked)
        assertEquals(listOf("turnvideo01"), player.requested)
        assertEquals(listOf(90_000L), player.starts)
        assertEquals(1.5f, player.speed)
    }

    @Test
    fun `the same video already live isn't asked again, unless starting over or trying again`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.RESUMED
        request("turnvideo01", resumeMs = { null })
        idle()
        player.ready(atMs = 120_000)
        idle()
        request("turnvideo01")
        assertEquals(listOf("turnvideo01"), player.requested)
        request("turnvideo01", startingOver = true)
        request("turnvideo01", afresh = true, resumeMs = { 120_000 })
        idle()
        assertEquals(listOf<Long?>(null, 0, 120_000), asked)
        assertEquals(listOf(C.TIME_UNSET, 0L, 120_000L), player.starts)
    }

    @Test
    fun `the same video asked as a Short is asked again`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.RESUMED
        request("turnvideo01")
        idle()
        player.ready()
        idle()
        request("turnvideo01", short = true)
        assertEquals(listOf("turnvideo01", "turnvideo01"), player.requested)
    }

    @Test
    fun `starting the same video over keeps the speed it plays at`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.RESUMED
        request("turnvideo01")
        idle()
        player.setPlaybackSpeed(2f)
        player.ready()
        idle()
        request("turnvideo01", startingOver = true)
        idle()
        assertEquals(2f, player.speed)
        request("othervideo1")
        idle()
        assertEquals(1.5f, player.speed)
    }

    @Test
    fun `leaving during the resume read asks nothing until the screen is started again`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.RESUMED
        val read = CompletableDeferred<Long?>()
        val job = launch { request("turnvideo01", resumeMs = { read.await() }) }
        lifecycle.currentState = Lifecycle.State.CREATED
        read.complete(90_000)
        assertTrue(asked.isEmpty())
        assertTrue(player.requested.isEmpty())
        lifecycle.currentState = Lifecycle.State.STARTED
        job.join()
        idle()
        assertEquals(listOf("turnvideo01"), player.requested)
    }

    @Test
    fun `a video started once it has played, not while the last turn's same item is still ended`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.RESUMED
        val view = PlaybackView(player).also(player::addListener)
        player.setMediaItem(dressedItem("thisturn001"))
        player.prepare()
        player.end()
        settle()
        var started = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            view.awaitStarted("thisturn001")
            started = true
        }
        yield()
        assertFalse(started)
        player.setMediaItem(dressedItem("thisturn001"))
        player.prepare()
        settle()
        assertFalse(started)
        player.play()
        player.ready()
        settle()
        yield()
        assertTrue(started)
    }

    /** M6-D5: a phone's background audio moving on asks with the screen stopped. */
    @Test
    fun `a request made away is asked without waiting for the screen`() = runTest(UnconfinedTestDispatcher()) {
        lifecycle.currentState = Lifecycle.State.CREATED
        request("turnvideo01", away = true)
        idle()
        assertEquals(listOf("turnvideo01"), player.requested)
    }

    @Test
    fun `leaving keeps playing when it plays away`() {
        var pauses = 0
        var away = true
        lifecycle.addObserver(pauseWhileAway({ pauses++ }, {}, startPending = { false }, playsAway = { away }))
        lifecycle.currentState = Lifecycle.State.RESUMED
        lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(0, pauses)
        away = false
        lifecycle.currentState = Lifecycle.State.RESUMED
        lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(1, pauses)
    }

    @Test
    fun `leaving pauses, and coming back starts only a video still to start`() {
        var pauses = 0
        var plays = 0
        var pending = true
        lifecycle.addObserver(pauseWhileAway({ pauses++ }, { plays++ }, startPending = { pending }))
        lifecycle.currentState = Lifecycle.State.RESUMED
        lifecycle.currentState = Lifecycle.State.CREATED
        pending = false
        lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(1, pauses)
        assertEquals(1, plays) // the first start, while it was pending
    }
}
