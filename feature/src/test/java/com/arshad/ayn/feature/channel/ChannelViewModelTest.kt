package com.arshad.ayn.feature.channel

import com.arshad.ayn.core.access.AccessFailure.OFFLINE
import com.arshad.ayn.core.access.AccessFailure.UNREADABLE
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ChannelTab.LIVE
import com.arshad.ayn.core.access.ChannelTab.PLAYLISTS
import com.arshad.ayn.core.access.ChannelTab.SHORTS
import com.arshad.ayn.core.access.ChannelTab.VIDEOS
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.feature.FakeChannelExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.testChannel
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * ChannelViewModel tests against fakes (KT-TEST-01, ARCH-4): page load and failure
 * (either half fails the page, D8 screen-level), tabs (Shorts by Show Shorts and Turn on Shorts, select-and-keep, Retry
 * returning to the chosen tab, a tab-less channel's feed fallback), the subscribe toggle driving the real store seam, and the
 * superseded-refresh guards. Deterministic via injected Main dispatcher (KT-CONC-09).
 */
class ChannelViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakeChannelExtractor()
    private val store = FakeSubscriptionStore()
    private val settings = FakeProfileSettingsStore(selectedId = 1L)
    private val url = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun videoPage(vararg videoIds: String): ItemsPage =
        ItemsPage(items = videoIds.map { testVideo(it) }, next = null)

    private fun testPlaylist(id: String): PlaylistItem = PlaylistItem(
        title = "playlist $id",
        url = "https://www.youtube.com/playlist?list=$id",
        thumbnailUrl = null,
        videoCount = 3,
        uploaderName = null,
    )

    private fun playlistPage(id: String): ItemsPage = ItemsPage(items = listOf(testPlaylist(id)), next = null)

    private fun channelViewModel(navUrl: String = url, settingsStore: ProfileSettingsStore = settings) =
        ChannelViewModel(navUrl, extractor, store, settingsStore)

    private fun ChannelViewModel.ready(): ChannelPageState.Ready = uiState.value.pageState as ChannelPageState.Ready

    private fun ChannelPageState.Ready.selectedVideoIds(): List<String>? =
        selectedItems?.map { (it as VideoItem).id.value }

    private fun askedTabs(): List<ChannelTab> = extractor.tabCalls.map { it.second }

    @Test
    fun initialLoadReadiesHeaderAndTheFirstTab() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, name = "Bench & Solder", tabs = listOf(VIDEOS, PLAYLISTS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa", "bbbbbbbbbbb")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals("Bench & Solder", page.channel.name)
        assertEquals(listOf(VIDEOS, PLAYLISTS), page.tabs)
        assertEquals(VIDEOS, page.selectedTab)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), page.selectedVideoIds())
        assertFalse(page.shortsOnly)
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun theShortsTabStaysHiddenWhileShowShortsIsOff() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, SHORTS, LIVE))

        val viewModel = channelViewModel()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(listOf(VIDEOS, LIVE), page.tabs)
        assertFalse(page.shortsOnly)
        viewModel.selectTab(SHORTS)
        advanceUntilIdle()
        assertEquals(VIDEOS, viewModel.ready().selectedTab)
        assertFalse(SHORTS in askedTabs())
    }

    @Test
    fun aShortsOnlyChannelIsFlaggedWithNoTabs() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))

        val viewModel = channelViewModel()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(emptyList<ChannelTab>(), page.tabs)
        assertEquals(null, page.selectedTab)
        assertEquals(emptyList<Any>(), page.selectedItems)
        assertTrue(page.shortsOnly)
    }

    private fun showShorts(on: Boolean) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = on))
    }

    private val shortsToken = PageToken(id = "shorts-2")

    private fun shortsPage(vararg ids: String, next: PageToken? = null): ItemsPage =
        ItemsPage(items = emptyList(), next = next, shorts = ids.map { testVideo(it) })

    @Test
    fun theShortsTabShowsWhileShowShortsIsOnAndListsThePagesShorts() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, SHORTS, LIVE))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01", "sssssssss02")
        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS, SHORTS, LIVE), viewModel.ready().tabs)
        viewModel.selectTab(SHORTS)
        advanceUntilIdle()

        assertEquals(listOf("sssssssss01", "sssssssss02"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aShortsOnlyChannelOpensOnItsShortsWhileShowShortsIsOn() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01")
        val viewModel = channelViewModel()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(SHORTS, page.selectedTab)
        assertFalse(page.shortsOnly)
        assertEquals(listOf("sssssssss01"), page.selectedVideoIds())
    }

    @Test
    fun turnOnShortsWritesTheSettingForTheShownProfileAndOpensTheShortsTab() = runTest(dispatcher) {
        settings.selected.value = 7L
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01")
        val viewModel = channelViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.ready().shortsOnly)

        viewModel.turnOnShorts()
        advanceUntilIdle()

        assertEquals(listOf(7L), settings.updatedIds)
        assertTrue(settings.of(7L).showShorts)
        val page = viewModel.ready()
        assertEquals(SHORTS, page.selectedTab)
        assertEquals(listOf("sssssssss01"), page.selectedVideoIds())
    }

    @Test
    fun aFailedTurnOnShortsLeavesTheStatement() = runTest(dispatcher) {
        settings.failWrites = true
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.turnOnShorts()
        advanceUntilIdle()

        assertTrue(viewModel.ready().shortsOnly)
        assertFalse(SHORTS in askedTabs())
    }

    @Test
    fun turningShowShortsOffOnTheShortsTabMovesToTheFirstShownTab() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, SHORTS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01")
        val viewModel = channelViewModel()
        advanceUntilIdle()
        viewModel.selectTab(SHORTS)
        advanceUntilIdle()

        showShorts(false)
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(listOf(VIDEOS), page.tabs)
        assertEquals(VIDEOS, page.selectedTab)
        assertEquals(listOf("aaaaaaaaaaa"), page.selectedVideoIds())
    }

    @Test
    fun aPageTheShortsRunFetchesShowsInTheShortsTab() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01", next = shortsToken)
        extractor.laterPages[shortsToken] = shortsPage("sssssssss02")
        val viewModel = channelViewModel()
        advanceUntilIdle()

        val run = viewModel.openShorts()
        assertTrue(run.hasMore)
        assertTrue(run.loadMore())
        advanceUntilIdle()

        assertEquals(listOf("sssssssss01", "sssssssss02"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun backReturnsToTheLastShortTheRunPlayedOnce() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01", next = shortsToken)
        extractor.laterPages[shortsToken] = shortsPage("sssssssss02")
        val viewModel = channelViewModel()
        advanceUntilIdle()

        val run = viewModel.openShorts()
        run.loadMore()
        run.played(run.shorts.value.last().id)

        assertEquals("sssssssss02", viewModel.shortsReturn()?.value)
        viewModel.shortsReturned()
        assertNull(viewModel.shortsReturn())
    }

    @Test
    fun aShortsRunsFailedPageEndsPagingWithTheTabKeepingItsShorts() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.tabPages[SHORTS] = shortsPage("sssssssss01", next = shortsToken)
        extractor.failingPages += shortsToken
        val viewModel = channelViewModel()
        advanceUntilIdle()

        val run = viewModel.openShorts()
        assertFalse(run.loadMore())
        advanceUntilIdle()

        assertFalse(run.hasMore)
        assertEquals(listOf("sssssssss01"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun unreadableSettingsHideTheShortsTabAndTurnOnShortsWritesNothing() = runTest(dispatcher) {
        var writes = 0
        val unreadable = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = flow { throw IOException("disk") }
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean {
                writes++
                return false
            }
        }
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, SHORTS))
        val viewModel = channelViewModel(settingsStore = unreadable)
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS), viewModel.ready().tabs)
        viewModel.turnOnShorts()
        advanceUntilIdle()
        assertEquals(0, writes)
        assertFalse(SHORTS in askedTabs())
    }

    @Test
    fun settingsReadWhileThePageLoadsDecideItsTabsOnceItLands() = runTest(dispatcher) {
        settings.selected.value = null // not yet read
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, SHORTS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")
        val tabGate = CompletableDeferred<Unit>()
        extractor.gatedTab = VIDEOS
        extractor.tabGate = tabGate
        val viewModel = channelViewModel()
        advanceUntilIdle() // the header is in, Videos held: the page is built with Shorts off

        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        settings.selected.value = 1L
        advanceUntilIdle()
        tabGate.complete(Unit)
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(listOf(VIDEOS, SHORTS), page.tabs)
        assertEquals(VIDEOS, page.selectedTab)
    }

    @Test
    fun aShortsOnlyPageWaitsOnUnreadSettingsAsShortsOff() = runTest(dispatcher) {
        settings.selected.value = null
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.ready().shortsOnly)
        assertFalse(SHORTS in askedTabs())
    }

    @Test
    fun aFailedShortsTabFailsThePageWhileSelected() = runTest(dispatcher) {
        showShorts(true)
        extractor.channels[url] = testChannel(url = url, tabs = listOf(SHORTS))
        extractor.failingTabs += SHORTS
        extractor.failure = OFFLINE
        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(OFFLINE), viewModel.uiState.value.pageState)
    }

    @Test
    fun aChannelWithoutVideosOpensOnItsFirstTabDespiteTheFailedGuess() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(PLAYLISTS))
        extractor.failingTabs += VIDEOS
        extractor.tabPages[PLAYLISTS] = playlistPage("PLa")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(PLAYLISTS, page.selectedTab)
        assertEquals(listOf(testPlaylist("PLa")), page.selectedItems)
    }

    @Test
    fun aSelectedTabLoadsOnceAndIsKeptForRevisits() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, PLAYLISTS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")
        extractor.tabPages[PLAYLISTS] = playlistPage("PLa")
        extractor.gatedTab = PLAYLISTS
        extractor.tabGate = CompletableDeferred()
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()
        assertEquals(null, viewModel.ready().selectedItems)
        extractor.tabGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(testPlaylist("PLa")), viewModel.ready().selectedItems)

        viewModel.selectTab(VIDEOS)
        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS, PLAYLISTS), askedTabs())
        assertEquals(listOf(testPlaylist("PLa")), viewModel.ready().selectedItems)
    }

    @Test
    fun anAnswerForATabMovedPastIsKeptWithoutTakingTheSelection() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, LIVE, PLAYLISTS))
        extractor.tabPages[LIVE] = videoPage("lllllllllll")
        extractor.tabPages[PLAYLISTS] = playlistPage("PLa")
        extractor.gatedTab = PLAYLISTS
        extractor.tabGate = CompletableDeferred()
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle() // the playlists ask is in flight, held at its gate
        viewModel.selectTab(LIVE)
        advanceUntilIdle()
        extractor.tabGate!!.complete(Unit)
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(LIVE, page.selectedTab)
        assertEquals(listOf("lllllllllll"), page.selectedVideoIds())
        assertEquals(listOf(testPlaylist("PLa")), page.tabItems[PLAYLISTS])
    }

    @Test
    fun aFailureOfATabMovedPastLeavesThePageReady() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, LIVE, PLAYLISTS))
        extractor.tabPages[LIVE] = videoPage("lllllllllll")
        extractor.failingTabs += PLAYLISTS
        extractor.gatedTab = PLAYLISTS
        extractor.tabGate = CompletableDeferred()
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()
        viewModel.selectTab(LIVE)
        advanceUntilIdle()
        extractor.tabGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(LIVE, viewModel.ready().selectedTab)
    }

    @Test
    fun aFirstTabOtherThanTheGuessFailingFailsThePage() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(PLAYLISTS))
        extractor.failingTabs += PLAYLISTS

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)
    }

    @Test
    fun retryFallsBackToTheFirstTabWhenTheChosenOneIsGone() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, LIVE))
        extractor.failingTabs += LIVE
        val viewModel = channelViewModel()
        advanceUntilIdle()
        viewModel.selectTab(LIVE)
        advanceUntilIdle()
        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)

        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")
        viewModel.load()
        advanceUntilIdle()

        assertEquals(VIDEOS, viewModel.ready().selectedTab)
        assertEquals(listOf("aaaaaaaaaaa"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aHeaderWithoutParsedTabsOpensOnVideos() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = emptyList())
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")
        extractor.failingFeed = true // a Videos with items never asks the feed

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS), viewModel.ready().tabs)
        assertEquals(listOf("aaaaaaaaaaa"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aChannelWithoutTabsAndAnEmptyVideosListsItsFeed() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = emptyList())
        extractor.feed = videoPage("aaaaaaaaaaa", "aaaaaaaaaaa", "bbbbbbbbbbb")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS), viewModel.ready().tabs)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aChannelWithTabsKeepsAnEmptyVideosEmpty() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, PLAYLISTS))
        extractor.feed = videoPage("aaaaaaaaaaa")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aFailedFeedLeavesATablessChannelsVideosEmpty() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = emptyList())
        extractor.failingFeed = true

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun theFeedIsAskedByTheHeadersCanonicalUrl() = runTest(dispatcher) {
        val canonical = "https://www.youtube.com/channel/UCcanonical0000000000000"
        val alias = "https://www.youtube.com/@bench"
        extractor.channels[alias] = testChannel(url = canonical, tabs = emptyList())

        channelViewModel(alias)
        advanceUntilIdle()

        assertEquals(listOf(canonical), extractor.feedCalls)
    }

    @Test
    fun aRepeatedItemInATabShowsOnce() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS))
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa", "aaaaaaaaaaa", "bbbbbbbbbbb")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), viewModel.ready().selectedVideoIds())
    }

    @Test
    fun aTabFailureFailsThePageAndRetryReturnsToThatTab() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, PLAYLISTS))
        extractor.failingTabs += PLAYLISTS
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()
        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)

        extractor.failingTabs.clear()
        extractor.tabPages[PLAYLISTS] = playlistPage("PLa")
        viewModel.load()
        advanceUntilIdle()

        val page = viewModel.ready()
        assertEquals(PLAYLISTS, page.selectedTab)
        assertEquals(listOf(testPlaylist("PLa")), page.selectedItems)
        assertEquals(listOf(VIDEOS, PLAYLISTS, PLAYLISTS), askedTabs()) // Retry guessed it
    }

    @Test
    fun passingBackOverATabStillLoadingAsksForItOnce() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, LIVE, PLAYLISTS))
        extractor.tabPages[PLAYLISTS] = playlistPage("PLa")
        val viewModel = channelViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        extractor.gatedTab = PLAYLISTS
        extractor.tabGate = gate

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()
        viewModel.selectTab(VIDEOS)
        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(VIDEOS, PLAYLISTS), askedTabs())
        assertEquals(listOf(testPlaylist("PLa")), viewModel.ready().selectedItems)
    }

    @Test
    fun toggleSubscribeWritesAndRemovesTheStoreRow() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, name = "Bench & Solder")
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertEquals(listOf(url), store.rows.value.map { it.channelUrl })
        assertEquals("Bench & Solder", store.rows.value.single().name)
        assertTrue(viewModel.uiState.value.isSubscribed) // the store flow drives the button state

        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun doubleToggleBeforeTheStoreEmitsNetsOneToggle() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url)
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.toggleSubscribe()
        viewModel.toggleSubscribe() // second press lands before the store flow emits
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun failedStoreWriteRevertsThePillState() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url)
        val viewModel = channelViewModel()
        advanceUntilIdle()
        store.failWrites = true

        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
        assertFalse(viewModel.uiState.value.isSubscribed) // reverted; the store stayed truth
    }

    @Test
    fun doubleFailedToggleSettlesOnStoreTruth() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url)
        val viewModel = channelViewModel()
        advanceUntilIdle()
        store.failWrites = true

        viewModel.toggleSubscribe()
        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
        // The second press captures the first's optimistic flip; reverting each press to
        // the store's truth (not the captured value) keeps the pill honest under both.
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun aliasNavUrlTogglesUnderTheCanonicalStoreKey() = runTest(dispatcher) {
        val canonical = "https://www.youtube.com/channel/UCcanonical0000000000000"
        val alias = "https://www.youtube.com/@bench"
        extractor.channels[alias] = testChannel(url = canonical, name = "Bench & Solder")
        val viewModel = channelViewModel(alias)
        advanceUntilIdle()

        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertEquals(listOf(alias), extractor.tabCalls.map { it.first }.distinct()) // tabs ask by the nav URL
        assertEquals(listOf(canonical), store.rows.value.map { it.channelUrl }) // the repository's PK
        assertTrue(viewModel.uiState.value.isSubscribed) // the re-keyed observer sees the write

        viewModel.toggleSubscribe()
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun toggleIsIgnoredBeforeThePageIsReady() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url)
        val viewModel = channelViewModel()

        viewModel.toggleSubscribe() // pageState is still Loading here
        advanceUntilIdle()

        assertTrue(store.rows.value.isEmpty())
    }

    @Test
    fun subscribedStateComesFromTheStoreNotThePage() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url)
        store.rows.value = listOf(testSubscription(channelUrl = url, name = "stored earlier"))

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun headerFailureFailsThePage() = runTest(dispatcher) {
        extractor.failingHeaderUrls.add(url)
        extractor.tabPages[VIDEOS] = videoPage("aaaaaaaaaaa")

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)
    }

    @Test
    fun anOfflineHeaderFailsThePageAsOffline() = runTest(dispatcher) {
        extractor.failingHeaderUrls.add(url)
        extractor.failure = OFFLINE

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(OFFLINE), viewModel.uiState.value.pageState)
    }

    @Test
    fun anOfflineGuessedTabFailsThePageAsOffline() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS))
        extractor.failingTabs += VIDEOS
        extractor.failure = OFFLINE

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(OFFLINE), viewModel.uiState.value.pageState)
    }

    @Test
    fun anOfflineSelectedTabFailsThePageAsOffline() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS, PLAYLISTS))
        extractor.failingTabs += PLAYLISTS
        extractor.failure = OFFLINE
        val viewModel = channelViewModel()
        advanceUntilIdle()

        viewModel.selectTab(PLAYLISTS)
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(OFFLINE), viewModel.uiState.value.pageState)
    }

    @Test
    fun firstTabFailureFailsThePage() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, tabs = listOf(VIDEOS))
        extractor.failingTabs += VIDEOS

        val viewModel = channelViewModel()
        advanceUntilIdle()

        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)
    }

    @Test
    fun retryRecoversFromTheFailedState() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, name = "Bench & Solder")
        extractor.failingHeaderUrls.add(url)
        val viewModel = channelViewModel()
        advanceUntilIdle()
        assertEquals(ChannelPageState.Failed(UNREADABLE), viewModel.uiState.value.pageState)

        extractor.failingHeaderUrls.clear()
        viewModel.load()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageState is ChannelPageState.Ready)
    }

    @Test
    fun supersededLoadDoesNotOverwriteTheNewerState() = runTest(dispatcher) {
        extractor.channels[url] = testChannel(url = url, name = "Bench & Solder")
        extractor.firstHeaderGate = CompletableDeferred()
        val viewModel = channelViewModel()
        advanceUntilIdle() // first load parks at the gate

        viewModel.load() // retry; its header call is ungated
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pageState is ChannelPageState.Ready)

        // Release the parked load as a failure — it must not overwrite the newer Ready.
        extractor.failingHeaderUrls.add(url)
        extractor.firstHeaderGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageState is ChannelPageState.Ready)
    }
}
