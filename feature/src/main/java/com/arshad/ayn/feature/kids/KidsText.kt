package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.settings.KidSummary
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.feature.settings.spokenList
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration

// Kids profiles' text both UIs show (`kp-kids` kind…kidtime, profiles, ownlockheld, kidhome, warn5…moretimepick;
// KP.m: 12-hour times).

private val Weekdays = DayOfWeek.entries.filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }
private val Weekend = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
internal val ClockTime = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** A length of watching time: "45 min", "1 h", "1 h 30 min". */
fun limitText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** A day's limit in full words, or No limit (TV day tiles, the phone's TalkBack). */
fun dayLimitText(minutes: Int?): String = minutes?.let(::limitText) ?: "No limit"

/** A phone day tile's value (`m6-kids` `.day b`): "1 h 30" for 1 h 30 min, which fits a quarter
 *  of the width; any other limit as [dayLimitText]. */
fun tileLimitText(minutes: Int?): String =
    if (minutes != null && minutes > 60 && minutes % 60 != 0) "${minutes / 60} h ${minutes % 60}" else dayLimitText(minutes)

/** A minute of the day on a 12-hour clock: "7:30 PM". */
fun clockText(minuteOfDay: Int): String = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(ClockTime)

/** "Mon", "Sat" (a day tile). */
fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.US)

/** "Monday", "Saturday" (a day's sheet). */
fun DayOfWeek.long(): String = getDisplayName(TextStyle.FULL, Locale.US)

/** How [limits] read when weekdays share one and the weekend another; `null` when they vary. */
private class LimitShape(val weekdays: Int?, val weekend: Int?)

private fun shapeOf(limits: Map<DayOfWeek, Int>): LimitShape? {
    val weekdays = Weekdays.map { limits[it] }.distinct()
    val weekend = Weekend.map { limits[it] }.distinct()
    return if (weekdays.size == 1 && weekend.size == 1) LimitShape(weekdays.single(), weekend.single()) else null
}

/** The daily limits in a kids profile's row: "1 h a day, 1 h 30 min at weekends". */
fun rowLimitsText(limits: Map<DayOfWeek, Int>): String {
    val shape = shapeOf(limits) ?: return "limits vary by day"
    if (shape.weekdays == shape.weekend) return shape.weekdays?.let { "${limitText(it)} a day" } ?: "no daily limit"
    val weekdays = shape.weekdays?.let { "${limitText(it)} a day" } ?: "no limit on weekdays"
    val weekend = shape.weekend?.let { "${limitText(it)} at weekends" } ?: "no limit at weekends"
    return "$weekdays, $weekend"
}

/** The daily limits beside the time step's heading: "Mon to Fri 1 h · Sat and Sun 1 h 30 min". */
fun limitsHeadingText(limits: Map<DayOfWeek, Int>): String {
    val shape = shapeOf(limits) ?: return "Set per day"
    if (shape.weekdays == shape.weekend) return shape.weekdays?.let { "Every day ${limitText(it)}" } ?: "No limit any day"
    return "Mon to Fri ${dayLimitText(shape.weekdays).lowercase()} · Sat and Sun ${dayLimitText(shape.weekend).lowercase()}"
}

/** The daily limits on the Parent page (`kp-kids` parent): "1 h · weekends 1 h 30 min". */
fun parentLimitsText(limits: Map<DayOfWeek, Int>): String {
    val shape = shapeOf(limits) ?: return "Set per day"
    if (shape.weekdays == shape.weekend) return shape.weekdays?.let { "${limitText(it)} every day" } ?: "No limit"
    return "${dayLimitText(shape.weekdays)} · weekends ${dayLimitText(shape.weekend).lowercase()}"
}

/** A kids profile's row hint (`kp-kids` profiles): its approved channels and videos, its limits. */
fun kidsHint(contents: ProfileContents, kid: KidSummary?): String = listOf(
    "Kids profile",
    plural(contents.subscriptionCount, "approved channel"),
    plural(kid?.approvedVideos ?: 0, "approved video"),
    rowLimitsText(kid?.dailyLimitMinutes.orEmpty()),
).joinToString(" · ")

/** The Approve sheet's note (`kp-kids`, `m6-home-and-menus`): approvals live on [kid]'s Parent page. */
fun approveNoteText(kid: String): String = "Change it any time on $kid’s Parent page."

/** Why the owner's lock can't be removed while [kids] have kids profiles (`kp-kids` ownlockheld). */
fun lockHeldHint(kids: List<String>): String =
    if (kids.size == 1) "Needed while ${kids.single()} has a kids profile" else "Needed while ${spokenList(kids)} have kids profiles"

/** What the owner's lock does for [kids] (`kp-kids` ownlockheld's note, without the mockup's
 *  "she": Ayn doesn't know a child's pronouns). */
fun lockHeldNote(kids: List<String>): String {
    val profile = if (kids.size == 1) "${kids.single()}’s profile" else "a kids profile"
    return "Your lock leaves $profile, adds more time and opens its Parent page."
}

/** "48 min", "1 h 5 min", "2 h" ([limitText]): minutes rounded up, so the time left never shows 0
 *  while time is left. */
fun timeLeftText(left: Duration): String =
    limitText(((left.inWholeSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt())

/** A warning's line (`kp-kids` warn5, warn1): "5 minutes left today", "1 minute left today". */
fun warningText(minutes: Int): String = if (minutes == 1) "1 minute left today" else "$minutes minutes left today"

/** What Time's up says: [title] and [body], for a kid and whether a video was stopped. */
class StoppedCopy(val title: String, val body: String)

/** `kp-kids` timesup and bedtime; without a stopped video ([videoSaved] false) no place is named. */
fun stoppedCopy(kid: String, time: KidsTime, videoSaved: Boolean): StoppedCopy {
    if (time is KidsTime.Bedtime) {
        val opens = clockText(time.endsAt.hour * MINUTES_PER_HOUR + time.endsAt.minute)
        val saved = if (videoSaved) " Your video is saved for the morning." else ""
        return StoppedCopy("It’s bedtime", "Ayn opens again at $opens.$saved Sleep well, $kid!")
    }
    val saved = if (videoSaved) " Your video is saved right where you stopped." else ""
    return StoppedCopy("Time’s up for today", "That’s all for today, $kid.$saved See you tomorrow!")
}

/** More time's amount (`kp-kids` moretimepick): today only, tomorrow's limit when there is one. */
fun moreTimePickBody(kid: String, limitTomorrow: Int?, failed: Boolean): String {
    if (failed) return "That didn’t save, so $kid’s time is still up. Try again."
    return "For today only." + (limitTomorrow?.let { " Tomorrow $kid has the usual ${limitText(it)}." } ?: "")
}

/** "15 more minutes", "30 more minutes", "1 more hour". */
fun moreTimeLabel(minutes: Int): String =
    if (minutes % MINUTES_PER_HOUR == 0) {
        val hours = minutes / MINUTES_PER_HOUR
        if (hours == 1) "1 more hour" else "$hours more hours"
    } else {
        "$minutes more minutes"
    }

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60
