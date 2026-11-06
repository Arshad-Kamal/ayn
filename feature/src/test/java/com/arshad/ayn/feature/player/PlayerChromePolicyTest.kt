package com.arshad.ayn.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seek clamping, the unset duration guard and the live window's edge (LIVE-D23), plus the
 *  screen's re-entry rule. */
class PlayerChromePolicyTest {

    @Test
    fun `seek target clamps to the duration`() {
        assertEquals(30_000L, PlayerChromePolicy.seekTargetMs(positionMs = 25_000, deltaMs = 60_000, durationMs = 30_000))
    }

    @Test
    fun `seek target never goes below zero`() {
        assertEquals(0L, PlayerChromePolicy.seekTargetMs(positionMs = 5_000, deltaMs = -60_000, durationMs = 30_000))
    }

    @Test
    fun `seek target lands past the position by the delta`() {
        assertEquals(
            35_000L,
            PlayerChromePolicy.seekTargetMs(positionMs = 25_000, deltaMs = PlayerChromePolicy.SEEK_STEP_MS, durationMs = 60_000),
        )
    }

    @Test
    fun `an unset duration keeps the current position`() {
        assertEquals(25_000L, PlayerChromePolicy.seekTargetMs(positionMs = 25_000, deltaMs = PlayerChromePolicy.SEEK_STEP_MS, durationMs = 0))
        // C.TIME_UNSET is negative.
        assertEquals(25_000L, PlayerChromePolicy.seekTargetMs(positionMs = 25_000, deltaMs = -PlayerChromePolicy.SEEK_STEP_MS, durationMs = Long.MIN_VALUE))
    }

    /** LIVE-D23 on an hour's window: the edge is 10 s short of its end; behind live shows only past
     *  30 s behind it, so a segment's jitter at the edge never shows. */
    @Test
    fun `behind live is measured from the edge and shows past thirty seconds`() {
        val window = 3_600_000L
        val edge = window - PlayerPolicy.LIVE_OFFSET_MS
        assertEquals(edge, PlayerChromePolicy.liveEdgeMs(window))
        assertEquals(0L, PlayerChromePolicy.liveBehindMs(edge + 4_000, window))
        assertEquals(1_440_000L, PlayerChromePolicy.liveBehindMs(edge - 1_440_000, window))
        assertFalse(PlayerChromePolicy.isBehindLive(edge - PlayerChromePolicy.LIVE_BEHIND_SHOWN_MS, window))
        assertTrue(PlayerChromePolicy.isBehindLive(edge - PlayerChromePolicy.LIVE_BEHIND_SHOWN_MS - 1, window))
        assertFalse(PlayerChromePolicy.isBehindLive(0, durationMs = 0))
    }

    /** Once shown, behind live holds down to 15 s: the position's run between the window's
     *  refreshes can't flip it at 30 s. */
    @Test
    fun `behind live once shown clears only under fifteen seconds`() {
        val window = 3_600_000L
        val edge = 3_590_000L
        assertTrue(PlayerChromePolicy.isBehindLive(edge - 20_000, window, shown = true))
        assertFalse(PlayerChromePolicy.isBehindLive(edge - 20_000, window, shown = false))
        assertFalse(PlayerChromePolicy.isBehindLive(edge - PlayerChromePolicy.LIVE_BEHIND_CLEARS_MS, window, shown = true))
    }

    @Test
    fun `a live seek rejoins the edge at it or forward near it, never going back`() {
        val window = 3_600_000L
        val edge = 3_590_000L
        assertTrue(PlayerChromePolicy.rejoinsLiveEdge(fromMs = edge - 5_000, targetMs = edge, durationMs = window))
        assertTrue(PlayerChromePolicy.rejoinsLiveEdge(fromMs = edge - 35_000, targetMs = edge - 25_000, durationMs = window))
        assertFalse(PlayerChromePolicy.rejoinsLiveEdge(fromMs = edge, targetMs = edge - 10_000, durationMs = window))
        assertFalse(PlayerChromePolicy.rejoinsLiveEdge(fromMs = edge - 90_000, targetMs = edge - 60_000, durationMs = window))
    }

    @Test
    fun `a step targets a live window's span or a video's`() {
        assertEquals(3_590_000L, PlayerChromePolicy.stepTargetMs(3_589_000, PlayerChromePolicy.SEEK_STEP_MS, 3_600_000, live = true))
        assertEquals(3_599_000L, PlayerChromePolicy.stepTargetMs(3_589_000, PlayerChromePolicy.SEEK_STEP_MS, 3_600_000, live = false))
    }

    @Test
    fun `a live seek stops at the edge and the window's start`() {
        val window = 3_600_000L
        val edge = PlayerChromePolicy.liveEdgeMs(window)
        assertEquals(edge, PlayerChromePolicy.liveSeekTargetMs(edge - 5_000, PlayerChromePolicy.SEEK_STEP_MS, window))
        assertEquals(edge - 20_000, PlayerChromePolicy.liveSeekTargetMs(edge - 10_000, -PlayerChromePolicy.SEEK_STEP_MS, window))
        assertEquals(0L, PlayerChromePolicy.liveSeekTargetMs(5_000, -PlayerChromePolicy.SEEK_STEP_MS, window))
        assertEquals(5_000L, PlayerChromePolicy.liveSeekTargetMs(5_000, PlayerChromePolicy.SEEK_STEP_MS, durationMs = 0))
    }

    /** The screen's re-entry rule (PlaybackContract.shouldRestartResolve): the same LIVE
     *  item (READY/BUFFERING) resumes; IDLE, ENDED, or a different item resolves fresh —
     *  `playWhenReady` alone never restarts ENDED playback (M3-PLAYER-05). */
    @Test
    fun `re-entry resumes only a live item and restarts everything else`() {
        assertFalse(restart("a", currentMediaId = "a", playerIsLive = true))
        assertTrue(restart("a", currentMediaId = "a", playerIsLive = false)) // ENDED or IDLE
        assertTrue(restart("b", currentMediaId = "a", playerIsLive = true))
        assertTrue(restart("a", currentMediaId = null, playerIsLive = true))
    }

    /** A Short and the same video as a video play differently (M7-D13): neither resumes as the other. */
    @Test
    fun `re-entry restarts a live item asked for as the other of Short and video`() {
        assertFalse(restart("a", "a", playerIsLive = true, requestedShort = true, playingShort = true))
        assertTrue(restart("a", "a", playerIsLive = true, requestedShort = true, playingShort = false))
        assertTrue(restart("a", "a", playerIsLive = true, requestedShort = false, playingShort = true))
    }

    private fun restart(
        requested: String,
        currentMediaId: String?,
        playerIsLive: Boolean,
        requestedShort: Boolean = false,
        playingShort: Boolean = false,
    ): Boolean = PlaybackContract.shouldRestartResolve(requested, currentMediaId, playerIsLive, requestedShort, playingShort)
}
