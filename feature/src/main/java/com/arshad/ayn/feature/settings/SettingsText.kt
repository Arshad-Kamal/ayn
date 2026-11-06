package com.arshad.ayn.feature.settings

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Text shared by every Settings page on both UIs (M6-D12).

fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** "a", "a and b", "a, b and c"; empty for none. */
fun spokenList(parts: List<String>): String =
    if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString() + " and " + parts.last()

/** A date as the Settings rows say it ("October 3"; another year than [today]'s with its year). */
fun monthDayText(epochMillis: Long, today: LocalDate, zone: ZoneId, locale: Locale): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val skeleton = if (date.year == today.year) "MMMMd" else "MMMMdyyyy"
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}

/** When a listed rule was added (Q52): "Added today", "yesterday", "3 days ago" within the week (the
 *  Library's day words), then its date ("Added October 2"). */
fun addedText(epochMillis: Long, today: LocalDate, zone: ZoneId, locale: Locale): String {
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(), today).coerceAtLeast(0)
    val ago = when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> monthDayText(epochMillis, today, zone, locale)
    }
    return "Added $ago"
}
