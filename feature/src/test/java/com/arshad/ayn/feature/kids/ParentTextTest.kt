package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.settings.DEFAULT_DAILY_LIMITS
import com.arshad.ayn.feature.testVideo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Parent page's and the review's text (`kp-kids` parent, review; `m6-kids` 14–20). */
class ParentTextTest {

    @Test
    fun theReviewRowSaysTodaysTimeAndTheNewUploadsOnceKnown() {
        assertEquals("42 min today", usedTodayText(42 * 60 + 30))
        assertEquals("Time used this week, what Zainab watched", reviewHint("Zainab", null))
        assertEquals("Time used this week, what Zainab watched, 4 new videos from Zainab’s channels", reviewHint("Zainab", 4))
        assertEquals("Time used this week, what Zainab watched, no new videos from Zainab’s channels", reviewHint("Zainab", 0))
    }

    @Test
    fun theAsideNamesTheOwnersLockOnlyWhenThereIsOne() {
        assertEquals("Zainab’s profile · opened with Arshad’s lock", parentAside("Zainab", "Arshad", ownerLocked = true))
        assertEquals("Zainab’s profile", parentAside("Zainab", "Arshad", ownerLocked = false))
    }

    @Test
    fun limitsAndBedtimeReadAsTheMockupShowsThem() {
        assertEquals("1 h · weekends 1 h 30 min", parentLimitsText(DEFAULT_DAILY_LIMITS))
        assertEquals("No limit", parentLimitsText(emptyMap()))
        assertEquals("45 min every day", parentLimitsText(DayOfWeek.entries.associateWith { 45 }))
        assertEquals("7:30 PM – 7:00 AM", bedtimeText(Bedtime(19 * 60 + 30, 7 * 60)))
        assertEquals("Off", bedtimeText(null))
        assertEquals("Ayn doesn’t open for Zainab from 7:30 PM to 7:00 AM.", bedtimeSub("Zainab", Bedtime(19 * 60 + 30, 7 * 60)))
        assertEquals("Off: Ayn opens for Zainab at any time, within the daily limit.", bedtimeSub("Zainab", null))
    }

    @Test
    fun manyChannelsAreNamedThenCounted() {
        val names = (1..8).map { "C$it" }

        assertEquals("C1, C2, C3, C4, C5, C6 and 2 more", channelsHint(names))
        assertEquals("C1, C2", channelsHint(names.take(2)))
        assertEquals("None yet", channelsHint(emptyList()))
    }

    @Test
    fun theWeekReadsShortUnderEachBarAndTodayAgainstItsAllowance() {
        assertEquals(listOf("–", "52 min", "1 h", "1 h 15"), listOf(0L, 52 * 60L, 60 * 60L, 75 * 60L).map(::barTimeText))
        val today = ReviewDay(LocalDate.of(2026, 10, 10), usedSeconds = 42 * 60, limitMinutes = 75, moreTimeMinutes = 15, isToday = true)
        assertEquals("42 min of 1 h 30 min today", todayAgainstLimit(today))
        assertEquals("42 min today", todayAgainstLimit(today.copy(limitMinutes = null)))
        assertEquals("Zainab · week of October 5", reviewAside("Zainab", LocalDate.of(2026, 10, 5)))
        assertEquals("4 · allowed unless you block one", newUploadsAside(4))
    }

    @Test
    fun aWatchedRowSaysHowLongWhenKnownAndWhen() {
        val at = Instant.parse("2026-10-16T16:12:00Z")
        val video = testVideo("watched0001", uploaderName = "Bluey").copy(durationSeconds = 36 * 60 + 32)

        assertEquals("Bluey · 24 min, 4:12 PM", watchedLine(HistoryVideo(video, WatchMark.PartWay(24 * 60_000L + 5_000, 36 * 60_000L), at), ZoneOffset.UTC))
        assertEquals("Bluey · 36 min, 4:12 PM", watchedLine(HistoryVideo(video, WatchMark.Watched, at), ZoneOffset.UTC))
        assertEquals("Bluey · 4:12 PM", watchedLine(HistoryVideo(video, null, at), ZoneOffset.UTC))
        assertEquals("Bluey · 4:12 PM", watchedLine(HistoryVideo(video, WatchMark.PartWay(20_000, 36 * 60_000L), at), ZoneOffset.UTC))
    }

    @Test
    fun aReviewBarReadsOutItsDayLimitAndMoreTime() {
        val thursday = ReviewDay(LocalDate.of(2026, 10, 15), usedSeconds = 75 * 60L, limitMinutes = 60, moreTimeMinutes = 15, isToday = false)

        assertEquals("Thursday, 1 h 15 min watched, limit 1 h, 15 min more time", reviewDaySpoken(thursday))
        assertEquals("Today, nothing watched, no limit", reviewDaySpoken(thursday.copy(usedSeconds = 30, limitMinutes = null, moreTimeMinutes = 0, isToday = true)))
    }

    @Test
    fun theReviewsListsSayWhatIsMissing() {
        assertEquals("and 3 more today", moreWatchedTodayText(3))
        assertEquals("Looking for new videos from Zainab’s channels…", uploadsLoadingText("Zainab"))
        assertEquals("Ayn couldn’t reach Zainab’s channels. Check the connection, then try again.", uploadsFailedText("Zainab"))
        assertEquals("Nothing new in the last 7 days.", NO_NEW_UPLOADS_TEXT)
        assertEquals("2 of Zainab’s channels didn’t answer; their videos aren’t here.", uploadsPartText(2, "Zainab"))
    }

    @Test
    fun theBarsScaleToTheWeeksBusiestDayWithAnHourAtLeast() {
        fun day(usedMinutes: Int, limit: Int?, more: Int = 0) =
            ReviewDay(LocalDate.of(2026, 10, 12), usedSeconds = usedMinutes * 60L, limitMinutes = limit, moreTimeMinutes = more, isToday = false)

        assertEquals(60, reviewScaleMinutes(emptyList()))
        assertEquals(60, reviewScaleMinutes(listOf(day(10, 30))))
        assertEquals(105, reviewScaleMinutes(listOf(day(52, 60), day(70, 90, more = 15))))
        assertEquals(130, reviewScaleMinutes(listOf(day(130, null), day(0, 90))))
    }
}
