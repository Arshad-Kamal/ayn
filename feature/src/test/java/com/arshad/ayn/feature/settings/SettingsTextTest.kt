package com.arshad.ayn.feature.settings

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text every Settings page shares. Robolectric only for the locale's date pattern. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTextTest {

    @Test
    fun aListIsSpokenWithAndBeforeItsLastPart() {
        assertEquals(listOf("", "Mira", "Mira and Yuri", "Mira, Yuri and Zainab"), listOf(0, 1, 2, 3).map { spokenList(NAMES.take(it)) })
    }

    @Test
    fun aCountTakesThePluralPastOne() {
        assertEquals(listOf("0 words", "1 word", "2 words"), listOf(0, 1, 2).map { plural(it, "word") })
    }

    @Test
    fun aDateFromAnotherYearCarriesItsYear() {
        val october3 = LocalDate.of(2026, 10, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals("October 3", monthDayText(october3, LocalDate.of(2026, 12, 1), ZoneOffset.UTC, Locale.US))
        assertEquals("October 3, 2026", monthDayText(october3, LocalDate.of(2027, 1, 1), ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun aDateIsTheDayInTheGivenZone() {
        val earlyOctober4Utc = Instant.parse("2026-10-04T03:00:00Z").toEpochMilli()

        assertEquals("October 3", monthDayText(earlyOctober4Utc, LocalDate.of(2026, 12, 1), ZoneId.of("America/Edmonton"), Locale.US))
    }

    @Test
    fun aRuleSaysWhenItWasAddedInDayWordsForAWeekThenByDate() {
        val today = LocalDate.of(2026, 10, 6)
        fun added(daysBefore: Long) = addedText(today.minusDays(daysBefore).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), today, ZoneOffset.UTC, Locale.US)

        assertEquals(
            listOf("Added today", "Added today", "Added yesterday", "Added 3 days ago", "Added 6 days ago", "Added September 29"),
            listOf(-1L, 0L, 1L, 3L, 6L, 7L).map(::added),
        )
    }

    private companion object {
        val NAMES = listOf("Mira", "Yuri", "Zainab")
    }
}
