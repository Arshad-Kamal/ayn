package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.VideoItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The day a run of feed videos was uploaded in (`b3-subscriptions` state 1), newest first. */
sealed interface FeedDay {
    data class Today(val date: LocalDate) : FeedDay

    data class Yesterday(val date: LocalDate) : FeedDay

    /** This calendar week, before yesterday. */
    data object EarlierThisWeek : FeedDay

    data object LastWeek : FeedDay

    /** Older: the month, with [sameYear] false when it isn't this year's. */
    data class Month(val month: YearMonth, val sameYear: Boolean) : FeedDay

    /** Videos without an upload date (they sort last). */
    data object Undated : FeedDay
}

data class FeedDayGroup(val day: FeedDay, val videos: List<VideoItem>)

/**
 * Groups [entries] (newest first, as [SubscriptionsFeed.NewestFirst] sorts them) by the day each
 * was uploaded, as seen in [zone] at [now], with weeks starting on [firstDayOfWeek] (the
 * device's locale). A date past today (a clock running behind) counts as today.
 */
fun feedDays(entries: List<VideoItem>, now: Instant, zone: ZoneId, firstDayOfWeek: DayOfWeek): List<FeedDayGroup> {
    val today = now.toJavaInstant().atZone(zone).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    val lastWeekStart = weekStart.minusWeeks(1)
    fun dayOf(video: VideoItem): FeedDay {
        val date = video.uploadDate?.toJavaInstant()?.atZone(zone)?.toLocalDate() ?: return FeedDay.Undated
        return when {
            !date.isBefore(today) -> FeedDay.Today(today)
            date == today.minusDays(1) -> FeedDay.Yesterday(date)
            !date.isBefore(weekStart) -> FeedDay.EarlierThisWeek
            !date.isBefore(lastWeekStart) -> FeedDay.LastWeek
            else -> FeedDay.Month(YearMonth.from(date), sameYear = date.year == today.year)
        }
    }
    val runs = mutableListOf<Pair<FeedDay, MutableList<VideoItem>>>()
    for (video in entries) {
        val day = dayOf(video)
        if (runs.lastOrNull()?.first == day) runs.last().second += video else runs += day to mutableListOf(video)
    }
    return runs.map { (day, videos) -> FeedDayGroup(day, videos.toList()) }
}
