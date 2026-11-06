package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.SavedIn
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.testVideo
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
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

/** The card menu and its save sheet: the play rows' resume point, removing by source, saving, create and save. */
class VideoMenuViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val video = testVideo("aaaaaaaaaaa")
    private val settings = FakeProfileSettingsStore(selectedId = 1L)
    private val blocking = MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic)
    private val viewModel by lazy { VideoMenuViewModel(watch, playlists, settings, blocking) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun menu() = viewModel.menu.value!!

    @Test
    fun aVideoWithAResumePointOffersToResumeFromIt() = runTest(dispatcher) {
        watch.resumePointsMs[video.id] = 2_091_000

        viewModel.open(video, VideoSource.History)
        assertEquals(MenuResume.Reading, menu().resume)
        advanceUntilIdle()

        assertEquals(MenuResume.At(2_091_000), menu().resume)
    }

    @Test
    fun withResumeOffAVideoWithAResumePointOffersPlay() = runTest(dispatcher) {
        watch.resumePointsMs[video.id] = 2_091_000
        settings.byProfile.value = mapOf(1L to ProfileSettings(resume = false))

        viewModel.open(video, VideoSource.History)
        advanceUntilIdle()

        assertEquals(MenuResume.None, menu().resume)
    }

    @Test
    fun withTheSettingsUnreadableAVideoStillOffersToResume() = runTest(dispatcher) {
        watch.resumePointsMs[video.id] = 2_091_000
        settings.failReads = true

        viewModel.open(video, VideoSource.History)
        advanceUntilIdle()

        assertEquals(MenuResume.At(2_091_000), menu().resume)
    }

    @Test
    fun aShortsMenuOffersPlayEvenWithAResumePointAndAsksNothing() = runTest(dispatcher) {
        watch.resumePointsMs[video.id] = 2_091_000
        watch.resumeGate = CompletableDeferred() // a read would hold the menu at Reading

        viewModel.open(video, VideoSource.Elsewhere, short = true)
        advanceUntilIdle()

        assertEquals(MenuResume.None, menu().resume)
        assertTrue(menu().short)
    }

    @Test
    fun aVideoWithoutOneOrAStoreThatCantAnswerOffersPlay() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        advanceUntilIdle()
        assertEquals(MenuResume.None, menu().resume)

        watch.failReads = true
        viewModel.open(video, VideoSource.Elsewhere)
        advanceUntilIdle()
        assertEquals(MenuResume.None, menu().resume)
    }

    @Test
    fun aResumeReadForAMenuNoLongerOpenIsDropped() = runTest(dispatcher) {
        val other = testVideo("bbbbbbbbbbb")
        watch.resumePointsMs[video.id] = 2_091_000
        val gate = CompletableDeferred<Unit>().also { watch.resumeGate = it }
        viewModel.open(video, VideoSource.History)
        advanceUntilIdle()

        viewModel.open(other, VideoSource.Elsewhere)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(other, menu().video)
        assertEquals(MenuResume.None, menu().resume)
    }

    @Test
    fun aSaveSheetThatCouldntBeReadIsReadAgainOnTheNextVisit() = runTest(dispatcher) {
        playlists.failReads = true
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()
        assertNull(menu().savedIn)

        playlists.failReads = false
        viewModel.onBack()
        viewModel.onSaveTo()
        advanceUntilIdle()

        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), menu().savedIn)
    }

    @Test
    fun aSwitchFlipsAtOnceAndTwoQuickPressesEndWhereTheyStarted() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()

        viewModel.onToggleWatchLater()
        assertTrue("flipped before the write lands", menu().savedIn!!.watchLater)
        viewModel.onToggleWatchLater()
        advanceUntilIdle()

        assertFalse(menu().savedIn!!.watchLater)
        assertFalse(playlists.savedIn(video.id).first().watchLater)
    }

    @Test
    fun aFailedRemoveClosesTheMenuAndLeavesTheVideo() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry(video.id.value))
        watch.failWrites = true
        viewModel.open(video, VideoSource.History)

        viewModel.onRemove()
        advanceUntilIdle()

        assertNull(viewModel.menu.value)
        assertEquals(listOf(video.id), watch.entries.value.map { it.videoId })
    }

    @Test
    fun aPlaylistCreatedWhoseSaveFailedIsShownAndNotMadeTwice() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Code breakers")
        playlists.failAdds = true

        viewModel.onNamingDone()
        advanceUntilIdle()

        assertEquals(MenuStage.SaveTo, menu().stage)
        assertEquals(listOf("Code breakers"), menu().playlists!!.map { it.name })
        assertEquals(emptySet<Long>(), menu().savedIn!!.playlistIds)
    }

    @Test
    fun aFailedCreateKeepsTheNameForAnotherTry() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Code breakers")
        playlists.failWrites = true

        viewModel.onNamingDone()
        advanceUntilIdle()

        assertEquals(MenuStage.Naming(PlaylistNaming("Code breakers")), menu().stage)
    }

    @Test
    fun aResumePointFromHistoryIsOffered() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry(video.id.value, positionMs = 120_000))

        viewModel.open(video, VideoSource.History)
        advanceUntilIdle()

        assertEquals(MenuResume.At(120_000), menu().resume)
    }

    /** A For you pick's menu hides through Home, never through a remove row (FY-D11). */
    @Test
    fun onlyAVideoListedInTheLibraryCanBeRemoved() = runTest(dispatcher) {
        val forYou = VideoSource.ForYou(ForYouReason(VideoId("seed0000001"), SeedKind.Watched, "a seed"))
        for (source in listOf(VideoSource.Elsewhere, forYou)) {
            viewModel.open(video, source)
            assertFalse("$source", menu().canRemove)
            viewModel.onRemove()
            assertTrue("the menu stays open: $source", viewModel.menu.value != null)
        }

        viewModel.open(video, VideoSource.History)
        assertTrue(menu().canRemove)
    }

    @Test
    fun removeTakesTheVideoOutOfHistory() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry(video.id.value), watchEntry("bbbbbbbbbbb"))
        viewModel.open(video, VideoSource.History)

        viewModel.onRemove()
        advanceUntilIdle()

        assertNull(viewModel.menu.value)
        assertEquals(listOf(VideoId("bbbbbbbbbbb")), watch.entries.value.map { it.videoId })
    }

    @Test
    fun removeTakesTheVideoOutOfWatchLaterOrItsPlaylistOnly() = runTest(dispatcher) {
        playlists.addToWatchLater(video)
        val id = playlists.seed("Engineering deep dives", video)

        viewModel.open(video, VideoSource.Playlist(id))
        viewModel.onRemove()
        advanceUntilIdle()
        assertEquals(SavedIn(watchLater = true, playlistIds = emptySet()), playlists.savedIn(video.id).first())

        viewModel.open(video, VideoSource.WatchLater)
        viewModel.onRemove()
        advanceUntilIdle()
        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), playlists.savedIn(video.id).first())
    }

    @Test
    fun theSaveSheetSwitchesFollowTheStore() = runTest(dispatcher) {
        val id = playlists.seed("Engineering deep dives")
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()
        assertEquals(MenuStage.SaveTo, menu().stage)
        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), menu().savedIn)
        assertEquals(listOf(id), menu().playlists!!.map { it.id })

        viewModel.onToggleWatchLater()
        viewModel.onTogglePlaylist(id)
        advanceUntilIdle()
        assertEquals(SavedIn(watchLater = true, playlistIds = setOf(id)), menu().savedIn)

        viewModel.onToggleWatchLater()
        viewModel.onTogglePlaylist(id)
        advanceUntilIdle()
        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), menu().savedIn)
    }

    @Test
    fun aSwitchKeepsItsRowWhereItWasAndANewPlaylistLeadsTheList() = runTest(dispatcher) {
        val older = playlists.seed("Weekend cooking")
        val newer = playlists.seed("Engineering deep dives")
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()
        assertEquals(listOf(newer, older), menu().playlists!!.map { it.id })

        // Saving stamps the older playlist, which the store then lists first.
        viewModel.onTogglePlaylist(older)
        advanceUntilIdle()
        assertEquals(listOf(older, newer), playlists.playlists().first().map { it.id })
        assertEquals(listOf(newer, older), menu().playlists!!.map { it.id })

        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Code breakers")
        viewModel.onNamingDone()
        advanceUntilIdle()
        val created = playlists.playlists().first().first { it.name == "Code breakers" }.id
        assertEquals(listOf(created, newer, older), menu().playlists!!.map { it.id })
    }

    @Test
    fun aFailedSaveReturnsTheSwitchToWhatTheStoreHas() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()
        playlists.failWrites = true

        viewModel.onToggleWatchLater()
        assertTrue(menu().savedIn!!.watchLater)
        advanceUntilIdle()

        assertFalse(menu().savedIn!!.watchLater)
    }

    @Test
    fun createAndSavePutsTheVideoInTheNewPlaylist() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()

        viewModel.onNewPlaylist()
        viewModel.onNamingChange("Code breakers")
        viewModel.onNamingDone()
        advanceUntilIdle()

        val created = playlists.playlists().first().single()
        assertEquals("Code breakers", created.name)
        assertEquals(MenuStage.SaveTo, menu().stage)
        assertEquals(setOf(created.id), menu().savedIn!!.playlistIds)
    }

    @Test
    fun createAndSaveWithABlankNameDoesNothing() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        viewModel.onNewPlaylist()

        viewModel.onNamingDone()
        advanceUntilIdle()

        assertEquals(MenuStage.Naming(PlaylistNaming()), menu().stage)
        assertEquals(emptyList<Any>(), playlists.playlists().first())
    }

    @Test
    fun backStepsFromNamingToTheSaveSheetToTheMenuThenCloses() = runTest(dispatcher) {
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        viewModel.onNewPlaylist()

        viewModel.onBack()
        assertEquals(MenuStage.SaveTo, menu().stage)
        viewModel.onBack()
        assertEquals(MenuStage.Options, menu().stage)
        viewModel.onBack()
        assertNull(viewModel.menu.value)
    }

    @Test
    fun theSaveSheetOpenedAloneFollowsTheStoreAndBackClosesIt() = runTest(dispatcher) {
        playlists.addToWatchLater(video)
        viewModel.openSaveTo(video, short = true)
        assertEquals(MenuStage.SaveTo, menu().stage)
        advanceUntilIdle()
        assertTrue(menu().savedIn!!.watchLater)
        assertTrue(menu().short)

        viewModel.onNewPlaylist()
        viewModel.onBack()
        assertEquals(MenuStage.SaveTo, menu().stage)
        viewModel.onBack()

        assertNull(viewModel.menu.value)
    }

    @Test
    fun aMenuOpenedOnAnotherVideoStartsAfresh() = runTest(dispatcher) {
        val other = testVideo("bbbbbbbbbbb")
        playlists.addToWatchLater(other)
        viewModel.open(video, VideoSource.Elsewhere)
        viewModel.onSaveTo()
        advanceUntilIdle()

        viewModel.open(other, VideoSource.WatchLater)
        assertEquals(MenuStage.Options, menu().stage)
        viewModel.onSaveTo()
        advanceUntilIdle()

        assertEquals(other, menu().video)
        assertTrue(menu().savedIn!!.watchLater)
    }
}
