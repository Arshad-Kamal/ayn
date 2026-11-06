package com.arshad.ayn.core.access

import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The time from YouTube's own answers (KP-D8, D22): the `Date` of the latest InnerTube answer plus
 * the time since it on [elapsed] (a monotonic clock that keeps counting through deep sleep), so
 * changing the device's clock moves nothing. Memory only: after a reboot or a process death it
 * knows nothing until the next InnerTube answer, and a kids profile plays nothing until then.
 */
class ServerClock(private val elapsed: TimeSource) {
    @Volatile
    private var anchor: Anchor? = null

    fun record(serverTime: Instant) {
        anchor = Anchor(serverTime, elapsed.markNow())
    }

    /** The time now, or `null` before any InnerTube answer. */
    fun now(): Instant? = anchor?.let { it.serverTime + it.mark.elapsedNow() }

    private class Anchor(val serverTime: Instant, val mark: TimeMark)
}
