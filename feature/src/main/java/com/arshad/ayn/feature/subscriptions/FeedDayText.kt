package com.arshad.ayn.feature.subscriptions

import java.time.format.DateTimeFormatter
import java.util.Locale

/** A day group's heading. */
fun dayTitle(day: FeedDay, locale: Locale): String = when (day) {
    is FeedDay.Today -> "Today"
    is FeedDay.Yesterday -> "Yesterday"
    FeedDay.EarlierThisWeek -> "Earlier this week"
    FeedDay.LastWeek -> "Last week"
    is FeedDay.Month -> day.month.format(localizedPattern(if (day.sameYear) "LLLL" else "LLLLyyyy", locale))
    FeedDay.Undated -> "Older"
}

/** Today and Yesterday carry their date ("Saturday, October 3"). */
fun dayNote(day: FeedDay, locale: Locale): String? = when (day) {
    is FeedDay.Today -> day.date.format(localizedPattern("EEEEMMMMd", locale))
    is FeedDay.Yesterday -> day.date.format(localizedPattern("EEEEMMMMd", locale))
    else -> null
}

private fun localizedPattern(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
