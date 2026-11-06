package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager

/**
 * Capture tool, not a behavior test: records the extractor's live traffic for every fixture
 * flow into `src/testFixtures/resources/extract-fixtures/` so [FixtureDownloader] can replay it
 * offline. Flows record into a sibling directory that replaces its target only once recording
 * succeeded, so a run that fails while recording leaves the old corpus whole; after
 * [recordAllFixtureFlows] the corpus is exactly one run's capture (no orphans from key changes). Re-record when YouTube or the vendored extractor changes
 * what is fetched, then update the goldens in `RecordedCorpus.kt` from the new payloads.
 *
 * The channel fixtures are pinned to the fixed video's uploader channel (read from the video
 * extraction itself), so the corpus stays self-consistent. Flows run in one process; the
 * bootstrap mirror carries the cache-seeding requests to flows recorded with warm caches.
 *
 * Opt-in only: `KE_SMOKE=1 ./gradlew :core:testDebugUnitTest`. A flow added for a new seam call
 * is recorded alone (the rest of the corpus is not re-recorded) through its own test method, e.g.
 * `KE_SMOKE=1 ./gradlew :core:testDebugUnitTest --tests '*FixtureRecorderTest.recordSuggestionsFlow'`.
 */
class FixtureRecorderTest {

    private val corpus = Paths.get("src", "testFixtures", "resources", "extract-fixtures")

    @Test
    fun recordAllFixtureFlows() {
        assumeSmoke()
        recordAndSwap(::recordEveryFlow) { recording -> replaceCorpus(recording, corpus) }
    }

    @Test
    fun recordSuggestionsFlow() {
        assumeSmoke()
        recordNewFlows(listOf(SUGGESTIONS_FLOW), ::recordSuggestions)
    }

    @Test
    fun recordChannelPageFlows() {
        assumeSmoke()
        recordNewFlows(listOf(CHANNEL_PAGE_FLOW) + CHANNEL_TAB_FLOWS.values) { recordChannelPage(it, RECORDED_CHANNEL_URL) }
    }

    @Test
    fun recordChaptersVideoFlow() {
        assumeSmoke()
        recordNewFlows(listOf(CHAPTERS_VIDEO_FLOW), ::recordChaptersVideo)
    }

    /** The recorded feed's newest entry, pinned (a live feed may have moved on since). */
    @Test
    fun recordFeedNewestVideoFlow() {
        assumeSmoke()
        recordNewFlows(listOf(FEED_NEWEST_VIDEO_FLOW)) { recordFeedNewestVideo(it, RecordedChannel.FEED_ENTRIES.first().first) }
    }

    @Test
    fun recordPlaylistFlows() {
        assumeSmoke()
        recordNewFlows(listOf(PLAYLIST_FLOW, PAGED_PLAYLIST_FLOW, PLAYLIST_UNAVAILABLE_FLOW), ::recordPlaylists)
    }

    /** The fixed video's page with the WEB storyboard spec. Recorded alone it runs cold, so its
     *  player code lands in the flow; in a full run `bootstrap/` carries it. */
    @Test
    fun recordStoryboardsVideoFlow() {
        assumeSmoke()
        recordNewFlows(listOf(STORYBOARDS_VIDEO_FLOW), ::recordStoryboardsVideo)
    }

    /** The video page of a stream live at recording time (the Live kiosk's first item). */
    @Test
    fun recordLiveVideoFlow() {
        assumeSmoke()
        recordNewFlows(listOf(LIVE_VIDEO_FLOW), ::recordLiveVideo)
    }

    /** A Releases-tab album: the channel page opens it as a playlist too. */
    @Test
    fun recordAlbumPlaylistFlow() {
        assumeSmoke()
        recordNewFlows(listOf(ALBUM_PLAYLIST_FLOW), ::recordAlbumPlaylist)
    }

    /** A search whose answer leads with a channel card. */
    @Test
    fun recordChannelSearchFlow() {
        assumeSmoke()
        recordNewFlows(listOf(CHANNEL_SEARCH_FLOW), ::recordChannelSearch)
    }

    /** Video pages YouTube refuses: an age gate and a private video. */
    @Test
    fun recordRefusedVideoFlows() {
        assumeSmoke()
        recordNewFlows(listOf(AGE_RESTRICTED_VIDEO_FLOW, PRIVATE_VIDEO_FLOW), ::recordRefusedVideos)
    }

    /** Refusals the player names: a made-for-kids video and a not-yet-started stream. */
    @Test
    fun recordNamedRefusalFlows() {
        assumeSmoke()
        recordNewFlows(listOf(MADE_FOR_KIDS_VIDEO_FLOW, UPCOMING_VIDEO_FLOW)) { outDir ->
            listOf(MADE_FOR_KIDS_VIDEO_FLOW to MADE_FOR_KIDS_VIDEO_ID, UPCOMING_VIDEO_FLOW to UPCOMING_VIDEO_ID).forEach { (flow, id) ->
                recordFailedFlow(outDir, flow) { it.video(VideoId(id)) }
            }
        }
    }

    /** A premiere before it starts, in a search and on its channel's Videos tab. */
    @Test
    fun recordUpcomingListingFlows() {
        assumeSmoke()
        recordNewFlows(listOf(UPCOMING_SEARCH_FLOW, UPCOMING_CHANNEL_FLOW)) { outDir ->
            val pages = listOf(
                recordFlow(outDir, UPCOMING_SEARCH_FLOW) { it.search(UPCOMING_SEARCH_QUERY) },
                recordFlow(outDir, UPCOMING_CHANNEL_FLOW) { it.channelTab(UPCOMING_CHANNEL_URL, ChannelTab.VIDEOS) },
            )
            pages.forEach { page ->
                val premiere = page.items.filterIsInstance<VideoItem>().firstOrNull { it.id.value == UPCOMING_PREMIERE_ID }
                assertTrue("the premiere must be listed upcoming, got $premiere", premiere?.upcoming == true)
            }
        }
    }

    /** A made-for-kids video through MWEB (KP-D29), with a placeholder token and a stand-in `n`
     *  solver; the player code is fetched cold, so it lands in the flow. */
    @Test
    fun recordMadeForKidsMwebFlow() {
        assumeSmoke()
        recordNewFlows(listOf(MWEB_VIDEO_FLOW)) { outDir ->
            YoutubeJavaScriptPlayerManager.clearAllCaches()
            val recorder = RecordingDownloader(outDir, MWEB_VIDEO_FLOW)
            val access = NewPipeAccess(FixedWebPoTokenSource(PLACEHOLDER_WEB_PO_TOKEN), FixedNChallengeSolver(), recorder)
            val metadata = runBlocking { access.video(VideoId(MWEB_VIDEO_ID)) }
            assertTrue("the MWEB answer must stream over SABR", metadata.webStreamingClient != null && metadata.streams.isNotEmpty())
        }
    }

    /** A video's comments (two pages and one comment's replies, one session), a Short's, a kids
     *  video's (off) and a private video's (fails). */
    @Test
    fun recordCommentsFlows() {
        assumeSmoke()
        recordNewFlows(listOf(COMMENTS_FLOW, SHORT_COMMENTS_FLOW, COMMENTS_OFF_FLOW, PRIVATE_COMMENTS_FLOW), ::recordComments)
    }

    /** The related call's own `next` answer (FY.2): the page flows' `next` answers drive its
     *  offline tests; this one is the request it really makes. */
    @Test
    fun recordRelatedFlow() {
        assumeSmoke()
        recordNewFlows(listOf(RELATED_FLOW), ::recordRelated)
    }

    /** Swaps only [flows]' directories, leaving the rest of the corpus and `bootstrap/` as they
     *  are (a cache-seeding request made while recording lands in the flow's own directory). */
    private fun recordNewFlows(flows: List<String>, record: (Path) -> Unit) {
        recordAndSwap(record) { recording ->
            flows.forEach { replaceCorpus(recording.resolve(it), corpus.resolve(it)) }
        }
    }

    private fun recordAndSwap(record: (Path) -> Unit, swap: (Path) -> Unit) {
        val recording = Files.createTempDirectory(corpus.parent, "extract-fixtures-recording")
        try {
            record(recording)
            swap(recording)
        } finally {
            recording.toFile().deleteRecursively()
        }
    }

    private fun assumeSmoke() {
        assumeTrue("set KE_SMOKE=1 to record fixtures (talks to YouTube)", System.getenv("KE_SMOKE") != null)
    }

    private fun recordEveryFlow(outDir: Path) {
        val metadata = recordVideoFlow(outDir)
        val channelUrl = metadata.uploaderUrl
        assertTrue("video extraction must expose the uploader channel", !channelUrl.isNullOrBlank())

        val searchRecorder = RecordingDownloader(outDir, "search")
        val searchAccess = NewPipeAccess(downloader = searchRecorder)
        val search = runBlocking { searchAccess.search(FIXED_SEARCH_QUERY) }
        assertTrue("search must return items, got ${search.items.size}", search.items.isNotEmpty())
        assertTrue("search must return at least one video", search.items.any { it is VideoItem })
        assertTrue("search corpus must paginate for the page-2 fixture", search.next != null)
        val searchPage2 = runBlocking { searchAccess.search(FIXED_SEARCH_QUERY, search.next) }
        assertTrue("search page 2 must return items", searchPage2.items.isNotEmpty())

        recordSuggestions(outDir)
        recordChaptersVideo(outDir)
        recordStoryboardsVideo(outDir)
        recordChannelSearch(outDir)
        recordRefusedVideos(outDir)
        recordPlaylists(outDir)
        recordAlbumPlaylist(outDir)
        recordLiveVideo(outDir)
        recordComments(outDir)
        recordRelated(outDir)

        // Every exposed kiosk gets a corpus: each rides its own vendored extractor and endpoint.
        KIOSK_FLOWS.forEach { (kiosk, flow) ->
            val page = recordFlow(outDir, flow) { it.browse(kiosk) }
            assertTrue("$flow must return items", page.items.isNotEmpty())
            if (kiosk == BrowseKiosk.LIVE) {
                assertTrue("live kiosk items must be marked live", page.items.filterIsInstance<VideoItem>().all { it.isLive })
            }
        }

        val channelVideos = recordFlow(outDir, "channel-videos") { it.channelTab(channelUrl!!, ChannelTab.VIDEOS) }
        assertTrue("channel tab must return videos", channelVideos.items.isNotEmpty())
        recordChannelPage(outDir, channelUrl!!)

        val feed = recordFlow(outDir, "channel-feed") { it.channelFeed(channelUrl!!) }
        assertTrue("channel feed must return videos", feed.items.isNotEmpty())
        val newest = feed.items.filterIsInstance<VideoItem>().maxBy { it.uploadDate!! }
        recordFeedNewestVideo(outDir, newest.id.value)

        // The Shorts filter's fixture teeth (D5): the shorts tab is recorded raw, so the offline
        // test can map real short-form items and prove none becomes a listing item.
        val shortsRecorder = RecordingDownloader(outDir, "channel-shorts")
        val rawShorts = rawShortsTabItems(shortsRecorder)
        assertTrue(
            "shorts-tab corpus must contain short-form items, got ${rawShorts.size}",
            rawShorts.isNotEmpty() && rawShorts.all { it.isShortFormContent },
        )
    }

    /** One replay session, in [SUGGESTION_QUERIES] order. */
    private fun recordSuggestions(outDir: Path) {
        val recorder = RecordingDownloader(outDir, SUGGESTIONS_FLOW)
        val access = NewPipeAccess(downloader = recorder)
        val (latin, nonLatin, nonsense) = SUGGESTION_QUERIES.map { runBlocking { access.suggestions(it) } }
        assertTrue("a completable prefix must get suggestions", latin.isNotEmpty())
        assertTrue("a non-Latin prefix must get suggestions", nonLatin.isNotEmpty())
        assertTrue("the nonsense query must get none", nonsense.isEmpty())
    }

    private fun recordChannelSearch(outDir: Path) {
        val page = recordFlow(outDir, CHANNEL_SEARCH_FLOW) { it.search(CHANNEL_SEARCH_QUERY) }
        assertTrue("the search must lead with a channel card, got ${page.items.firstOrNull()}", page.items.firstOrNull() is ChannelItem)
    }

    /** The channel page (header + tabs), then the first page of each tab in [CHANNEL_TAB_FLOWS]. */
    private fun recordChannelPage(outDir: Path, channelUrl: String) {
        val channel = recordFlow(outDir, CHANNEL_PAGE_FLOW) { it.channel(channelUrl) }
        CHANNEL_TAB_FLOWS.forEach { (tab, flow) ->
            assertTrue("the channel page must list $tab, got ${channel.tabs}", tab in channel.tabs)
            val page = recordFlow(outDir, flow) { it.channelTab(channelUrl, tab) }
            assertTrue("$flow must return items", page.items.isNotEmpty())
        }
    }

    /** A playlist that fits one page, the first two pages of a longer one, then a playlist YouTube
     *  says doesn't exist. */
    private fun recordPlaylists(outDir: Path) {
        val single = recordFlow(outDir, PLAYLIST_FLOW) { it.playlist(PLAYLIST_URL) }
        assertTrue("the playlist must list videos", single.videos.items.isNotEmpty())

        val second = recordFlow(outDir, PAGED_PLAYLIST_FLOW) { access ->
            val next = access.playlist(PAGED_PLAYLIST_URL).videos.next
            assertTrue("the paged playlist must continue for the page-2 fixture", next != null)
            access.playlistVideos(PAGED_PLAYLIST_URL, next!!)
        }
        assertTrue("playlist page 2 must list videos", second.items.isNotEmpty())

        recordFailedFlow(outDir, PLAYLIST_UNAVAILABLE_FLOW) { it.playlist(UNAVAILABLE_PLAYLIST_URL) }
    }

    private fun recordRefusedVideos(outDir: Path) {
        listOf(AGE_RESTRICTED_VIDEO_FLOW to AGE_RESTRICTED_VIDEO_ID, PRIVATE_VIDEO_FLOW to PRIVATE_VIDEO_ID).forEach { (flow, id) ->
            recordFailedFlow(outDir, flow) { it.video(VideoId(id)) }
        }
    }

    private fun recordComments(outDir: Path) {
        recordFlow(outDir, COMMENTS_FLOW) { access ->
            val first = access.comments(VideoId(FIXED_VIDEO_ID))
            assertTrue("the video's comments must continue for the page-2 fixture", first.next != null)
            val second = access.comments(VideoId(FIXED_VIDEO_ID), first.next)
            assertTrue("comments page 2 must list comments", second.comments.isNotEmpty())
            val replies = first.comments.first { it.repliesToken != null }.repliesToken
            assertTrue("replies must list comments", access.comments(VideoId(FIXED_VIDEO_ID), replies).comments.isNotEmpty())
        }
        val short = recordFlow(outDir, SHORT_COMMENTS_FLOW) { it.comments(VideoId(SHORT_COMMENTS_VIDEO_ID)) }
        assertTrue("the Short must list comments", short.comments.isNotEmpty())
        val off = recordFlow(outDir, COMMENTS_OFF_FLOW) { it.comments(VideoId(COMMENTS_OFF_VIDEO_ID)) }
        assertTrue("the kids video's comments must be off, got $off", off.commentsOff)

        recordFailedFlow(outDir, PRIVATE_COMMENTS_FLOW) { it.comments(VideoId(PRIVATE_VIDEO_ID)) }
    }

    private fun recordRelated(outDir: Path) {
        val related = recordFlow(outDir, RELATED_FLOW) { it.related(VideoId(CHAPTERS_VIDEO_ID)) }
        assertTrue("the related call must list videos", related.isNotEmpty())
    }

    private fun recordAlbumPlaylist(outDir: Path) {
        val album = recordFlow(outDir, ALBUM_PLAYLIST_FLOW) { it.playlist(ALBUM_PLAYLIST_URL) }
        assertTrue("the album must list videos", album.videos.items.isNotEmpty())
    }

    private fun recordLiveVideo(outDir: Path) {
        val live = runBlocking { NewPipeAccess().browse(BrowseKiosk.LIVE) }.items.filterIsInstance<VideoItem>().first()
        val metadata = recordFlow(outDir, LIVE_VIDEO_FLOW) { it.video(live.id) }
        assertTrue("the Live kiosk's first item must still be live", metadata.isLive)
    }

    private fun recordChaptersVideo(outDir: Path) {
        val metadata = recordFlow(outDir, CHAPTERS_VIDEO_FLOW) { it.video(VideoId(CHAPTERS_VIDEO_ID)) }
        assertTrue("the chapters video must have chapters", metadata.chapters.isNotEmpty())
        assertTrue("the chapters video must have storyboards", metadata.storyboards.isNotEmpty())
    }

    private fun recordStoryboardsVideo(outDir: Path) {
        val metadata = recordFlow(outDir, STORYBOARDS_VIDEO_FLOW) { it.video(VideoId(FIXED_VIDEO_ID)) }
        assertTrue("the WEB spec must offer 320x180 frames", metadata.storyboards.any { it.frameWidth == 320 })
    }

    /** The video page of the feed's newest upload: what the Home hero shows. */
    private fun recordFeedNewestVideo(outDir: Path, videoId: String) {
        val metadata = recordFlow(outDir, FEED_NEWEST_VIDEO_FLOW) { it.video(VideoId(videoId)) }
        assertTrue("the hero video must have a description", metadata.description != null)
        assertTrue("the hero video must offer video streams with a height", metadata.streams.any { it.height != null })
    }

    private fun recordVideoFlow(outDir: Path): VideoMetadata {
        val recorder = RecordingDownloader(outDir, "video")
        val extractor = NewPipeAccess(downloader = recorder)
        val metadata = runBlocking { extractor.video(VideoId(FIXED_VIDEO_ID)) }
        assertTrue(
            "expected recorded video fixtures, got ${recorder.recordedFiles}",
            recorder.recordedFiles.size >= 3,
        )
        assertTrue("recording must exercise the SABR fields", !metadata.serverAbrStreamingUrl.isNullOrBlank())
        return metadata
    }

    /** Records a call YouTube refuses or the extractor can't read: it must fail at the seam. */
    private fun recordFailedFlow(outDir: Path, flow: String, call: suspend (Extractor) -> Unit) {
        val recorder = RecordingDownloader(outDir, flow)
        val failure = runCatching { runBlocking { call(NewPipeAccess(downloader = recorder)) } }.exceptionOrNull()
        assertTrue("$flow must fail at the seam, got $failure", failure is AccessException)
        assertTrue("expected recorded $flow fixtures", recorder.recordedFiles.isNotEmpty())
    }

    private fun <T> recordFlow(outDir: Path, flow: String, call: suspend (Extractor) -> T): T {
        val recorder = RecordingDownloader(outDir, flow)
        val extractor = NewPipeAccess(downloader = recorder)
        val page = runBlocking { call(extractor) }
        assertTrue(
            "expected recorded $flow fixtures, got ${recorder.recordedFiles}",
            recorder.recordedFiles.size >= 1,
        )
        return page
    }
}
