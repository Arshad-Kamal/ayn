package com.arshad.ayn.ui.mobile.profiles

import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.feature.profiles.StoppedAsk
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's unlock on Time's up (`m6-kids` 11): what it is for, the lock's words, where Back goes. */
class StoppedAskCopyTest {

    @Test
    fun moreTimeOnASequenceReturnsToTimesUp() {
        val copy = stoppedAskCopy(StoppedAsk.MoreTime, "Zainab", "Arshad", LockKind.Sequence, KidsTime.TimesUp)

        assertEquals("More time for Zainab", copy.label)
        assertEquals("Only Arshad adds time. Enter Arshad’s sequence, then tap OK.", copy.line)
        assertEquals("⌫ erases a press · Back returns to Time’s up", copy.hint)
    }

    @Test
    fun switchProfileOnAPinAtBedtimeReturnsToTheBedtimeScreen() {
        val copy = stoppedAskCopy(StoppedAsk.SwitchProfile, "Zainab", "Arshad", LockKind.Pin, KidsTime.Bedtime(LocalTime.of(7, 0)))

        assertEquals("Switch profile", copy.label)
        assertEquals("Only Arshad switches profiles. Enter Arshad’s PIN.", copy.line)
        assertEquals("⌫ erases a digit · Back returns to the bedtime screen", copy.hint)
        assertEquals(", or go back to the bedtime screen.", copy.waitTail)
    }
}
