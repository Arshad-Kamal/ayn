package com.arshad.ayn.ui.tv.player

import androidx.compose.ui.geometry.Size
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Kicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The TV-worded stop copy and the preview's fitted frame, against hand-worked values. */
class PlayerFormatsTest {

    @Test
    fun `a frame keeps its own shape inside the 16-9 preview`() {
        val slot = Size(400f, 225f)

        assertEquals(Size(400f, 225f), fittedSize(Size(160f, 90f), slot))
        assertEquals(Size(225f, 225f), fittedSize(Size(90f, 90f), slot)) // square (live smoke)
        assertEquals(Size(126.5625f, 225f), fittedSize(Size(90f, 160f), slot)) // portrait
        assertEquals(Size(400f, 160f), fittedSize(Size(160f, 64f), slot)) // 2.5:1
    }

    @Test
    fun `stops say what happened, name the kept place, and refusals offer Back only`() {
        val exhausted = stopCopy(PlayerPolicy.TERMINAL_EXHAUSTED, 2_091_000)
        assertEquals("Stopped after 6 tries", exhausted.kicker.text)
        assertEquals("Try again from 34:51", exhausted.retryLabel)
        assertEquals("YouTube kept dropping the connection.", stopCopy(PlayerPolicy.TERMINAL_EXHAUSTED, 0).body)
        assertEquals("Try again", stopCopy(PlayerPolicy.TERMINAL_EXHAUSTED, 0).retryLabel)
        assertEquals("Stopped after 3 refreshes", stopCopy(PlayerPolicy.TERMINAL_ATTESTATION, 1_000).kicker.text)
        assertNull(stopCopy(PlayerPolicy.TERMINAL_UNAVAILABLE, 0).retryLabel)
        assertNull(stopCopy(PlayerPolicy.TERMINAL_PROTOCOL, 5_000).retryLabel)
        assertEquals(Kicker(R.drawable.ic_offline, "No connection to YouTube"), stopCopy(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0).kicker)
        assertEquals(Kicker(R.drawable.ic_alert, "YouTube changed something"), stopCopy(PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE, 0).kicker)
    }

    @Test
    fun `under Restricted Mode YouTube's refusal names it too, and only that refusal`() {
        val restricted = "It may be private, removed, age-restricted, members-only, blocked in your region, or hidden by Restricted Mode."

        assertEquals(restricted, stopCopy(PlayerPolicy.TERMINAL_UNAVAILABLE, 0, restricted = true).body)
        assertEquals(restricted, stopCopy(PlayerPolicy.TERMINAL_PROTOCOL, 0, restricted = true).forShort().body)
        assertEquals("It may be private, removed, age-restricted, members-only, or blocked in your region.", stopCopy(PlayerPolicy.TERMINAL_UNAVAILABLE, 0).body)
        assertEquals(stopCopy(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0), stopCopy(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0, restricted = true))
        assertEquals(stopCopy(PlayerPolicy.TERMINAL_FILTERED, 0), stopCopy(PlayerPolicy.TERMINAL_FILTERED, 0, restricted = true))
    }

    @Test
    fun `a profile's own refusals say so and offer Back only`() {
        val filtered = stopCopy(PlayerPolicy.TERMINAL_FILTERED, 60_000)
        assertEquals("Hidden in this profile", filtered.kicker.text)
        assertNull(filtered.retryLabel)

        val timeUp = stopCopy(PlayerPolicy.TERMINAL_TIME_UP, 60_000)
        assertEquals("Time’s up for now", timeUp.title)
        assertNull(timeUp.retryLabel)
    }

    @Test
    fun `a made-for-kids refusal names made-for-kids videos and offers Back only`() {
        val copy = stopCopy(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0)

        assertEquals("This made-for-kids video can’t play in Ayn", copy.title)
        assertEquals("YouTube wouldn’t send it to Ayn, through either of the players Ayn uses.", copy.body)
        assertNull(copy.retryLabel)
    }

    @Test
    fun `a not-started video says YouTube's sentence, or a plain one when it gave none`() {
        val said = stopCopy(PlayerPolicy.TERMINAL_NOT_STARTED, 0, detail = "This live event will begin in 5 days.")
        val plain = stopCopy(PlayerPolicy.TERMINAL_NOT_STARTED, 0)

        assertEquals(Kicker(R.drawable.ic_later, "Not started yet"), said.kicker)
        assertEquals("This video hasn’t started yet", said.title)
        assertEquals("This live event will begin in 5 days.", said.body)
        assertEquals("It’s a premiere or a scheduled stream. Come back when it begins.", plain.body)
        assertNull(said.retryLabel)
    }
}
