package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.FakeChannelFeedStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.StoredChannelFeed
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Subscriptions over the kept feed (PERF P9c): the kept feed shows first, and the fresh one lands
 * by [FeedLanding]'s rule (at once with no key pressed; held over a pressed key until an arrival,
 * never on BACK; while browsing past a day); Retry and a subscription change skip the kept feed;
 * a fresh load that fails a channel, or every channel, keeps what the kept feed shows.
 */
class SubscriptionsViewModelKeptTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeProfileSettingsStore(selectedId = 1L)
    private val extractor = FakeFeedExtractor()
    private val store = FakeSubscriptionStore()
    private val kept = FakeChannelFeedStore()
    private val clock = TestClock(Instant.parse("2026-06-28T00:00:00Z"))
    private val gate = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theKeptFeedShowsFirstAndTheFreshOneLandsWithNoKeyPressed() = runTest(dispatcher) {
        val viewModel = keptThenFresh()

        assertEquals(listOf(KEPT), shownIds(viewModel))
        clock.current += 1.hours
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(FRESH), shownIds(viewModel))
        assertEquals("ages from when the fresh feed loaded", clock.current, ready(viewModel).loadedAt)
    }

    @Test
    fun aKeyPressedHoldsTheFreshFeedUntilAnArrivalButNotABackReturn() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        viewModel.onKeyPressed()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(KEPT), shownIds(viewModel))

        viewModel.onVisible(arrived = false)
        assertEquals("BACK keeps the card it returns to", listOf(KEPT), shownIds(viewModel))

        viewModel.onVisible(arrived = true)
        assertEquals(listOf(FRESH), shownIds(viewModel))
    }

    @Test
    fun aKeptFeedOlderThanADayLetsTheFreshOneLandWhileBrowsing() = runTest(dispatcher) {
        val viewModel = keptThenFresh(age = FeedLanding.StaleAfter + 1.hours)
        viewModel.onKeyPressed()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(FRESH), shownIds(viewModel))
    }

    @Test
    fun retryFetchesAfreshAndLandsAtOnce() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        viewModel.onKeyPressed()
        gate.complete(Unit)
        advanceUntilIdle()
        extractor.feeds[CHANNEL_A] = page(upload("rrrrrrrrrrr"))
        clock.current += 1.hours // past the cache

        viewModel.refresh()
        assertEquals(FeedState.Loading, viewModel.uiState.value.feedState)
        advanceUntilIdle()

        assertEquals(listOf("rrrrrrrrrrr"), shownIds(viewModel))
    }

    @Test
    fun aSubscriptionChangeReloadsAndLandsAtOnce() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        viewModel.onKeyPressed()
        gate.complete(Unit)
        advanceUntilIdle()

        store.rows.value = store.rows.value + testSubscription(CHANNEL_B)
        extractor.feeds[CHANNEL_B] = page(upload("bbbbbbbbbbb", "2026-06-27T18:00:00Z"))
        advanceUntilIdle()

        assertEquals(listOf("bbbbbbbbbbb", FRESH), shownIds(viewModel))
    }

    @Test
    fun anOfflineFreshLoadKeepsShowingTheKeptFeed() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A))
        kept.keep(CHANNEL_A, StoredChannelFeed(listOf(upload(KEPT)), emptyList(), clock.current - 1.hours))
        extractor.failingChannels += CHANNEL_A
        extractor.failureCauses[CHANNEL_A] = UnknownHostException()

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf(KEPT), shownIds(viewModel))
        assertEquals("its videos aren't missing", emptySet<String>(), ready(viewModel).failedChannels)
    }

    @Test
    fun aPartlyFailedFreshLoadKeepsTheFailedChannelsKeptVideos() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B), testSubscription(CHANNEL_C))
        kept.keep(CHANNEL_A, StoredChannelFeed(listOf(upload(KEPT)), emptyList(), clock.current - 1.hours))
        kept.keep(CHANNEL_B, StoredChannelFeed(listOf(upload("bbbbbbbbbb0")), emptyList(), clock.current - 1.hours))
        extractor.feeds[CHANNEL_B] = page(upload("bbbbbbbbbb1", "2026-06-27T12:00:00Z"))
        extractor.failingChannels += setOf(CHANNEL_A, CHANNEL_C)

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("bbbbbbbbbb1", KEPT), shownIds(viewModel))
        assertEquals("only a channel with nothing to show is marked", setOf(CHANNEL_C), ready(viewModel).failedChannels)
    }

    @Test
    fun everyChannelFailingWithNothingKeptIsStillFailed() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A))
        extractor.failingChannels += CHANNEL_A
        extractor.failureCauses[CHANNEL_A] = UnknownHostException()

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(FeedState.Failed(AccessFailure.OFFLINE), viewModel.uiState.value.feedState)
    }

    /** CHANNEL_A subscribed with [KEPT] kept ([age] old) and [FRESH] behind [gate]; returns with
     *  the kept feed shown. */
    private fun TestScope.keptThenFresh(age: Duration = 1.hours): SubscriptionsViewModel {
        store.rows.value = listOf(testSubscription(CHANNEL_A))
        kept.keep(CHANNEL_A, StoredChannelFeed(listOf(upload(KEPT)), emptyList(), clock.current - age))
        extractor.feeds[CHANNEL_A] = page(upload(FRESH, "2026-06-27T12:00:00Z"))
        extractor.gateCall = 1
        extractor.gate = gate
        return newViewModel().also { advanceUntilIdle() }
    }

    private fun TestScope.newViewModel() =
        SubscriptionsViewModel(SubscriptionsFeed(extractor, clock, channelFeeds = kept, writeScope = backgroundScope), store, settings)

    private fun ready(viewModel: SubscriptionsViewModel) = viewModel.uiState.value.feedState as FeedState.Ready

    private fun shownIds(viewModel: SubscriptionsViewModel): List<String> = ready(viewModel).entries.map { it.id.value }

    private fun page(vararg items: VideoItem) = ItemsPage(items.toList(), next = null)

    private fun upload(id: String, uploaded: String = "2026-06-20T00:00:00Z"): VideoItem = testVideo(id).copy(uploadDate = Instant.parse(uploaded))

    private companion object {
        const val KEPT = "kkkkkkkkkkk"
        const val FRESH = "fffffffffff"
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCb"
        const val CHANNEL_C = "https://www.youtube.com/channel/UCc"
    }
}
