package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.testVideo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Day groups of the feed (`b3-subscriptions` state 1). "Now" is Saturday 2026-10-03 21:00 in
 * Toronto (UTC−4); expected groups are worked by hand from a calendar.
 */
class FeedDaysTest {

    private val now = Instant.parse("2026-10-04T01:00:00Z") // Sat 2026-10-03 21:00 in Toronto
    private val toronto = ZoneId.of("America/Toronto")

    @Test
    fun videosGroupByTodayYesterdayThisWeekLastWeekAndMonth() {
        val entries = listOf(
            video("t1", "2026-10-03T23:30:00Z"), // Sat 19:30 local
            video("t2", "2026-10-03T04:30:00Z"), // Sat 00:30 local
            video("y1", "2026-10-03T03:30:00Z"), // Fri 23:30 local
            video("w1", "2026-09-28T16:00:00Z"), // Mon
            video("l1", "2026-09-21T16:00:00Z"), // Mon last week
            video("m1", "2026-09-10T16:00:00Z"),
            video("m2", "2025-12-30T16:00:00Z"),
        )

        val groups = feedDays(entries, now, toronto, DayOfWeek.MONDAY)

        assertEquals(
            listOf(
                FeedDay.Today(LocalDate.of(2026, 10, 3)) to listOf("t1", "t2"),
                FeedDay.Yesterday(LocalDate.of(2026, 10, 2)) to listOf("y1"),
                FeedDay.EarlierThisWeek to listOf("w1"),
                FeedDay.LastWeek to listOf("l1"),
                FeedDay.Month(YearMonth.of(2026, Month.SEPTEMBER), sameYear = true) to listOf("m1"),
                FeedDay.Month(YearMonth.of(2025, Month.DECEMBER), sameYear = false) to listOf("m2"),
            ),
            groups.map { it.day to it.videos.map { video -> video.id.value.trimEnd('-') } },
        )
    }

    @Test
    fun theWeekStartsOnTheLocalesFirstDay() {
        // With Sunday first, this week began Sun 09-27: Sat 09-26 falls in last week.
        val entries = listOf(video("a", "2026-09-27T16:00:00Z"), video("b", "2026-09-26T16:00:00Z"))

        assertEquals(
            listOf(FeedDay.EarlierThisWeek, FeedDay.LastWeek),
            feedDays(entries, now, toronto, DayOfWeek.SUNDAY).map { it.day },
        )
        assertEquals(
            listOf(FeedDay.LastWeek),
            feedDays(entries, now, toronto, DayOfWeek.MONDAY).map { it.day },
        )
    }

    @Test
    fun onAWeeksFirstDayYesterdayIsStillYesterdayNotLastWeek() {
        // Monday 2026-10-05 12:00 UTC, weeks starting Monday: Sunday's upload is Yesterday.
        val monday = Instant.parse("2026-10-05T12:00:00Z")
        val entries = listOf(video("sunday", "2026-10-04T12:00:00Z"), video("saturday", "2026-10-03T12:00:00Z"))

        assertEquals(
            listOf(FeedDay.Yesterday(LocalDate.of(2026, 10, 4)), FeedDay.LastWeek),
            feedDays(entries, monday, ZoneOffset.UTC, DayOfWeek.MONDAY).map { it.day },
        )
    }

    @Test
    fun aDateAheadOfTheClockCountsAsTodayAndUndatedVideosCloseTheFeed() {
        val entries = listOf(video("future", "2026-10-05T12:00:00Z"), video("undated", null))

        assertEquals(
            listOf(FeedDay.Today(LocalDate.of(2026, 10, 3)), FeedDay.Undated),
            feedDays(entries, now, toronto, DayOfWeek.MONDAY).map { it.day },
        )
    }

    @Test
    fun theDayIsTheDeviceZonesDay() {
        // 2026-10-03 03:30Z is Friday evening in Toronto but Saturday in UTC.
        val entries = listOf(video("a", "2026-10-03T03:30:00Z"))

        assertEquals(FeedDay.Yesterday(LocalDate.of(2026, 10, 2)), feedDays(entries, now, toronto, DayOfWeek.MONDAY).single().day)
        assertEquals(FeedDay.Yesterday(LocalDate.of(2026, 10, 3)), feedDays(entries, now, ZoneOffset.UTC, DayOfWeek.MONDAY).single().day)
    }

    @Test
    fun noEntriesMakeNoGroups() {
        assertEquals(emptyList<FeedDayGroup>(), feedDays(emptyList(), now, toronto, DayOfWeek.MONDAY))
    }

    // Short readable names padded to the 11-character video id.
    private fun video(name: String, uploaded: String?): VideoItem =
        testVideo(name.padEnd(11, '-')).copy(uploadDate = uploaded?.let(Instant::parse))
}
