package com.arshad.ayn.core.store

import kotlin.time.Clock
import kotlin.time.Instant

/** Deterministic stand-in for the stores' injected clock (KT-IDIOM-06); tests set [nowMillis]. */
internal class MutableClock(var nowMillis: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
}
