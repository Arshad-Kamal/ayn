package com.arshad.ayn.feature.library

import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.playOrder
import com.arshad.ayn.feature.testVideo
import kotlin.random.Random
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

/** A saved playlist's page: its videos and marks, playing it, the ⋯ sheet's rename and delete. */
class SavedPlaylistViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val compute = StandardTestDispatcher(dispatcher.scheduler, "compute")
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val videos = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"), testVideo("ccccccccccc"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun page(id: Long, random: Random = Random(7)): SavedPlaylistViewModel = SavedPlaylistViewModel(id, playlists, watch, random, compute)

    private fun ready(viewModel: SavedPlaylistViewModel): SavedPlaylistState.Ready = viewModel.uiState.value.playlist as SavedPlaylistState.Ready

    @Test
    fun thePageListsTheVideosInSavedOrderWithTheirMarks() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        watch.entries.value = listOf(watchEntry("bbbbbbbbbbb", positionMs = 600_000, watched = true))

        val viewModel = page(id)
        advanceUntilIdle()

        val ready = ready(viewModel)
        assertEquals("Engineering deep dives", ready.summary.name)
        assertEquals(videos.map { it.id }, ready.videos.map { it.id })
        assertEquals(mapOf(videos[1].id to WatchMark.Watched), ready.marks)
    }

    @Test
    fun theStoresAreReadOffTheMainThread() = runTest(dispatcher) {
        page(playlists.seed("Engineering deep dives", *videos.toTypedArray()))
        advanceUntilIdle()

        assertEquals(listOf(compute), watch.readOn)
    }

    @Test
    fun aPlaylistThatIsntThereIsGone() = runTest(dispatcher) {
        val viewModel = page(42)
        advanceUntilIdle()

        assertEquals(SavedPlaylistState.Gone, viewModel.uiState.value.playlist)
    }

    @Test
    fun playAllStartsAtTheFirstVideoInOrder() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        val viewModel = page(id)
        advanceUntilIdle()

        val route = viewModel.playAll()!!

        assertEquals(videos[0].id.value, route.videoId)
        assertEquals(PlayAll.Saved(id, "Engineering deep dives", 3, shuffleSeed = null), route.playAll())
    }

    @Test
    fun shuffleOpensOnTheFirstVideoOfAFreshDeal() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        val viewModel = page(id, Random(7))
        advanceUntilIdle()
        val seed = Random(7).nextLong()

        val route = viewModel.shuffle()!!

        assertEquals(seed, route.shuffleSeed)
        assertEquals(playOrder(ready(viewModel).videos, seed).first().id.value, route.videoId)
        assertTrue("each Shuffle deals anew", viewModel.shuffle()!!.shuffleSeed != seed)
    }

    @Test
    fun anEmptyPlaylistHasNothingToPlay() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()

        assertNull(viewModel.playAll())
        assertNull(viewModel.shuffle())
    }

    @Test
    fun aRowPlaysOnThroughThePlaylistFromItself() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        val viewModel = page(id)
        advanceUntilIdle()

        val route = viewModel.playFrom(ready(viewModel).videos[1], startOver = true)!!

        assertEquals(videos[1].id.value, route.videoId)
        assertEquals(id, (route.playAll() as PlayAll.Saved).playlistId)
        assertTrue(route.startOver)
    }

    @Test
    fun renamingSavesTheNameAndReturnsToTheSheet() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        val viewModel = page(id)
        advanceUntilIdle()

        viewModel.onOptions()
        viewModel.onRename()
        assertEquals(PlaylistSheet.Renaming(PlaylistNaming("Engineering deep dives")), viewModel.uiState.value.sheet)
        viewModel.onRenameChange("Code breakers")
        viewModel.onRenameDone()
        advanceUntilIdle()

        assertEquals(PlaylistSheet.Options, viewModel.uiState.value.sheet)
        assertEquals("Code breakers", ready(viewModel).summary.name)
    }

    @Test
    fun anUnchangedNameReturnsToTheSheetWithoutAWrite() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()
        val updated = ready(viewModel).summary.updatedAtMillis
        viewModel.onOptions()
        viewModel.onRename()

        viewModel.onRenameChange("Engineering deep dives ")
        viewModel.onRenameDone()
        advanceUntilIdle()

        assertEquals(PlaylistSheet.Options, viewModel.uiState.value.sheet)
        assertEquals(updated, ready(viewModel).summary.updatedAtMillis)
    }

    @Test
    fun aFailedRenameKeepsTheNameForAnotherTry() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()
        viewModel.onOptions()
        viewModel.onRename()
        playlists.failWrites = true

        viewModel.onRenameChange("Code breakers")
        viewModel.onRenameDone()
        advanceUntilIdle()

        assertEquals(PlaylistSheet.Renaming(PlaylistNaming("Code breakers")), viewModel.uiState.value.sheet)
        assertEquals("Engineering deep dives", ready(viewModel).summary.name)
    }

    @Test
    fun aFailedReadSaysSoAndTryAgainReadsAgain() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        playlists.failReads = true
        val viewModel = page(id)
        advanceUntilIdle()
        assertEquals(SavedPlaylistState.Failed, viewModel.uiState.value.playlist)

        playlists.failReads = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(3, ready(viewModel).videos.size)
    }

    @Test
    fun renamingToABlankNameDoesNothing() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()
        viewModel.onOptions()
        viewModel.onRename()

        viewModel.onRenameChange(" ")
        viewModel.onRenameDone()
        advanceUntilIdle()

        assertEquals(PlaylistSheet.Renaming(PlaylistNaming(" ")), viewModel.uiState.value.sheet)
        assertEquals("Engineering deep dives", playlists.playlists().first().single().name)
    }

    @Test
    fun deletingAfterTheConfirmLeavesThePage() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives", *videos.toTypedArray())
        val viewModel = page(id)
        advanceUntilIdle()

        viewModel.onOptions()
        viewModel.onDelete()
        assertEquals(PlaylistSheet.ConfirmDelete, viewModel.uiState.value.sheet)
        viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(SavedPlaylistState.Gone, viewModel.uiState.value.playlist)
        assertNull(viewModel.uiState.value.sheet)
        assertEquals(emptyList<Any>(), playlists.playlists().first())
    }

    @Test
    fun keepPlaylistReturnsToThePageAndBackStepsThroughTheSheet() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()

        viewModel.onOptions()
        viewModel.onDelete()
        viewModel.onSheetBack()
        assertEquals(PlaylistSheet.Options, viewModel.uiState.value.sheet)
        viewModel.onDelete()
        viewModel.onKeepPlaylist()
        assertNull(viewModel.uiState.value.sheet)

        viewModel.onOptions()
        viewModel.onRename()
        viewModel.onSheetBack()
        assertEquals(PlaylistSheet.Options, viewModel.uiState.value.sheet)
        viewModel.onSheetBack()
        assertNull(viewModel.uiState.value.sheet)
        assertFalse(playlists.playlists().first().isEmpty())
    }

    @Test
    fun closingTheSheetMidRenameClosesItAndASaveUnderWayStillLands() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()
        viewModel.onOptions()
        viewModel.onRename()
        viewModel.onRenameChange("Code breakers")
        viewModel.onRenameDone()

        viewModel.onCloseSheet()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sheet)
        assertEquals("Code breakers", ready(viewModel).summary.name)
    }

    @Test
    fun closingTheSheetFromTheDeleteConfirmClosesItAndDeletesNothing() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        val viewModel = page(id)
        advanceUntilIdle()
        viewModel.onOptions()
        viewModel.onDelete()

        viewModel.onCloseSheet()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.sheet)
        assertFalse(playlists.playlists().first().isEmpty())
    }
}
