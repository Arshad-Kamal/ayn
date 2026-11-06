package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.net.UnknownHostException
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * SubscriptionsViewModel tests (KT-TEST-01, KT-CONC-09): empty store → no subscriptions,
 * subscribed → merged Ready with New ids, the channel filter narrows client-side, the strip's
 * order and failed channels, all-channels-failed → Failed with its kind, Retry reloads, a
 * superseded refresh never lands, and the feed following the subscription store after it loaded.
 */
class SubscriptionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeProfileSettingsStore(selectedId = 1L)
    private val extractor = FakeFeedExtractor()
    private val store = FakeSubscriptionStore()
    private val clock = TestClock(Instant.parse("2026-06-28T00:00:00Z"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyStoreSurfacesTheNoSubscriptionsState() = runTest(dispatcher) {
        val viewModel = newViewModel()

        advanceUntilIdle()

        assertEquals(FeedState.NoSubscriptions, viewModel.uiState.value.feedState)
    }

    @Test
    fun subscribedChannelsMergeIntoReadyWithNewIds() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(name = "Bench"))
        extractor.feeds[store.rows.value.first().channelUrl] = ItemsPage(
            items = listOf(
                testVideo("aaaaaaaaaaa").copy(uploadDate = Instant.parse("2026-06-26T00:00:00Z"), uploaderName = "Bench"),
                testVideo("bbbbbbbbbbb").copy(uploadDate = Instant.parse("2026-06-07T00:00:00Z"), uploaderName = "Bench"),
            ),
            next = null,
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.feedState as FeedState.Ready
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ready.entries.map { it.id.value })
        assertEquals(setOf("aaaaaaaaaaa"), ready.newIds) // 3 weeks is outside the band
        assertEquals(clock.current, ready.loadedAt)
    }

    @Test
    fun theChannelFilterNarrowsToThatChannelsFeed() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(name = "Bench"))
        extractor.feeds[store.rows.value.first().channelUrl] = ItemsPage(
            items = listOf(
                testVideo("aaaaaaaaaaa").copy(uploaderName = "Bench"),
                testVideo("bbbbbbbbbbb").copy(uploaderName = "Elsewhere"),
            ),
            next = null,
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectFilter(store.rows.value.first().channelUrl)

        // Narrowed by the feed it came from, not by the uploader name a video carries.
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), viewModel.uiState.value.visibleEntries().map { it.id.value })
        viewModel.selectFilter("https://www.youtube.com/channel/UCother")
        assertEquals(emptyList<String>(), viewModel.uiState.value.visibleEntries().map { it.id.value })
        viewModel.selectFilter(null)
        assertEquals(2, viewModel.uiState.value.visibleEntries().size)
    }

    private val channelA = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
    private val channelB = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"

    /** Channel A: a video and a Short; channel B: a newer Short only (RSS without videos). */
    private fun twoChannelsWithShorts() {
        store.rows.value = listOf(testSubscription(channelA, "A"), testSubscription(channelB, "B"))
        extractor.feeds[channelA] = ItemsPage(
            items = listOf(testVideo("aaaaaaaaaaa")),
            next = null,
            shorts = listOf(testVideo("sssssssssa1").copy(uploadDate = Instant.parse("2026-06-20T00:00:00Z"))),
        )
        extractor.feeds[channelB] = ItemsPage(
            items = emptyList(),
            next = null,
            shorts = listOf(testVideo("sssssssssb1").copy(uploadDate = Instant.parse("2026-06-25T00:00:00Z"))),
        )
    }

    @Test
    fun theShelfHoldsEveryChannelsShortsNewestFirstWhileShowShortsIsOn() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        twoChannelsWithShorts()
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("sssssssssb1", "sssssssssa1"), viewModel.uiState.value.visibleShorts().map { it.id.value })

        viewModel.selectFilter(channelA)

        assertEquals(listOf("sssssssssa1"), viewModel.uiState.value.visibleShorts().map { it.id.value })
        assertEquals(listOf("sssssssssa1"), viewModel.openShorts().shorts.value.map { it.id.value })
    }

    @Test
    fun backReturnsToTheLastShortPlayedFromTheHandedShelfOnce() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        twoChannelsWithShorts()
        val viewModel = newViewModel()
        advanceUntilIdle()
        val shelf = viewModel.openShorts()

        shelf.played(shelf.shorts.value.last().id)

        assertEquals("sssssssssa1", viewModel.shortsReturn()?.value)
        viewModel.shortsReturned()
        assertNull(viewModel.shortsReturn())
    }

    @Test
    fun withShowShortsOffTheShelfIsEmptyAndTurningItOnShowsItWithoutRefetching() = runTest(dispatcher) {
        twoChannelsWithShorts()
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<VideoItem>(), viewModel.uiState.value.visibleShorts())
        assertEquals(listOf("aaaaaaaaaaa"), viewModel.uiState.value.visibleEntries().map { it.id.value })

        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        advanceUntilIdle()

        assertEquals(listOf("sssssssssb1", "sssssssssa1"), viewModel.uiState.value.visibleShorts().map { it.id.value })
        assertEquals(2, extractor.calls)
    }

    @Test
    fun everyChannelFailingSurfacesTheFailedState() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription())
        extractor.failingChannels += store.rows.value.first().channelUrl
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(FeedState.Failed(AccessFailure.UNREADABLE), viewModel.uiState.value.feedState)
    }

    @Test
    fun everyChannelFailingWithOneOfflineIsTheOfflineFailure() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B))
        extractor.failingChannels += listOf(CHANNEL_A, CHANNEL_B)
        extractor.failureCauses[CHANNEL_B] = UnknownHostException("www.youtube.com")
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(FeedState.Failed(AccessFailure.OFFLINE), viewModel.uiState.value.feedState)
    }

    @Test
    fun theSubscriptionsArePublishedWhileTheFeedLoads() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B))
        extractor.gateCall = 1
        extractor.gate = CompletableDeferred()
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(FeedState.Loading, viewModel.uiState.value.feedState)
        assertEquals(2, viewModel.uiState.value.subscriptions.size)
    }

    @Test
    fun aReadyFeedOrdersChannelsByLatestUploadAndNamesTheFailedOnes() = runTest(dispatcher) {
        val channelC = "https://www.youtube.com/channel/UCc"
        store.rows.value = listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B), testSubscription(channelC))
        extractor.feeds[CHANNEL_A] = ItemsPage(listOf(testVideo("aaaaaaaaaaa").copy(uploadDate = Instant.parse("2026-06-20T00:00:00Z"))), next = null)
        extractor.failingChannels += CHANNEL_B
        extractor.feeds[channelC] = ItemsPage(listOf(testVideo("ccccccccccc").copy(uploadDate = Instant.parse("2026-06-27T00:00:00Z"))), next = null)
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf(channelC, CHANNEL_A, CHANNEL_B), viewModel.uiState.value.subscriptions.map { it.channelUrl })
        assertEquals(setOf(CHANNEL_B), (viewModel.uiState.value.feedState as FeedState.Ready).failedChannels)
    }

    @Test
    fun retryReloadsAfterRecovery() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription())
        val url = store.rows.value.first().channelUrl
        extractor.failingChannels += url
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(FeedState.Failed(AccessFailure.UNREADABLE), viewModel.uiState.value.feedState)

        extractor.failingChannels.clear()
        extractor.feeds[url] = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        viewModel.refresh()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.feedState as FeedState.Ready
        assertEquals(1, ready.entries.size)
    }

    @Test
    fun supersededRefreshNeverLands() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription())
        val stale = testVideo("aaaaaaaaaaa")
        val fresh = testVideo("bbbbbbbbbbb")
        extractor.sequencePages = mutableListOf(
            ItemsPage(items = listOf(stale), next = null), // call 1 (refresh #1): stale
            ItemsPage(items = listOf(fresh), next = null), // call 2 (refresh #2): fresh
        )
        val gate = CompletableDeferred<Unit>()
        extractor.gateCall = 1
        extractor.gate = gate

        val viewModel = newViewModel()
        advanceUntilIdle() // refresh #1 is held inside the fake's gate

        viewModel.refresh() // cancels #1 before it landed
        advanceUntilIdle()
        assertEquals("bbbbbbbbbbb", (viewModel.uiState.value.feedState as FeedState.Ready).entries.first().id.value)

        gate.complete(Unit) // release the stale fetch; its cancelled load must not land
        advanceUntilIdle()
        assertEquals(
            "bbbbbbbbbbb",
            (viewModel.uiState.value.feedState as FeedState.Ready).entries.first().id.value,
        )
    }

    @Test
    fun aSubscribeAfterTheFeedLoadedReloadsIt() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        subscribe(CHANNEL_B, upload("bbbbbbbbbbb", "2026-06-27T00:00:00Z"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), (state.feedState as FeedState.Ready).entries.map { it.id.value })
        assertEquals(listOf(CHANNEL_B, CHANNEL_A), state.subscriptions.map { it.channelUrl })
    }

    @Test
    fun aFirstSubscribeAfterTheBlankSlateLoadsTheFeed() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("aaaaaaaaaaa"), (state.feedState as FeedState.Ready).entries.map { it.id.value })
        assertEquals(listOf(CHANNEL_A), state.subscriptions.map { it.channelUrl })
    }

    @Test
    fun unsubscribingTheLastChannelAfterTheFeedLoadedIsTheBlankSlate() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectFilter(CHANNEL_A)

        store.unsubscribe(CHANNEL_A)
        advanceUntilIdle()

        assertEquals(FeedState.NoSubscriptions, viewModel.uiState.value.feedState)
        assertEquals(emptyList<String>(), viewModel.uiState.value.subscriptions.map { it.channelUrl })
        assertNull(viewModel.uiState.value.filter)
    }

    @Test
    fun theShownFeedAndStripStayWhileAReloadIsInFlight() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        extractor.gateCall = 2 // A answers from the cache, so B's feed is the second call
        extractor.gate = gate

        subscribe(CHANNEL_B, upload("bbbbbbbbbbb", "2026-06-27T00:00:00Z"))
        advanceUntilIdle()
        val during = viewModel.uiState.value
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa"), (during.feedState as FeedState.Ready).entries.map { it.id.value })
        assertEquals(listOf(CHANNEL_A), during.subscriptions.map { it.channelUrl })
        assertEquals(listOf(CHANNEL_B, CHANNEL_A), viewModel.uiState.value.subscriptions.map { it.channelUrl })
    }

    @Test
    fun aStoreChangeCancelsAReloadStillInFlight() = runTest(dispatcher) {
        val channelC = "https://www.youtube.com/channel/UCc"
        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        extractor.gateCall = 2 // the reload for B waits on B's feed
        extractor.gate = gate
        subscribe(CHANNEL_B, upload("bbbbbbbbbbb", "2026-06-27T00:00:00Z"))
        advanceUntilIdle()

        subscribe(channelC, upload("ccccccccccc", "2026-06-26T00:00:00Z"))
        advanceUntilIdle()
        val reloaded = viewModel.uiState.value // landed with the gate still closed
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(CHANNEL_B, channelC, CHANNEL_A), reloaded.subscriptions.map { it.channelUrl })
        assertEquals(listOf(CHANNEL_B, channelC, CHANNEL_A), viewModel.uiState.value.subscriptions.map { it.channelUrl })
    }

    @Test
    fun unsubscribingTheFilteredChannelDropsTheFilter() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaaa", "2026-06-20T00:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbbb", "2026-06-27T00:00:00Z"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectFilter(CHANNEL_B)

        store.unsubscribe(CHANNEL_B)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.filter)
        assertEquals(listOf("aaaaaaaaaaa"), viewModel.uiState.value.visibleEntries().map { it.id.value })
    }

    private fun subscribe(url: String, vararg uploads: VideoItem) {
        extractor.feeds[url] = ItemsPage(uploads.toList(), next = null)
        store.rows.value = store.rows.value + testSubscription(url)
    }

    private fun upload(id: String, uploaded: String): VideoItem = testVideo(id).copy(uploadDate = Instant.parse(uploaded))

    private fun newViewModel() = SubscriptionsViewModel(SubscriptionsFeed(extractor, clock), store, settings)

    private companion object {
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCb"
    }
}
