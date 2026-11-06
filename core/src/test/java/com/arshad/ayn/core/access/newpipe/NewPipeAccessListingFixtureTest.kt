package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Offline listing regression tests: the seam's browse/search/channel/feed paths run
 * against recorded InnerTube fixtures (no network, deterministic). Goldens are read from the
 * recorded payload into `RecordedCorpus.kt` (TEST-ORACLE-01); they change only when the corpus
 * is re-recorded (`KE_SMOKE=1`, [FixtureRecorderTest]).
 */
class NewPipeAccessListingFixtureTest {

    @Test
    fun searchMapsTheCollectorsItemsInOrder() {
        val rawVideoIds = rawSearchItems().filterNot { it.isShortFormContent }.map { it.streamKey() }

        val page = runBlocking { seamAccess("search").search(FIXED_SEARCH_QUERY) }

        val seamIds = page.items.filterIsInstance<VideoItem>().map { it.id.value }
        assertEquals("seam output must equal the raw collected streams, in order", rawVideoIds, seamIds)
        assertEquals(RecordedSearch.PAGE_1_VIDEOS, seamIds.size)
        assertEquals(RecordedSearch.PAGE_1_FIRST_ID, seamIds.first())
    }

    /** M7-D12: the page's Shorts shelves arrive apart from its results, each once, in page order,
     *  with their portrait picture; no Short is among the results (D5). */
    @Test
    fun searchListsItsShelvesShortsApartFromItsResults() {
        val page = runBlocking { seamAccess("search").search(FIXED_SEARCH_QUERY) }

        val shorts = page.shorts
        assertEquals(RecordedSearch.PAGE_1_SHORTS, shorts.size)
        assertEquals(RecordedSearch.PAGE_1_FIRST_SHORT_ID, shorts.first().id.value)
        assertEquals(RecordedSearch.PAGE_1_LAST_SHORT_ID, shorts.last().id.value)
        assertEquals(RecordedSearch.PAGE_1_FIRST_SHORT_VIEWS, shorts.first().viewCount)
        assertEquals(RecordedSearch.PAGE_1_FIRST_SHORT_PICTURE, shorts.first().thumbnailUrl)
        val resultIds = page.items.filterIsInstance<VideoItem>().map { it.id }.toSet()
        assertTrue("no Short is among the results", shorts.none { it.id in resultIds })
    }

    /** A Short whose listing picture isn't portrait gets its own picture by id. */
    @Test
    fun aShortWithASquareListingPictureGetsItsPortraitPicture() {
        val shorts = runBlocking { seamAccess("search").search(FIXED_SEARCH_QUERY) }.shorts

        val square = shorts.single { it.id.value == RecordedSearch.PAGE_1_SQUARE_PICTURE_SHORT_ID }
        assertEquals("https://i.ytimg.com/vi/${square.id.value}/oar2.jpg", square.thumbnailUrl)
    }

    /** YouTube's shelves list a Short more than once. */
    @Test
    fun aSearchsRepeatedShortsAreListedOnceInPageOrder() {
        val shorts = runBlocking { seamAccess(CHANNEL_SEARCH_FLOW).search(CHANNEL_SEARCH_QUERY) }.shorts

        assertEquals(RecordedChannelSearch.SHORTS, shorts.size)
        assertEquals(RecordedChannelSearch.FIRST_SHORT_ID, shorts.first().id.value)
        assertEquals(RecordedChannelSearch.FIRST_SHORT_TITLE, shorts.first().title)
        assertEquals(RecordedChannelSearch.LAST_SHORT_ID, shorts.last().id.value)
    }

    @Test
    fun aSearchShelfsShortsCarryNoChannel() {
        val shorts = runBlocking { seamAccess(CHANNEL_SEARCH_FLOW).search(CHANNEL_SEARCH_QUERY) }.shorts

        assertTrue("YouTube sends no channel for a shelf's Short", shorts.all { it.uploaderName == null && it.uploaderUrl == null })
    }

    @Test
    fun aSearchsLaterPageListsItsOwnShorts() {
        val access = seamAccess("search")
        val page1 = runBlocking { access.search(FIXED_SEARCH_QUERY) }

        val page2 = runBlocking { access.search(FIXED_SEARCH_QUERY, page1.next) }

        assertEquals(RecordedSearch.PAGE_2_SHORTS, page2.shorts.size)
        assertEquals(RecordedSearch.PAGE_2_FIRST_SHORT_ID, page2.shorts.first().id.value)
    }

    @Test
    fun aSearchForAChannelLeadsWithItsChannelCard() {
        val page = runBlocking { seamAccess(CHANNEL_SEARCH_FLOW).search(CHANNEL_SEARCH_QUERY) }

        val channel = page.items.first() as ChannelItem
        assertEquals(RecordedChannelSearch.CHANNEL_NAME, channel.name)
        assertEquals(RecordedChannelSearch.CHANNEL_URL, channel.url)
        assertEquals(RecordedChannelSearch.SUBSCRIBERS, channel.subscriberCount)
        val videos = page.items.drop(1).map { it as VideoItem }
        assertEquals(RecordedChannelSearch.VIDEOS, videos.size)
        assertEquals(RecordedChannelSearch.FIRST_VIDEO_ID, videos.first().id.value)
    }

    @Test
    fun searchPaginatesWithRecordedPageToken() {
        // One replay session (FixtureDownloader) across both pages: occurrence numbering
        // continues, exactly like the recorded capture order.
        val access = seamAccess("search")
        val page1 = runBlocking { access.search(FIXED_SEARCH_QUERY) }
        assertTrue("corpus provides a continuation token", page1.next != null)

        val page2 = runBlocking { access.search(FIXED_SEARCH_QUERY, page1.next) }

        val page2Ids = page2.items.filterIsInstance<VideoItem>().map { it.id.value }
        assertEquals(RecordedSearch.PAGE_2_VIDEOS, page2Ids.size)
        assertEquals(RecordedSearch.PAGE_2_FIRST_ID, page2Ids.first())
        assertTrue(
            "page 2 must advance past page 1",
            (page1.items.first() as VideoItem).id.value != page2Ids.first(),
        )
    }

    @Test
    fun browseMapsATrendingKiosk() {
        val kiosk = BrowseKiosk.TRENDING_MUSIC
        val page = runBlocking { seamAccess(KIOSK_FLOWS.getValue(kiosk)).browse(kiosk) }

        val videos = page.items.filterIsInstance<VideoItem>()
        assertEquals(RecordedKiosks.VIDEO_COUNTS[kiosk], videos.size)
        assertEquals("kiosk items are videos only", videos.size, page.items.size)
        assertTrue("kiosks do not paginate in this fork", page.next == null)
        assertEquals(RecordedKiosks.TRENDING_MUSIC_FIRST_ID, videos.first().id.value)
        assertTrue("a charts kiosk carries no live items", videos.none { it.isLive })
    }

    @Test
    fun browseMapsTheLiveKioskWithLiveMarkers() {
        val page = runBlocking { seamAccess(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)).browse(BrowseKiosk.LIVE) }

        val videos = page.items.filterIsInstance<VideoItem>()
        assertTrue("live kiosk must return items", videos.isNotEmpty())
        assertTrue("every live-kiosk item carries the D11 marker", videos.all { it.isLive })
        assertEquals(
            "ids must be distinct (the same stream can repeat across live shelves)",
            videos.size.toLong(),
            videos.map { it.id.value }.distinct().size.toLong(),
        )
    }

    /** Every exposed kiosk is replayed: each rides its own vendored extractor and endpoint,
     *  so an offline parse regression must fail here, not on a device. */
    @Test
    fun browseMapsTheRemainingKiosks() {
        for (kiosk in listOf(BrowseKiosk.TRENDING_GAMING, BrowseKiosk.TRENDING_MOVIES_AND_SHOWS, BrowseKiosk.TRENDING_PODCASTS)) {
            val flow = KIOSK_FLOWS.getValue(kiosk)
            val page = runBlocking { seamAccess(flow).browse(kiosk) }
            val videos = page.items.filterIsInstance<VideoItem>()
            assertEquals("$flow item count", RecordedKiosks.VIDEO_COUNTS[kiosk], videos.size)
            assertTrue("$flow items are videos only", videos.size == page.items.size)
            assertTrue("$flow carries no live items", videos.none { it.isLive })
            assertTrue("$flow does not paginate", page.next == null)
        }
    }

    @Test
    fun channelVideosTabMapsUploads() {
        val page = runBlocking { seamAccess("channel-videos").channelTab(RECORDED_CHANNEL_URL, ChannelTab.VIDEOS) }

        val videos = page.items.filterIsInstance<VideoItem>()
        assertEquals(RecordedChannel.UPLOADS_TAB_VIDEOS, videos.size)
        assertEquals(RecordedChannel.UPLOADS_TAB_FIRST_IDS.first(), videos.first().id.value)
        assertEquals(RecordedChannel.UPLOADS_TAB_FIRST_DURATION_SECONDS, videos.first().durationSeconds)
        assertTrue("a VOD uploads tab carries no live items", videos.none { it.isLive })
        assertTrue("uploads tab must offer a continuation", page.next != null)
    }

    /** Each payload marks one item upcoming: the search's `upcomingEventData` (start 1793721600) and
     *  the Videos tab's "Premieres 03/11/2026, 16:00" (UTC). Both give the start, neither a view count. */
    @Test
    fun aPremiereBeforeItsStartIsListedUpcomingWithItsStart() {
        val pages = listOf(
            runBlocking { seamAccess(UPCOMING_SEARCH_FLOW).search(UPCOMING_SEARCH_QUERY) },
            runBlocking { seamAccess(UPCOMING_CHANNEL_FLOW).channelTab(UPCOMING_CHANNEL_URL, ChannelTab.VIDEOS) },
        )

        pages.forEach { page ->
            val upcoming = page.items.filterIsInstance<VideoItem>().filter { it.upcoming }
            assertEquals(listOf(UPCOMING_PREMIERE_ID), upcoming.map { it.id.value })
            assertEquals(Instant.parse(UPCOMING_PREMIERE_START), upcoming.single().uploadDate)
            assertEquals(-1L, upcoming.single().viewCount)
            assertTrue("the rest have started", page.items.filterIsInstance<VideoItem>().count { !it.upcoming } > 1)
        }
    }

    @Test
    fun channelFeedMapsRssWithoutShortsOrContinuation() {
        val page = runBlocking { seamAccess("channel-feed").channelFeed(RECORDED_CHANNEL_URL) }

        val videos = page.items.filterIsInstance<VideoItem>()
        // The recorded corpus is 15 entries, 11 of them /shorts/ links — the D5 filter
        // must drop those; the goldens are the watch-linked remainder.
        assertEquals(RecordedChannel.FEED_ENTRIES.map { it.first }, videos.map { it.id.value })
        assertTrue("RSS feeds do not paginate", page.next == null)
    }

    /** M7-D12: the feed's `/shorts/` entries arrive apart, in feed order, with exact instants and a
     *  portrait picture asked for by id (the entry's own is landscape). */
    @Test
    fun channelFeedListsItsShortsApartWithPortraitPictures() {
        val page = runBlocking { seamAccess("channel-feed").channelFeed(RECORDED_CHANNEL_URL) }

        val shorts = page.shorts
        assertEquals(RecordedChannel.FEED_SHORTS, shorts.map { it.id.value to it.uploadDate })
        assertEquals(RecordedChannel.FEED_FIRST_SHORT_VIEWS, shorts.first().viewCount)
        assertEquals("https://i.ytimg.com/vi/${shorts.first().id.value}/oar2.jpg", shorts.first().thumbnailUrl)
        assertTrue("no ISO string may pose as display text", shorts.all { it.textualUploadDate == null })
        assertTrue(shorts.all { it.uploaderUrl == RECORDED_CHANNEL_URL })
    }

    /** The vendored feed extractor's one status branch: a 404 (a channel without a feed) is
     *  content-not-available, so the feed's uploads-tab fallback runs. Outside the playlist calls
     *  that vendored type still reads as UNREADABLE (it also covers endpoint drift). */
    @Test
    fun channelFeedAnswering404IsAnAccessFailure() {
        val access = NewPipeAccess(downloader = FixtureDownloader("channel-feed", responseCode = 404))

        val error = assertThrows(AccessException::class.java) {
            runBlocking { access.channelFeed(RECORDED_CHANNEL_URL) }
        }

        // Walk the chain: coroutine stack-trace recovery (on under -ea) re-wraps across withContext.
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        assertTrue(chain.joinToString(">") { it.javaClass.simpleName }, chain.any { it is ContentNotAvailableException })
        assertEquals(AccessFailure.UNREADABLE, error.failure)
    }

    /** RSS `<published>` is an ISO timestamp: it must arrive as the exact instant, never as
     *  display text. Goldens are the recorded entries' `<published>` values. */
    @Test
    fun channelFeedCarriesExactUploadInstantsAndNoDisplayText() {
        val page = runBlocking { seamAccess("channel-feed").channelFeed(RECORDED_CHANNEL_URL) }

        val videos = page.items.filterIsInstance<VideoItem>()
        assertEquals(RecordedChannel.FEED_ENTRIES, videos.map { it.id.value to it.uploadDate })
        assertTrue("no ISO string may pose as display text", videos.all { it.textualUploadDate == null })
    }

    /** The Shorts filter's real-payload regression (charter D5): a recorded shorts tab is
     *  driven raw, then mapped — none may become a listing item. */
    @Test
    fun shortsFromARealShortsTabNeverCrossTheSeam() {
        val rawShorts = rawShortsTabItems(FixtureDownloader("channel-shorts"))

        assertTrue("corpus must contain short-form items, got ${rawShorts.size}", rawShorts.isNotEmpty())
        assertTrue("every shorts-tab item is short-form", rawShorts.all { it.isShortFormContent })
        assertTrue(
            "no short-form item may map to a listing VideoItem",
            rawShorts.all { it.toVideoItemOrNull() == null },
        )
    }

    /** M7-D12: the Shorts tab is fetched; its videos arrive as Shorts, with the channel's own URL
     *  (the vendored item appends `/shorts`), and none as listing items. */
    @Test
    fun aShortsTabListsItsVideosAsShorts() {
        val page = runBlocking { seamAccess("channel-shorts").channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }

        assertEquals(emptyList<Any>(), page.items)
        assertEquals(RecordedShortsTab.SHORTS, page.shorts.size)
        val first = page.shorts.first()
        assertEquals(RecordedShortsTab.FIRST_ID, first.id.value)
        assertEquals(RecordedShortsTab.FIRST_TITLE, first.title)
        assertEquals(RecordedShortsTab.FIRST_VIEWS, first.viewCount)
        assertEquals(RecordedShortsTab.FIRST_PICTURE, first.thumbnailUrl)
        assertEquals(SHORTS_CHANNEL_URL, first.uploaderUrl)
        assertTrue("the tab continues", page.next != null)
    }

    private fun seamAccess(flow: String) = NewPipeAccess(downloader = FixtureDownloader(flow))

    /** The raw (pre-seam) search result, driven through the same fixtures the seam replays. */
    private fun rawSearchItems(): List<StreamInfoItem> {
        NewPipeAccess(downloader = FixtureDownloader("search")) // installs the replay transport
        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getSearchExtractor(FIXED_SEARCH_QUERY)
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
    }

    private fun StreamInfoItem.streamKey(): String =
        YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
}
