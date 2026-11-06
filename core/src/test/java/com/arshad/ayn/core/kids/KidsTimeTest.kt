package com.arshad.ayn.core.kids

import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.ProfileRestrictions
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A kids profile's time from its limits, bedtime and ledger (KP-D8, D22). */
class KidsTimeTest {

    @Test
    fun noLimitAndNoBedtimeIsUnlimited() {
        assertEquals(KidsTime.Unlimited, kidsTime(ProfileRestrictions(), DayTime(usedSeconds = 9_000), at(12, 0)))
    }

    @Test
    fun theLimitLeftIsTheDaysLimitLessWhatIsUsed() {
        assertEquals(KidsTime.Left(20.minutes), kidsTime(limit(30), DayTime(usedSeconds = 600), at(12, 0)))
    }

    @Test
    fun theLimitIsTheWeekdaysOwn() {
        assertEquals(KidsTime.Unlimited, kidsTime(limit(30), DayTime(usedSeconds = 9_000), at(12, 0).plusDays(1)))
    }

    @Test
    fun aUsedUpOrZeroLimitIsTimesUp() {
        assertEquals(KidsTime.TimesUp, kidsTime(limit(30), DayTime(usedSeconds = 1_800), at(12, 0)))
        assertEquals(KidsTime.TimesUp, kidsTime(limit(0), DayTime(), at(12, 0)))
    }

    @Test
    fun theTimeLeftIsTheNearerOfTheLimitAndBedtime() {
        val both = limit(60).copy(bedtime = EVENING)

        assertEquals(KidsTime.Left(30.minutes), kidsTime(both, DayTime(), at(19, 0)))
        assertEquals(KidsTime.Left(45.minutes), kidsTime(both, DayTime(usedSeconds = 900), at(12, 0)))
    }

    @Test
    fun aBedtimeAcrossMidnightHoldsOnBothSidesAndEndsAtItsEnd() {
        val evening = ProfileRestrictions(bedtime = EVENING)

        assertEquals(KidsTime.Bedtime(LocalTime.of(7, 0)), kidsTime(evening, DayTime(), at(19, 30)))
        assertEquals(KidsTime.Bedtime(LocalTime.of(7, 0)), kidsTime(evening, DayTime(), at(6, 59)))
        assertEquals(KidsTime.Left(12.hours + 30.minutes), kidsTime(evening, DayTime(), at(7, 0)))
    }

    @Test
    fun aDaytimeBedtimeHoldsOnlyInsideIt() {
        val nap = ProfileRestrictions(bedtime = Bedtime(startMinute = 13 * 60, endMinute = 15 * 60))

        assertEquals(KidsTime.Bedtime(LocalTime.of(15, 0)), kidsTime(nap, DayTime(), at(14, 0)))
        assertEquals(KidsTime.Left(22.hours), kidsTime(nap, DayTime(), at(15, 0)))
    }

    /** "See you tomorrow" outlasts a bedtime that ends today. */
    @Test
    fun aUsedUpLimitInsideBedtimeIsTimesUp() {
        val both = limit(30).copy(bedtime = EVENING)

        assertEquals(KidsTime.TimesUp, kidsTime(both, DayTime(usedSeconds = 1_800), at(20, 0)))
    }

    /** The owner's grant wins over both the limit and bedtime while it runs. */
    @Test
    fun moreTimeRunsPastTheLimitAndBedtimeUntilSpent() {
        val both = limit(30).copy(bedtime = EVENING)

        assertEquals(
            KidsTime.Left(15.minutes),
            kidsTime(both, DayTime(usedSeconds = 1_800, moreTimeMinutes = 15, allowedUntilSeconds = 2_700), at(20, 0)),
        )
        assertEquals(
            KidsTime.TimesUp,
            kidsTime(both, DayTime(usedSeconds = 2_700, moreTimeMinutes = 15, allowedUntilSeconds = 2_700), at(20, 0)),
        )
    }

    @Test
    fun warningsComeAtFiveMinutesThenAtOne() {
        assertNull(dueWarning(KidsTime.Left(6.minutes), lastWarned = null))
        assertEquals(5, dueWarning(KidsTime.Left(5.minutes), lastWarned = null))
        assertNull(dueWarning(KidsTime.Left(3.minutes), lastWarned = 5))
        assertEquals(1, dueWarning(KidsTime.Left(1.minutes), lastWarned = 5))
        assertNull(dueWarning(KidsTime.Left(30.seconds), lastWarned = 1))
    }

    @Test
    fun openingUnderAMarkWarnsAtTheSmallestReached() {
        assertEquals(5, dueWarning(KidsTime.Left(3.minutes), lastWarned = null))
        assertEquals(1, dueWarning(KidsTime.Left(30.seconds), lastWarned = null))
    }

    @Test
    fun onlyTimeLeftWarns() {
        assertNull(dueWarning(KidsTime.TimesUp, lastWarned = null))
        assertNull(dueWarning(KidsTime.Unlimited, lastWarned = null))
    }

    private fun limit(minutes: Int): ProfileRestrictions =
        ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to minutes))

    /** On Saturday 2026-10-10. */
    private fun at(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(2026, 10, 10, hour, minute)

    private companion object {
        val EVENING = Bedtime(startMinute = 19 * 60 + 30, endMinute = 7 * 60)
    }
}
