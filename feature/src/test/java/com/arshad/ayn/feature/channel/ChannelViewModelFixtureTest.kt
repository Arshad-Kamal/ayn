package com.arshad.ayn.feature.channel

import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHANNEL_PAGE_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_TAB_FLOWS
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.newpipe.RecordedShortsTab
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.FakeChannelExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-layer: the recorded channel page (header + tabs), its recorded tab pages and RSS feed,
 * mapped by the real seam, fill the channel page. Each flow is its own replay session, so the fake serves
 * the pages (the flows would share the vendored extractor's process-global transport).
 */
class ChannelViewModelFixtureTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeProfileSettingsStore(selectedId = 1L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordedChannelOpensOnItsUploadsWithShortsHidden() = runTest(dispatcher) {
        val viewModel = ChannelViewModel(RECORDED_CHANNEL_URL, recordedExtractor(), FakeSubscriptionStore(), settings)
        advanceUntilIdle()

        val page = viewModel.uiState.value.pageState as ChannelPageState.Ready
        assertEquals(RecordedChannel.PAGE_TABS - ChannelTab.SHORTS, page.tabs)
        val videos = page.selectedItems.orEmpty().filterIsInstance<VideoItem>()
        assertEquals(RecordedChannel.UPLOADS_TAB_VIDEOS, videos.size)
        assertEquals(RecordedChannel.UPLOADS_TAB_FIRST_IDS, videos.take(RecordedChannel.UPLOADS_TAB_FIRST_IDS.size).map { it.id.value })
        assertEquals(RecordedChannel.UPLOADS_TAB_FIRST_DURATION_SECONDS, videos.first().durationSeconds)
    }

    @Test
    fun recordedShortsTabListsItsShortsWhileShowShortsIsOn() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        val extractor = recordedExtractor().apply {
            tabPages[ChannelTab.SHORTS] = recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }
        }
        val viewModel = ChannelViewModel(RECORDED_CHANNEL_URL, extractor, FakeSubscriptionStore(), settings)
        advanceUntilIdle()

        viewModel.selectTab(ChannelTab.SHORTS)
        advanceUntilIdle()

        val page = viewModel.uiState.value.pageState as ChannelPageState.Ready
        assertEquals(RecordedChannel.PAGE_TABS, page.tabs)
        val shorts = page.selectedItems.orEmpty().filterIsInstance<VideoItem>()
        assertEquals(RecordedShortsTab.SHORTS, shorts.size)
        assertEquals(RecordedShortsTab.FIRST_ID, shorts.first().id.value)
        assertEquals(RecordedShortsTab.FIRST_PICTURE, shorts.first().thumbnailUrl)
        // The recorded tab continues: a Shorts run opened here pages on.
        assertTrue(viewModel.openShorts().hasMore)
    }

    @Test
    fun recordedPlaylistsTabFillsThePageInTabOrder() = runTest(dispatcher) {
        val viewModel = ChannelViewModel(RECORDED_CHANNEL_URL, recordedExtractor(), FakeSubscriptionStore(), settings)
        advanceUntilIdle()

        viewModel.selectTab(ChannelTab.PLAYLISTS)
        advanceUntilIdle()

        val playlists = (viewModel.uiState.value.pageState as ChannelPageState.Ready).selectedItems.orEmpty()
            .filterIsInstance<PlaylistItem>()
        assertEquals(RecordedChannel.PLAYLISTS_TAB_COUNT, playlists.size)
        assertEquals(
            RecordedChannel.PLAYLISTS_TAB_FIRST.map { it.first },
            playlists.take(RecordedChannel.PLAYLISTS_TAB_FIRST.size).map { it.url.substringAfter("list=") },
        )
    }

    @Test
    fun recordedFeedFillsATablessChannelsVideos() = runTest(dispatcher) {
        val extractor = recordedExtractor().apply {
            channels[RECORDED_CHANNEL_URL] = channels.getValue(RECORDED_CHANNEL_URL).copy(tabs = emptyList())
            tabPages[ChannelTab.VIDEOS] = ItemsPage(items = emptyList(), next = null)
            feed = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
        }
        val viewModel = ChannelViewModel(RECORDED_CHANNEL_URL, extractor, FakeSubscriptionStore(), settings)
        advanceUntilIdle()

        val videos = (viewModel.uiState.value.pageState as ChannelPageState.Ready).selectedItems.orEmpty()
            .filterIsInstance<VideoItem>()
        assertEquals(RecordedChannel.FEED_ENTRIES.map { it.first }, videos.map { it.id.value })
    }

    private fun recordedExtractor(): FakeChannelExtractor = FakeChannelExtractor().apply {
        channels[RECORDED_CHANNEL_URL] = runBlocking {
            NewPipeAccess(downloader = FixtureDownloader(CHANNEL_PAGE_FLOW)).channel(RECORDED_CHANNEL_URL)
        }
        tabPages[ChannelTab.VIDEOS] = recorded("channel-videos") { channelTab(RECORDED_CHANNEL_URL, ChannelTab.VIDEOS) }
        tabPages[ChannelTab.PLAYLISTS] = recorded(CHANNEL_TAB_FLOWS.getValue(ChannelTab.PLAYLISTS)) {
            channelTab(RECORDED_CHANNEL_URL, ChannelTab.PLAYLISTS)
        }
    }
}
