package com.arshad.ayn.core.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Wrong tries in a row that start no wait (§ B3p). */
internal const val FREE_TRIES = 4
private val FIRST_WAIT: Duration = 30.seconds
private val LONGEST_WAIT: Duration = 15.minutes

/** 30 s × 2⁵ is past [LONGEST_WAIT]; stopping there keeps the shift from overflowing. */
private const val MAX_DOUBLINGS = 5

/** The wait the [failedTries]-th wrong try in a row starts: none for the first [FREE_TRIES],
 *  then 30 s, doubling with each further one, capped at 15 min. */
internal fun waitStep(failedTries: Int): Duration? {
    if (failedTries <= FREE_TRIES) return null
    val doublings = (failedTries - FREE_TRIES - 1).coerceAtMost(MAX_DOUBLINGS)
    return (FIRST_WAIT * (1 shl doublings)).coerceAtMost(LONGEST_WAIT)
}

/** Wrong tries left before one starts a wait, after [failedTries] in a row. */
internal fun triesBeforeWait(failedTries: Int): Int = (FREE_TRIES + 1 - failedTries).coerceAtLeast(0)

/**
 * The stored [waitUntilMillis] as it stands at [nowMillis]: `null` once it has passed (by
 * waiting or by a clock set forward, which § B3p accepts), and never further away than the
 * current step, so a clock set back can't lengthen the wait.
 */
internal fun effectiveWaitUntil(failedTries: Int, waitUntilMillis: Long?, nowMillis: Long): Long? {
    if (waitUntilMillis == null || nowMillis >= waitUntilMillis) return null
    val step = waitStep(failedTries) ?: return null
    return minOf(waitUntilMillis, nowMillis + step.inWholeMilliseconds)
}
