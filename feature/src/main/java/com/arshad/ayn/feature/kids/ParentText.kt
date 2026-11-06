package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.settings.plural
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.toJavaInstant

// The Parent page's and the review's text both UIs show (`kp-kids` parent, review; `m6-kids` 14–20;
// KP.m: 12-hour times, neutral pronouns: Ayn doesn't know a child's).

private val WeekOf = DateTimeFormatter.ofPattern("MMMM d", Locale.US)

/** How many approved channels the Parent page names before it counts the rest. */
private const val NAMED_CHANNELS = 6

/** Beside the Parent page's title: whose profile, and whose lock opened it. */
fun parentAside(kid: String, owner: String, ownerLocked: Boolean): String =
    if (ownerLocked) "$kid’s profile · opened with $owner’s lock" else "$kid’s profile"

/** The Review row's hint; [newUploads] is `null` until the review has looked. */
fun reviewHint(kid: String, newUploads: Int?): String {
    val start = "Time used this week, what $kid watched"
    return when (newUploads) {
        null -> start
        0 -> "$start, no new videos from $kid’s channels"
        else -> "$start, ${plural(newUploads, "new video")} from $kid’s channels"
    }
}

/** The Review row's value: today's watch time ("42 min today"). */
fun usedTodayText(usedSeconds: Long): String = "${limitText((usedSeconds / 60).toInt())} today"

/** The approved channels' names, the first few then how many more; "None yet" without any. */
fun channelsHint(names: List<String>): String {
    if (names.isEmpty()) return "None yet"
    val named = names.take(NAMED_CHANNELS).joinToString(", ")
    val more = names.size - NAMED_CHANNELS
    return if (more > 0) "$named and $more more" else named
}

/** Bedtime's row value: "7:30 PM – 7:00 AM", or Off. */
fun bedtimeText(bedtime: Bedtime?): String =
    bedtime?.let { "${clockText(it.startMinute)} – ${clockText(it.endMinute)}" } ?: "Off"

/** The Bedtime sheet's line: when Ayn doesn't open for [kid], or that it always does. */
fun bedtimeSub(kid: String, bedtime: Bedtime?): String =
    bedtime?.let { "Ayn doesn’t open for $kid from ${clockText(it.startMinute)} to ${clockText(it.endMinute)}." }
        ?: "Off: Ayn opens for $kid at any time, within the daily limit."

/** "Zainab · week of October 5". */
fun reviewAside(kid: String, monday: LocalDate): String = "$kid · week of ${monday.format(WeekOf)}"

/** Beside "Time this week": today's time against today's limit ("42 min of 1 h 30 min today"). */
fun todayAgainstLimit(today: ReviewDay?): String {
    val used = limitText(((today?.usedSeconds ?: 0) / 60).toInt())
    val limit = today?.limitMinutes ?: return "$used today"
    return "$used of ${limitText(limit + today.moreTimeMinutes)} today"
}

/** A review bar read out: the day, what was watched against its limit, and any More time
 *  ("Thursday, 1 h 15 min watched, limit 1 h, 15 min more time"). */
fun reviewDaySpoken(day: ReviewDay): String {
    val minutes = (day.usedSeconds / 60).toInt()
    return listOfNotNull(
        if (day.isToday) "Today" else day.date.dayOfWeek.long(),
        if (minutes == 0) "nothing watched" else "${limitText(minutes)} watched",
        day.limitMinutes?.let { "limit ${limitText(it)}" } ?: "no limit",
        day.moreTimeMinutes.takeIf { it > 0 }?.let { "${limitText(it)} more time" },
    ).joinToString(", ")
}

/** A day's watch time under its bar, short enough for the column: "52 min", "1 h", "1 h 15", "–". */
fun barTimeText(usedSeconds: Long): String {
    val minutes = (usedSeconds / 60).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        minutes == 0 -> "–"
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest"
    }
}

/** A watched-today row's line: "Bluey · 24 min, 4:12 PM" (the minutes when known). */
fun watchedLine(watched: HistoryVideo, zone: ZoneId): String {
    val at = watched.lastWatchedAt.toJavaInstant().atZone(zone).format(ClockTime)
    val minutes = when (val mark = watched.mark) {
        is WatchMark.PartWay -> mark.positionMs / 60_000
        WatchMark.Watched -> watched.video.durationSeconds.takeIf { it > 0 }?.let { it / 60 }
        null -> null
    }
    val time = minutes?.takeIf { it > 0 }?.let { "${limitText(it.toInt())}, $at" } ?: at
    return metaLine(watched.video.uploaderName, time)
}

/** Above the new uploads: how many, and what not acting on them means. */
fun newUploadsAside(count: Int): String = "$count · allowed unless you block one"

/** How many of today's videos the review lists before it counts the rest. */
const val WATCHED_TODAY_SHOWN = 5

const val NOTHING_WATCHED_TODAY = "Nothing yet today."

/** Under the listed videos watched today: how many more there were. */
fun moreWatchedTodayText(more: Int): String = "and $more more today"

fun uploadsLoadingText(kid: String): String = "Looking for new videos from $kid’s channels…"

fun uploadsFailedText(kid: String): String = "Ayn couldn’t reach $kid’s channels. Check the connection, then try again."

const val NO_NEW_UPLOADS_TEXT = "Nothing new in the last $NEW_UPLOAD_DAYS days."

/** Under the new uploads when [failed] of the channels couldn't be read. */
fun uploadsPartText(failed: Int, kid: String): String = "$failed of $kid’s channels didn’t answer; their videos aren’t here."
