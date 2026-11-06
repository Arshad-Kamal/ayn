package com.arshad.ayn.feature.library

import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** History grouped by the day each video was last watched, named as `b3-library` history names them. */
class HistoryDaysTest {
    private val zone = ZoneId.of("America/Toronto")

    /** Saturday 2026-10-03, 21:00 in Toronto. */
    private val now = Instant.parse("2026-10-04T01:00:00Z")

    private fun watchedAt(id: String, iso: String): HistoryVideo =
        watchEntry(id, lastWatchedAtMillis = Instant.parse(iso).toEpochMilliseconds()).let { listOf(it).historyVideos().single() }

    @Test
    fun eachDayIsNamedByHowLongAgoItWas() {
        val videos = listOf(
            watchedAt("aaaaaaaaaaa", "2026-10-04T00:12:00Z"), // Saturday 20:12
            watchedAt("bbbbbbbbbbb", "2026-10-03T02:00:00Z"), // Friday 22:00
            watchedAt("ccccccccccc", "2026-09-27T16:00:00Z"), // six days back
            watchedAt("ddddddddddd", "2026-09-26T16:00:00Z"), // a week back
        )

        val days = historyDays(videos, now, zone)

        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 3) to HistoryDayName.Today,
                LocalDate.of(2026, 10, 2) to HistoryDayName.Yesterday,
                LocalDate.of(2026, 9, 27) to HistoryDayName.Weekday,
                LocalDate.of(2026, 9, 26) to HistoryDayName.Date,
            ),
            days.map { it.date to it.name },
        )
    }

    @Test
    fun aDayHoldsItsVideosInTheOrderGiven() {
        val late = watchedAt("aaaaaaaaaaa", "2026-10-04T00:12:00Z")
        val early = watchedAt("bbbbbbbbbbb", "2026-10-03T13:00:00Z") // Saturday 09:00

        val days = historyDays(listOf(late, early), now, zone)

        assertEquals(listOf(listOf(late, early)), days.map { it.videos })
    }

    @Test
    fun aTimeAheadOfTheClockCountsAsToday() {
        val ahead = watchedAt("aaaaaaaaaaa", "2026-10-05T12:00:00Z")

        val days = historyDays(listOf(ahead), now, zone)

        assertEquals(LocalDate.of(2026, 10, 3) to HistoryDayName.Today, days.single().date to days.single().name)
    }
}
