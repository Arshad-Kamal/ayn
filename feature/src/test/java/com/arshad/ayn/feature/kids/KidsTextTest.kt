package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.settings.DEFAULT_DAILY_LIMITS
import com.arshad.ayn.feature.settings.KidSummary
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kids profiles' text (`kp-kids` profiles, kidtime, ownlockheld, kidhome, warn5…moretimepick; 12-hour times). */
class KidsTextTest {

    @Test
    fun lengthsReadInHoursAndMinutes() {
        assertEquals(listOf("15 min", "1 h", "1 h 30 min", "4 h"), listOf(15, 60, 90, 240).map(::limitText))
        assertEquals("No limit", dayLimitText(null))
        assertEquals(listOf("No limit", "45 min", "1 h", "1 h 30", "2 h 15"), listOf(null, 45, 60, 90, 135).map(::tileLimitText))
    }

    @Test
    fun bedtimeTimesReadOnATwelveHourClock() {
        assertEquals(listOf("12:00 AM", "7:00 AM", "7:30 PM"), listOf(0, 7 * 60, 19 * 60 + 30).map(::clockText))
    }

    @Test
    fun aKidsRowSaysItsApprovalsAndItsWeekdayAndWeekendLimits() {
        val contents = ProfileContents(subscriptionCount = 6, playlistCount = 0, historyCount = 12)

        assertEquals(
            "Kids profile · 6 approved channels · 1 approved video · 1 h a day, 1 h 30 min at weekends",
            kidsHint(contents, KidSummary(approvedVideos = 1, dailyLimitMinutes = DEFAULT_DAILY_LIMITS)),
        )
    }

    @Test
    fun limitsSayWhenTheyAreTheSameEveryDayMissingOrVarying() {
        val everyDay = DayOfWeek.entries.associateWith { 45 }

        assertEquals("45 min a day", rowLimitsText(everyDay))
        assertEquals("no daily limit", rowLimitsText(emptyMap()))
        assertEquals("1 h a day, no limit at weekends", rowLimitsText(DEFAULT_DAILY_LIMITS - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY))
        assertEquals("limits vary by day", rowLimitsText(DEFAULT_DAILY_LIMITS + (DayOfWeek.MONDAY to 30)))
        assertEquals("Mon to Fri 1 h · Sat and Sun 1 h 30 min", limitsHeadingText(DEFAULT_DAILY_LIMITS))
        assertEquals("No limit any day", limitsHeadingText(emptyMap()))
    }

    @Test
    fun theHeldLockNamesEveryKidsProfile() {
        assertEquals("Needed while Zainab has a kids profile", lockHeldHint(listOf("Zainab")))
        assertEquals("Needed while Zainab and Omar have kids profiles", lockHeldHint(listOf("Zainab", "Omar")))
    }

    @Test
    fun timeLeftRoundsUpToTheMinute() {
        assertEquals("48 min", timeLeftText(47.minutes + 30.seconds))
        assertEquals("1 min", timeLeftText(5.seconds))
        assertEquals("1 h 30 min", timeLeftText(90.minutes))
    }

    @Test
    fun warningsCountTheMinutesLeft() {
        assertEquals("5 minutes left today", warningText(5))
        assertEquals("1 minute left today", warningText(1))
    }

    @Test
    fun timesUpNamesTheSavedVideoOnlyWhenOneStopped() {
        val stopped = stoppedCopy("Zainab", KidsTime.TimesUp, videoSaved = true)
        assertEquals("Time’s up for today", stopped.title)
        assertEquals("That’s all for today, Zainab. Your video is saved right where you stopped. See you tomorrow!", stopped.body)

        assertEquals("That’s all for today, Zainab. See you tomorrow!", stoppedCopy("Zainab", KidsTime.TimesUp, videoSaved = false).body)
    }

    @Test
    fun bedtimeSaysWhenAynOpensOnATwelveHourClock() {
        val bedtime = stoppedCopy("Zainab", KidsTime.Bedtime(LocalTime.of(7, 0)), videoSaved = true)

        assertEquals("It’s bedtime", bedtime.title)
        assertEquals("Ayn opens again at 7:00 AM. Your video is saved for the morning. Sleep well, Zainab!", bedtime.body)
    }

    @Test
    fun theAmountsAreTodayOnlyAndNameTomorrowsLimit() {
        assertEquals("For today only. Tomorrow Zainab has the usual 1 h 30 min.", moreTimePickBody("Zainab", 90, failed = false))
        assertEquals("For today only.", moreTimePickBody("Zainab", null, failed = false))
        assertEquals("That didn’t save, so Zainab’s time is still up. Try again.", moreTimePickBody("Zainab", 90, failed = true))
        assertEquals(listOf("15 more minutes", "30 more minutes", "1 more hour", "2 more hours"), listOf(15, 30, 60, 120).map(::moreTimeLabel))
    }

    /** PASS question 15: approvals are changed on the kid's Parent page, not in Settings › Profiles. */
    @Test
    fun theApproveNoteNamesTheKidsParentPage() {
        assertEquals("Change it any time on Zainab’s Parent page.", approveNoteText("Zainab"))
    }
}
