package com.arshad.ayn.core

import kotlin.time.Clock
import kotlin.time.Instant

/** A clock stopped at [now] (feed ages, TEST-DETERM-01). */
class FixedClock(private val now: Instant) : Clock {
    override fun now(): Instant = now
}
