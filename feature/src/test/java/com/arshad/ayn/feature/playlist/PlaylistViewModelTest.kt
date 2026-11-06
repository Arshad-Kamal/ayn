package com.arshad.ayn.feature.playlist

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
 * PlaylistViewModel against a fake seam (KT-TEST-01, ARCH-4): the seeded header, the loaded page,
 * failures by kind, Retry, appending pages, and superseded loads. Deterministic via the injected
 * Main dispatcher (KT-CONC-09).
 */
class PlaylistViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakePlaylistExtractor()
    private val opening = PlaylistItem(
        title = "Biology",
        url = "https://www.youtube.com/playlist?list=PLbio",
        thumbnailUrl = "https://i.ytimg.com/vi/cover/hqdefault.jpg",
        videoCount = 22,
        uploaderName = "Veritasium",
    )
    /** YouTube's answer differs from the card in every field, so seed and loaded never pass for each other. */
    private val details = PlaylistDetails(
        title = "Biology (2026)",
        uploaderName = "Veritasium Science",
        uploaderUrl = "https://www.youtube.com/channel/UCveritasium",
        uploaderAvatarUrl = "https://yt3.ggpht.com/veritasium",
        videoCount = 23,
        thumbnailUrl = "https://i.ytimg.com/vi/newcover/hqdefault.jpg",
    )
    private val page2 = PageToken(id = "2")
    private val page3 = PageToken(id = "3")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun page(vararg ids: String, next: PageToken? = null): ItemsPage =
        ItemsPage(items = ids.map { testVideo(it) }, next = next)

    private fun PlaylistViewModel.videoIds(): List<String> =
        (uiState.value.videos as PlaylistVideosState.Ready).videos.map { it.id.value }

    @Test
    fun whileVideosLoadTheHeaderComesFromTheOpeningCard() = runTest(dispatcher) {
        extractor.gate = CompletableDeferred()
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa"))

        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()

        val seeded = PlaylistDetails(
            title = "Biology",
            uploaderName = "Veritasium",
            uploaderUrl = null,
            uploaderAvatarUrl = null,
            videoCount = 22,
            thumbnailUrl = "https://i.ytimg.com/vi/cover/hqdefault.jpg",
        )
        assertEquals(PlaylistUiState(details = seeded, videos = PlaylistVideosState.Loading), viewModel.uiState.value)
    }

    @Test
    fun aLoadedPlaylistShowsYouTubesDetailsAndItsVideosInOrderWithRepeats() = runTest(dispatcher) {
        extractor.first = PlaylistPage(details, page("ccccccccccc", "aaaaaaaaaaa", "ccccccccccc", "bbbbbbbbbbb"))

        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()

        assertEquals(details, viewModel.uiState.value.details)
        assertEquals(listOf("ccccccccccc", "aaaaaaaaaaa", "ccccccccccc", "bbbbbbbbbbb"), viewModel.videoIds())
        assertEquals(false, (viewModel.uiState.value.videos as PlaylistVideosState.Ready).hasMore)
    }

    @Test
    fun aFailedLoadKeepsTheSeededHeaderAndNamesTheFailure() = runTest(dispatcher) {
        extractor.failure = AccessFailure.UNAVAILABLE

        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()

        assertEquals(PlaylistVideosState.Failed(AccessFailure.UNAVAILABLE), viewModel.uiState.value.videos)
        assertEquals("Biology", viewModel.uiState.value.details.title)
    }

    @Test
    fun retryAfterAFailureLoadsThePlaylist() = runTest(dispatcher) {
        extractor.failure = AccessFailure.OFFLINE
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()
        extractor.failure = null
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa"))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa"), viewModel.videoIds())
    }

    @Test
    fun loadMoreAppendsPagesUntilThePlaylistEnds() = runTest(dispatcher) {
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa", next = page2))
        extractor.pages[page2] = page("bbbbbbbbbbb", next = page3)
        extractor.pages[page3] = page("ccccccccccc")
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), viewModel.videoIds())
        assertEquals(false, (viewModel.uiState.value.videos as PlaylistVideosState.Ready).hasMore)
        assertEquals("no ask past the last page", listOf(page2, page3), extractor.pageCalls)
    }

    @Test
    fun aFailedNextPageKeepsTheListAndIsAskedForAgain() = runTest(dispatcher) {
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa", next = page2))
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()
        extractor.failingPages += page2

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("aaaaaaaaaaa"), viewModel.videoIds())
        assertEquals(true, (viewModel.uiState.value.videos as PlaylistVideosState.Ready).hasMore)

        extractor.failingPages.clear()
        extractor.pages[page2] = page("bbbbbbbbbbb")
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), viewModel.videoIds())
    }

    @Test
    fun loadMoreWhileAPageIsInFlightAsksOnce() = runTest(dispatcher) {
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa", next = page2))
        extractor.pages[page2] = page("bbbbbbbbbbb")
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()
        extractor.gate = CompletableDeferred()

        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        extractor.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(page2), extractor.pageCalls)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), viewModel.videoIds())
    }

    @Test
    fun retryCancelsAPageInFlightSoItNeverLandsOnTheNewList() = runTest(dispatcher) {
        extractor.first = PlaylistPage(details, page("aaaaaaaaaaa", next = page2))
        extractor.pages[page2] = page("stalestales")
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()
        val stale = CompletableDeferred<Unit>()
        extractor.gate = stale
        viewModel.loadMore()
        advanceUntilIdle()

        extractor.gate = null
        extractor.first = PlaylistPage(details, page("ddddddddddd"))
        viewModel.refresh()
        advanceUntilIdle()
        stale.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("ddddddddddd"), viewModel.videoIds())
    }

    @Test
    fun retryCancelsAFirstLoadInFlightSoItNeverLands() = runTest(dispatcher) {
        val stale = CompletableDeferred<Unit>()
        extractor.gate = stale
        extractor.first = PlaylistPage(details, page("stalestales"))
        val viewModel = PlaylistViewModel(opening, extractor)
        advanceUntilIdle()

        extractor.gate = null
        extractor.first = PlaylistPage(details, page("ddddddddddd"))
        viewModel.refresh()
        advanceUntilIdle()
        stale.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("ddddddddddd"), viewModel.videoIds())
    }
}

/** Playlist-only seam fake: one first page or a failure, later pages by token, a gate for
 *  in-flight pins (a call answers with the data of its time), and the page tokens asked for. */
private class FakePlaylistExtractor : UnsupportedExtractor("playlist-only") {
    var first: PlaylistPage? = null
    var failure: AccessFailure? = null
    val pages = mutableMapOf<PageToken, ItemsPage>()
    val failingPages = mutableSetOf<PageToken>()
    val pageCalls = mutableListOf<PageToken>()

    /** When set, every call suspends on it before answering. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun playlist(playlistUrl: String): PlaylistPage {
        val answer = first
        val failed = failure
        gate?.await()
        failed?.let { throw AccessException("playlist down", IOException(), it) }
        return answer!!
    }

    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage {
        pageCalls += page
        val answer = pages[page]
        val failed = page in failingPages
        gate?.await()
        if (failed) throw AccessException("page down", IOException())
        return answer!!
    }
}
