package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.StreamCandidate
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.foryou.FetchBudget
import com.arshad.ayn.feature.foryou.ForYouBuilder
import com.arshad.ayn.feature.foryou.ForYouRefresher
import com.arshad.ayn.feature.library.watchEntry
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.testChannel
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.net.UnknownHostException
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel against fakes (KT-TEST-01, ARCH-4): the hero and New from your subscriptions
 * picked from each channel's newest upload, the hero dressed from its video page after the rows
 * land, Your channels ordered by latest upload, Live now, the D8 error split, Retry, the rows
 * following the subscription store after Home loaded, and the profile's part: watched
 * videos left out, Continue watching, the hero's Watch later.
 * Deterministic via the injected Main dispatcher and clock (KT-CONC-09). Real payloads:
 * [HomeViewModelFixtureTest].
 */
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakeHomeExtractor()
    private val feeds = FakeFeedExtractor()
    private val store = FakeSubscriptionStore()
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val related = FakeRelatedListStore()
    private val clock = TestClock(NOW)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun heroIsTheNewestUploadAcrossChannels() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-20T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z"))

        val hero = ready(load()).hero!!

        assertEquals("bbbbbbbbbb1", hero.video.id.value)
    }

    @Test
    fun newFromSubscriptionsHoldsEachOtherChannelsNewestUploadNewestFirst() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-26T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z"))
        subscribe(CHANNEL_C, upload("ccccccccccc", "2026-06-10T09:00:00Z"))

        val row = ready(load()).newFromSubscriptions

        assertEquals(listOf("aaaaaaaaaa2", "ccccccccccc"), row.map { it.id.value })
    }

    @Test
    fun newFromSubscriptionsIsCapped() = runTest(dispatcher) {
        // 12 channels, one upload each, an hour apart: channel 12's is the hero.
        (1..12).forEach { n ->
            subscribe("https://www.youtube.com/channel/UC$n", upload("v%010d".format(n), "2026-06-27T%02d:00:00Z".format(n)))
        }

        val row = ready(load()).newFromSubscriptions

        assertEquals((11 downTo 4).map { "v%010d".format(it) }, row.map { it.id.value })
    }

    @Test
    fun aCollaborationUploadNewestInTwoFeedsShowsOnlyAsTheHero() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("collab00001", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("collab00001", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_C, upload("ccccccccccc", "2026-06-20T10:00:00Z"))

        val subscriptions = ready(load())

        assertEquals("collab00001", subscriptions.hero!!.video.id.value)
        assertEquals(listOf("ccccccccccc"), subscriptions.newFromSubscriptions.map { it.id.value })
    }

    @Test
    fun rowsLandBeforeTheHerosVideoPage() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "blurb")
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate

        val viewModel = newViewModel()
        advanceUntilIdle()

        val waiting = ready(viewModel.uiState.value)
        assertEquals(HeroPage.Loading, waiting.hero!!.page)
        assertEquals(listOf("bbbbbbbbbb1"), waiting.newFromSubscriptions.map { it.id.value })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HeroPage.Ready(description = "blurb", format = null), ready(viewModel.uiState.value).hero!!.page)
    }

    @Test
    fun heroIsDressedFromItsVideoPage() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage(
            "aaaaaaaaaa1",
            durationSeconds = 3369,
            description = "An octopus hunts crabs on land.",
            streams = listOf(stream(720, 25), stream(1080, 25)),
        )

        val hero = ready(load()).hero!!

        assertEquals(3369L, hero.video.durationSeconds)
        assertEquals(
            HeroPage.Ready(description = "An octopus hunts crabs on land.", format = VideoFormat(height = 1080, fps = 25)),
            hero.page,
        )
    }

    /** FY-D4: For you keeps the related list of every page Home holds. */
    @Test
    fun theHerosPageHandsItsRelatedListToForYou() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val relatedVideos = listOf(testVideo("rrrrrrrrrr1"), testVideo("rrrrrrrrrr2"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", relatedVideos = relatedVideos)

        load()

        assertEquals(listOf(VideoId("aaaaaaaaaa1") to relatedVideos), related.recorded)
    }

    @Test
    fun aRelatedListThatCantBeSavedLeavesTheHeroDressed() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "blurb", relatedVideos = listOf(testVideo("rrrrrrrrrr1")))
        related.failWrites = true

        val hero = ready(load()).hero!!

        assertEquals(HeroPage.Ready(description = "blurb", format = null), hero.page)
    }

    /** An RSS upload has no length or live flag, so a length rule only shows on its page. */
    @Test
    fun heroWhosePageTheFilterRefusesIsMarkedFilteredWithoutFailingHome() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.filtered += "aaaaaaaaaa1"

        val hero = ready(load()).hero!!

        assertEquals(HeroPage.Filtered, hero.page)
    }

    @Test
    fun heroWhoseVideoPageFailsIsUnavailableAndKeepsTheFeedLength() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        // No page set: the fake's video() fails.

        val hero = ready(load()).hero!!

        assertEquals(HeroPage.Unavailable, hero.page)
        assertEquals(-1L, hero.video.durationSeconds)
    }

    @Test
    fun aLiveHerosZeroLengthNeverReplacesTheUnknownOne() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", durationSeconds = 0)

        val hero = ready(load()).hero!!

        assertEquals(-1L, hero.video.durationSeconds)
    }

    @Test
    fun heroTakesItsPagesPictureAndKeepsTheFeedsWhenThePageHasNone() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z").copy(thumbnailUrl = "feed-a.jpg"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1").copy(thumbnailUrl = "page-a.jpg")
        assertEquals("page-a.jpg", ready(load()).hero!!.video.thumbnailUrl)

        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1")
        assertEquals("feed-a.jpg", ready(load()).hero!!.video.thumbnailUrl)
    }

    @Test
    fun aHeroWhosePageIsLiveIsMarkedLive() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", durationSeconds = 0).copy(isLive = true)

        assertTrue(ready(load()).hero!!.video.isLive)
    }

    @Test
    fun heroUploadedWithinTheBadgeWindowIsNew() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))

        assertTrue(ready(load()).hero!!.isNew)
    }

    @Test
    fun heroOlderThanTheBadgeWindowIsNotNew() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-01T10:00:00Z"))

        assertFalse(ready(load()).hero!!.isNew)
    }

    @Test
    fun yourChannelsListsEverySubscriptionByLatestUploadUnknownLast() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-20T10:00:00Z"))
        subscribe(CHANNEL_B)
        subscribe(CHANNEL_C, upload("ccccccccccc", "2026-06-26T10:00:00Z"))
        feeds.failingChannels += CHANNEL_B

        val channels = ready(load()).channels

        assertEquals(listOf(CHANNEL_C, CHANNEL_A, CHANNEL_B), channels.map { it.subscription.channelUrl })
        assertEquals(
            listOf(Instant.parse("2026-06-26T10:00:00Z"), Instant.parse("2026-06-20T10:00:00Z"), null),
            channels.map { it.latestUpload },
        )
    }

    @Test
    fun noSubscriptionsIsTheBlankSlateWithLiveNowBelow() = runTest(dispatcher) {
        extractor.live = ItemsPage(listOf(liveVideo("lllllllllll")), next = null)

        val state = load()

        assertEquals(SubscriptionsState.NoSubscriptions, state.subscriptions)
        assertEquals(listOf("lllllllllll"), (state.liveNow as LiveNowState.Ready).items.map { it.id.value })
        assertNull(state.failure)
    }

    @Test
    fun liveNowKeepsVideosOnceEach() = runTest(dispatcher) {
        extractor.live = ItemsPage(
            items = listOf(liveVideo("aaaaaaaaaaa"), testChannel(), liveVideo("aaaaaaaaaaa"), liveVideo("bbbbbbbbbbb")),
            next = null,
        )

        val live = load().liveNow as LiveNowState.Ready

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), live.items.map { it.id.value })
    }

    @Test
    fun liveNowFailingAloneIsNotAScreenError() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.liveFailure = UnknownHostException("www.youtube.com")

        val state = load()

        assertEquals(LiveNowState.Failed(AccessFailure.OFFLINE), state.liveNow)
        assertNull(state.failure)
    }

    @Test
    fun everyChannelFailingAloneIsNotAScreenError() = runTest(dispatcher) {
        subscribe(CHANNEL_A)
        feeds.failingChannels += CHANNEL_A
        extractor.live = ItemsPage(listOf(liveVideo("lllllllllll")), next = null)

        val state = load()

        assertNull(ready(state).hero)
        assertTrue(ready(state).feedsFailed)
        assertNull(state.failure)
    }

    @Test
    fun oneChannelFailingOfTwoIsNotFeedsFailed() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B)
        feeds.failingChannels += CHANNEL_B

        assertFalse(ready(load()).feedsFailed)
    }

    @Test
    fun everythingFailingOfflineIsTheNoConnectionError() = runTest(dispatcher) {
        subscribe(CHANNEL_A)
        feeds.failingChannels += CHANNEL_A
        extractor.liveFailure = UnknownHostException("www.youtube.com")

        assertEquals(AccessFailure.OFFLINE, load().failure)
    }

    @Test
    fun everythingFailingOnlineIsTheUnreadableError() = runTest(dispatcher) {
        subscribe(CHANNEL_A)
        feeds.failingChannels += CHANNEL_A
        extractor.liveFailure = IllegalStateException("unexpected response shape")

        assertEquals(AccessFailure.UNREADABLE, load().failure)
    }

    @Test
    fun noSubscriptionsAndLiveNowFailingIsAScreenError() = runTest(dispatcher) {
        extractor.liveFailure = UnknownHostException("www.youtube.com")

        assertEquals(AccessFailure.OFFLINE, load().failure)
    }

    @Test
    fun noErrorWhileTheSubscriptionsPartIsStillLoading() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.liveFailure = UnknownHostException("www.youtube.com")
        val gate = CompletableDeferred<Unit>()
        feeds.gateCall = 1
        feeds.gate = gate

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(SubscriptionsState.Loading, viewModel.uiState.value.subscriptions)
        assertNull(viewModel.uiState.value.failure)
        gate.complete(Unit)
    }

    @Test
    fun retryReloadsAndRestampsTheAttempt() = runTest(dispatcher) {
        extractor.liveFailure = UnknownHostException("www.youtube.com")
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(AccessFailure.OFFLINE, viewModel.uiState.value.failure)

        extractor.liveFailure = null
        clock.current = NOW + 5.minutes
        viewModel.refresh()
        assertEquals(LiveNowState.Loading, viewModel.uiState.value.liveNow)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failure)
        assertTrue(viewModel.uiState.value.liveNow is LiveNowState.Ready)
        assertEquals(NOW + 5.minutes, viewModel.uiState.value.attemptedAt)
    }

    @Test
    fun aSupersededLoadNeverLandsLate() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "first")
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate
        val viewModel = newViewModel()
        advanceUntilIdle() // the first load waits on the hero's video page

        extractor.videoGate = null
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "second")
        viewModel.refresh()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(HeroPage.Ready(description = "second", format = null), ready(viewModel.uiState.value).hero!!.page)
    }

    @Test
    fun aSubscribeAfterHomeLoadedReloadsTheRows() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z"))
        advanceUntilIdle()

        val rows = ready(viewModel.uiState.value)
        assertEquals("bbbbbbbbbb1", rows.hero!!.video.id.value)
        assertEquals(listOf("aaaaaaaaaa1"), rows.newFromSubscriptions.map { it.id.value })
        assertEquals(listOf(CHANNEL_B, CHANNEL_A), rows.channels.map { it.subscription.channelUrl })
    }

    @Test
    fun unsubscribingTheLastChannelAfterHomeLoadedIsTheBlankSlate() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        store.unsubscribe(CHANNEL_A)
        advanceUntilIdle()

        assertEquals(SubscriptionsState.NoSubscriptions, viewModel.uiState.value.subscriptions)
    }

    @Test
    fun aStoreChangeCancelsAReloadStillInFlight() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "a")
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate
        val viewModel = newViewModel()
        advanceUntilIdle() // the first load waits on A's video page

        extractor.videoGate = null
        extractor.pages["bbbbbbbbbb1"] = videoPage("bbbbbbbbbb1", description = "b")
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T11:00:00Z"))
        advanceUntilIdle()
        val reloaded = ready(viewModel.uiState.value).hero!! // landed with A's gate still closed
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("bbbbbbbbbb1", reloaded.video.id.value)
        assertEquals(HeroPage.Ready(description = "b", format = null), ready(viewModel.uiState.value).hero!!.page)
    }

    @Test
    fun theOldRowsStayWhileAReloadIsInFlight() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        feeds.gateCall = 2 // A answers from the cache, so B's feed is the second call
        feeds.gate = gate

        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z"))
        advanceUntilIdle()
        val during = ready(viewModel.uiState.value).hero!!
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("aaaaaaaaaa1", during.video.id.value)
        assertEquals("bbbbbbbbbb1", ready(viewModel.uiState.value).hero!!.video.id.value)
    }

    @Test
    fun aReloadKeepingTheHeroKeepsItsPageWithoutRefetching() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "blurb")
        val viewModel = newViewModel()
        advanceUntilIdle()
        extractor.pages.remove("aaaaaaaaaa1") // a second fetch would make the page Unavailable

        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-20T10:00:00Z"))
        advanceUntilIdle()

        val rows = ready(viewModel.uiState.value)
        assertEquals(HeroPage.Ready(description = "blurb", format = null), rows.hero!!.page)
        assertEquals(listOf("bbbbbbbbbb1"), rows.newFromSubscriptions.map { it.id.value })
    }

    @Test
    fun watchedVideosLeaveTheHeroAndNewFromSubscriptions() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-25T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))
        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"))

        val subscriptions = ready(load())

        assertEquals("bbbbbbbbbb1", subscriptions.hero!!.video.id.value)
        assertEquals(listOf("aaaaaaaaaa2"), subscriptions.newFromSubscriptions.map { it.id.value })
    }

    @Test
    fun aPartWatchedVideoStaysInTheRows() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        watch.entries.value = listOf(partWay("aaaaaaaaaa1", lastWatched = 1))

        val state = load()

        assertEquals("aaaaaaaaaa1", ready(state).hero!!.video.id.value)
        assertEquals(listOf("aaaaaaaaaa1"), state.continueWatching.map { it.video.id.value })
    }

    @Test
    fun aChannelWithEveryVideoWatchedLeavesTheRowsButKeepsItsChannelCard() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"), upload("bbbbbbbbbb2", "2026-06-20T10:00:00Z"))
        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"))

        val subscriptions = ready(load())

        assertEquals("bbbbbbbbbb1", subscriptions.hero!!.video.id.value)
        assertEquals(emptyList<String>(), subscriptions.newFromSubscriptions.map { it.id.value })
        assertEquals(
            Instant.parse("2026-06-27T10:00:00Z"),
            subscriptions.channels.first { it.subscription.channelUrl == CHANNEL_A }.latestUpload,
        )
    }

    @Test
    fun everythingWatchedWithLiveNowFailingIsNotAScreenError() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"))
        extractor.liveFailure = UnknownHostException("www.youtube.com")

        val state = load()

        assertNull(state.failure)
        assertNull(ready(state).hero)
        assertTrue(ready(state).hasUploads)
    }

    @Test
    fun aVideoWatchedAfterHomeLoadedRepicksTheRowsWithoutRefetching() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        val calls = feeds.calls
        clock.current = NOW + SubscriptionsFeed.CacheTtl + 1.minutes // a reload would refetch every feed

        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"))
        advanceUntilIdle()

        assertEquals("bbbbbbbbbb1", ready(viewModel.uiState.value).hero!!.video.id.value)
        assertEquals("no feed fetched again", calls, feeds.calls)
    }

    @Test
    fun aPositionSaveThatWatchesNothingNeverFetchesTheHerosPageAgain() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        // No page: the hero's fetch fails, and a re-pick would retry it.
        val viewModel = newViewModel()
        advanceUntilIdle()
        extractor.pages["aaaaaaaaaa1"] = videoPage("aaaaaaaaaa1", description = "blurb")

        watch.entries.value = listOf(partWay("zzzzzzzzzz1", lastWatched = 1))
        advanceUntilIdle()

        assertEquals(HeroPage.Unavailable, ready(viewModel.uiState.value).hero!!.page)
    }

    @Test
    fun aSavedHeroWatchedAwayHandsOverToAnUnsavedOne() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))
        playlists.addToWatchLater(upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.heroInWatchLater)

        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"))
        advanceUntilIdle()
        assertEquals("bbbbbbbbbb1", ready(viewModel.uiState.value).hero!!.video.id.value)
        assertFalse(viewModel.uiState.value.heroInWatchLater)

        viewModel.onHeroWatchLater()
        advanceUntilIdle()
        assertEquals(listOf("aaaaaaaaaa1", "bbbbbbbbbb1"), laterIds())
    }

    @Test
    fun anUnreadablePlaylistStoreShowsTheHeroUnsaved() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        playlists.failReads = true

        val state = load()

        assertEquals("aaaaaaaaaa1", ready(state).hero!!.video.id.value)
        assertFalse(state.heroInWatchLater)
    }

    @Test
    fun anUnreadableWatchStoreLeavesTheRowsUnfilteredAndNoContinueWatching() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        watch.entries.value = listOf(watchedEntry("aaaaaaaaaa1"), partWay("zzzzzzzzzz1", lastWatched = 1))
        watch.failReads = true

        val state = load()

        assertEquals("aaaaaaaaaa1", ready(state).hero!!.video.id.value)
        assertEquals(emptyList<Any>(), state.continueWatching)
    }

    @Test
    fun continueWatchingHoldsPartWayVideosMostRecentFirstCapped() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        // The store lists the most recently watched first; a watched video is never part-way.
        watch.entries.value = (14 downTo 1).map { partWay("p%010d".format(it), lastWatched = it.toLong()) } +
            watchedEntry("wwwwwwwwww1")

        val continueWatching = load().continueWatching

        assertEquals((14 downTo 3).map { "p%010d".format(it) }, continueWatching.map { it.video.id.value })
    }

    @Test
    fun theWatchStoreIsReadOffTheMainThread() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-26T10:00:00Z"))
        watch.entries.value = listOf(partWay("pppppppppp1", lastWatched = 1), watchedEntry("aaaaaaaaaa1"))
        val compute = StandardTestDispatcher(testScheduler, "compute")

        val viewModel = homeViewModel(extractor, SubscriptionsFeed(feeds, clock), store, watch, playlists, related, clock, compute = compute)
        advanceUntilIdle()

        // Continue watching and the watched set (For you ranks on onVisible).
        assertEquals(2, watch.readOn.size)
        assertEquals(setOf(compute), watch.readOn.toSet())
        val state = viewModel.uiState.value
        assertEquals(listOf("pppppppppp1"), state.continueWatching.map { it.video.id.value })
        assertEquals("aaaaaaaaaa2", ready(state).hero!!.video.id.value)
    }

    @Test
    fun continueWatchingStaysThroughRetry() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        watch.entries.value = listOf(partWay("pppppppppp1", lastWatched = 1))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.refresh()

        assertEquals(SubscriptionsState.Loading, viewModel.uiState.value.subscriptions)
        assertEquals(listOf("pppppppppp1"), viewModel.uiState.value.continueWatching.map { it.video.id.value })
    }

    @Test
    fun continueWatchingShowsWithNoSubscriptions() = runTest(dispatcher) {
        watch.entries.value = listOf(partWay("pppppppppp1", lastWatched = 1))

        val state = load()

        assertEquals(SubscriptionsState.NoSubscriptions, state.subscriptions)
        assertEquals(listOf("pppppppppp1"), state.continueWatching.map { it.video.id.value })
    }

    @Test
    fun heroWatchLaterSavesAndRemovesTheHerosVideo() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.heroInWatchLater)

        viewModel.onHeroWatchLater()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.heroInWatchLater)
        assertEquals(listOf("aaaaaaaaaa1"), laterIds())

        viewModel.onHeroWatchLater()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.heroInWatchLater)
        assertEquals(emptyList<String>(), laterIds())
    }

    @Test
    fun aHeroAlreadyInWatchLaterSaysSo() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        playlists.addToWatchLater(upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))

        assertTrue(load().heroInWatchLater)
    }

    @Test
    fun aFailedWatchLaterWriteChangesNothing() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        playlists.failWrites = true

        viewModel.onHeroWatchLater()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.heroInWatchLater)
    }

    @Test
    fun bestFormatIsTheTallestVideoStreamAtItsHighestFrameRate() {
        val streams = listOf(
            stream(1080, 30),
            stream(1080, 60),
            stream(720, 60),
            stream(2160, 60, StreamKind.AUDIO), // never a video format, whatever it states
        )

        assertEquals(VideoFormat(height = 1080, fps = 60), bestVideoFormat(streams))
    }

    private fun subscribe(url: String, vararg uploads: VideoItem) {
        feeds.feeds[url] = ItemsPage(uploads.toList(), next = null)
        store.rows.value = store.rows.value + testSubscription(url, name = url.substringAfterLast('/'))
    }

    /** KP-D11 (`kp-kids` kidhome): no hero, no Live now, and no video page asked for. */
    @Test
    fun aKidsHomeHasNoHeroAndAsksYouTubeForNothingButTheFeeds() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z"))
        extractor.live = ItemsPage(listOf(liveVideo("livelive001")), next = null)
        val picks = KidsPicks("Arshad", listOf(testVideo("pickpick001")))

        val state = load(kids = flowOf(picks))

        assertNull(ready(state).hero)
        assertEquals(listOf("bbbbbbbbbb1", "aaaaaaaaaa1"), ready(state).newFromSubscriptions.map { it.id.value })
        assertEquals(LiveNowState.Ready(emptyList()), state.liveNow)
        assertEquals(emptyList<String>(), extractor.asked)
        assertEquals(picks, state.kids)
    }

    /** FPK-D13: a kids Home's feed takes in the channels its packs approve, after its approved
     *  ones and without a second copy of one it approved itself. */
    @Test
    fun aKidsHomeListsThePackChannelsNewVideosToo() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-25T10:00:00Z"))
        feeds.feeds[CHANNEL_B] = ItemsPage(listOf(upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z")), next = null)
        val packs = listOf(testSubscription(CHANNEL_B, name = "Pack channel"), testSubscription(CHANNEL_A, name = "Approved too"))

        val state = load(kids = flowOf(KidsPicks("Arshad", emptyList(), packs)))

        assertEquals(listOf("bbbbbbbbbb1", "aaaaaaaaaa1"), ready(state).newFromSubscriptions.map { it.id.value })
        assertEquals(2, ready(state).channels.size)
    }

    /** FPK-D13: pack channels alone fill a kids Home that has no approved channel. */
    @Test
    fun aKidsHomeWithOnlyPackChannelsIsNotEmpty() = runTest(dispatcher) {
        feeds.feeds[CHANNEL_B] = ItemsPage(listOf(upload("bbbbbbbbbb1", "2026-06-27T09:00:00Z")), next = null)

        val state = load(kids = flowOf(KidsPicks("Arshad", emptyList(), listOf(testSubscription(CHANNEL_B, name = "Pack channel")))))

        assertEquals(listOf("bbbbbbbbbb1"), ready(state).newFromSubscriptions.map { it.id.value })
    }

    /** PAF-D5: a kids Home has no hero and no For you, so nothing to wait for. */
    @Test
    fun aKidsHomeHoldsNoHero() = runTest(dispatcher) {
        assertFalse(newViewModel(flowOf(KidsPicks.None)).uiState.value.heroHeld)
    }

    @Test
    fun anotherProfilesHomeHasNoKidsPart() = runTest(dispatcher) {
        assertNull(load().kids)
    }

    private fun TestScope.load(kids: Flow<KidsPicks>? = null): HomeUiState {
        val viewModel = newViewModel(kids)
        advanceUntilIdle()
        return viewModel.uiState.value
    }

    private fun ready(state: HomeUiState): SubscriptionsState.Ready = state.subscriptions as SubscriptionsState.Ready

    private suspend fun laterIds(): List<String> = playlists.watchLater().first().map { it.video.id.value }

    private fun newViewModel(kids: Flow<KidsPicks>? = null) =
        homeViewModel(extractor, SubscriptionsFeed(feeds, clock), store, watch, playlists, related, clock, kids = kids)

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-28T00:00:00Z")
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCb"
        const val CHANNEL_C = "https://www.youtube.com/channel/UCc"

        /** An RSS feed entry: exact instant, no duration. */
        fun upload(id: String, uploaded: String): VideoItem =
            testVideo(id).copy(uploadDate = Instant.parse(uploaded), durationSeconds = -1)

        fun liveVideo(id: String): VideoItem = testVideo(id).copy(isLive = true, durationSeconds = -1)

        fun watchedEntry(id: String) = watchEntry(id, durationMs = null, watched = true)

        /** Ten minutes into an hour: a resume point (M5-D7). */
        fun partWay(id: String, lastWatched: Long) =
            watchEntry(id, lastWatchedAtMillis = lastWatched, positionMs = 600_000, durationMs = 3_600_000)

        fun stream(height: Int?, fps: Int?, kind: StreamKind = StreamKind.VIDEO_ONLY) = StreamCandidate(
            url = "sabr://0", kind = kind, mimeType = null, itag = null, resolution = null, bitrate = null,
            height = height, fps = fps,
        )

        fun videoPage(
            id: String,
            durationSeconds: Long = 60,
            description: String? = null,
            streams: List<StreamCandidate> = emptyList(),
            relatedVideos: List<VideoItem> = emptyList(),
        ) = VideoMetadata(
            id = VideoId(id), title = "title $id", uploaderName = "uploader", durationSeconds = durationSeconds,
            description = description, relatedVideos = relatedVideos, hlsUrl = null, dashUrl = null, streams = streams,
        )
    }
}

/** A HomeViewModel on these seams; For you's own (its store, the profile's settings with For you
 *  on, the attempt log) are fakes unless passed. */
internal fun homeViewModel(
    extractor: Extractor,
    feed: SubscriptionsFeed,
    store: SubscriptionStore,
    watch: WatchStore,
    playlists: PlaylistStore,
    related: RelatedListStore,
    clock: Clock,
    forYouStore: ForYouStore = FakeForYouStore(),
    settings: ProfileSettingsStore = FakeProfileSettingsStore(selectedId = 1),
    attempts: FetchAttemptLog = FakeFetchAttemptLog(clock),
    kids: Flow<KidsPicks>? = null,
    compute: CoroutineDispatcher = Dispatchers.Main,
): HomeViewModel {
    val budget = FetchBudget(attempts, clock)
    return HomeViewModel(
        extractor = extractor,
        feed = feed,
        subscriptionStore = store,
        watchStore = watch,
        playlistStore = playlists,
        relatedLists = related,
        forYouBuilder = ForYouBuilder(watch, playlists, store, related, forYouStore, settings, clock, ZoneOffset.UTC, compute = compute),
        forYouStore = forYouStore,
        budget = budget,
        refresher = ForYouRefresher(extractor, related, settings, budget, clock),
        kidsPicks = kids,
        clock = clock,
        compute = compute,
    )
}

/**
 * Home's own calls: the Live kiosk, video pages by id (a missing page fails; every id asked is
 * kept in [asked]), with a gate for in-flight pins, and For you's related lists by id. Feeds come
 * from [FakeFeedExtractor] through [SubscriptionsFeed].
 */
internal class FakeHomeExtractor : UnsupportedExtractor("home-only (feeds come from FakeFeedExtractor)") {
    var live: ItemsPage = ItemsPage(items = emptyList(), next = null)

    /** When set, the Live kiosk fails with this as the transport/parse cause. */
    var liveFailure: Throwable? = null
    val pages = mutableMapOf<String, VideoMetadata>()
    val asked = mutableListOf<String>()

    /** Ids whose video() the profile's filter refuses, as the filtering seam does. */
    val filtered = mutableSetOf<String>()

    /** When set, video() suspends on it before answering with the page it had when called
     *  (a request in flight answers with the data of its time). */
    var videoGate: CompletableDeferred<Unit>? = null

    override suspend fun video(id: VideoId): VideoMetadata {
        asked += id.value
        val page = pages[id.value]
        videoGate?.await()
        if (id.value in filtered) throw VideoFiltered()
        return page ?: throw AccessException("video down", IllegalStateException())
    }

    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage {
        check(kiosk == BrowseKiosk.LIVE) { "Home browses Live only" }
        liveFailure?.let { throw AccessException("kiosk down", it) }
        return live
    }

    /** Answers by id (a list, or a failure such as an [AccessException]); every id asked is kept
     *  in [relatedAsked]. An id without an answer throws unsupported before For you's budget
     *  records anything, and Home's top-up logs and drops it: assert [relatedAsked] to prove no
     *  fetch. */
    val relatedAnswers = mutableMapOf<String, Result<List<VideoItem>>>()
    val relatedAsked = mutableListOf<String>()

    /** When set, related() suspends on it before answering. */
    var relatedGate: CompletableDeferred<Unit>? = null

    override suspend fun related(videoId: VideoId): List<VideoItem> {
        relatedAsked += videoId.value
        val answer = relatedAnswers[videoId.value] ?: return super.related(videoId)
        relatedGate?.await()
        return answer.getOrThrow()
    }
}
