package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.testVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/** The Library hub over the watch and playlist stores: its rows, the empty case, a failed read, naming a new playlist. */
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val compute = StandardTestDispatcher(dispatcher.scheduler, "compute")
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ready(viewModel: LibraryViewModel): LibraryState.Ready = viewModel.uiState.value.library as LibraryState.Ready

    @Test
    fun theHubShowsRecentHistoryEveryPlaylistAndWatchLater() = runTest(dispatcher) {
        watch.entries.value = (0 until 14).map { watchEntry("video${it}x".padEnd(11, 'x'), lastWatchedAtMillis = 100L - it) } +
            watchEntry("untitledxxx", lastWatchedAtMillis = 1, title = null)
        val later = testVideo("lllllllllll")
        playlists.addToWatchLater(later)
        val older = playlists.seed("Weekend cooking", testVideo("ccccccccccc"))
        val newer = playlists.seed("Engineering deep dives")

        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        val hub = ready(viewModel)
        assertEquals(LibraryViewModel.HUB_HISTORY_CARDS, hub.recent.size)
        assertEquals("video0xxxxx", hub.recent.first().video.id.value)
        assertEquals(14, hub.historyCount)
        assertEquals(listOf(newer, older), hub.playlists.map { it.id })
        assertEquals(listOf(later.id), hub.watchLater.map { it.id })
        assertFalse(hub.isEmpty)
    }

    @Test
    fun theStoresAreReadOffTheMainThread() = runTest(dispatcher) {
        LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        assertEquals(listOf(compute), watch.readOn)
    }

    @Test
    fun watchLaterCardsCarryTheirWatchMarks() = runTest(dispatcher) {
        val later = testVideo("lllllllllll")
        playlists.addToWatchLater(later)
        watch.entries.value = listOf(watchEntry(later.id.value, positionMs = 120_000))

        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        assertEquals(WatchMark.PartWay(120_000, 600_000), ready(viewModel).marks[later.id])
    }

    @Test
    fun nothingWatchedOrSavedIsTheEmptyLibrary() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        assertTrue(ready(viewModel).isEmpty)
    }

    @Test
    fun aRemovedHistoryVideoLeavesTheHub() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("aaaaaaaaaaa"), watchEntry("bbbbbbbbbbb"))
        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        watch.remove(VideoId("aaaaaaaaaaa"))
        advanceUntilIdle()

        assertEquals(listOf("bbbbbbbbbbb"), ready(viewModel).recent.map { it.video.id.value })
        assertEquals(1, ready(viewModel).historyCount)
    }

    @Test
    fun aFailedReadSaysSoAndTryAgainReadsAgain() = runTest(dispatcher) {
        watch.failReads = true
        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()
        assertEquals(LibraryState.Failed, viewModel.uiState.value.library)

        watch.failReads = false
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.library is LibraryState.Ready)
    }

    @Test
    fun naming_aNewPlaylistIsCreatedAndHandedToTheScreen() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(watch, playlists, compute)
        advanceUntilIdle()

        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Weekend projects")
        viewModel.onNamingDone()
        advanceUntilIdle()

        val created = viewModel.uiState.value.createdPlaylistId
        assertNull(viewModel.uiState.value.naming)
        assertEquals(listOf("Weekend projects"), playlists.playlists().first().map { it.name })
        assertEquals(playlists.playlists().first().single().id, created)

        viewModel.onPlaylistOpened()
        assertNull(viewModel.uiState.value.createdPlaylistId)
    }

    @Test
    fun naming_doneWithABlankNameDoesNothing() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(watch, playlists, compute)
        viewModel.onNewPlaylist()
        viewModel.onNamingChange("   ")

        viewModel.onNamingDone()
        advanceUntilIdle()

        assertEquals(PlaylistNaming("   "), viewModel.uiState.value.naming)
        assertEquals(emptyList<Any>(), playlists.playlists().first())
    }

    @Test
    fun naming_aFailedCreateKeepsTheNameForAnotherTry() = runTest(dispatcher) {
        playlists.failWrites = true
        val viewModel = LibraryViewModel(watch, playlists, compute)
        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Weekend projects")

        viewModel.onNamingDone()
        advanceUntilIdle()

        assertEquals(PlaylistNaming("Weekend projects"), viewModel.uiState.value.naming)
        assertNull(viewModel.uiState.value.createdPlaylistId)
    }

    @Test
    fun naming_cancelCloses() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(watch, playlists, compute)
        viewModel.onNewPlaylist()

        viewModel.onNamingCancel()

        assertNull(viewModel.uiState.value.naming)
    }
}
