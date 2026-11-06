package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.newpipe.FEED_NEWEST_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.KIOSK_FLOWS
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.newpipe.RecordedFeedNewestVideo
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.foryou.RecordedLists
import com.arshad.ayn.feature.library.watchEntry
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.testSubscription
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-layer: Home filled from recorded payloads mapped by the real seam. The recorded RSS feed
 * replays live through [NewPipeAccess] on the test dispatcher; the Live kiosk and the hero's
 * video page are replayed before the ViewModel runs (one replay transport per process) and
 * served by delegation. Expected values are the corpus goldens (TEST-ORACLE-01).
 */
class HomeViewModelFixtureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordedFeedFillsTheHeroAndYourChannelsAndTheLiveKioskFillsLiveNow() = runTest(dispatcher) {
        val heroId = RecordedChannel.FEED_ENTRIES.first().first
        val heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(heroId)) }
        val livePage = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
        // Constructed last: construction installs the extractor's process-global transport.
        val feedAccess = NewPipeAccess(downloader = FixtureDownloader("channel-feed"), io = dispatcher)
        val extractor = RecordedHomeExtractor(feedAccess, livePage, heroPage)
        val store = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(RECORDED_CHANNEL_URL)) }
        val clock = TestClock(RecordedChannel.FEED_CLOCK)

        val viewModel = homeViewModel(extractor, SubscriptionsFeed(feedAccess, clock), store, FakeWatchStore(), FakePlaylistStore(), FakeRelatedListStore(), clock)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.failure)
        val subscriptions = state.subscriptions as SubscriptionsState.Ready
        val hero = subscriptions.hero!!
        assertEquals(heroId, hero.video.id.value)
        assertTrue("uploaded two days before the clock", hero.isNew)
        assertEquals(RecordedFeedNewestVideo.DURATION_SECONDS, hero.video.durationSeconds)
        assertEquals(RecordedFeedNewestVideo.THUMBNAIL_URL, hero.video.thumbnailUrl)
        val page = hero.page as HeroPage.Ready
        assertEquals(VideoFormat(RecordedFeedNewestVideo.MAX_HEIGHT, RecordedFeedNewestVideo.FPS), page.format)
        assertTrue("got: ${page.description?.take(200)}", page.description!!.startsWith(RecordedFeedNewestVideo.DESCRIPTION_PREFIX))
        assertEquals("one channel: its newest upload is the hero", emptyList<Any>(), subscriptions.newFromSubscriptions)
        assertEquals(
            listOf(RECORDED_CHANNEL_URL to RecordedChannel.FEED_ENTRIES.first().second),
            subscriptions.channels.map { it.subscription.channelUrl to it.latestUpload },
        )
        val live = (state.liveNow as LiveNowState.Ready).items
        assertTrue("live row has items, all marked live", live.isNotEmpty() && live.all { it.isLive })
    }

    @Test
    fun aHeroWhoseRecordedPageIsALiveStreamIsLiveWithNoLength() = runTest(dispatcher) {
        val heroId = RecordedChannel.FEED_ENTRIES.first().first
        // The recorded live stream's page, served as the hero's: RSS can't tell a live upload.
        val livePage = recorded(LIVE_VIDEO_FLOW) { video(VideoId(RecordedLiveVideo.ID)) }.copy(id = VideoId(heroId))
        val liveKiosk = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
        val feedAccess = NewPipeAccess(downloader = FixtureDownloader("channel-feed"), io = dispatcher)
        val store = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(RECORDED_CHANNEL_URL)) }
        val clock = TestClock(RecordedChannel.FEED_CLOCK)

        val viewModel = homeViewModel(
            RecordedHomeExtractor(feedAccess, liveKiosk, livePage),
            SubscriptionsFeed(feedAccess, clock),
            store,
            FakeWatchStore(),
            FakePlaylistStore(),
            FakeRelatedListStore(),
            clock,
        )
        advanceUntilIdle()

        val hero = (viewModel.uiState.value.subscriptions as SubscriptionsState.Ready).hero!!
        assertTrue(hero.video.isLive)
        assertEquals("a live stream's length stays unknown", -1L, hero.video.durationSeconds)
    }

    @Test
    fun aWatchedNewestEntryHandsTheHeroToTheFeedsNextOne() = runTest(dispatcher) {
        val (newestId, newestAt) = RecordedChannel.FEED_ENTRIES[0]
        val nextId = RecordedChannel.FEED_ENTRIES[1].first
        // The newest entry's recorded page, served as the next one's: only the hero's id matters here.
        val heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(newestId)) }.copy(id = VideoId(nextId))
        val liveKiosk = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
        val feedAccess = NewPipeAccess(downloader = FixtureDownloader("channel-feed"), io = dispatcher)
        val store = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(RECORDED_CHANNEL_URL)) }
        val watch = FakeWatchStore().apply { entries.value = listOf(watchEntry(newestId, durationMs = null, watched = true)) }
        val clock = TestClock(RecordedChannel.FEED_CLOCK)

        val viewModel = homeViewModel(
            RecordedHomeExtractor(feedAccess, liveKiosk, heroPage),
            SubscriptionsFeed(feedAccess, clock),
            store,
            watch,
            FakePlaylistStore(),
            FakeRelatedListStore(),
            clock,
        )
        advanceUntilIdle()

        val subscriptions = viewModel.uiState.value.subscriptions as SubscriptionsState.Ready
        assertEquals(nextId, subscriptions.hero!!.video.id.value)
        assertEquals("Your channels still date the channel by its newest upload", newestAt, subscriptions.channels.single().latestUpload)
    }

    /**
     * FY-D7 on a recorded watch page's related list kept for a watched seed: its first card is the
     * hero (the order `ForYouRankerTest` works by hand), dressed as one recorded request, and the
     * recorded feed's newest upload leads New from your subscriptions.
     */
    @Test
    fun aRecordedRelatedListGivesForYouTheHeroAndTheFeedsNewestLeadsItsRow() = runTest(dispatcher) {
        val newestId = RecordedChannel.FEED_ENTRIES.first().first
        val feedHeroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(newestId)) }
        val forYouHeroPage = feedHeroPage.copy(id = VideoId("MiUHjLxm3V0"), description = "for you")
        val liveKiosk = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
        val relatedList = RelatedList(RecordedLists.chapters, fetchedAt = RecordedChannel.FEED_CLOCK, failedAt = null)
        val feedAccess = NewPipeAccess(downloader = FixtureDownloader("channel-feed"), io = dispatcher)
        val store = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(RECORDED_CHANNEL_URL)) }
        val watchedAt = (RecordedChannel.FEED_CLOCK - 1.hours).toEpochMilliseconds()
        val watch = FakeWatchStore().apply { entries.value = listOf(watchEntry(CHAPTERS_VIDEO_ID, lastWatchedAtMillis = watchedAt, watched = true)) }
        val related = FakeRelatedListStore().apply { kept[VideoId(CHAPTERS_VIDEO_ID)] = relatedList }
        val clock = TestClock(RecordedChannel.FEED_CLOCK)
        val attempts = FakeFetchAttemptLog(clock)

        val viewModel = homeViewModel(
            RecordedHomeExtractor(feedAccess, liveKiosk, feedHeroPage, forYouHeroPage),
            SubscriptionsFeed(feedAccess, clock),
            store,
            watch,
            FakePlaylistStore(),
            related,
            clock,
            attempts = attempts,
        )
        advanceUntilIdle()
        viewModel.onVisible()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val forYou = state.forYou!!
        assertEquals("MiUHjLxm3V0", forYou.hero.video.id.value)
        assertEquals(HeroPage.Ready(description = "for you", format = VideoFormat(RecordedFeedNewestVideo.MAX_HEIGHT, RecordedFeedNewestVideo.FPS)), forYou.hero.page)
        assertEquals(listOf(FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
        assertEquals("twDvxNooI_4", forYou.row.first().video.id.value)
        val subscriptions = state.subscriptions as SubscriptionsState.Ready
        assertNull(subscriptions.hero)
        assertEquals(newestId, subscriptions.newFromSubscriptions.first().id.value)
    }

    /** The real feed seam, with the heroes' video pages and the Live kiosk served from recordings. */
    private class RecordedHomeExtractor(
        feedAccess: Extractor,
        private val livePage: ItemsPage,
        private vararg val heroPages: VideoMetadata,
    ) : Extractor by feedAccess {
        override suspend fun video(id: VideoId): VideoMetadata =
            checkNotNull(heroPages.firstOrNull { it.id == id }) { "Home asked for ${id.value}; only the heroes' pages are recorded" }

        /** For you's top-up of the followed seeds fails here before the budget records it, so the
         *  attempt log holds the hero's request alone (the refresher has its own tests). */
        override suspend fun related(videoId: VideoId): List<VideoItem> =
            error("Home asked for ${videoId.value}'s related list; none is recorded")

        override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage {
            check(kiosk == BrowseKiosk.LIVE && page == null) { "Home browses Live's first page only" }
            return livePage
        }
    }
}
