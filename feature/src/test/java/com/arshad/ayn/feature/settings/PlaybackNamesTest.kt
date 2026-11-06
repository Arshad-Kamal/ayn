package com.arshad.ayn.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The playback names both UIs show: speeds and captions languages. */
class PlaybackNamesTest {

    @Test
    fun speedsReadAsThePlayersChoiceDoes() {
        assertEquals(listOf("0.5×", "0.75×", "Normal", "1.25×", "1.5×", "1.75×", "2×"), listOf(50, 75, 100, 125, 150, 175, 200).map(::speedLabel))
    }

    @Test
    fun everyOfferedCaptionsLanguageHasAynsOwnName() {
        val offered = captionsChoices("", chosen = null).flatMap { it.tags }
        assertEquals(offered.toSet(), CAPTIONS_NAMES.keys)
        assertEquals("Bengali", captionsLabel("bn"))
        assertEquals("Stoney Nakoda", captionsLabel("sto"))
    }

    @Test
    fun aLanguageNotOfferedTakesThePlatformsNameAndNoneIsOff() {
        assertEquals("Japanese", captionsLabel("ja"))
        assertEquals("Off", captionsLabel(null))
    }
}
