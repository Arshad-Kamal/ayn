package com.arshad.ayn.feature.format

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.WatchMark
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected strings are YouTube's own forms; (TEST-ORACLE-01). */
class FormatsTest {

    @Test
    fun playlistCountsAndLatestCountsSayVideoOrVideos() {
        assertEquals("1 video", videoCountText(1))
        assertEquals("24 videos", videoCountText(24))
        assertNull("the extractor's unknown count", videoCountText(-1))
        assertEquals("1 latest video", latestVideosText(1))
        assertEquals("30 latest videos", latestVideosText(30))
    }

    @Test
    fun aBlurbIsTheFirstParagraphWithItsLineBreaksFolded() {
        assertEquals("One two three", blurb("\n\nOne two\nthree\n\nFour"))
        assertNull(blurb("  \n\n "))
    }

    @Test
    fun lengthsShowMinutesAndSecondsHoursOnlyWhenThere() {
        assertEquals("4:19", lengthText(259))
        assertEquals("56:09", lengthText(3369))
        assertEquals("1:02:03", lengthText(3723))
        assertNull("RSS and live streams have no length", lengthText(-1))
        assertNull(lengthText(0))
    }

    @Test
    fun countsAbbreviateWithoutRoundingUp() {
        assertEquals(
            listOf("999", "1K", "1.4K", "9.9K", "795K", "1M", "1.4M", "7.2M", "15M", "2B"),
            listOf(999L, 1_000, 1_450, 9_999, 795_900, 1_000_000, 1_490_000, 7_200_000, 15_300_000, 2_000_000_000)
                .map(::compactCount),
        )
    }

    @Test
    fun unknownCountsShowNothing() {
        assertNull(viewsText(-1))
        assertNull(watchingText(-1))
        assertEquals("1 view", viewsText(1))
        assertEquals("110 watching", watchingText(110))
        assertNull(subscribersText(-1))
        assertEquals("1 subscriber", subscribersText(1))
        assertEquals("21M subscribers", subscribersText(21_300_000))
    }

    @Test
    fun agesUseTheLargestWholeUnit() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        assertEquals("Just now", ageText(now - 30.seconds, now))
        assertEquals("1 hour ago", ageText(now - 1.hours, now))
        assertEquals("2 days ago", ageText(now - 2.days, now))
        assertEquals("2 weeks ago", ageText(now - 15.days, now))
        assertEquals("2 months ago", ageText(now - 64.days, now))
        assertEquals("1 year ago", ageText(now - 400.days, now))
    }

    /** The recorded live page's own line reads "Started streaming 27 minutes ago". */
    @Test
    fun aLiveStreamSaysHowLongItHasRun() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        assertEquals("Started streaming 27 minutes ago", startedStreamingText(now - 27.minutes, now))
        assertEquals("Started streaming 3 hours ago", startedStreamingText(now - 3.hours - 59.minutes, now))
        assertEquals("Started streaming just now", startedStreamingText(now - 30.seconds, now))
    }

    @Test
    fun anEndedStreamSaysHowLongItRan() {
        val end = Instant.parse("2026-10-03T12:00:00Z")
        assertEquals("streamed for 1 minute", streamedForText(end - 20.seconds, end))
        assertEquals("streamed for 45 minutes", streamedForText(end - 45.minutes, end))
        assertEquals("streamed for 1 hour", streamedForText(end - 1.hours - 10.minutes, end))
        assertEquals("streamed for 3 hours", streamedForText(end - 3.hours - 59.minutes, end))
        assertEquals("streamed for 2 days", streamedForText(end - 50.hours, end))
    }

    private val video = VideoItem(VideoId("JsBZOcqZerk"), "Enigma", "Veritasium", null, 2862, -1, null, null, null, isLive = false)
    private val partWay = WatchMark.PartWay(positionMs = 2_091_000, durationMs = 2_862_000)

    @Test
    fun theWatchBarIsFullOnceWatchedAndAbsentWhenNeverStarted() {
        assertEquals(0.5f, WatchMark.PartWay(1_000, 2_000).barFraction())
        assertEquals(1f, WatchMark.Watched.barFraction())
        assertNull((null as WatchMark?).barFraction())
    }

    @Test
    fun aHistoryCardSaysWhatIsLeftOrWatched() {
        assertEquals("Veritasium · 12:51 left", historyCardMeta(video, partWay))
        assertEquals("Veritasium · Watched", historyCardMeta(video, WatchMark.Watched))
        assertEquals("Veritasium", historyCardMeta(video, null))
    }

    /** Premiere `34GOO-dOZNU` starts 2026-11-03 16:00 UTC; its search card read "2026-11-03 09:00"
     *  in MST. A fixed offset: tz databases disagree on Edmonton after 2026-11-01. */
    @Test
    fun anUpcomingVideoSaysWhenItStarts() {
        val mst = ZoneOffset.ofHours(-7)
        val premiere = video.copy(upcoming = true, durationSeconds = -1, uploadDate = Instant.parse("2026-11-03T16:00:00Z"), textualUploadDate = "2026-11-03 09:00")

        assertEquals("Upcoming · Nov 3, 9:00 AM", premiere.upcomingText(mst))
        assertEquals("Upcoming · Nov 13, 3:30 PM", premiere.copy(uploadDate = Instant.parse("2026-11-13T22:30:00Z")).upcomingText(mst))
        assertEquals("no start: YouTube's text", "Upcoming · 03/11/2026, 16:00", premiere.copy(uploadDate = null, textualUploadDate = "03/11/2026, 16:00").upcomingText(mst))
        assertEquals("Upcoming", premiere.copy(uploadDate = null, textualUploadDate = null).upcomingText(mst))
        assertNull(premiere.copy(upcoming = false).upcomingText(mst))
    }

    /** A card's age and date text say when an upcoming video starts, whatever the clock reads (a
     *  start already past included: the listing still calls it upcoming). */
    @Test
    fun anUpcomingVideosAgeAndDateAreItsStart() {
        val premiere = video.copy(upcoming = true, uploadDate = Instant.parse("2026-11-03T16:00:00Z"), textualUploadDate = "2026-11-03 09:00")
        val upcoming = premiere.upcomingText()

        assertEquals(upcoming, premiere.ageText(Instant.parse("2026-11-02T10:00:00Z")))
        assertEquals(upcoming, premiere.ageText(Instant.parse("2026-11-04T10:00:00Z")))
        assertEquals(upcoming, premiere.dateText())
        assertEquals("2026-11-03 09:00", premiere.copy(upcoming = false).dateText())
        assertEquals("1 day ago", premiere.copy(upcoming = false).ageText(Instant.parse("2026-11-04T17:00:00Z")))
    }
}
