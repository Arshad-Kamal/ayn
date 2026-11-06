package com.arshad.ayn.feature.player

import android.os.Looper
import androidx.media3.common.C
import com.arshad.ayn.feature.player.PlayerChromePolicy.SEEK_GATHER_MS
import com.arshad.ayn.feature.player.PlayerChromePolicy.SEEK_STEP_MS
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ±10 s steps gathered into one seek after the last (PERF-D6 B1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeekGatheringTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val player = ScriptedPlayer().apply {
        setMediaItem(dressedItem("gathered000"))
        prepare()
        ready(atMs = 60_000)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private val seeks = SeekGathering(player, scope)

    @Test
    fun `steps gather into one seek after the last, each from the last target`() {
        seeks.stepBy(SEEK_STEP_MS)
        scope.advanceTimeBy(SEEK_GATHER_MS - 1)
        seeks.stepBy(SEEK_STEP_MS)
        seeks.stepBy(-SEEK_STEP_MS)
        seeks.stepBy(SEEK_STEP_MS)
        assertEquals(80_000L, seeks.targetMs)
        scope.advanceTimeBy(SEEK_GATHER_MS - 1)
        scope.runCurrent()
        assertEquals(emptyList<Long>(), player.seeks)

        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(listOf(80_000L), player.seeks)
        assertNull(seeks.targetMs)
    }

    @Test
    fun `a target stays within the video`() {
        seeks.stepBy(-SEEK_STEP_MS * 9)
        assertEquals(0L, seeks.targetMs)
        seeks.stepBy(ScriptedPlayer.DURATION_MS * 2)
        assertEquals(ScriptedPlayer.DURATION_MS, seeks.targetMs)
    }

    /** LIVE-D23 on an hour's window (its edge at 59:50): a step forward to short of showing as
     *  behind rejoins the edge at the default position (a seek to `C.TIME_UNSET`). */
    @Test
    fun `a live step forward to near the edge rejoins it`() {
        val live = liveAt(behindMs = 25_000)

        assertEquals(EDGE_MS - 15_000, live.stepBy(SEEK_STEP_MS))
        gathered()

        assertEquals(listOf(C.TIME_UNSET), player.seeks)
    }

    @Test
    fun `live steps stop at the edge`() {
        val live = liveAt(behindMs = 25_000)

        assertEquals(EDGE_MS, live.stepBy(SEEK_STEP_MS * 3))
    }

    @Test
    fun `one step back from the edge lands ten seconds back`() {
        val live = liveAt(behindMs = 0)

        live.stepBy(-SEEK_STEP_MS)
        gathered()

        assertEquals(listOf(EDGE_MS - 10_000), player.seeks)
    }

    @Test
    fun `a live step back lands where it was aimed`() {
        val live = liveAt(behindMs = 25_000)

        live.stepBy(-SEEK_STEP_MS)
        live.stepBy(-SEEK_STEP_MS)
        gathered()

        assertEquals(listOf(EDGE_MS - 45_000), player.seeks)
    }

    /** The scrub bar's OK: a direct seek back lands where aimed; one to the edge rejoins it. */
    @Test
    fun `a direct live seek back lands where aimed and one to the edge rejoins it`() {
        liveAt(behindMs = 0).seekTo(EDGE_MS - 20_000)
        shadowOf(Looper.getMainLooper()).idle()
        liveAt(behindMs = 60_000).seekTo(EDGE_MS)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(EDGE_MS - 20_000, C.TIME_UNSET), player.seeks)
    }

    @Test
    fun `Go live drops a pending step and rejoins the edge`() {
        val live = liveAt(behindMs = 600_000)
        live.stepBy(-SEEK_STEP_MS)

        live.goLive()
        shadowOf(Looper.getMainLooper()).idle()
        gathered()

        assertEquals(listOf(C.TIME_UNSET), player.seeks)
        assertNull(live.targetMs)
    }

    /** The player on an hour's live window, [behindMs] behind its edge, and its gathering. */
    private fun liveAt(behindMs: Long): SeekGathering {
        player.liveWindowMs = WINDOW_MS
        player.ready(atMs = EDGE_MS - behindMs)
        shadowOf(Looper.getMainLooper()).idle()
        return seeks
    }

    private fun gathered() {
        scope.advanceTimeBy(SEEK_GATHER_MS + 1)
        scope.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a direct seek replaces a pending one`() {
        seeks.stepBy(SEEK_STEP_MS)
        seeks.seekTo(5_000)
        scope.advanceTimeBy(SEEK_GATHER_MS * 3)
        scope.runCurrent()

        assertEquals(listOf(5_000L), player.seeks)
        assertNull(seeks.targetMs)
    }

    @Test
    fun `a step aimed at one video never seeks the next`() {
        seeks.stepBy(SEEK_STEP_MS)
        player.setMediaItem(dressedItem("nextvideo00"))
        shadowOf(Looper.getMainLooper()).idle()
        scope.advanceTimeBy(SEEK_GATHER_MS * 3)
        scope.runCurrent()

        assertEquals(emptyList<Long>(), player.seeks)
        assertNull(seeks.targetMs)
    }

    @Test
    fun `a cancelled step never seeks`() {
        seeks.stepBy(SEEK_STEP_MS)
        seeks.cancel()
        scope.advanceTimeBy(SEEK_GATHER_MS * 3)
        scope.runCurrent()

        assertEquals(emptyList<Long>(), player.seeks)
        assertNull(seeks.targetMs)
    }

    private companion object {
        const val WINDOW_MS = 3_600_000L
        const val EDGE_MS = 3_590_000L
    }
}
