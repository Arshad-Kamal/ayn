package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Library's lines, counts and dates (`b3-library`, `m6-library-and-playlist`); date patterns come from the platform. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTextTest {
    private val zone: ZoneId = ZoneId.of("America/Toronto")
    private val video = VideoItem(VideoId("JsBZOcqZerk"), "Enigma", "Veritasium", null, 2862, -1, null, null, null, isLive = false)
    private val partWay = WatchMark.PartWay(positionMs = 2_091_000, durationMs = 2_862_000)
    private val now: Instant = LocalDateTime.of(2026, 10, 3, 21, 0).inZone()
    private val today = LocalDate.of(2026, 10, 3)

    private fun LocalDateTime.inZone(): Instant = atZone(zone).toInstant().toKotlinInstant()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Instant = LocalDateTime.of(year, month, day, hour, 0).inZone()

    @Test
    fun aHistoryRowSaysWhereTheVideoWasLeft() {
        assertEquals("Veritasium · 34:51 of 47:42", historyRowLine(video, partWay))
        assertEquals("Veritasium · Watched", historyRowLine(video, WatchMark.Watched))
        assertEquals("Veritasium", historyRowLine(video, null))
        assertNull(historyRowLine(video.copy(uploaderName = null), null))
    }

    @Test
    fun aSavedCardSaysWhatIsLeftElseItsAge() {
        val uploaded = video.copy(uploadDate = at(2026, 9, 26))
        assertEquals("Veritasium · 12:51 left", savedCardMeta(uploaded, partWay, now))
        assertEquals("Veritasium · 1 week ago", savedCardMeta(uploaded, WatchMark.Watched, now))
        assertEquals("Veritasium", savedCardMeta(video, null, now))
    }

    @Test
    fun aSavedPlaylistRowSaysTheChannelAndTheVideosAge() {
        assertEquals("Veritasium · 1 week ago", savedRowLine(video.copy(uploadDate = at(2026, 9, 26)), now))
        assertEquals("Veritasium", savedRowLine(video, now))
        assertNull(savedRowLine(video.copy(uploaderName = null), now))
    }

    @Test
    fun deletingAPlaylistSaysHowManySavedVideosGoWithIt() {
        assertEquals(
            "This removes the playlist and its 1 saved video from it. The videos stay in your history, Watch later and " +
                "other playlists. It can’t be undone.",
            deletePlaylistText(1),
        )
        assertEquals(true, deletePlaylistText(8).startsWith("This removes the playlist and its 8 saved videos from it."))
        assertEquals("This removes the playlist, which has no videos. It can’t be undone.", deletePlaylistText(0))
    }

    @Test
    fun countsReadAsTheMockupWritesThem() {
        assertEquals("1 video", videosText(1))
        assertEquals("214 videos", videosText(214))
        assertEquals("None yet", playlistsText(0))
        assertEquals("1 playlist", playlistsText(1))
        assertEquals("4 playlists", playlistsText(4))
        assertEquals("No videos", playlistCountText(0))
        assertEquals("8 videos", playlistCountText(8))
    }

    @Test
    fun aPlaylistsRunningTimeIsInHoursAndMinutes() {
        assertEquals("3 h 39 min", playlistLengthText(3 * 3600 + 39 * 60 + 20))
        assertEquals("2 h", playlistLengthText(2 * 3600 + 30))
        assertEquals("45 min", playlistLengthText(45 * 60 + 59))
        assertEquals("1 min", playlistLengthText(30))
        assertNull(playlistLengthText(0))
    }

    @Test
    fun updatedCountsCalendarDaysInTheZone() {
        assertEquals("Updated today", updatedText(at(2026, 10, 3, hour = 0), now, zone))
        assertEquals("Updated today", updatedText(at(2026, 10, 4), now, zone))
        assertEquals("Updated yesterday", updatedText(at(2026, 10, 2, hour = 23), now, zone))
        assertEquals("Updated 3 days ago", updatedText(at(2026, 9, 30), now, zone))
        assertEquals("Updated last week", updatedText(at(2026, 9, 26), now, zone))
        assertEquals("Updated 2 weeks ago", updatedText(at(2026, 9, 19), now, zone))
        assertEquals("Updated last month", updatedText(at(2026, 9, 1), now, zone))
        assertEquals("Updated 5 months ago", updatedText(at(2026, 5, 1), now, zone))
        assertEquals("Updated 2 years ago", updatedText(at(2024, 9, 1), now, zone))
    }

    @Test
    fun aPlaylistTileLineJoinsItsLengthAndItsUpdate() {
        assertEquals("1 h 20 min · Updated today", playlistMeta(80 * 60, now, now, zone))
        assertEquals("Updated today", playlistMeta(0, now, now, zone))
    }

    @Test
    fun dayHeadingsFollowTheMockup() {
        fun heading(date: LocalDate, name: HistoryDayName) = dayHeading(HistoryDay(date, name, emptyList()), today, Locale.US)

        assertEquals("Today" to "Saturday, October 3", heading(today, HistoryDayName.Today))
        assertEquals("Yesterday" to "Friday, October 2", heading(today.minusDays(1), HistoryDayName.Yesterday))
        assertEquals("Thursday" to "October 1", heading(today.minusDays(2), HistoryDayName.Weekday))
        assertEquals("September 20" to "Sunday", heading(LocalDate.of(2026, 9, 20), HistoryDayName.Date))
        assertEquals("December 24, 2025" to "Wednesday", heading(LocalDate.of(2025, 12, 24), HistoryDayName.Date))
    }

    @Test
    fun theTimeOfDayFollowsTheDevicesClockStyle() {
        val at = LocalDateTime.of(2026, 10, 3, 20, 12).inZone()
        assertEquals("8:12 PM", timeOfDayText(at, zone, Locale.US, use24Hour = false))
        assertEquals("20:12", timeOfDayText(at, zone, Locale.US, use24Hour = true))
    }
}
