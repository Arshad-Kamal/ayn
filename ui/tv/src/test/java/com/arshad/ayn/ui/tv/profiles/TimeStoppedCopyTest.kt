package com.arshad.ayn.ui.tv.profiles

import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.feature.profiles.StoppedAsk
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** More time's and Switch profile's unlock copy (`kp-kids` moretime, switchunlock). */
class TimeStoppedCopyTest {

    @Test
    fun moreTimesUnlockNamesTheOwnerAndWhereBackGoes() {
        val sequence = stoppedAskCopy(StoppedAsk.MoreTime, "Zainab", "Arshad", LockKind.Sequence, KidsTime.TimesUp)
        assertEquals("More time for Zainab", sequence.kicker?.text)
        assertEquals("Only Arshad adds time. Enter Arshad’s sequence, then press OK.", sequence.line)
        assertEquals("BACK returns to Time’s up", sequence.hint)

        val pin = stoppedAskCopy(StoppedAsk.MoreTime, "Zainab", "Arshad", LockKind.Pin, KidsTime.Bedtime(LocalTime.of(7, 0)))
        assertEquals("Only Arshad adds time. Enter Arshad’s PIN.", pin.line)
        assertEquals("BACK returns to the bedtime screen", pin.hint)
    }

    @Test
    fun switchProfilesUnlockSaysOnlyTheOwnerSwitches() {
        val sequence = stoppedAskCopy(StoppedAsk.SwitchProfile, "Zainab", "Arshad", LockKind.Sequence, KidsTime.TimesUp)
        assertEquals("Switch profile", sequence.kicker?.text)
        assertEquals("Only Arshad switches profiles. Enter Arshad’s sequence, then press OK.", sequence.line)
        assertEquals("BACK returns to Time’s up", sequence.hint)

        val pin = stoppedAskCopy(StoppedAsk.SwitchProfile, "Zainab", "Arshad", LockKind.Pin, KidsTime.Bedtime(LocalTime.of(7, 0)))
        assertEquals("Only Arshad switches profiles. Enter Arshad’s PIN.", pin.line)
        assertEquals("BACK returns to the bedtime screen", pin.hint)
    }
}
