package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.newpipe.RecordedShortsTab
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.ryd.RecordedVotes
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.testVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * A Shorts run (M7-D13, Q36/Q37/Q39) through the recorded Shorts tab's first page (48 Shorts; it
 * continues): Up/Down as turns, paging on at the end, a failed page, the history card per Short
 * and no resume point, the Short last played kept on the list, a lone Short, a related pick
 * ending the run.
 */
class PlayerViewModelShortsTest {

    private val dispatcher = StandardTestDispatcher()
    private val watch = FakeWatchStore()
    private val nextToken = PageToken(id = "shorts-page-2")
    private val secondPage = ItemsPage(items = emptyList(), next = null, shorts = listOf(testVideo("sssssssss49")))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val recordedTab: ItemsPage by lazy {
        recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }
    }

    /** The recorded first page, continuing to [secondPage] (or to [fetch]'s answer). */
    private fun tabList(fetch: suspend (PageToken) -> ItemsPage = { secondPage }): ShortsList =
        ShortsList(recordedTab.shorts, nextToken, fetch)

    private val settings = FakeProfileSettingsStore(selectedId = 1L)

    private fun player(opening: VideoItem, shorts: ShortsList?): PlayerViewModel = PlayerViewModel(
        opening = opening,
        playAll = null,
        extractor = UnsupportedExtractor("shorts run"),
        store = FakeSubscriptionStore(),
        watchStore = watch,
        playlistStore = FakePlaylistStore(),
        settingsStore = settings,
        voteSource = RecordedVotes.source {},
        captionSource = RecordedCaptions.source(),
        liveChat = FakeLiveChat(),
        shorts = shorts,
        compute = Dispatchers.Main,
    )

    private fun PlayerViewModel.position(): ShortsPosition = uiState.value.shorts!!

    @Test
    fun aShortStartsAtNormalSpeedWhateverTheProfilesSpeed() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(speedPercent = 150))
        val list = tabList()

        val viewModel = player(list.shorts.value.first(), list)

        assertEquals(100, viewModel.startSpeedPercent())
    }

    @Test
    fun aShortOpensAtItsPlaceInTheList() = runTest(dispatcher) {
        val list = tabList()
        val opening = list.shorts.value[3]

        val viewModel = player(opening, list)
        advanceUntilIdle()
        viewModel.playbackStarted(0)
        advanceUntilIdle()

        assertEquals(ShortsPosition(3, hasPrevious = true, hasNext = true), viewModel.position())
        assertEquals(opening.id, list.lastPlayed.value)
        assertEquals(listOf(opening), watch.opened)
    }

    @Test
    fun upAndDownMoveThroughTheListAsNewTurnsEachInHistory() = runTest(dispatcher) {
        val list = tabList()
        val shorts = list.shorts.value
        val viewModel = player(shorts.first(), list)
        advanceUntilIdle()
        assertEquals(RecordedShortsTab.FIRST_ID, viewModel.uiState.value.video.id.value)

        viewModel.playbackStarted(0)
        viewModel.nextShort()
        viewModel.playbackStarted(1)
        viewModel.nextShort()
        viewModel.playbackStarted(2)
        viewModel.previousShort()
        viewModel.playbackStarted(3)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(shorts[1], state.video)
        assertEquals(3, state.turn)
        assertEquals(ShortsPosition(1, hasPrevious = true, hasNext = true), state.shorts)
        assertEquals(listOf(shorts[0], shorts[1], shorts[2], shorts[1]), watch.opened)
        assertEquals("each recorded as a Short (PAF-D6)", watch.opened.map { it.id }, watch.openedAsShort)
        assertEquals(shorts[1].id, list.lastPlayed.value)
    }

    @Test
    fun theShortsBesideAreTheLoadedNeighboursAndNoneAtTheEnds() = runTest(dispatcher) {
        val list = tabList()
        val shorts = list.shorts.value
        val viewModel = player(shorts.first(), list)
        advanceUntilIdle()

        assertEquals(shorts[1], viewModel.shortBeside(next = true))
        assertNull(viewModel.shortBeside(next = false))

        viewModel.nextShort()
        assertEquals(shorts[0], viewModel.shortBeside(next = false))

        val atLoadedEnd = player(shorts.last(), list)
        advanceUntilIdle()
        assertNull(atLoadedEnd.shortBeside(next = true)) // the next page isn't loaded yet
        assertNull(player(testVideo("vvvvvvvvv01"), null).shortBeside(next = true))
    }

    @Test
    fun upOnTheFirstShortDoesNothing() = runTest(dispatcher) {
        val list = tabList()
        val viewModel = player(list.shorts.value.first(), list)
        advanceUntilIdle()

        viewModel.previousShort()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.turn)
        assertFalse(viewModel.position().hasPrevious)
    }

    @Test
    fun downOnTheLoadedLastShortPagesOnThenMoves() = runTest(dispatcher) {
        val list = tabList()
        val last = list.shorts.value.last()
        val viewModel = player(last, list)
        advanceUntilIdle()
        assertEquals(ShortsPosition(RecordedShortsTab.SHORTS - 1, hasPrevious = true, hasNext = true), viewModel.position())

        viewModel.nextShort()
        advanceUntilIdle()

        assertEquals("sssssssss49", viewModel.uiState.value.video.id.value)
        assertEquals(ShortsPosition(RecordedShortsTab.SHORTS, hasPrevious = true, hasNext = false), viewModel.position())
        assertEquals(RecordedShortsTab.SHORTS + 1, list.shorts.value.size)

        viewModel.nextShort() // the last Short of all
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.turn)
    }

    @Test
    fun aFailedPageMakesThisShortTheLast() = runTest(dispatcher) {
        val list = tabList { throw AccessException("page down", RuntimeException()) }
        val last = list.shorts.value.last()
        val viewModel = player(last, list)
        advanceUntilIdle()

        viewModel.nextShort()
        advanceUntilIdle()

        assertEquals(last, viewModel.uiState.value.video)
        assertFalse(viewModel.position().hasNext)
    }

    @Test
    fun aPageLandingAfterUpMovesNothing() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val list = tabList {
            gate.await()
            secondPage
        }
        val shorts = list.shorts.value
        val viewModel = player(shorts.last(), list)
        advanceUntilIdle()

        viewModel.nextShort()
        advanceUntilIdle() // the page is on its way
        viewModel.previousShort()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(shorts[shorts.size - 2], viewModel.uiState.value.video)
        assertTrue(viewModel.position().hasNext)
    }

    @Test
    fun aPageFailingAfterTheRunMovedBackToTheLastShortMakesItTheLast() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val list = tabList {
            gate.await()
            throw AccessException("page down", RuntimeException())
        }
        val last = list.shorts.value.last()
        val viewModel = player(last, list)
        advanceUntilIdle()

        viewModel.nextShort()
        advanceUntilIdle() // the page is on its way
        viewModel.previousShort()
        viewModel.nextShort() // back on the last loaded Short
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(last, viewModel.uiState.value.video)
        assertFalse(viewModel.position().hasNext)
    }

    @Test
    fun aShortNeverResumes() = runTest(dispatcher) {
        val list = tabList()
        val opening = list.shorts.value.first()
        watch.resumePointsMs[opening.id] = 40_000L
        val viewModel = player(opening, list)

        assertNull(viewModel.resumePointMs(opening.id))
    }

    @Test
    fun aListWithoutTheOpeningShortPlaysItAlone() = runTest(dispatcher) {
        val restored = testVideo("sssssssss00")
        val viewModel = player(restored, ShortsList(emptyList()))
        advanceUntilIdle()

        assertEquals(ShortsPosition(0, hasPrevious = false, hasNext = false), viewModel.position())
        viewModel.nextShort()
        advanceUntilIdle()
        assertEquals(restored, viewModel.uiState.value.video)
    }

    @Test
    fun aRelatedPickEndsTheRunAndResumesAsAnyVideo() = runTest(dispatcher) {
        val list = tabList()
        val viewModel = player(list.shorts.value.first(), list)
        advanceUntilIdle()
        val related = testVideo("rrrrrrrrrrr")
        watch.resumePointsMs[related.id] = 40_000L

        viewModel.play(related)
        viewModel.nextShort()
        viewModel.playbackStarted(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.shorts)
        assertEquals(related, state.video)
        assertEquals(40_000L, viewModel.resumePointMs(related.id))
        assertEquals(list.shorts.value.first().id, list.lastPlayed.value)
        assertEquals(listOf(related), watch.opened)
        assertEquals("History records the related pick as a video", emptyList<VideoId>(), watch.openedAsShort)
    }

    @Test
    fun aSearchShortTakesItsChannelFromItsOwnTurnsResolveOnly() = runTest(dispatcher) {
        val bare = { id: String -> testVideo(id).copy(uploaderName = null, uploaderUrl = null) }
        val list = ShortsList(listOf(bare("sssssssss01"), bare("sssssssss02")))
        val viewModel = player(list.shorts.value.first(), list)
        advanceUntilIdle()
        viewModel.playbackStarted(0)

        viewModel.nextShort()
        viewModel.playbackStarted(1)
        viewModel.channelResolved(turn = 0, name = "Stale", url = "https://www.youtube.com/channel/UCstale")
        assertNull(viewModel.uiState.value.video.uploaderUrl)

        viewModel.channelResolved(turn = 1, name = "Veritasium", url = VERITASIUM_URL)
        val video = viewModel.uiState.value.video
        assertEquals("Veritasium", video.uploaderName)
        assertEquals(VERITASIUM_URL, video.uploaderUrl)

        viewModel.channelResolved(turn = 1, name = "Other", url = "https://www.youtube.com/channel/UCother")
        assertEquals(VERITASIUM_URL, viewModel.uiState.value.video.uploaderUrl)

        advanceUntilIdle()
        val (first, second) = list.shorts.value
        assertEquals(listOf(first, second, video), watch.opened)
        assertEquals("the channel's re-record stays a Short", listOf(first.id, second.id, video.id), watch.openedAsShort)
    }

    /** PAF-D1: a channel named before playback starts is in the one card History records. */
    @Test
    fun aChannelNamedBeforeTheStartIsInTheOneRecordedCard() = runTest(dispatcher) {
        val list = ShortsList(listOf(testVideo("sssssssss01").copy(uploaderName = null, uploaderUrl = null)))
        val viewModel = player(list.shorts.value.first(), list)
        advanceUntilIdle()

        viewModel.channelResolved(turn = 0, name = "Veritasium", url = VERITASIUM_URL)
        advanceUntilIdle()
        assertEquals(emptyList<VideoItem>(), watch.opened)
        viewModel.playbackStarted(0)
        advanceUntilIdle()

        assertEquals(listOf(VERITASIUM_URL), watch.opened.map { it.uploaderUrl })
    }

    @Test
    fun aVideoHasNoShortsRun() = runTest(dispatcher) {
        val viewModel = player(testVideo("vvvvvvvvvvv"), shorts = null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.shorts)
        viewModel.nextShort()
        assertEquals(0, viewModel.uiState.value.turn)
    }

    @Test
    fun theRouteCarriesTheShortFlag() {
        val card = testVideo("sssssssss01")

        assertTrue(PlayerRoute(card, short = true).short)
        assertFalse(PlayerRoute(card).short)
        assertEquals(VideoId("sssssssss01"), PlayerRoute(card, short = true).video().id)
    }

    private companion object {
        const val VERITASIUM_URL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
    }
}
