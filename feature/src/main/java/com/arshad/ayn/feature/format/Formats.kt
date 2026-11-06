package com.arshad.ayn.feature.format

import com.arshad.ayn.core.access.VideoItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** A video length as a badge shows it ("4:19", "1:02:03"); `null` when unknown (RSS, live). */
fun lengthText(seconds: Long): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.twoDigits()}:${s.twoDigits()}" else "$m:${s.twoDigits()}"
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

/**
 * A count the way YouTube abbreviates it: "999", "1.4K", "795K", "7.2M", "1B". Digits past
 * the shown ones are dropped, never rounded up ("1.49M" reads "1.4M").
 */
fun compactCount(count: Long): String {
    val (unit, suffix) = when {
        count < 1_000 -> return count.toString()
        count < 1_000_000 -> 1_000L to "K"
        count < 1_000_000_000 -> 1_000_000L to "M"
        else -> 1_000_000_000L to "B"
    }
    val tenths = count * 10 / unit
    val number = if (tenths < 100 && tenths % 10 != 0L) "${tenths / 10}.${tenths % 10}" else "${count / unit}"
    return number + suffix
}

/** "110K views"; `null` when the count is unknown (the extractor's -1). */
fun viewsText(count: Long): String? = when {
    count < 0 -> null
    count == 1L -> "1 view"
    else -> "${compactCount(count)} views"
}

/** "21M subscribers"; `null` when the count is unknown or hidden (the extractor's -1). */
fun subscribersText(count: Long): String? = when {
    count < 0 -> null
    count == 1L -> "1 subscriber"
    else -> "${compactCount(count)} subscribers"
}

/** "12 channels" in the subscriptions strip; nothing for none. */
fun channelsText(count: Int): String? = when (count) {
    0 -> null
    1 -> "1 channel"
    else -> "$count channels"
}

/** "24 videos" in a playlist; `null` when the count is unknown (the extractor's -1). */
fun videoCountText(count: Long): String? = when {
    count < 0 -> null
    count == 1L -> "1 video"
    else -> "$count videos"
}

/** "30 latest videos": how many of a channel's newest videos a page lists. */
fun latestVideosText(count: Int): String = if (count == 1) "1 latest video" else "$count latest videos"

/** A live stream's view count is its audience: "110 watching". */
fun watchingText(count: Long): String? = count.takeIf { it >= 0 }?.let { "${compactCount(it)} watching" }

/** A card's audience: only a card that says live counts watchers (another card's count is its
 *  total views). */
fun VideoItem.liveWatchingText(): String? = watchingText(viewCount).takeIf { isLive }

/** How long a live stream has run at [now]: "Started streaming 3 hours ago", "… just now". */
fun startedStreamingText(started: Instant, now: Instant): String =
    "Started streaming " + ageText(started, now).replaceFirstChar(Char::lowercaseChar)

/** How long a stream ran, from [started] to [ended]: "streamed for 3 hours", "… 45 minutes",
 *  "… 2 days"; a minute at least. */
fun streamedForText(started: Instant, ended: Instant): String {
    val length: Duration = ended - started
    val span = when {
        length < 1.hours -> plural(length.inWholeMinutes.coerceAtLeast(1), "minute")
        length < 1.days -> plural(length.inWholeHours, "hour")
        else -> plural(length.inWholeDays, "day")
    }
    return "streamed for $span"
}

private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

/** How long ago [uploaded] was, measured at [now]: "3 hours ago", "2 days ago", "5 weeks ago". */
fun ageText(uploaded: Instant, now: Instant): String {
    val age: Duration = now - uploaded
    return when {
        age < 1.minutes -> "Just now"
        age < 1.hours -> ago(age.inWholeMinutes, "minute")
        age < 1.days -> ago(age.inWholeHours, "hour")
        age < 7.days -> ago(age.inWholeDays, "day")
        age < 30.days -> ago(age.inWholeDays / 7, "week")
        age < 365.days -> ago(age.inWholeDays / 30, "month")
        else -> ago(age.inWholeDays / 365, "year")
    }
}

private fun ago(count: Long, unit: String): String = plural(count, unit) + " ago"

/** A video's age: when it starts if it hasn't ([upcomingText]), else from its upload instant when
 *  known, else YouTube's own text. */
fun VideoItem.ageText(now: Instant): String? = upcomingText() ?: uploadDate?.let { ageText(it, now) } ?: textualUploadDate

/** YouTube's own date text for a card that shows it as given, or when an upcoming video starts. */
fun VideoItem.dateText(): String? = upcomingText() ?: textualUploadDate

/**
 * An upcoming video's date: "Upcoming · Nov 3, 9:00 AM", its start read in [zone]; YouTube's own
 * text in place of the time when the listing gives no start. `null` for a video that isn't upcoming.
 */
fun VideoItem.upcomingText(zone: ZoneId = ZoneId.systemDefault()): String? {
    if (!upcoming) return null
    val start = uploadDate?.let { StartFormat.format(it.toJavaInstant().atZone(zone)) } ?: textualUploadDate
    return metaLine("Upcoming", start)
}

private val StartFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** The parts of a meta line joined the way every surface shows them ("NASA · 110 watching"). */
fun metaLine(vararg parts: String?): String = parts.filterNotNull().joinToString(MetaSeparator)

const val MetaSeparator = " · "

/** A description's first paragraph, its line breaks folded (the Home hero's and a channel's blurb). */
fun blurb(description: String): String? =
    description.split("\n\n").firstOrNull { it.isNotBlank() }?.trim()?.replace('\n', ' ')

/** A video's line on a card in a row: the channel and its age at [now], or its audience while live. */
fun cardMeta(video: VideoItem, now: Instant?): String? =
    metaLine(video.uploaderName, if (video.isLive) watchingText(video.viewCount) else now?.let { video.ageText(it) }).ifEmpty { null }
