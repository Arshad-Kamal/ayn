package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedChaptersVideo
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.newpipe.RecordedRelatedVideos
import com.arshad.ayn.core.access.newpipe.RecordedStoryboardsVideo
import com.arshad.ayn.core.access.newpipe.RecordedVideo
import com.arshad.ayn.core.access.newpipe.STORYBOARDS_VIDEO_FLOW
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The player's dressing and the resolve's live playable on recorded video pages (Robolectric: the
 *  dressing crosses a real `Bundle`). */
@RunWith(RobolectricTestRunner::class)
class PlaybackDressingFixtureTest {

    /** The scrub preview gets the largest storyboard level YouTube's WEB answer offers
     *  (320x180), not the streaming client's 160x90. */
    @Test
    fun theScrubPreviewGetsTheLargestStoryboardLevel() {
        val page = page(STORYBOARDS_VIDEO_FLOW, FIXED_VIDEO_ID)

        val board = PlaybackDressing.from(page).storyboard!!

        assertEquals(RecordedStoryboardsVideo.STORYBOARDS_WITHOUT_PAGES.last(), board.copy(pageUrls = emptyList()))
        assertEquals(RecordedStoryboardsVideo.LARGEST_FIRST_PAGE, board.pageUrls.first())
    }

    /** The live page's related list holds a live lockup (no length, no upload date): it crosses
     *  the bundle as it was, its approximate upload instants in whole milliseconds. */
    @Test
    fun relatedVideosCrossTheBundleLiveLockupIncluded() {
        val related = page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID).relatedVideos
        val dressing = PlaybackDressing(uploaderAvatarUrl = null, chapters = emptyList(), storyboard = null, relatedVideos = related)

        val crossed = PlaybackDressing.fromBundle(dressing.toBundle())!!.relatedVideos

        assertEquals(RecordedRelatedVideos.LIVE_PAGE_COUNT, crossed.size)
        assertEquals(RecordedRelatedVideos.LIVE_PAGE_LIVE_ID, crossed[RecordedRelatedVideos.LIVE_PAGE_LIVE_INDEX].id.value)
        assertTrue(crossed[RecordedRelatedVideos.LIVE_PAGE_LIVE_INDEX].isLive)
        assertEquals(related.map { it.copy(uploadDate = it.uploadDate?.let { at -> Instant.fromEpochMilliseconds(at.toEpochMilliseconds()) }) }, crossed)
    }

    /** Each lookup outcome crosses the bundle as it was; no lookup stays none. */
    @Test
    fun theSegmentLookupCrossesTheBundle() {
        val found = SegmentLookup.Found(
            listOf(Segment(SegmentCategory.Preview, 0, 51_970), Segment(SegmentCategory.MusicOffTopic, 56_208, 134_034)),
        )

        listOf(found, SegmentLookup.None, SegmentLookup.Failed).forEach { lookup ->
            val dressing = PlaybackDressing(uploaderAvatarUrl = null, chapters = emptyList(), storyboard = null, segments = lookup)
            assertEquals(lookup, PlaybackDressing.fromBundle(dressing.toBundle())!!.segments)
        }
        val unlooked = PlaybackDressing(uploaderAvatarUrl = null, chapters = emptyList(), storyboard = null)
        assertNull(PlaybackDressing.fromBundle(unlooked.toBundle())!!.segments)
    }

    /** Details' part of the recorded chapters page (subscribers, likes, description) and its
     *  channel cross the bundle; a page that has none of them crosses as none. */
    @Test
    fun detailsCountsAndDescriptionCrossTheBundle() {
        val dressing = PlaybackDressing.from(page(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID))

        val crossed = PlaybackDressing.fromBundle(dressing.toBundle())!!

        assertEquals(RecordedChaptersVideo.SUBSCRIBER_COUNT, crossed.subscriberCount)
        assertEquals(RecordedChaptersVideo.LIKE_COUNT, crossed.likeCount)
        assertTrue(crossed.description!!.startsWith(RecordedChaptersVideo.DESCRIPTION_PREFIX))
        // A search Short's card names no channel: the page's crosses with it.
        assertEquals(RecordedChaptersVideo.UPLOADER_NAME, crossed.uploaderName)
        assertEquals(RecordedChaptersVideo.UPLOADER_URL, crossed.uploaderUrl)
        val bare = PlaybackDressing(uploaderAvatarUrl = null, chapters = emptyList(), storyboard = null)
        assertEquals(bare, PlaybackDressing.fromBundle(bare.toBundle()))
    }

    /** The tracks the page's mapper admitted cross the bundle in YouTube's order, links included. */
    @Test
    fun captionTracksCrossTheBundle() {
        val page = page("video", FIXED_VIDEO_ID)

        val crossed = PlaybackDressing.fromBundle(PlaybackDressing.from(page).toBundle())!!.captionTracks

        assertEquals(page.captionTracks, crossed)
        assertEquals(RecordedVideo.CAPTION_TRACKS, crossed.map { Triple(it.languageTag, it.name, it.autoGenerated) })
    }

    /** LIVE-D23: the screen's live chrome keys on the resolved page: the recorded live page's
     *  dressing says live across the bundle, a video's doesn't. */
    @Test
    fun theLivePagesDressingSaysLive() {
        val live = PlaybackDressing.fromBundle(PlaybackDressing.from(page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)).toBundle())!!
        val video = PlaybackDressing.fromBundle(PlaybackDressing.from(page(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID)).toBundle())!!

        assertTrue(live.live)
        assertFalse(video.live)
    }

    /** A live page shows no chapters, storyboard or SponsorBlock; its related videos stay (LIVE-D24).
     *  A video's dressing is shown as it is. */
    @Test
    fun aLivePageIsShownWithoutAVideosParts() {
        val found = SegmentLookup.Found(listOf(Segment(SegmentCategory.Sponsor, 0, 10_000)))
        val video = PlaybackDressing.from(page(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID)).copy(segments = found)
        assertTrue(video.chapters.isNotEmpty())

        val shown = video.copy(live = true).forScreen()

        assertTrue(shown.chapters.isEmpty())
        assertNull(shown.storyboard)
        assertNull(shown.segments)
        assertEquals(video.relatedVideos, shown.relatedVideos)
        assertEquals(video, video.forScreen())
    }

    /** LIVE-D24: the live page's start crosses the bundle; a video names none. */
    @Test
    fun theLivePagesStartCrossesTheBundle() {
        val live = PlaybackDressing.fromBundle(PlaybackDressing.from(page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)).toBundle())!!
        val video = PlaybackDressing.fromBundle(PlaybackDressing.from(page(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID)).toBundle())!!

        assertEquals(RecordedLiveVideo.STARTED_AT, live.liveStartedAt)
        assertNull(video.liveStartedAt)
    }

    /** LIVE-D6: the recorded live page's chat seed crosses the bundle; a video has none, and a
     *  replay's keeps its mark. */
    @Test
    fun theLivePagesChatSeedCrossesTheBundle() {
        val live = PlaybackDressing.fromBundle(PlaybackDressing.from(page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)).toBundle())!!
        val video = PlaybackDressing.fromBundle(PlaybackDressing.from(page(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID)).toBundle())!!
        val replay = PlaybackDressing(null, emptyList(), null, liveChat = LiveChatSeed("r", replay = true))

        assertEquals(LiveChatSeed(RecordedLiveVideo.CHAT_SEED, replay = false), live.liveChat)
        assertNull(video.liveChat)
        assertEquals(replay, PlaybackDressing.fromBundle(replay.toBundle()))
    }

    /** LIVE-D1/D2/D5: the recorded live page plays from the HLS manifest its VISIONOS answer names,
     *  carrying the quality cap. */
    @Test
    fun aLivePagePlaysFromItsHlsManifest() {
        val page = page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)

        val playable = livePlayableOrRefuse(page, kidsProfile = false, maxShortSide = 720)

        assertEquals(page.hlsUrl, playable.hlsUrl)
        assertTrue(playable.hlsUrl.startsWith(RecordedLiveVideo.HLS_URL_PREFIX))
        assertEquals(720, playable.maxShortSide)
    }

    /** LIVE-D13: a kids profile is refused the live page, manifest or not. */
    @Test
    fun aLivePageOnAKidsProfileIsRefusedAndStopsAsLive() {
        val page = page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)

        val refusal = assertThrows(LiveStreamRefused::class.java) { livePlayableOrRefuse(page, kidsProfile = true, maxShortSide = 1080) }

        assertEquals(PlayerPolicy.TERMINAL_LIVE, resolveStopReason(refusal))
    }

    /** LIVE-D2: no manifest, or one off YouTube's serving hosts (M3-SEC-01; the host check's own
     *  matrix is `SabrBridgeTest`'s), stops as live. */
    @Test
    fun aLivePageWithoutAUsableManifestIsRefusedAndStopsAsLive() {
        val page = page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID)

        listOf(null, "https://evil.example/api/manifest/hls_variant").forEach { url ->
            val refusal = assertThrows(LiveStreamRefused::class.java) { livePlayableOrRefuse(page.copy(hlsUrl = url), kidsProfile = false, maxShortSide = 1080) }
            assertEquals(PlayerPolicy.TERMINAL_LIVE, resolveStopReason(refusal))
        }
    }

    private fun page(flow: String, id: String) = runBlocking { NewPipeAccess(downloader = FixtureDownloader(flow)).video(VideoId(id)) }
}
