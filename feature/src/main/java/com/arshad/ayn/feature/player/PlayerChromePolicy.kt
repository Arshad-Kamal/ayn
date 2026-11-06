package com.arshad.ayn.feature.player

/**
 * Player chrome policy: the timing and the seek-target math behind the chrome (`b3-player`),
 * pure so it is unit-tested without a `Player`.
 */
object PlayerChromePolicy {
    /** While playing, the chrome recedes after this idle window (any key press restarts it). */
    const val HIDE_AFTER_IDLE_MS = 4_000L

    /** TVU-8: left/right = seek ±10 s; the scrub target moves by the same step. */
    const val SEEK_STEP_MS = 10_000L

    /** Position tick for the progress state (no progress callbacks exist, M3-PLAYER-07). */
    const val POSITION_TICK_MS = 500L

    /** ±10 s steps (hidden Left/Right, the chrome's buttons) gather into one seek this long after
     *  the last (PERF-D6 B1): a held key seeks once, not per repeat. */
    const val SEEK_GATHER_MS = 300L

    /** LIVE-D23: past this far behind the edge a live stream shows as behind (Go live). A
     *  segment's jitter at the edge stays well under it. */
    const val LIVE_BEHIND_SHOWN_MS = 30_000L

    /** Once shown, behind live clears only this close to the edge: between the window's
     *  refreshes the position runs ahead of the window's last known end by a few seconds, which
     *  would otherwise flip the chrome at the threshold. */
    const val LIVE_BEHIND_CLEARS_MS = 15_000L

    /**
     * A seek target clamped to `[0, durationMs]`. An unset duration (`C.TIME_UNSET` is negative)
     * keeps the current position.
     */
    fun seekTargetMs(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return positionMs
        return (positionMs + deltaMs).coerceIn(0L, durationMs)
    }

    /** A live window's edge (LIVE-D23): where Go live plays, [PlayerPolicy.LIVE_OFFSET_MS] short of
     *  the window's end ([durationMs]). */
    fun liveEdgeMs(durationMs: Long): Long = (durationMs - PlayerPolicy.LIVE_OFFSET_MS).coerceAtLeast(0L)

    /** How far [positionMs] is behind the live edge; 0 at or past it, and while the window's
     *  length is unknown. */
    fun liveBehindMs(positionMs: Long, durationMs: Long): Long =
        if (durationMs <= 0) 0L else (liveEdgeMs(durationMs) - positionMs).coerceAtLeast(0L)

    /** Whether [positionMs] shows as behind live: past [LIVE_BEHIND_SHOWN_MS], or, while it
     *  already [shown], past [LIVE_BEHIND_CLEARS_MS]. */
    fun isBehindLive(positionMs: Long, durationMs: Long, shown: Boolean = false): Boolean =
        liveBehindMs(positionMs, durationMs) > if (shown) LIVE_BEHIND_CLEARS_MS else LIVE_BEHIND_SHOWN_MS

    /** One scrub step on a Short (SSC-D1): the TV's Left/Right in scrub mode and the phone's double-taps. */
    const val SHORT_STEP_MS = 5_000L

    /** A ±step's target from [positionMs]: a [live] window's ([liveSeekTargetMs]), else a video's. */
    fun stepTargetMs(positionMs: Long, deltaMs: Long, durationMs: Long, live: Boolean): Long =
        if (live) liveSeekTargetMs(positionMs, deltaMs, durationMs) else seekTargetMs(positionMs, deltaMs, durationMs)

    /** Whether a live seek from [fromMs] to [targetMs] rejoins the edge at the default position
     *  (LIVE-D23): one reaching the edge, or one forward to short of showing as behind. A seek
     *  back always lands where it was aimed. */
    fun rejoinsLiveEdge(fromMs: Long, targetMs: Long, durationMs: Long): Boolean =
        targetMs >= liveEdgeMs(durationMs) || (targetMs > fromMs && !isBehindLive(targetMs, durationMs))

    /** A live seek target, clamped to `[0, edge]`: past the edge lie only the window's last
     *  seconds (LIVE-D23). An unset window keeps the current position. */
    fun liveSeekTargetMs(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return positionMs
        return (positionMs + deltaMs).coerceIn(0L, liveEdgeMs(durationMs))
    }
}

/** Behind live as a chrome last showed it, for [PlayerChromePolicy.isBehindLive]'s hysteresis;
 *  read and written only by the one derivation that asks it. */
class BehindLiveLatch {
    private var shown = false

    /** Whether [positionMs] shows as behind live now, given what was shown before. */
    fun behind(positionMs: Long, durationMs: Long): Boolean =
        PlayerChromePolicy.isBehindLive(positionMs, durationMs, shown).also { shown = it }
}
