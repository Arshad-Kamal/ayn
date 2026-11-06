package com.arshad.ayn.core.access

import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.sabr.isVp9Profile0
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Network smoke check for the access core. Opt-in only:
 * `KE_SMOKE=1 ./gradlew :core:testDebugUnitTest`.
 *
 * Live canary against real YouTube (catches YouTube-side drift); the regression suite is the
 * offline counterpart `NewPipeAccessFixtureTest`.
 */
class ExtractionSmokeTest {

    @Test
    fun extractsMetadataForFixedVideo() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val metadata = runBlocking { extractor.video(VideoId(FIXED_VIDEO_ID)) }

        assertTrue("title should not be blank", metadata.title.isNotBlank())
        assertTrue("duration should be positive", metadata.durationSeconds > 0)
        assertTrue("expected stream candidates, got ${metadata.streams.size}", metadata.streams.isNotEmpty())
        // The SABR path needs the server endpoint + ustreamer config and VP9 video formats.
        // The video-page extras degrade silently to absent, so drift shows only here.
        assertTrue("description missing", metadata.description != null)
        assertTrue("storyboards missing", metadata.storyboards.isNotEmpty())
        assertTrue("serverAbrStreamingUrl missing", !metadata.serverAbrStreamingUrl.isNullOrBlank())
        assertTrue("ustreamerConfig missing", !metadata.ustreamerConfig.isNullOrBlank())
        assertTrue(
            "expected VP9 video candidates",
            metadata.streams.any { it.kind != StreamKind.AUDIO && it.isVp9Profile0() },
        )
    }

    @Test
    fun extractsChannelHeaderForFixedChannel() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val channel = runBlocking { extractor.channel(SHORTS_CHANNEL_URL) }

        assertTrue("name should not be blank", channel.name.isNotBlank())
        assertTrue("url should not be blank", channel.url.isNotBlank())
        assertTrue("avatar should be present", !channel.avatarUrl.isNullOrBlank())
        assertTrue("banner should be present", !channel.bannerUrl.isNullOrBlank())
        // The uncropped-art rewrite depends on yt3's URL shape: this says when YouTube changes it.
        assertTrue("banner should be the uncropped art", channel.bannerUrl!!.endsWith("=w1920"))
    }

    /** M7-D12: YouTube's Shorts shelves in search and a channel's Shorts tab still read as Shorts. */
    @Test
    fun searchAndAShortsTabListShorts() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val search = runBlocking { extractor.search(CHANNEL_SEARCH_QUERY) }
        val tab = runBlocking { extractor.channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }

        assertTrue("search should list Shorts, got none", search.shorts.isNotEmpty())
        assertTrue("the Shorts tab should list Shorts, got none", tab.shorts.isNotEmpty())
        val shortIds = search.shorts.map { it.id }.toSet()
        assertTrue("Shorts never ride as items", search.items.none { it is VideoItem && it.id in shortIds })
    }

    /** CMT-D1: a video's comments page on and a comment's replies open. The fields a reshaped
     *  answer would drop first (the entity-mutation payloads) are checked, not only the counts. */
    @Test
    fun aVideosCommentsPageOnAndOpenReplies() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val video = VideoId(FIXED_VIDEO_ID)
        val first = runBlocking { extractor.comments(video) }
        val second = runBlocking { extractor.comments(video, checkNotNull(first.next) { "page 1 must continue" }) }
        val parent = first.comments.first { it.repliesToken != null }
        val replies = runBlocking { extractor.comments(video, parent.repliesToken) }

        assertTrue("page 1 should list comments", first.comments.isNotEmpty())
        assertTrue("the count should be read, got ${first.totalCount}", (first.totalCount ?: 0) > 0)
        assertTrue("page 2 should list comments", second.comments.isNotEmpty())
        assertTrue("replies should list comments", replies.comments.isNotEmpty())
        assertTrue(
            "replies should be that comment's, got ${replies.comments.map { it.id }}",
            replies.comments.all { it.id.startsWith("${parent.id}.") && it.repliesToken == null },
        )
        assertTrue("authors should read as handles", first.comments.all { it.authorName.startsWith("@") })
        assertTrue("ages should be read", first.comments.all { it.age != null })
        assertTrue("likes should be read", first.comments.any { it.likes != null })
    }

    /** FY.2: the one-request related list still answers (17–20 videos when probed, 2026-10-09). */
    @Test
    fun aVideosRelatedListInOneRequest() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val related = runBlocking { extractor.related(VideoId(CHAPTERS_VIDEO_ID)) }

        assertTrue("expected at least $RELATED_FLOOR related videos, got ${related.size}", related.size >= RELATED_FLOOR)
        // For you's channel rules (affinity, the per-channel cap, hidden channels) read these.
        assertTrue(
            "channels should be read",
            related.count { !it.uploaderUrl.isNullOrBlank() } >= RELATED_FLOOR,
        )
    }

    /** KP-D2 through the fork's own transport (red team #23): the probe's Restricted-Mode-blocked
     *  video plays without the header and is refused with it (VISIONOS `ERROR`, 2026-10-10). A
     *  failing first half means YouTube changed that video, not the header: pick another. */
    @Test
    fun theRestrictHeaderRefusesARestrictedVideo() {
        assumeTrue("set KE_SMOKE=1 to run network smoke tests", System.getenv("KE_SMOKE") != null)

        val extractor: Extractor = NewPipeAccess()
        val open = runBlocking { extractor.video(RESTRICTED_VIDEO) }
        val refused = assertThrows(AccessException::class.java) {
            runBlocking { withContext(YouTubeRestrict.context(YouTubeRestrict.STRICT)) { extractor.video(RESTRICTED_VIDEO) } }
        }

        assertTrue("without the header the video should open", open.title.isNotBlank())
        assertEquals(AccessFailure.UNAVAILABLE, refused.failure)
    }

    private companion object {
        const val RELATED_FLOOR = 10

        /** Not age-restricted, hidden by Restricted Mode (KP derisk ledger, `rm_player.json`). */
        val RESTRICTED_VIDEO = VideoId("zP6eqCOM7tk")
    }
}
