package com.arshad.ayn.feature.playlist

import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.newpipe.ALBUM_PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_TAB_FLOWS
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedAlbumPlaylist
import com.arshad.ayn.core.access.newpipe.RecordedPlaylist
import com.arshad.ayn.feature.nav.PlaylistRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Cross-layer: the recorded channel's Playlists card opens the recorded playlist, mapped by the
 * real seam; its Releases card opens an album (square art from the first frame) through the
 * route the shell navigates with; and the recorded long playlist continues through
 * [PlaylistViewModel.loadMore]. The
 * seam runs on the test dispatcher, so virtual time covers its work (KT-CONC-09).
 */
class PlaylistViewModelFixtureTest {

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
    fun theChannelsPlaylistCardOpensThePlaylistInOrder() = runTest(dispatcher) {
        val card = recorded(CHANNEL_TAB_FLOWS.getValue(ChannelTab.PLAYLISTS)) {
            channelTab(RECORDED_CHANNEL_URL, ChannelTab.PLAYLISTS)
        }.items.filterIsInstance<PlaylistItem>().single { it.url == PLAYLIST_URL }

        val viewModel = PlaylistViewModel(card, NewPipeAccess(downloader = FixtureDownloader(PLAYLIST_FLOW), io = dispatcher))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RecordedPlaylist.TITLE, state.details.title)
        assertEquals(RecordedPlaylist.UPLOADER_AVATAR_URL, state.details.uploaderAvatarUrl)
        val videos = (state.videos as PlaylistVideosState.Ready).videos
        assertEquals(RecordedPlaylist.VIDEO_COUNT.toInt(), videos.size)
        assertEquals(RecordedPlaylist.FIRST_IDS, videos.take(3).map { it.id.value })
        assertEquals(RecordedPlaylist.LAST_ID, videos.last().id.value)
    }

    @Test
    fun theChannelsReleasesCardOpensAnAlbumThroughItsRoute() = runTest(dispatcher) {
        val card = recorded(CHANNEL_TAB_FLOWS.getValue(ChannelTab.RELEASES)) {
            channelTab(RECORDED_CHANNEL_URL, ChannelTab.RELEASES)
        }.items.filterIsInstance<PlaylistItem>().first()
        assertEquals("the route carries the whole card", card, PlaylistRoute(card).card())

        val viewModel = PlaylistViewModel(
            PlaylistRoute(card).card(),
            NewPipeAccess(downloader = FixtureDownloader(ALBUM_PLAYLIST_FLOW), io = dispatcher),
        )
        val first = viewModel.uiState.value
        advanceUntilIdle()

        assertEquals("square art from the first frame", true, first.isAlbum)
        assertEquals(RecordedAlbumPlaylist.TITLE, first.details.title)
        val state = viewModel.uiState.value
        assertEquals(true, state.isAlbum)
        assertEquals(null, state.details.uploaderName)
        assertEquals(RecordedAlbumPlaylist.IDS, (state.videos as PlaylistVideosState.Ready).videos.map { it.id.value })
    }

    @Test
    fun aChannelPlaylistIsNotAnAlbum() = runTest(dispatcher) {
        val card = recorded(CHANNEL_TAB_FLOWS.getValue(ChannelTab.PLAYLISTS)) {
            channelTab(RECORDED_CHANNEL_URL, ChannelTab.PLAYLISTS)
        }.items.filterIsInstance<PlaylistItem>().single { it.url == PLAYLIST_URL }

        val viewModel = PlaylistViewModel(card, NewPipeAccess(downloader = FixtureDownloader(PLAYLIST_FLOW), io = dispatcher))
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isAlbum)
    }

    @Test
    fun aLongPlaylistAppendsItsSecondPage() = runTest(dispatcher) {
        val opening = PlaylistItem(
            title = "Uploads from Rick Astley",
            url = PAGED_PLAYLIST_URL,
            thumbnailUrl = null,
            videoCount = RecordedPlaylist.PAGED_VIDEO_COUNT,
            uploaderName = RecordedPlaylist.UPLOADER_NAME,
        )
        val viewModel = PlaylistViewModel(opening, NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher))
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.videos as PlaylistVideosState.Ready
        assertEquals(2 * RecordedPlaylist.PAGE_SIZE, ready.videos.size)
        assertEquals(RecordedPlaylist.PAGE_2_FIRST_ID, ready.videos[RecordedPlaylist.PAGE_SIZE].id.value)
        assertEquals(true, ready.hasMore)
    }
}
