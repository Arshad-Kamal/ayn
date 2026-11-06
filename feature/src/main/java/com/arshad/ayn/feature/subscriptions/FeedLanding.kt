package com.arshad.ayn.feature.subscriptions

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * When a fresh subscriptions feed loaded over a kept one lands (PERF P9c, PERF-D6), for a screen
 * that shows the kept feed first. From loading, the fresh feed always lands. Over a kept feed it
 * lands at once if no key was pressed this visit; else it is held until the next visit that
 * arrives from the rail or with the app back in the foreground, never one that returns with BACK
 * (BACK restores the exact card). Over a kept feed older than [StaleAfter] it lands even while
 * the screen is browsed, except while the top (Home's hero) holds focus: then once focus leaves it.
 * Main-thread only: the key check and the landing it decides are one step.
 */
internal class FeedLanding<T : Any> {
    private var keyPressed = false
    private var topFocused = false

    /** How old the kept feed on screen was when read; `null` while none shows. */
    private var keptAge: Duration? = null
    private var held: T? = null

    private val stale: Boolean get() = keptAge?.let { it > StaleAfter } == true

    /** A kept feed [age] old ([SubscriptionsFeed.Stored.age]) now shows. */
    fun keptShown(age: Duration) {
        keptAge = age
        held = null
    }

    /** What shows is no longer the kept feed (a reload, Retry, or the fresh feed landed). */
    fun reset() {
        keptAge = null
        held = null
    }

    /** The fresh feed loaded: returned when it lands now, else held and `null`. */
    fun onFresh(fresh: T): T? {
        if (keptAge == null || !keyPressed || (stale && !topFocused)) {
            reset()
            return fresh
        }
        held = fresh
        return null
    }

    /** A visit starts; [arrived] when it came from the rail or the foreground. Returns the held
     *  feed when it lands now. */
    fun onVisible(arrived: Boolean): T? {
        keyPressed = false
        return if (arrived) release() else null
    }

    fun onKeyPressed() {
        keyPressed = true
    }

    /** Focus entered or left the top; a stale feed held while it was there lands as it leaves. */
    fun onTopFocus(focused: Boolean): T? {
        topFocused = focused
        return if (!focused && stale) release() else null
    }

    private fun release(): T? = held?.also { reset() }

    companion object {
        /** A kept feed this old lets the fresh one land while browsing (Arshad's call, spec 15). */
        val StaleAfter = 24.hours
    }
}
