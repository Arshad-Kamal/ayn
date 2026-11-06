package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.FakeChannelFeedStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.StoredChannelFeed
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.subscriptions.FeedLanding
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Home over the kept feed (PERF P9c): its rows show first, and the fresh feed lands by
 * [FeedLanding]'s rule (at once with no key pressed; held over a pressed key until an arrival,
 * never on BACK; past a day while browsing, but never under a focused hero); Retry skips the kept
 * feed; an offline fresh load keeps the kept rows.
 */
class HomeViewModelKeptTest {
    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakeHomeExtractor()
    private val feeds = FakeFeedExtractor()
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
    fun theKeptRowsShowFirstAndTheFreshOnesLandWithNoKeyPressed() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        assertEquals(KEPT, heroId(viewModel))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(FRESH, heroId(viewModel))
    }

    @Test
    fun aKeyPressedHoldsTheFreshRowsUntilAnArrivalButNotABackReturn() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        viewModel.onKeyPressed()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KEPT, heroId(viewModel))

        viewModel.onVisible(arrived = false)
        advanceUntilIdle()
        assertEquals("BACK keeps the card it returns to", KEPT, heroId(viewModel))

        viewModel.onVisible(arrived = true)
        advanceUntilIdle()
        assertEquals(FRESH, heroId(viewModel))
    }

    @Test
    fun aStaleKeptFeedLandsWhileBrowsingOnceFocusLeavesTheHero() = runTest(dispatcher) {
        val viewModel = keptThenFresh(age = FeedLanding.StaleAfter + 1.hours)
        viewModel.onTopFocus(true)
        viewModel.onKeyPressed()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("the focused hero never swaps", KEPT, heroId(viewModel))

        viewModel.onTopFocus(false)
        advanceUntilIdle()
        assertEquals(FRESH, heroId(viewModel))
    }

    @Test
    fun retryFetchesAfreshWithoutTheKeptRows() = runTest(dispatcher) {
        val viewModel = keptThenFresh()
        viewModel.onKeyPressed()
        gate.complete(Unit)
        advanceUntilIdle()
        feeds.feeds[CHANNEL_A] = page(upload("rrrrrrrrrrr"))
        clock.current += 1.hours

        viewModel.refresh()
        assertEquals(SubscriptionsState.Loading, viewModel.uiState.value.subscriptions)
        advanceUntilIdle()

        assertEquals("rrrrrrrrrrr", heroId(viewModel))
    }

    @Test
    fun anOfflineFreshLoadKeepsTheKeptRows() = runTest(dispatcher) {
        store.rows.value = listOf(testSubscription(CHANNEL_A))
        kept.keep(CHANNEL_A, StoredChannelFeed(listOf(upload(KEPT)), emptyList(), clock.current - 1.hours))
        feeds.failingChannels += CHANNEL_A
        feeds.failureCauses[CHANNEL_A] = UnknownHostException()

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(KEPT, heroId(viewModel))
        assertFalse(ready(viewModel).feedsFailed)
    }

    /** CHANNEL_A subscribed with [KEPT] kept ([age] old) and [FRESH] behind [gate]; returns with
     *  the kept rows shown. */
    private fun TestScope.keptThenFresh(age: Duration = 1.hours): HomeViewModel {
        store.rows.value = listOf(testSubscription(CHANNEL_A))
        kept.keep(CHANNEL_A, StoredChannelFeed(listOf(upload(KEPT)), emptyList(), clock.current - age))
        feeds.feeds[CHANNEL_A] = page(upload(FRESH, "2026-06-27T12:00:00Z"))
        feeds.gateCall = 1
        feeds.gate = gate
        return newViewModel().also { advanceUntilIdle() }
    }

    private fun TestScope.newViewModel(): HomeViewModel = homeViewModel(
        extractor,
        SubscriptionsFeed(feeds, clock, channelFeeds = kept, writeScope = backgroundScope),
        store,
        FakeWatchStore(),
        FakePlaylistStore(),
        FakeRelatedListStore(),
        clock,
    )

    private fun ready(viewModel: HomeViewModel) = viewModel.uiState.value.subscriptions as SubscriptionsState.Ready

    private fun heroId(viewModel: HomeViewModel): String? = ready(viewModel).hero?.video?.id?.value

    private fun page(vararg items: VideoItem) = ItemsPage(items.toList(), next = null)

    private fun upload(id: String, uploaded: String = "2026-06-20T00:00:00Z"): VideoItem =
        testVideo(id).copy(uploadDate = Instant.parse(uploaded), durationSeconds = -1)

    private companion object {
        const val KEPT = "kkkkkkkkkkk"
        const val FRESH = "fffffffffff"
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
    }
}
