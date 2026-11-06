package com.arshad.ayn.feature.library

import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant

/** How a history day is named (`b3-library` history: "Today", "Yesterday", "Thursday", a date). */
enum class HistoryDayName {
    Today,
    Yesterday,

    /** Two to six days ago: labelled by its weekday. */
    Weekday,

    /** A week ago or more: labelled by its date. */
    Date,
}

data class HistoryDay(val date: LocalDate, val name: HistoryDayName, val videos: List<HistoryVideo>)

/**
 * Groups [videos] by the day each was last watched, as seen in [zone] at [now], days in the order
 * their first video comes (the store lists the most recently watched first) and each day's videos
 * in the order given. A time past today (a clock running behind) counts as today.
 */
fun historyDays(videos: List<HistoryVideo>, now: Instant, zone: ZoneId): List<HistoryDay> {
    val today = now.localDate(zone)
    return videos
        .groupBy { minOf(it.lastWatchedAt.localDate(zone), today) }
        .map { (date, dayVideos) ->
            val name = when {
                date == today -> HistoryDayName.Today
                date == today.minusDays(1) -> HistoryDayName.Yesterday
                date.isAfter(today.minusDays(WEEK_DAYS)) -> HistoryDayName.Weekday
                else -> HistoryDayName.Date
            }
            HistoryDay(date, name, dayVideos)
        }
}

/** A weekday name stays unambiguous for six days back; the seventh repeats today's. */
private const val WEEK_DAYS = 7L
