package com.arshad.ayn.core.kids

import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.ProfileRestrictions
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where a kids profile's watch time stands (KP-D8, D22). */
sealed interface KidsTime {
    /** Not a kids profile, or a kids day with no limit and no bedtime. */
    data object Unlimited : KidsTime

    /** No time from YouTube yet: nothing plays until an InnerTube answer sets the clock. */
    data object Unknown : KidsTime

    /** [left] until the day's limit, the bedtime or the owner's More time runs out. */
    data class Left(val left: Duration) : KidsTime

    /** The day's limit is used up. */
    data object TimesUp : KidsTime

    /** It's bedtime until [endsAt] (local time). */
    data class Bedtime(val endsAt: LocalTime) : KidsTime
}

val KidsTime.allowsPlay: Boolean get() = this is KidsTime.Unlimited || this is KidsTime.Left

/** Time's up or bedtime: the session ends and nothing plays (KP-D8). */
val KidsTime.stopped: Boolean get() = this is KidsTime.TimesUp || this is KidsTime.Bedtime

/** The resolve's refusal while a kids profile's time is up, or not yet known (KP-D22). */
class PlayTimeUp : Exception("the kids profile's time is up")

/** The minutes-left marks a warning shows at, largest first; 0 is Time's up itself (KP-D8). */
val WARNING_MINUTES: List<Int> = listOf(5, 1)

/**
 * A kids profile's time at [now] (local) from the owner's [restrictions] and the day's [ledger]:
 * the time left is the nearer of the day's limit and the bedtime's start; More time still running
 * stretches past both. A used-up limit reads [KidsTime.TimesUp] even inside bedtime: it lasts
 * until tomorrow, which bedtime's end may not reach.
 */
fun kidsTime(restrictions: ProfileRestrictions, ledger: DayTime, now: LocalDateTime): KidsTime {
    val limitLeft = restrictions.dailyLimitMinutes[now.dayOfWeek]
        ?.let { (it.minutes - ledger.usedSeconds.seconds).coerceAtLeast(Duration.ZERO) }
    val bedtime = restrictions.bedtime
    val untilBedtime = bedtime?.untilStart(now.toLocalTime().toSecondOfDay())
    val ordinaryLeft = listOfNotNull(limitLeft, untilBedtime).minOrNull() ?: return KidsTime.Unlimited
    val grantLeft = (ledger.allowedUntilSeconds - ledger.usedSeconds).coerceAtLeast(0).seconds
    val left = maxOf(ordinaryLeft, grantLeft)
    if (left > Duration.ZERO) return KidsTime.Left(left)
    if (limitLeft == Duration.ZERO || bedtime == null) return KidsTime.TimesUp
    return KidsTime.Bedtime(LocalTime.ofSecondOfDay(bedtime.endMinute * SECONDS_PER_MINUTE))
}

/**
 * The warning [time] calls for, in minutes left, when [lastWarned] (the last one published, `null`
 * for none) hasn't covered it: the smallest mark reached, so opening a profile with 3 minutes left
 * warns at 5 and later at 1.
 */
fun dueWarning(time: KidsTime, lastWarned: Int?): Int? {
    val left = (time as? KidsTime.Left)?.left ?: return null
    val due = WARNING_MINUTES.lastOrNull { left <= it.minutes } ?: return null
    return due.takeIf { lastWarned == null || it < lastWarned }
}

/** Zero inside the window, else the time to its next start. */
private fun Bedtime.untilStart(secondOfDay: Int): Duration {
    val start = startMinute * SECONDS_PER_MINUTE.toInt()
    val end = endMinute * SECONDS_PER_MINUTE.toInt()
    val inside = if (start < end) secondOfDay in start..<end else secondOfDay >= start || secondOfDay < end
    return if (inside) Duration.ZERO else Math.floorMod(start - secondOfDay, SECONDS_PER_DAY).seconds
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_DAY = 24 * 60 * 60
