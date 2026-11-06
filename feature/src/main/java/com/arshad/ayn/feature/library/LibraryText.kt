package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.leftText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.timecode
import com.arshad.ayn.feature.format.videoCountText
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

// The Library's device-neutral words (`b3-library`, `m6-library-and-playlist`), shared by both UIs.

/** Where a history video was left: "34:51 of 47:42", "Watched", or nothing when it has no mark. */
fun historyMarkText(mark: WatchMark?): String? = when (mark) {
    is WatchMark.PartWay -> "${timecode(mark.positionMs)} of ${timecode(mark.durationMs)}"
    WatchMark.Watched -> "Watched"
    null -> null
}

/** A History row's line: "Veritasium · 34:51 of 47:42", "· Watched", or the channel alone. */
fun historyRowLine(video: VideoItem, mark: WatchMark?): String? = metaLine(video.uploaderName, historyMarkText(mark)).ifEmpty { null }

/** A saved video's card (Watch later): the channel, then what's left, else its age. */
fun savedCardMeta(video: VideoItem, mark: WatchMark?, now: Instant): String? =
    metaLine(video.uploaderName, leftText(mark) ?: video.ageText(now)).ifEmpty { null }

/** "214 videos" in a row's heading. */
fun videosText(count: Int): String = videoCountText(count.toLong()).orEmpty()

/** "4 playlists" in Your playlists' heading; none yet says so. */
fun playlistsText(count: Int): String = when (count) {
    0 -> "None yet"
    1 -> "1 playlist"
    else -> "$count playlists"
}

/** A playlist tile's badge: its count, "No videos" when empty. */
fun playlistCountText(count: Int): String = if (count == 0) "No videos" else videosText(count)

/** A playlist's running time ("3 h 39 min", "45 min"); `null` when no length is known. */
fun playlistLengthText(totalSeconds: Long): String? {
    val minutes = totalSeconds / 60
    val hours = minutes / 60
    return when {
        totalSeconds <= 0 -> null
        hours == 0L -> "${minutes.coerceAtLeast(1)} min"
        minutes % 60 == 0L -> "$hours h"
        else -> "$hours h ${minutes % 60} min"
    }
}

/**
 * When a playlist last changed, by calendar day in [zone]: "Updated today", "yesterday", "3 days
 * ago", "last week", "2 weeks ago", "last month", and so on. A time past today (a clock running
 * behind) counts as today.
 */
fun updatedText(updatedAt: Instant, now: Instant, zone: ZoneId): String {
    val days = ChronoUnit.DAYS.between(updatedAt.localDate(zone), now.localDate(zone)).coerceAtLeast(0)
    val ago = when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> lastOr(days / 7, "week")
        days < 365 -> lastOr(days / 30, "month")
        else -> lastOr(days / 365, "year")
    }
    return "Updated $ago"
}

private fun lastOr(count: Long, unit: String): String = if (count == 1L) "last $unit" else "$count ${unit}s ago"

/** When a playlist last changed (its save, removal or rename). */
val PlaylistSummary.updatedAt: Instant get() = Instant.fromEpochMilliseconds(updatedAtMillis)

/** A playlist tile's line: its running time and when it last changed. */
fun playlistMeta(totalSeconds: Long, updatedAt: Instant, now: Instant, zone: ZoneId): String =
    metaLine(playlistLengthText(totalSeconds), updatedText(updatedAt, now, zone))

/** A saved playlist's row: the channel and the video's age. */
fun savedRowLine(video: VideoItem, now: Instant): String? = metaLine(video.uploaderName, video.ageText(now)).ifEmpty { null }

/** What deleting a playlist takes and leaves. */
fun deletePlaylistText(count: Int): String = if (count == 0) {
    "This removes the playlist, which has no videos. It can’t be undone."
} else {
    val saved = if (count == 1) "1 saved video" else "$count saved videos"
    "This removes the playlist and its $saved from it. The videos stay in your history, Watch later and other " +
        "playlists. It can’t be undone."
}

/**
 * A history day's heading and the line beside it: "Today" · "Saturday, October 3", "Thursday" ·
 * "October 1", and past a week the date · its weekday (the year added when it isn't [today]'s).
 */
fun dayHeading(day: HistoryDay, today: LocalDate, locale: Locale): Pair<String, String> {
    fun format(skeleton: String): String = DateTimeFormatter.ofPattern(bestPattern(locale, skeleton), locale).format(day.date)
    return when (day.name) {
        HistoryDayName.Today -> "Today" to format("EEEEMMMMd")
        HistoryDayName.Yesterday -> "Yesterday" to format("EEEEMMMMd")
        HistoryDayName.Weekday -> format("EEEE") to format("MMMMd")
        HistoryDayName.Date -> format(if (day.date.year == today.year) "MMMMd" else "yMMMMd") to format("EEEE")
    }
}

/** The locale's own order for a date skeleton ("EEEE, MMMM d" in English). */
private fun bestPattern(locale: Locale, skeleton: String): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)

/** The time of day in [zone] as the device shows it ("8:12 PM", or "20:12" on a 24-hour clock). */
fun timeOfDayText(at: Instant, zone: ZoneId, locale: Locale, use24Hour: Boolean): String =
    DateTimeFormatter.ofPattern(bestPattern(locale, if (use24Hour) "Hm" else "hm"), locale).withZone(zone).format(at.toJavaInstant())

fun Instant.localDate(zone: ZoneId): LocalDate = toJavaInstant().atZone(zone).toLocalDate()
