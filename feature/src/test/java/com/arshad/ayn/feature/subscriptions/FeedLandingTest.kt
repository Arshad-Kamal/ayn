package com.arshad.ayn.feature.subscriptions

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FeedLanding]'s rule on its own: when a fresh feed over a kept one lands. */
class FeedLandingTest {
    private val landing = FeedLanding<String>()

    @Test
    fun withNoKeptFeedShowingTheFreshFeedAlwaysLands() {
        landing.onKeyPressed()

        assertEquals("fresh", landing.onFresh("fresh"))
    }

    @Test
    fun overAKeptFeedItLandsAtOnceUntilAKeyIsPressedThisVisit() {
        landing.keptShown(Duration.ZERO)
        assertEquals("fresh", landing.onFresh("fresh"))

        landing.keptShown(Duration.ZERO)
        landing.onKeyPressed()
        assertNull(landing.onFresh("fresh"))
    }

    @Test
    fun keysCountPerVisit() {
        landing.keptShown(Duration.ZERO)
        landing.onKeyPressed()

        landing.onVisible(arrived = false)

        assertEquals("fresh", landing.onFresh("fresh"))
    }

    @Test
    fun aHeldFeedLandsOnTheNextArrivalOnly() {
        landing.keptShown(Duration.ZERO)
        landing.onKeyPressed()
        landing.onFresh("fresh")

        assertNull(landing.onVisible(arrived = false))
        assertEquals("fresh", landing.onVisible(arrived = true))
        assertNull("landed once", landing.onVisible(arrived = true))
    }

    @Test
    fun aStaleKeptFeedLandsWhileBrowsingButNotOverAFocusedTop() {
        landing.keptShown(FeedLanding.StaleAfter + 1.hours)
        landing.onKeyPressed()
        landing.onTopFocus(true)

        assertNull(landing.onFresh("fresh"))
        assertEquals("fresh", landing.onTopFocus(false))
    }

    @Test
    fun aFreshKeptFeedHeldOverTheTopWaitsForAnArrivalNotTheTopLosingFocus() {
        landing.keptShown(1.hours)
        landing.onKeyPressed()
        landing.onTopFocus(true)
        landing.onFresh("fresh")

        assertNull(landing.onTopFocus(false))
        assertEquals("fresh", landing.onVisible(arrived = true))
    }

    @Test
    fun aResetDropsTheHeldFeed() {
        landing.keptShown(Duration.ZERO)
        landing.onKeyPressed()
        landing.onFresh("fresh")

        landing.reset()

        assertNull(landing.onVisible(arrived = true))
    }
}
