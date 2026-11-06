package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.VideoRefusal
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.Frameset

/**
 * The video page's UI extras on recorded payloads: description (plain text), chapters,
 * storyboards, related videos, caption tracks, the live marker, the page's picture and the uploader's avatar; and YouTube's
 * refusals (age gate, private) as UNAVAILABLE. Goldens are read from the raw payloads into `RecordedCorpus.kt` (TEST-ORACLE-01).
 */
class NewPipeAccessVideoDetailsFixtureTest {

    @Test
    fun chaptersVideo_mapsEveryChapterInStartOrder() {
        val metadata = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID)

        assertEquals(RecordedChaptersVideo.DURATION_SECONDS, metadata.durationSeconds)
        assertEquals(RecordedChaptersVideo.CHAPTERS, metadata.chapters)
    }

    @Test
    fun videoPage_offersTheUploadersLargestAvatar() {
        val metadata = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID)

        assertEquals(RecordedChaptersVideo.UPLOADER_AVATAR_URL, metadata.uploaderAvatarUrl)
    }

    @Test
    fun ageRestrictedVideo_failsAsUnavailable() {
        assertFailsAs(AccessFailure.UNAVAILABLE, AGE_RESTRICTED_VIDEO_FLOW, AGE_RESTRICTED_VIDEO_ID)
    }

    @Test
    fun privateVideo_failsAsUnavailable() {
        assertFailsAs(AccessFailure.UNAVAILABLE, PRIVATE_VIDEO_FLOW, PRIVATE_VIDEO_ID)
    }

    @Test
    fun ageRestrictedAndPrivateVideos_nameNoRefusal() {
        val refusals = listOf(AGE_RESTRICTED_VIDEO_FLOW to AGE_RESTRICTED_VIDEO_ID, PRIVATE_VIDEO_FLOW to PRIVATE_VIDEO_ID)
            .map { (flow, id) -> failureOf(flow, id).refusal }

        assertEquals(listOf(null, null), refusals)
    }

    @Test
    fun madeForKidsVideo_failsAsUnavailableNamedMadeForKids() {
        val failure = failureOf(MADE_FOR_KIDS_VIDEO_FLOW, MADE_FOR_KIDS_VIDEO_ID)

        assertEquals(AccessFailure.UNAVAILABLE to VideoRefusal.MadeForKids, failure.failure to failure.refusal)
    }

    @Test
    fun upcomingStream_failsAsUnavailableNamedNotStartedWithYouTubesSentence() {
        val failure = failureOf(UPCOMING_VIDEO_FLOW, UPCOMING_VIDEO_ID)

        assertEquals(AccessFailure.UNAVAILABLE to VideoRefusal.NotStarted(UPCOMING_VIDEO_REASON), failure.failure to failure.refusal)
    }

    @Test
    fun captionTracks_listEveryTrackInYouTubesOrderWithYouTubesName() {
        assertEquals(RecordedVideo.CAPTION_TRACKS, extractFixedVideo().captionTracks.summaries())
    }

    @Test
    fun captionTracks_linkToEachTracksTtml() {
        val (manual, auto) = extractFixedVideo().captionTracks.take(2).map { it.url.toHttpUrl() }

        assertEquals(listOf("www.youtube.com", "/api/timedtext", FIXED_VIDEO_ID, "en", "ttml", null),
            listOf(manual.host, manual.encodedPath, manual.queryParameter("v"), manual.queryParameter("lang"), manual.queryParameter("fmt"), manual.queryParameter("kind")))
        assertEquals(listOf("en", "ttml", "asr"), listOf(auto.queryParameter("lang"), auto.queryParameter("fmt"), auto.queryParameter("kind")))
    }

    @Test
    fun captionTracks_ofAnotherVideoKeepTheirNames() {
        val tracks = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).captionTracks

        assertEquals(RecordedChaptersVideo.CAPTION_TRACK_NAMES, tracks.map { it.name })
    }

    /** The captions seam fetches whatever link a track carries, so only YouTube's own timedtext
     *  endpoint over HTTPS passes: another host, plain HTTP or another path drops the track. */
    @Test
    fun captionTrack_linkingAnywhereButYouTubesTimedtextOverHttps_isDropped() {
        val prefix = RecordedVideo.FIRST_CAPTION_LINK_PREFIX
        val elsewhere = listOf("www.youtube.com" to "captions.example", "https:" to "http:", "/api/timedtext" to "/api/elsewhere")

        elsewhere.forEach { (from, to) ->
            val corrupting = CorruptingDownloader(FixtureDownloader("video"), prefix to prefix.replace(from, to))

            val tracks = extract(corrupting, FIXED_VIDEO_ID).captionTracks

            assertEquals(emptyList<Pair<String, String>>(), corrupting.unusedReplacements())
            assertEquals("$from -> $to", RecordedVideo.CAPTION_TRACKS.drop(1), tracks.summaries())
        }
    }

    @Test
    fun videoWithoutChapterPanel_hasNoChapters() {
        assertEquals(emptyList<Any>(), extractFixedVideo().chapters)
    }

    @Test
    fun storyboards_mapLevelsWithFramesSmallestFirst() {
        val storyboards = extractFixedVideo().storyboards

        assertEquals(RecordedVideo.STORYBOARDS_WITHOUT_PAGES, storyboards.map { it.copy(pageUrls = emptyList()) })
        assertEquals(RecordedVideo.STORYBOARD_PAGE_COUNTS, storyboards.map { it.pageUrls.size })
        assertEquals(RecordedVideo.STORYBOARD_LARGEST_FIRST_PAGE, storyboards.last().pageUrls.first())
    }

    @Test
    fun storyboards_comeFromTheWebSpecWhenTheWebAnswerHasOne() {
        YoutubeJavaScriptPlayerManager.clearAllCaches()

        val storyboards = extract(FixtureDownloader(STORYBOARDS_VIDEO_FLOW), FIXED_VIDEO_ID).storyboards

        assertEquals(RecordedStoryboardsVideo.STORYBOARDS_WITHOUT_PAGES, storyboards.map { it.copy(pageUrls = emptyList()) })
        assertEquals(RecordedStoryboardsVideo.LARGEST_PAGE_COUNT, storyboards.last().pageUrls.size)
        assertEquals(RecordedStoryboardsVideo.LARGEST_FIRST_PAGE, storyboards.last().pageUrls.first())
    }

    @Test
    fun storyboards_fallBackToTheStreamingClientsSpecWithoutAWebSpec() {
        YoutubeJavaScriptPlayerManager.clearAllCaches()
        val withoutWebSpec = CorruptingDownloader(
            FixtureDownloader(STORYBOARDS_VIDEO_FLOW),
            "\"storyboards\":" to "\"storyboardsGone\":",
            onlyKey = WEB_PLAYER_KEY,
        )

        val storyboards = extract(withoutWebSpec, FIXED_VIDEO_ID).storyboards

        assertEquals(emptyList<Pair<String, String>>(), withoutWebSpec.unusedReplacements())
        assertEquals(RecordedStoryboardsVideo.STREAMING_CLIENT_LEVELS, storyboards.map { it.copy(pageUrls = emptyList()) })
    }

    @Test
    fun webPlayerRequest_asksForStoryboardsWithThePlayersSignatureTimestamp() {
        assumeTrue("no player code in this tree", playerCodeRecorded(STORYBOARDS_VIDEO_FLOW))
        YoutubeJavaScriptPlayerManager.clearAllCaches()
        val capturing = CapturingDownloader(FixtureDownloader(STORYBOARDS_VIDEO_FLOW))

        extract(capturing, FIXED_VIDEO_ID)

        val webPlayer = capturing.requests.single { FixtureKeys.of(it) == WEB_PLAYER_KEY }
        assertTrue("fields: ${webPlayer.url()}", "storyboards" in webPlayer.url())
        val body = webPlayer.dataToSend()!!.toString(Charsets.UTF_8)
        assertTrue("body lacks the recorded player's timestamp", "\"signatureTimestamp\":${RecordedStoryboardsVideo.SIGNATURE_TIMESTAMP}" in body)
    }

    @Test
    fun aPlayerCodeThatCantBeFetchedIsTriedOncePerProcessAndTheRequestStaysMetadataOnly() {
        YoutubeJavaScriptPlayerManager.clearAllCaches()
        val first = CapturingDownloader(FixtureDownloader("video")) // the `video` flow has no player code
        val second = CapturingDownloader(FixtureDownloader("video"))

        val metadata = extract(first, FIXED_VIDEO_ID)
        extract(second, FIXED_VIDEO_ID)

        assertTrue("the first page tries the player code", first.requests.any { "iframe_api" in it.url() })
        assertFalse("a later page tries it again", second.requests.any { "iframe_api" in it.url() })
        val webPlayer = first.requests.single { FixtureKeys.of(it) == WEB_PLAYER_KEY }
        assertFalse("timestamp sent without player code", "signatureTimestamp" in webPlayer.dataToSend()!!.toString(Charsets.UTF_8))
        assertTrue("WEB metadata still maps", metadata.thumbnailUrl != null)
    }

    @Test
    fun storyboard_keepsGridAxesApart() {
        val frameset = Frameset(listOf("page-0"), 160, 90, 12, 5000, 3, 2)

        assertEquals(
            Storyboard(
                pageUrls = listOf("page-0"), frameWidth = 160, frameHeight = 90, frameCount = 12,
                frameDurationMs = 5000, framesPerPageX = 3, framesPerPageY = 2,
            ),
            frameset.toStoryboard(),
        )
    }

    @Test
    fun description_isPlainTextWithLinkTextAndLineBreaksKept() {
        val description = extractFixedVideo().description!!

        assertTrue("got: ${description.take(300)}", description.startsWith(RecordedVideo.DESCRIPTION_PREFIX))
        assertEquals(RecordedVideo.DESCRIPTION_LINES, description.lines().size)
        assertTrue("no markup, entities or no-break spaces may survive", HTML_RESIDUE.none { it in description })
    }

    @Test
    fun description_decodesEscapedQuotes() {
        val description = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).description!!

        assertTrue("got: ${description.take(300)}", description.startsWith(RecordedChaptersVideo.DESCRIPTION_PREFIX))
        assertEquals(RecordedChaptersVideo.DESCRIPTION_LINES, description.lines().size)
        assertTrue("no markup, entities or no-break spaces may survive", HTML_RESIDUE.none { it in description })
    }

    @Test
    fun blankDescription_isAbsent() {
        assertNull(Description("<br> &nbsp;", Description.Type.HTML).toPlainText())
        assertNull(Description.EMPTY_DESCRIPTION.toPlainText())
    }

    @Test
    fun carriageReturns_becomeLineFeeds() {
        assertEquals("a\nb\nc", Description("a\r\nb\rc", Description.Type.PLAIN_TEXT).toPlainText())
    }

    @Test
    fun liveStreamPage_isLiveWithNoLength() {
        val metadata = extract(FixtureDownloader(LIVE_VIDEO_FLOW), RecordedLiveVideo.ID)

        assertTrue(metadata.isLive)
        assertEquals(0L, metadata.durationSeconds)
    }

    /** LIVE-D24: the broadcast's start, not the page's earlier upload date. */
    @Test
    fun liveStreamPage_namesWhenItStarted() {
        assertEquals(RecordedLiveVideo.STARTED_AT, extract(FixtureDownloader(LIVE_VIDEO_FLOW), RecordedLiveVideo.ID).liveStartedAt)
    }

    /** A WEB answer for another video is dropped (the vendored extractor keeps no microformat):
     *  no start, and the page still plays as live. */
    @Test
    fun liveStreamPageWithoutItsWebAnswer_hasNoStartAndIsStillLive() {
        val foreign = CorruptingDownloader(
            FixtureDownloader(LIVE_VIDEO_FLOW),
            "\"videoDetails\":{\"videoId\":\"${RecordedLiveVideo.ID}\"" to "\"videoDetails\":{\"videoId\":\"$CHAPTERS_VIDEO_ID\"",
            onlyKey = WEB_PLAYER_KEY,
        )

        val metadata = extract(foreign, RecordedLiveVideo.ID)

        assertEquals(emptyList<Pair<String, String>>(), foreign.unusedReplacements())
        assertEquals(true to null, metadata.isLive to metadata.liveStartedAt)
    }

    /** LIVE-D6: the seed opens the chat without another request; written `%3D`, sent `=`. */
    @Test
    fun liveStreamPage_offersItsLiveChatSeed() {
        val metadata = extract(FixtureDownloader(LIVE_VIDEO_FLOW), RecordedLiveVideo.ID)

        assertEquals(LiveChatSeed(RecordedLiveVideo.CHAT_SEED, replay = false), metadata.liveChat)
    }

    @Test
    fun endedStreamPage_offersItsChatReplaySeed() {
        val metadata = extract(FixtureDownloader(FEED_NEWEST_VIDEO_FLOW), RecordedChannel.FEED_ENTRIES.first().first)

        assertEquals(LiveChatSeed(RecordedFeedNewestVideo.CHAT_REPLAY_SEED, replay = true), metadata.liveChat)
    }

    @Test
    fun videoPage_hasNoChat() {
        assertNull(extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).liveChat)
    }

    @Test
    fun videoPage_hasNoLiveStart() {
        assertNull(extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).liveStartedAt)
    }

    @Test
    fun premieredPage_keepsItsBroadcastStartAndIsNotLive() {
        val metadata = extract(FixtureDownloader(FEED_NEWEST_VIDEO_FLOW), RecordedChannel.FEED_ENTRIES.first().first)

        assertEquals(false to RecordedFeedNewestVideo.BROADCAST_STARTED_AT, metadata.isLive to metadata.liveStartedAt)
    }

    @Test
    fun heroPage_offersItsLargestPicture() {
        val metadata = extract(FixtureDownloader(FEED_NEWEST_VIDEO_FLOW), RecordedChannel.FEED_ENTRIES.first().first)

        assertEquals(RecordedFeedNewestVideo.THUMBNAIL_URL, metadata.thumbnailUrl)
    }

    @Test
    fun videoPage_offersYouTubesLikeCount() {
        assertEquals(RecordedChaptersVideo.LIKE_COUNT, extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).likeCount)
    }

    @Test
    fun videoWithRatingsHidden_hasNoLikeCount() {
        val hidden = CorruptingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), "\"allowRatings\":true" to "\"allowRatings\":false")

        val metadata = extract(hidden, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), hidden.unusedReplacements())
        assertNull(metadata.likeCount)
    }

    @Test
    fun unreadableLikeCount_isAbsentAndPlaybackSurvives() {
        val unreadable = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            RecordedChaptersVideo.LIKE_LABEL to "\"accessibilityText\":\"like this video along with many other people\"",
            onlyKey = WEB_NEXT_KEY,
        )

        val metadata = extract(unreadable, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), unreadable.unusedReplacements())
        assertNull(metadata.likeCount)
        assertTrue("streams must still map", metadata.streams.isNotEmpty())
    }

    @Test
    fun videoPage_offersTheUploadersSubscriberCount() {
        val metadata = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID)

        assertEquals(RecordedChaptersVideo.SUBSCRIBER_COUNT, metadata.uploaderSubscriberCount)
    }

    @Test
    fun unreadableSubscriberCount_isAbsentAndPlaybackSurvives() {
        val unreadable = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            RecordedChaptersVideo.SUBSCRIBER_TEXT to "\"simpleText\":\"many subscribers\"",
            onlyKey = WEB_NEXT_KEY,
        )

        val metadata = extract(unreadable, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), unreadable.unusedReplacements())
        assertNull(metadata.uploaderSubscriberCount)
        assertTrue("streams must still map", metadata.streams.isNotEmpty())
    }

    @Test
    fun blankSubscriberCount_isAbsent() {
        val blank = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            RecordedChaptersVideo.SUBSCRIBER_TEXT to "\"simpleText\":\"\"",
            onlyKey = WEB_NEXT_KEY,
        )

        val metadata = extract(blank, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), blank.unusedReplacements())
        assertNull(metadata.uploaderSubscriberCount)
    }

    /** A channel that hides its count has no count text at all. */
    @Test
    fun hiddenSubscriberCount_isAbsent() {
        val hidden = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            "\"subscriberCountText\":" to "\"hiddenSubscriberCountText\":",
            onlyKey = WEB_NEXT_KEY,
        )

        val metadata = extract(hidden, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), hidden.unusedReplacements())
        assertNull(metadata.uploaderSubscriberCount)
    }

    @Test
    fun ordinaryVideoPage_isNotLive() {
        assertFalse(extractFixedVideo().isLive)
    }

    @Test
    fun unparseableChaptersAndStoryboards_degradeToAbsentAndPlaybackSurvives() {
        // A chapter without a title and a storyboard level with a non-numeric frame height
        // each make the vendored parser throw.
        val corrupted = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            "{\"macroMarkersListItemRenderer\":{\"title\":{\"simpleText\":\"Who invented Enigma?\"}" to
                "{\"macroMarkersListItemRenderer\":{\"title\":{\"simpleText\":\"\"}",
            "80#45#288" to "80#xx#288",
        )

        val metadata = extract(corrupted, CHAPTERS_VIDEO_ID)

        assertTrue("both corruptions must have hit the payloads", corrupted.unusedReplacements().isEmpty())
        assertEquals(emptyList<Any>(), metadata.chapters)
        assertEquals(emptyList<Any>(), metadata.storyboards)
        assertTrue("streams must still map", metadata.streams.isNotEmpty())
        assertTrue("the description is unaffected", metadata.description!!.startsWith(RecordedChaptersVideo.DESCRIPTION_PREFIX))
    }

    @Test
    fun relatedVideos_comeInPageOrderFromTheNextAnswerAlreadyFetched() {
        val capturing = CapturingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW))

        val related = extract(capturing, CHAPTERS_VIDEO_ID).relatedVideos

        assertEquals(RecordedRelatedVideos.CHAPTERS_VIDEO_IDS, related.map { it.id.value })
        assertEquals(1, capturing.requests.count { FixtureKeys.of(it) == WEB_NEXT_KEY })
    }

    @Test
    fun relatedVideo_mapsItsCardFields() {
        val first = extract(FixtureDownloader(CHAPTERS_VIDEO_FLOW), CHAPTERS_VIDEO_ID).relatedVideos.first()

        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_TITLE, first.title)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_UPLOADER_NAME, first.uploaderName)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_UPLOADER_URL, first.uploaderUrl)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_DURATION_SECONDS, first.durationSeconds)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_VIEW_COUNT, first.viewCount)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_TEXTUAL_UPLOAD_DATE, first.textualUploadDate)
        assertEquals(RecordedRelatedVideos.CHAPTERS_FIRST_THUMBNAIL_URL, first.thumbnailUrl)
        assertFalse(first.isLive)
    }

    @Test
    fun relatedLiveStream_staysWithItsMarker() {
        val related = extract(FixtureDownloader(LIVE_VIDEO_FLOW), RecordedLiveVideo.ID).relatedVideos

        assertEquals(RecordedRelatedVideos.LIVE_PAGE_COUNT, related.size)
        val live = related[RecordedRelatedVideos.LIVE_PAGE_LIVE_INDEX]
        assertEquals(RecordedRelatedVideos.LIVE_PAGE_LIVE_ID, live.id.value)
        assertTrue(live.isLive)
        assertEquals(listOf(live), related.filter { it.isLive })
    }

    @Test
    fun relatedMix_isDropped() {
        val related = extract(FixtureDownloader(STORYBOARDS_VIDEO_FLOW), FIXED_VIDEO_ID).relatedVideos

        assertEquals(RecordedRelatedVideos.STORYBOARDS_PAGE_VIDEO_COUNT, related.size)
    }

    @Test
    fun relatedVideoWithoutALength_isDroppedAsAShort() {
        val asShort = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            "{\"thumbnailBadgeViewModel\":{\"text\":\"55:00\"" to "{\"thumbnailBadgeViewModel\":{\"text\":\"SHORTS\"",
            onlyKey = WEB_NEXT_KEY,
        )

        val related = extract(asShort, CHAPTERS_VIDEO_ID).relatedVideos

        assertEquals(emptyList<Pair<String, String>>(), asShort.unusedReplacements())
        assertEquals(RecordedRelatedVideos.CHAPTERS_VIDEO_IDS.drop(1), related.map { it.id.value })
    }

    @Test
    fun missingRelatedList_isEmptyAndPlaybackSurvives() {
        val withoutRelated = CorruptingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), NO_RELATED_LIST, onlyKey = WEB_NEXT_KEY)

        val metadata = extract(withoutRelated, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), withoutRelated.unusedReplacements())
        assertEquals(emptyList<Any>(), metadata.relatedVideos)
        assertTrue("streams must still map", metadata.streams.isNotEmpty())
        assertEquals(RecordedChaptersVideo.CHAPTERS, metadata.chapters)
    }

    @Test
    fun unreadableRelatedList_isEmptyAndPlaybackSurvives() {
        val unreadable = CorruptingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), UNDATED_RELATED_LOCKUP, onlyKey = WEB_NEXT_KEY)

        val metadata = extract(unreadable, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), unreadable.unusedReplacements())
        assertEquals(emptyList<Any>(), metadata.relatedVideos)
        assertTrue("streams must still map", metadata.streams.isNotEmpty())
        assertEquals(RecordedChaptersVideo.CHAPTERS, metadata.chapters)
    }

    private fun assertFailsAs(expected: AccessFailure, flow: String, id: String) {
        assertEquals(expected, failureOf(flow, id).failure)
    }

    private fun failureOf(flow: String, id: String): AccessException {
        val failure = runCatching { extract(FixtureDownloader(flow), id) }.exceptionOrNull()
        return failure as? AccessException ?: throw AssertionError("$flow must fail at the seam, got $failure")
    }

    private fun List<CaptionTrack>.summaries(): List<Triple<String, String, Boolean>> =
        map { Triple(it.languageTag, it.name, it.autoGenerated) }

    private fun extractFixedVideo(): VideoMetadata = extract(FixtureDownloader("video"), FIXED_VIDEO_ID)

    private fun extract(downloader: Downloader, id: String): VideoMetadata = runBlocking {
        NewPipeAccess(downloader = downloader).video(VideoId(id))
    }

    private companion object {
        const val WEB_PLAYER_KEY = "www.youtube.com_youtubei_v1_player__WEB"
        val HTML_RESIDUE = listOf("<a ", "<br>", "</", "&amp;", "&quot;", "&nbsp;", "\u00a0")
    }
}
