package com.arshad.ayn.feature.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chrome's texts and the storyboard frame math, against hand-worked values. */
class PlayerFormatsTest {

    @Test
    fun `times read as minutes and seconds, hours when there are any`() {
        assertEquals("0:00", timeText(0))
        assertEquals("0:07", timeText(7_900))
        assertEquals("34:51", timeText(2_091_000))
        assertEquals("1:02:03", timeText(3_723_000))
        assertEquals("\u221225:45", remainingText(positionMs = 412_000, durationMs = 1_957_000))
        assertEquals("\u22120:00", remainingText(positionMs = 10_000, durationMs = 5_000))
        assertEquals("", remainingText(positionMs = 10_000, durationMs = 0))
        assertEquals("", remainingText(positionMs = 10_000, durationMs = C.TIME_UNSET))
    }

    @Test
    fun `a live window is named to the nearest minute or hour`() {
        assertEquals("Last hour", liveWindowText(3_600_000))
        assertEquals("Last hour", liveWindowText(3_580_000))
        assertEquals("Last 2 hours", liveWindowText(5_400_000))
        assertEquals("Last 25 minutes", liveWindowText(1_500_000))
        assertEquals("Last minute", liveWindowText(20_000))
        assertNull(liveWindowText(0))
    }

    @Test
    fun `behind live reads after a minus sign`() {
        assertEquals("\u221224:00", behindLiveText(1_440_000))
    }

    @Test
    fun `the badge names the short side and the rounded rate once known`() {
        assertEquals("2160p · 24 fps", formatBadgeText(3840, 2160, 23.976f))
        assertEquals("1080p", formatBadgeText(1920, 1080, null))
        assertEquals("1080p", formatBadgeText(1080, 1920, 0f))
        assertNull(formatBadgeText(0, 0, 30f))
    }

    @Test
    fun `Quality names the short side and the codec once known`() {
        assertEquals("2160p · VP9", qualityValue(3840, 2160, "VP9"))
        assertEquals("1080p", qualityValue(1080, 1920, null))
        assertNull(qualityValue(0, 0, "VP9"))
    }

    @Test
    fun `the codec is the selected video track's, by name`() {
        fun tracks(mime: String, selected: Boolean) = Tracks(
            listOf(Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))),
        )
        assertEquals("VP9", videoCodec(tracks(MimeTypes.VIDEO_VP9, selected = true)))
        assertEquals("H.264", videoCodec(tracks(MimeTypes.VIDEO_H264, selected = true)))
        assertNull(videoCodec(tracks(MimeTypes.VIDEO_VP9, selected = false)))
        assertNull(videoCodec(tracks(MimeTypes.VIDEO_MPEG2, selected = true)))
        assertNull(videoCodec(Tracks.EMPTY))
        val adaptive = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).build()),
            true,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
            booleanArrayOf(false, true),
        )
        assertEquals("VP9", videoCodec(Tracks(listOf(adaptive))))
    }

    @Test
    fun `Details dates the upload in the long form, else YouTube's text`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val video = VideoItem(VideoId("JsBZOcqZerk"), "Enigma", null, null, 2861, 15_000_000, "12 days ago", null, null, false)
            assertEquals("September 21, 2026", uploadedText(video.copy(uploadDate = Instant.parse("2026-09-21T15:00:00Z")), Locale.US))
            assertEquals("12 days ago", uploadedText(video, Locale.US))
            assertNull(uploadedText(video.copy(textualUploadDate = null), Locale.US))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `the ratio reads to a tenth of a percent`() {
        assertEquals("98.9% liked", likedText(121_954 * 100.0 / 123_254, Locale.US))
        assertEquals("50.0% liked", likedText(50.0, Locale.US))
        assertEquals("98,9%", percentText(98.94, Locale.GERMANY))
    }

    @Test
    fun `comment counts read whole, replies by their count`() {
        assertEquals("2,458,514", commentCountText(2_458_514, Locale.US))
        assertEquals("Replies", repliesText(0, Locale.US))
        assertEquals("1 reply", repliesText(1, Locale.US))
        assertEquals("1,234 replies", repliesText(1_234, Locale.US))
    }

    @Test
    fun `the chapter is the last one started`() {
        val chapters = listOf(Chapter("Intro", 0), Chapter("Middle", 60), Chapter("End", 120))
        assertEquals("Intro", chapterAt(chapters, 59_999)?.title)
        assertEquals("Middle", chapterAt(chapters, 60_000)?.title)
        assertEquals("End", chapterAt(chapters, 10_000_000)?.title)
        assertNull(chapterAt(emptyList(), 0))
        assertEquals(-1, chapterIndexAt(listOf(Chapter("Late", 10)), 9_999))
    }

    @Test
    fun `the place in Play all leaves out an unknown count`() {
        assertEquals("3 of 22", playlistPlaceText(PlaylistPosition("Biology", index = 2, videoCount = 22, hasNext = true)))
        assertEquals("3", playlistPlaceText(PlaylistPosition("Biology", index = 2, videoCount = -1, hasNext = true)))
    }

    private val board = Storyboard(
        pageUrls = listOf("p0", "p1", "p2"),
        frameWidth = 160,
        frameHeight = 90,
        frameCount = 60,
        frameDurationMs = 2_000,
        framesPerPageX = 5,
        framesPerPageY = 5,
    )

    @Test
    fun `a frame is found by page, then row by row`() {
        assertEquals(StoryboardFrame(page = 0, column = 0, row = 0), storyboardFrame(board, 1_999))
        assertEquals(StoryboardFrame(page = 0, column = 1, row = 1), storyboardFrame(board, 12_000)) // frame 6
        assertEquals(StoryboardFrame(page = 1, column = 2, row = 0), storyboardFrame(board, 54_000)) // frame 27
    }

    @Test
    fun `past the end the last frame holds, and a board without timing shows its first`() {
        assertEquals(StoryboardFrame(page = 2, column = 4, row = 1), storyboardFrame(board, 999_999_000)) // frame 59
        assertEquals(StoryboardFrame(0, 0, 0), storyboardFrame(board.copy(frameDurationMs = 0), 50_000))
        assertNull(storyboardFrame(board.copy(pageUrls = emptyList()), 0))
    }

    @Test
    fun `the held pages are the frame's own, with its neighbours while prefetching, inside the board`() {
        assertEquals(4..4, pageWindow(page = 4, lastPage = 9, prefetch = false))
        assertEquals(3..5, pageWindow(page = 4, lastPage = 9, prefetch = true))
        assertEquals(0..1, pageWindow(page = 0, lastPage = 9, prefetch = true))
        assertEquals(8..9, pageWindow(page = 9, lastPage = 9, prefetch = true))
        assertEquals(0..0, pageWindow(page = 0, lastPage = 0, prefetch = true))
    }

    @Test
    fun `a frame's cut is its cell, clamped to a page a few pixels short`() {
        val frame = StoryboardFrame(page = 0, column = 4, row = 4)
        assertEquals(FrameCrop(640, 360, 160, 90), storyboardCrop(board, frame, pageWidth = 800, pageHeight = 450))
        assertEquals(FrameCrop(640, 360, 157, 88), storyboardCrop(board, frame, pageWidth = 797, pageHeight = 448))
        assertEquals(FrameCrop(299, 0, 1, 90), storyboardCrop(board, StoryboardFrame(0, column = 2, row = 0), pageWidth = 300, pageHeight = 450))
        assertNull(storyboardCrop(board.copy(frameWidth = 0), frame, pageWidth = 800, pageHeight = 450))
    }

    @Test
    fun `a skip's length reads in words, rounded to the second`() {
        assertEquals("11 seconds", skippedLengthText(10_732)) // the mockup's 309.888–320.620 s
        assertEquals("1 second", skippedLengthText(300))
        assertEquals("59 seconds", skippedLengthText(59_499))
        assertEquals("1 minute", skippedLengthText(59_500))
        assertEquals("1 minute 18 seconds", skippedLengthText(77_826)) // the recorded sponsor read
        assertEquals("2 minutes 1 second", skippedLengthText(121_000))
    }

    @Test
    fun `the prompt's line runs from full as it shows to empty at its end`() {
        // Shown 2 s into a span, its 8 s ending at 1:10.
        val prompt = SkipPrompt(SkipSpan(60_000, 134_034, listOf(SegmentCategory.Sponsor)), shownAtMs = 62_000, untilMs = 70_000)
        assertEquals(1f, promptLeft(prompt, 62_000), 0.001f)
        assertEquals(0.5f, promptLeft(prompt, 66_000), 0.001f)
        assertEquals(0f, promptLeft(prompt, 70_000), 0.001f)
        assertEquals(1f, promptLeft(prompt, 61_000), 0.001f) // a seek back inside it
        assertEquals(0f, promptLeft(prompt, 75_000), 0.001f)
    }

    @Test
    fun `SponsorBlock's line lists segments in time order, or says why there are none`() {
        val found = SegmentLookup.Found(
            listOf(Segment(SegmentCategory.Sponsor, 1_394_000, 1_480_000), Segment(SegmentCategory.SelfPromo, 309_888, 320_620)),
        )
        assertEquals("Self-promotion 5:09 · Sponsor 23:14", sponsorBlockText(found)) // the mockup's two
        assertEquals("No segments for this video", sponsorBlockText(SegmentLookup.None))
        assertEquals("Segments didn’t load for this video; it plays without them", sponsorBlockText(SegmentLookup.Failed))
        assertEquals("Off", sponsorBlockText(null))
    }

    @Test
    fun `the skip note names the categories in order by their Settings names`() {
        assertEquals("Skipped self-promotion", skippedText(listOf(SegmentCategory.SelfPromo)))
        assertEquals("Skipped sponsor and self-promotion", skippedText(listOf(SegmentCategory.Sponsor, SegmentCategory.SelfPromo)))
        assertEquals(
            "Skipped preview and recap, intro animation and sponsor",
            skippedText(listOf(SegmentCategory.Preview, SegmentCategory.Intro, SegmentCategory.Sponsor)),
        )
    }

    @Test
    fun `a retry under way says what it does and how far it has got, and nothing otherwise`() {
        assertEquals(RetryLine("Connection dropped · retrying", "2 of 6"), retryLine(PlayerErrorUiState.Retrying(2, 6)))
        assertEquals(RetryLine("Refreshing the playback session", "1 of 3"), retryLine(PlayerErrorUiState.Refreshing(1, 3)))
        assertNull(retryLine(PlayerErrorUiState.None))
        assertNull(retryLine(PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_EXHAUSTED)))
    }
}
