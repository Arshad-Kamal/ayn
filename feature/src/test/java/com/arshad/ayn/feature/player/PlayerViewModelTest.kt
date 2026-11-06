package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RecordedPlaylist
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.ryd.RecordedVotes
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.library.watchEntry
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Play all through the recorded uploads playlist (100 videos a page, the second page fetched by
 * the advance past the first), what a video's end leads to (Play all's next, else Up next's turn),
 * a related video picked in place, the Subscribe toggle against the store, each video's history card
 * and resume point in the watch store, and the route the shell navigates with.
 */
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeSubscriptionStore()
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val settings = FakeProfileSettingsStore(selectedId = PROFILE)
    private var settingsStore: ProfileSettingsStore = settings
    private val votesAsked = mutableListOf<VideoId>()
    private var votes: VoteSource = RecordedVotes.source { votesAsked += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun player(
        opening: VideoItem,
        playAll: PlayAll? = null,
        extractor: Extractor = UnreachablePlaylist(),
        startOver: Boolean = false,
        compute: CoroutineDispatcher = Dispatchers.Main,
    ): PlayerViewModel =
        PlayerViewModel(opening, playAll, extractor, store, watch, playlists, settingsStore, votes, RecordedCaptions.source(), FakeLiveChat(), startOver, compute = compute)

    private val uploads = PlaylistItem(
        title = "Uploads from Rick Astley",
        url = PAGED_PLAYLIST_URL,
        thumbnailUrl = null,
        videoCount = RecordedPlaylist.PAGED_VIDEO_COUNT,
        uploaderName = RecordedPlaylist.UPLOADER_NAME,
    )

    private fun recordedUploads(): List<VideoItem> =
        recorded(PAGED_PLAYLIST_FLOW) { playlist(PAGED_PLAYLIST_URL) }.videos.items.filterIsInstance<VideoItem>()

    private fun firstUpload(): VideoItem = recordedUploads().first()

    @Test
    fun playAllAdvancesInPlaylistOrderIntoTheSecondPage() = runTest(dispatcher) {
        val viewModel = player(
            firstUpload(),
            PlayAll.YouTube(uploads.copy(videoCount = -1)),
            NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher),
        )
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.playlist!!.index)
        assertTrue(viewModel.uiState.value.playlist!!.hasNext)

        repeat(RecordedPlaylist.PAGE_SIZE) {
            viewModel.endTurn()
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(RecordedPlaylist.PAGE_2_FIRST_ID, state.video.id.value)
        assertEquals(RecordedPlaylist.PAGE_SIZE, state.playlist!!.index)
        assertEquals(RecordedPlaylist.PAGED_VIDEO_COUNT, state.playlist!!.videoCount)
    }

    @Test
    fun playAllEndsAtTheLoadedVideosWhenTheNextPageFails() = runTest(dispatcher) {
        val viewModel = player(firstUpload(), PlayAll.YouTube(uploads), FirstPageOnly())
        advanceUntilIdle()
        repeat(RecordedPlaylist.PAGE_SIZE - 1) {
            viewModel.endTurn()
            advanceUntilIdle()
        }
        val last = viewModel.uiState.value
        assertEquals(RecordedPlaylist.PAGE_SIZE - 1, last.playlist!!.index)
        assertTrue(last.playlist!!.hasNext)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(last.video, viewModel.uiState.value.video)
        assertFalse(viewModel.uiState.value.playlist!!.hasNext)
        assertTrue(viewModel.uiState.value.atEnd)
    }

    @Test
    fun anAdvanceAskedForWhileThePlaylistLoadsLandsOnceItArrives() = runTest(dispatcher) {
        val viewModel = player(
            firstUpload(),
            PlayAll.YouTube(uploads),
            NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher),
        )

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.playlist!!.index)
    }

    @Test
    fun playAllStartsAtTheOpeningVideosPlaceInThePlaylist() = runTest(dispatcher) {
        val uploadsList = recordedUploads()
        val viewModel = player(
            uploadsList[4],
            PlayAll.YouTube(uploads),
            NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher),
        )
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.playlist!!.index)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(uploadsList[5], viewModel.uiState.value.video)
    }

    @Test
    fun aVideoFromALaterPageFindsItsPlaceByPagingOn() = runTest(dispatcher) {
        val secondPage = recorded(PAGED_PLAYLIST_FLOW) {
            playlistVideos(PAGED_PLAYLIST_URL, checkNotNull(playlist(PAGED_PLAYLIST_URL).videos.next))
        }.items.filterIsInstance<VideoItem>()
        val viewModel = player(
            secondPage[1],
            PlayAll.YouTube(uploads),
            NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher),
        )
        advanceUntilIdle()
        assertEquals(RecordedPlaylist.PAGE_SIZE + 1, viewModel.uiState.value.playlist!!.index)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(secondPage[2], viewModel.uiState.value.video)
    }

    @Test
    fun aVideoNoLongerListedPlaysAloneOutsidePlayAll() = runTest(dispatcher) {
        val gone = testVideo("zzzzzzzzzzz")
        val viewModel = player(gone, PlayAll.YouTube(uploads), TwoVideos(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb")))
        advanceUntilIdle()

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(gone, viewModel.uiState.value.video)
        assertNull(viewModel.uiState.value.playlist)
    }

    @Test
    fun aVideoWithoutAChannelIsNotShownAsSubscribed() = runTest(dispatcher) {
        val first = testVideo("aaaaaaaaaaa")
        val channelLess = testVideo("bbbbbbbbbbb").copy(uploaderUrl = null)
        store.rows.value = listOf(testSubscription(first.uploaderUrl!!))
        val viewModel = player(first, PlayAll.YouTube(uploads), TwoVideos(first, channelLess))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSubscribed)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(channelLess, viewModel.uiState.value.video)
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun playAllEndsWhenThePlaylistCannotLoad() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        val viewModel = player(opening, PlayAll.YouTube(uploads), UnreachablePlaylist())
        advanceUntilIdle()

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(opening, viewModel.uiState.value.video)
        assertNull(viewModel.uiState.value.playlist)
        assertTrue(viewModel.uiState.value.atEnd)
    }

    @Test
    fun aSingleVideosEndIsUpNextsTurn() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        val viewModel = player(opening)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.atEnd)

        viewModel.endTurn()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.playlist)
        assertEquals(opening, viewModel.uiState.value.video)
        assertEquals(0, viewModel.uiState.value.turn)
        assertTrue(viewModel.uiState.value.atEnd)
    }

    @Test
    fun aRelatedVideoPickedPlaysAsTheNextTurnAndEndsPlayAll() = runTest(dispatcher) {
        val (a, b) = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"))
        val related = testVideo("rrrrrrrrrrr")
        val viewModel = player(a, PlayAll.Saved(playlists.seed("Engineering deep dives", a, b), "Engineering deep dives", 2))
        advanceUntilIdle()
        viewModel.playbackStarted(0)

        viewModel.play(related)
        viewModel.playbackStarted(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(related, state.video)
        assertNull(state.playlist)
        assertEquals(1, state.turn)
        assertFalse(state.atEnd)
        assertEquals(listOf(a, related), watch.opened)

        viewModel.endTurn()
        advanceUntilIdle()
        assertEquals(related, viewModel.uiState.value.video)
        assertTrue("the run ended with the pick: nothing follows", viewModel.uiState.value.atEnd)
    }

    @Test
    fun aRelatedVideoPickedToPlayFromTheStartStartsOverForItsTurnOnly() = runTest(dispatcher) {
        val viewModel = player(testVideo("aaaaaaaaaaa"))

        viewModel.play(testVideo("rrrrrrrrrrr"), startOver = true)
        assertTrue(viewModel.uiState.value.startOver)
        viewModel.play(testVideo("sssssssssss"))

        assertFalse(viewModel.uiState.value.startOver)
    }

    @Test
    fun anEndReportedForTheTurnBeforeAPickIsDropped() = runTest(dispatcher) {
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        viewModel.play(testVideo("rrrrrrrrrrr"))
        viewModel.ended(turn = 0)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.atEnd)
    }

    @Test
    fun aPageFailingAfterAPickLeavesThePickPlaying() = runTest(dispatcher) {
        pickDuringAPageFetch(pageLands = false)
    }

    @Test
    fun aPageLandingAfterAPickLeavesThePickPlaying() = runTest(dispatcher) {
        pickDuringAPageFetch(pageLands = true)
    }

    /** At the first page's last video, the end fetches the next page; a pick cancels that fetch,
     *  but extraction can't be interrupted, so the page (or its failure) still arrives. */
    private fun TestScope.pickDuringAPageFetch(pageLands: Boolean) {
        val gate = CompletableDeferred<Unit>()
        val viewModel = player(firstUpload(), PlayAll.YouTube(uploads), GatedSecondPage(gate, pageLands))
        advanceUntilIdle()
        repeat(RecordedPlaylist.PAGE_SIZE - 1) {
            viewModel.endTurn()
            advanceUntilIdle()
        }
        val related = testVideo("rrrrrrrrrrr")

        viewModel.endTurn()
        advanceUntilIdle()
        viewModel.play(related)
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(related, state.video)
        assertNull(state.playlist)
        assertEquals(RecordedPlaylist.PAGE_SIZE, state.turn)
        assertFalse(state.atEnd)
    }

    @Test
    fun aVideoPickedAtTheEndStartsItsTurnNotEnded() = runTest(dispatcher) {
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        viewModel.endTurn()
        advanceUntilIdle()

        viewModel.play(testVideo("rrrrrrrrrrr"))

        assertFalse(viewModel.uiState.value.atEnd)
        assertEquals(1, viewModel.uiState.value.turn)
    }

    @Test
    fun anEndWaitingForThePlaylistIsDroppedWhenAVideoIsPickedMeanwhile() = runTest(dispatcher) {
        val viewModel = player(
            firstUpload(),
            PlayAll.YouTube(uploads),
            NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW), io = dispatcher),
        )
        val related = testVideo("rrrrrrrrrrr")

        viewModel.endTurn() // the playlist is still loading: the end waits for it
        viewModel.play(related)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(related, state.video)
        assertNull(state.playlist)
        assertEquals(1, state.turn)
        assertFalse(state.atEnd)
    }

    @Test
    fun theRowsBarsAreTheProfilesResumePoints() = runTest(dispatcher) {
        watch.entries.value = listOf(
            watchEntry("ppppppppppp", positionMs = 120_000L, durationMs = 600_000L),
            watchEntry("wwwwwwwwwww", positionMs = 600_000L, durationMs = 600_000L, watched = true),
        )

        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        assertEquals(mapOf(VideoId("ppppppppppp") to WatchMark.PartWay(120_000L, 600_000L)), viewModel.marks.value)
    }

    @Test
    fun aPositionSaveUpdatesTheBarsWithoutReEmittingTheScreensState() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("ppppppppppp", positionMs = 120_000L, durationMs = 600_000L))
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        val before = viewModel.uiState.value

        watch.entries.value = listOf(watchEntry("ppppppppppp", positionMs = 130_000L, durationMs = 600_000L))
        advanceUntilIdle()

        assertEquals(mapOf(VideoId("ppppppppppp") to WatchMark.PartWay(130_000L, 600_000L)), viewModel.marks.value)
        assertSame(before, viewModel.uiState.value)
    }

    @Test
    fun resumePointsAreReadOffTheMainThread() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("ppppppppppp", positionMs = 120_000L, durationMs = 600_000L))
        val compute = StandardTestDispatcher(testScheduler, "compute")

        val viewModel = player(testVideo("aaaaaaaaaaa"), compute = compute)
        advanceUntilIdle()

        assertEquals(listOf(compute), watch.readOn)
        assertEquals(mapOf(VideoId("ppppppppppp") to WatchMark.PartWay(120_000L, 600_000L)), viewModel.marks.value)
    }

    @Test
    fun resumePointsThatCannotBeReadLeaveTheRowWithoutBars() = runTest(dispatcher) {
        watch.failReads = true
        val opening = testVideo("aaaaaaaaaaa")

        val viewModel = player(opening)
        advanceUntilIdle()

        assertEquals(emptyMap<VideoId, WatchMark>(), viewModel.marks.value)
        assertEquals(opening, viewModel.uiState.value.video)
    }

    @Test
    fun subscribeStoresTheChannelWithThePagesAvatarAndUnsubscribeRemovesIt() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        val viewModel = player(opening)
        advanceUntilIdle()

        viewModel.toggleSubscribe(avatarUrl = "https://yt3.example/avatar")
        assertTrue("the toggle flips before the write lands", viewModel.uiState.value.isSubscribed)
        advanceUntilIdle()
        assertEquals(listOf(opening.uploaderUrl), store.rows.value.map { it.channelUrl })
        assertEquals("https://yt3.example/avatar", store.rows.value.single().avatarUrl)
        assertTrue(viewModel.uiState.value.isSubscribed)

        viewModel.toggleSubscribe(avatarUrl = null)
        assertFalse(viewModel.uiState.value.isSubscribed)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), store.rows.value.map { it.channelUrl })
        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun aFailedSubscribeRevertsToTheStore() = runTest(dispatcher) {
        store.failWrites = true
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        viewModel.toggleSubscribe(avatarUrl = null)
        assertTrue(viewModel.uiState.value.isSubscribed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSubscribed)
    }

    @Test
    fun theResumePointIsTheStoresAtTheTimeItIsAsked() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        val viewModel = player(opening)
        advanceUntilIdle()
        assertNull(viewModel.resumePointMs(opening.id))

        watch.resumePointsMs[opening.id] = 95_000L // saved while the screen was elsewhere

        assertEquals(95_000L, viewModel.resumePointMs(opening.id))
    }

    @Test
    fun withResumeOffAVideoStartsFromTheBeginning() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        watch.resumePointsMs[opening.id] = 95_000L
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(resume = false))
        val viewModel = player(opening)

        assertNull(viewModel.resumePointMs(opening.id))
    }

    @Test
    fun settingsThatCannotBeReadResumeAtNormalSpeed() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        watch.resumePointsMs[opening.id] = 95_000L
        settings.failReads = true
        val viewModel = player(opening)

        assertEquals(95_000L, viewModel.resumePointMs(opening.id))
        assertEquals(100, viewModel.startSpeedPercent())
    }

    @Test
    fun aVideoStartsAtTheProfilesSpeed() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(speedPercent = 150))
        val viewModel = player(testVideo("aaaaaaaaaaa"))

        assertEquals(150, viewModel.startSpeedPercent())
    }

    @Test
    fun aStoreThatCannotAnswerGivesNoResumePoint() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        watch.resumePointsMs[opening.id] = 95_000L
        watch.failReads = true
        val viewModel = player(opening)

        assertNull(viewModel.resumePointMs(opening.id))
    }

    @Test
    fun aFailedHistoryWriteLeavesThePlayerWorking() = runTest(dispatcher) {
        watch.failWrites = true
        val opening = testVideo("aaaaaaaaaaa")

        val viewModel = player(opening)
        advanceUntilIdle()

        assertEquals(opening, viewModel.uiState.value.video)
    }

    /** PAF-D1: History records the card once playback starts, once per turn. */
    @Test
    fun theOpeningCardIsRecordedForHistoryOnceItPlays() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")

        val viewModel = player(opening)
        advanceUntilIdle()
        assertEquals(emptyList<VideoItem>(), watch.opened)

        viewModel.playbackStarted(0)
        viewModel.playbackStarted(0)
        advanceUntilIdle()

        assertEquals(listOf(opening), watch.opened)
    }

    /** A turn left before it reports a start (as a refused, filtered or failed one is) records
     *  nothing, and a late start report for it is dropped. */
    @Test
    fun aTurnLeftBeforeItStartsRecordsNothing() = runTest(dispatcher) {
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        viewModel.play(testVideo("bbbbbbbbbbb"))
        viewModel.playbackStarted(0)
        advanceUntilIdle()

        assertEquals(emptyList<VideoItem>(), watch.opened)
    }

    @Test
    fun playAllsNextVideoRecordsItsCard() = runTest(dispatcher) {
        val first = testVideo("aaaaaaaaaaa")
        val second = testVideo("bbbbbbbbbbb")
        val viewModel = player(first, PlayAll.YouTube(uploads), TwoVideos(first, second))
        advanceUntilIdle()
        viewModel.playbackStarted(0)

        viewModel.endTurn()
        advanceUntilIdle()
        viewModel.playbackStarted(1)
        advanceUntilIdle()

        assertEquals(listOf(first, second), watch.opened)
    }

    @Test
    fun aSavedPlaylistPlaysOnInItsOrderAndEndsAtItsLastVideo() = runTest(dispatcher) {
        val (a, b, c) = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"), testVideo("ccccccccccc"))
        val id = playlists.seed("Engineering deep dives", a, b, c)
        val viewModel = player(b, PlayAll.Saved(id, "Engineering deep dives", videoCount = 5)) // a stale count
        advanceUntilIdle()
        assertEquals(PlaylistPosition("Engineering deep dives", index = 1, videoCount = 3, hasNext = true), viewModel.uiState.value.playlist)

        viewModel.endTurn()
        advanceUntilIdle()
        assertEquals(c.id, viewModel.uiState.value.video.id)
        assertFalse(viewModel.uiState.value.playlist!!.hasNext)
        assertFalse(viewModel.uiState.value.atEnd)

        viewModel.endTurn()
        advanceUntilIdle()
        assertEquals(c.id, viewModel.uiState.value.video.id)
        assertEquals(1, viewModel.uiState.value.turn)
        assertTrue("Q23: the run's last video ends at Up next", viewModel.uiState.value.atEnd)
    }

    @Test
    fun aShuffledRunPlaysTheDealThePageMadeFromTheSameSeed() = runTest(dispatcher) {
        val listed = (0 until 8).map { testVideo("video${it}xxxxx") }
        val id = playlists.seed("Space & science", *listed.toTypedArray())
        val dealt = playOrder(listed, shuffleSeed = 42L).map { it.id }
        val viewModel = player(listed.first { it.id == dealt.first() }, PlayAll.Saved(id, "Space & science", 8, shuffleSeed = 42L))
        advanceUntilIdle()

        val played = mutableListOf(viewModel.uiState.value.video.id)
        repeat(listed.size - 1) {
            viewModel.endTurn()
            advanceUntilIdle()
            played += viewModel.uiState.value.video.id
        }

        assertEquals(dealt, played)
    }

    @Test
    fun aDealIsEveryVideoOnceAndTheSeedDecidesTheOrder() {
        val listed = (0 until 8).map { testVideo("video${it}xxxxx") }

        val dealt = playOrder(listed, shuffleSeed = 42L)

        assertEquals(listed.sortedBy { it.id.value }, dealt.sortedBy { it.id.value })
        assertEquals(dealt, playOrder(listed, shuffleSeed = 42L))
        assertNotEquals(dealt, playOrder(listed, shuffleSeed = 43L))
        assertEquals(listed, playOrder(listed, shuffleSeed = null))
    }

    @Test
    fun aRunKeepsTheListItOpenedWithWhenThePlaylistChanges() = runTest(dispatcher) {
        val (a, b, c) = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"), testVideo("ccccccccccc"))
        val id = playlists.seed("Engineering deep dives", a, b, c)
        val viewModel = player(a, PlayAll.Saved(id, "Engineering deep dives", 3))
        advanceUntilIdle()

        playlists.remove(id, b.id)
        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(b.id, viewModel.uiState.value.video.id)
        assertEquals(1, viewModel.uiState.value.playlist!!.index)
    }

    @Test
    fun aVideoNoLongerInTheSavedPlaylistPlaysAlone() = runTest(dispatcher) {
        val gone = testVideo("zzzzzzzzzzz")
        val id = playlists.seed("Weekend cooking", testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"))
        val viewModel = player(gone, PlayAll.Saved(id, "Weekend cooking", 3))
        advanceUntilIdle()

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(gone, viewModel.uiState.value.video)
        assertNull(viewModel.uiState.value.playlist)
    }

    @Test
    fun aSavedPlaylistThatCannotBeReadPlaysTheOpeningVideoAlone() = runTest(dispatcher) {
        val opening = testVideo("aaaaaaaaaaa")
        val id = playlists.seed("Weekend cooking", opening, testVideo("bbbbbbbbbbb"))
        playlists.failReads = true
        val viewModel = player(opening, PlayAll.Saved(id, "Weekend cooking", 2))
        advanceUntilIdle()

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(opening, viewModel.uiState.value.video)
        assertNull(viewModel.uiState.value.playlist)
    }

    @Test
    fun startingOverHoldsForTheOpeningVideoOnly() = runTest(dispatcher) {
        val (first, second) = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"))
        val id = playlists.seed("Tiny Desk favourites", first, second)
        val viewModel = player(first, PlayAll.Saved(id, "Tiny Desk favourites", 2), startOver = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.startOver)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(second.id, viewModel.uiState.value.video.id)
        assertFalse(viewModel.uiState.value.startOver)
    }

    @Test
    fun theRouteCarriesTheCardAndAYouTubePlaylist() {
        val card = firstUpload()

        val route = PlayerRoute(card, PlayAll.YouTube(uploads))

        assertEquals(card, route.video())
        val playAll = route.playAll() as PlayAll.YouTube
        assertEquals(uploads.url, playAll.playlist.url)
        assertEquals(uploads.title, playAll.title)
        assertEquals(uploads.videoCount, playAll.videoCount)
        assertFalse(route.startOver)
        assertNull(PlayerRoute(card).playAll())
    }

    @Test
    fun theRouteCarriesASavedPlaylistItsSeedAndStartingOver() {
        val card = testVideo("aaaaaaaaaaa")
        val saved = PlayAll.Saved(playlistId = 7, title = "Engineering deep dives", videoCount = 8, shuffleSeed = -3L)

        val route = PlayerRoute(card, saved, startOver = true)

        assertEquals(saved, route.playAll())
        assertNull(route.playlistUrl)
        assertTrue(route.startOver)
    }

    @Test
    fun ticksSkipAndPromptByTheProfilesActions() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings().withAction(SegmentCategory.Outro, SegmentAction.Bar))
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        assertNull(viewModel.segmentTick(0, segments, 1_000))
        assertEquals(134_034L, viewModel.uiState.value.skipPrompt?.span?.endMs)
        assertEquals(687_665L, viewModel.segmentTick(0, segments, 681_500))
        assertEquals(listOf(SegmentCategory.Interaction), viewModel.uiState.value.skipNote?.span?.categories)
        assertNull(viewModel.uiState.value.skipPrompt)
        viewModel.dismissSkipNote()
        assertNull(viewModel.segmentTick(0, segments, 688_000))

        assertNull(viewModel.uiState.value.skipPrompt) // outro marks the bar only
        assertNull(viewModel.uiState.value.skipNote)
    }

    @Test
    fun theControlsSkipIsPublishedInsideAPromptSpanAndHiddenByAMaskedLookup() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        viewModel.segmentTick(0, segments, 20_000)
        assertEquals(134_034L, viewModel.uiState.value.skippable?.endMs)
        viewModel.segmentTick(0, SegmentLookup.None, 20_200)
        assertNull(viewModel.uiState.value.skippable)
        assertNull(viewModel.skipFromControls())
        viewModel.segmentTick(0, segments, 20_400)

        assertEquals(134_034L, viewModel.skipFromControls())
        assertEquals(134_034L, viewModel.uiState.value.skipNote?.span?.endMs)
        assertNull(viewModel.uiState.value.skippable)
    }

    @Test
    fun theControlsSkipGoesWhenPlaybackLeavesTheSpan() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, segments, 20_000)

        viewModel.segmentTick(0, segments, 140_000)

        assertNull(viewModel.uiState.value.skippable)
    }

    @Test
    fun undoAndSkipAnswerWhereToJumpAndClearWhatShowed() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, segments, 681_500)

        assertEquals(681_274L, viewModel.undoSkip())
        assertNull(viewModel.uiState.value.skipNote)
        assertNull(viewModel.segmentTick(0, segments, 682_000))
        viewModel.segmentTick(0, segments, 2_000)
        assertEquals(134_034L, viewModel.skipPrompted())
        assertNull(viewModel.uiState.value.skipPrompt)
        assertEquals(134_034L, viewModel.uiState.value.skipNote?.span?.endMs)
        viewModel.dismissSkipNote()
        viewModel.segmentTick(0, segments, 140_000)
        viewModel.segmentTick(0, segments, 60_000)
        assertEquals(60_000L, viewModel.uiState.value.skipPrompt?.shownAtMs)
        viewModel.dismissSkipPrompt()

        assertNull(viewModel.uiState.value.skipPrompt)
    }

    /** Q36, Q37: the times the screen gives hold for the turn's skips when given before they are built. */
    @Test
    fun theSkipTimesTheScreenGivesHoldForTheTurn() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.skipTimes(promptMs = 20_000, noteKeptMs = 20_000)

        viewModel.segmentTick(0, segments, 1_000)
        assertEquals(21_000L, viewModel.uiState.value.skipPrompt?.untilMs)
        viewModel.segmentTick(0, segments, 681_500)
        viewModel.skipPromptInView(false, 687_665)
        viewModel.segmentTick(0, segments, 697_000)

        assertEquals(listOf(SegmentCategory.Interaction), viewModel.uiState.value.skipNote?.span?.categories)
    }

    /** Q36: times given once a turn's skips exist take effect from the next turn, and stay for later ones. */
    @Test
    fun skipTimesGivenMidTurnApplyFromTheNextTurnOn() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, segments, 1_000)

        viewModel.skipTimes(promptMs = 20_000, noteKeptMs = SegmentSkips.NOTE_KEPT_MS)
        viewModel.segmentTick(0, segments, 2_000)
        assertEquals(9_000L, viewModel.uiState.value.skipPrompt?.untilMs)
        viewModel.play(testVideo("bbbbbbbbbbb"))
        viewModel.segmentTick(1, segments, 1_000)

        assertEquals(21_000L, viewModel.uiState.value.skipPrompt?.untilMs)
    }

    /** PAF-D2: the screen says the prompt's place is out of view before the turn's skips exist. */
    @Test
    fun aPromptHiddenByTheScreenHoldsItsTimeAcrossANewTurn() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.skipPromptInView(false, 0)
        viewModel.play(testVideo("bbbbbbbbbbb"))

        viewModel.segmentTick(1, segments, 1_000)
        viewModel.segmentTick(1, segments, 20_000)
        assertEquals(1_000L, viewModel.uiState.value.skipPrompt?.shownAtMs)
        viewModel.skipPromptInView(true, 20_000)

        assertEquals(SkipPrompt(SkipSpan(0, 134_034, listOf(SegmentCategory.Preview, SegmentCategory.Intro, SegmentCategory.Sponsor)), 20_000, 28_000), viewModel.uiState.value.skipPrompt)
    }

    @Test
    fun nothingIsSkippedBeforeTheSettingsAreRead() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        settings.selected.value = null
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        assertNull(viewModel.segmentTick(0, segments, 681_500))
        assertNull(viewModel.uiState.value.skipNote)
        settings.selected.value = PROFILE
        advanceUntilIdle()

        assertEquals(687_665L, viewModel.segmentTick(0, segments, 681_500))
    }

    @Test
    fun aProfileWithTheSkipNoteOffSkipsWithoutANote() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(skipNote = false))
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()

        assertEquals(687_665L, viewModel.segmentTick(0, segments, 681_500))
        assertNull(viewModel.uiState.value.skipNote)
    }

    @Test
    fun aTickForAnotherTurnIsDroppedAndANewTurnClearsThePrompt() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, segments, 1_000)
        assertEquals(1_000L, viewModel.uiState.value.skipPrompt?.shownAtMs)

        viewModel.play(testVideo("bbbbbbbbbbb"))
        assertNull(viewModel.uiState.value.skipPrompt)

        assertNull(viewModel.segmentTick(0, segments, 681_500))
        assertNull(viewModel.uiState.value.skipNote)
        assertEquals(687_665L, viewModel.segmentTick(1, segments, 681_500))
    }

    @Test
    fun aVideoWithoutSegmentsPromptsNothingAndLosingThemHidesThePromptAndTheNote() = runTest(dispatcher) {
        val segments = RecordedSegments.lookup()
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, segments, 1_000)
        assertEquals(1_000L, viewModel.uiState.value.skipPrompt?.shownAtMs)

        assertNull(viewModel.segmentTick(0, SegmentLookup.Failed, 1_500))
        assertNull(viewModel.uiState.value.skipPrompt)
        assertEquals(687_665L, viewModel.segmentTick(0, segments, 681_500))
        assertEquals(listOf(SegmentCategory.Interaction), viewModel.uiState.value.skipNote?.span?.categories)
        assertNull(viewModel.segmentTick(0, SegmentLookup.Failed, 687_665))
        assertNull(viewModel.uiState.value.skipNote)
        assertNull(viewModel.segmentTick(0, SegmentLookup.None, 681_500))
        assertNull(viewModel.segmentTick(0, null, 681_500))
    }

    /** A masked item (Try again) or a re-resolve hands the screen no lookup, then the same segments again. */
    @Test
    fun anUndoHoldsWhenTheSameSegmentsComeBackInTheTurn() = runTest(dispatcher) {
        val viewModel = player(testVideo("aaaaaaaaaaa"))
        advanceUntilIdle()
        viewModel.segmentTick(0, RecordedSegments.lookup(), 681_500)
        viewModel.undoSkip()

        viewModel.segmentTick(0, null, 681_274)

        assertNull(viewModel.segmentTick(0, RecordedSegments.lookup(), 682_000))
    }

    @Test
    fun playAllsNextVideoClearsThePromptAndTheVotes() = runTest(dispatcher) {
        val (first, second) = listOf(testVideo(RecordedVotes.VIDEO.value), testVideo("bbbbbbbbbbb"))
        val id = playlists.seed("Tiny Desk favourites", first, second)
        val viewModel = player(first, PlayAll.Saved(id, "Tiny Desk favourites", 2))
        advanceUntilIdle()
        viewModel.segmentTick(0, RecordedSegments.lookup(), 1_000)
        viewModel.openDetails()
        advanceUntilIdle()
        assertEquals(1_000L, viewModel.uiState.value.skipPrompt?.shownAtMs)
        assertEquals(DetailsVotes.Found(RecordedVotes.DISLIKES), viewModel.uiState.value.votes)

        viewModel.endTurn()
        advanceUntilIdle()

        assertEquals(second.id, viewModel.uiState.value.video.id)
        assertNull(viewModel.uiState.value.skipPrompt)
        assertNull(viewModel.uiState.value.votes)
    }

    @Test
    fun detailsAsksForTheRecordedVotesOnceInATurn() = runTest(dispatcher) {
        val viewModel = player(testVideo(RecordedVotes.VIDEO.value))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.votes)

        viewModel.openDetails()
        assertEquals(DetailsVotes.Loading, viewModel.uiState.value.votes)
        advanceUntilIdle()
        viewModel.openDetails()
        advanceUntilIdle()

        assertEquals(DetailsVotes.Found(RecordedVotes.DISLIKES), viewModel.uiState.value.votes)
        assertEquals(listOf(RecordedVotes.VIDEO), votesAsked)
    }

    @Test
    fun aVideoReturnYouTubeDislikeDoesNotKnowHasNoDislikeData() = runTest(dispatcher) {
        val viewModel = player(testVideo(RecordedVotes.UNKNOWN.value))
        advanceUntilIdle()

        viewModel.openDetails()
        advanceUntilIdle()

        assertEquals(DetailsVotes.NoData, viewModel.uiState.value.votes)
    }

    @Test
    fun aFailedLookupHasNoDislikeData() = runTest(dispatcher) {
        votes = VoteSource { VoteLookup.Failed }
        val viewModel = player(testVideo(RecordedVotes.VIDEO.value))
        advanceUntilIdle()

        viewModel.openDetails()
        advanceUntilIdle()

        assertEquals(DetailsVotes.NoData, viewModel.uiState.value.votes)
    }

    @Test
    fun dislikesOffAsksNothing() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(dislikes = false))
        val viewModel = player(testVideo(RecordedVotes.VIDEO.value))
        advanceUntilIdle()

        viewModel.openDetails()
        advanceUntilIdle()

        assertEquals(DetailsVotes.Off, viewModel.uiState.value.votes)
        assertEquals(emptyList<VideoId>(), votesAsked)
    }

    @Test
    fun settingsThatCannotBeReadAskNothing() = runTest(dispatcher) {
        settingsStore = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = flow { throw IOException("disk") }
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings) = false
        }
        val viewModel = player(testVideo(RecordedVotes.VIDEO.value))
        advanceUntilIdle()

        viewModel.openDetails()
        advanceUntilIdle()

        assertEquals(DetailsVotes.Off, viewModel.uiState.value.votes)
        assertEquals(emptyList<VideoId>(), votesAsked)
    }

    @Test
    fun aNewTurnDropsTheVotesAndALateAnswerForTheLastVideo() = runTest(dispatcher) {
        val answer = CompletableDeferred<VoteLookup>()
        votes = VoteSource { id ->
            votesAsked += id
            withContext(NonCancellable) { answer.await() }
        }
        val viewModel = player(testVideo(RecordedVotes.VIDEO.value))
        advanceUntilIdle()
        viewModel.openDetails()
        advanceUntilIdle()

        viewModel.play(testVideo(RecordedVotes.UNKNOWN.value))
        answer.complete(VoteLookup.Found(RecordedVotes.DISLIKES))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.votes)

        viewModel.openDetails()
        advanceUntilIdle()
        assertEquals(listOf(RecordedVotes.VIDEO, RecordedVotes.UNKNOWN), votesAsked)
    }

    /** The screen's report that the playing turn's video ended. */
    private fun PlayerViewModel.endTurn() = ended(uiState.value.turn)

    private fun ProfileSettings.withAction(category: SegmentCategory, action: SegmentAction): ProfileSettings =
        copy(segmentActions = segmentActions + (category to action))

    private class UnreachablePlaylist : UnsupportedExtractor("player") {
        override suspend fun playlist(playlistUrl: String) = throw AccessException("offline", IOException("no route"))
    }

    /** A two-video playlist with no further pages. */
    private class TwoVideos(private vararg val videos: VideoItem) : UnsupportedExtractor("player") {
        override suspend fun playlist(playlistUrl: String) = PlaylistPage(
            details = PlaylistDetails(
                title = "two", uploaderName = null, uploaderUrl = null, uploaderAvatarUrl = null,
                videoCount = videos.size.toLong(), thumbnailUrl = null,
            ),
            videos = ItemsPage(items = videos.toList(), next = null),
        )
    }

    /** The recorded first page; the second waits for [gate] past any cancellation, as extraction
     *  does, then arrives ([pageLands]) or fails. */
    private class GatedSecondPage(private val gate: CompletableDeferred<Unit>, private val pageLands: Boolean) : UnsupportedExtractor("player") {
        override suspend fun playlist(playlistUrl: String) = recorded(PAGED_PLAYLIST_FLOW) { playlist(playlistUrl) }

        override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage {
            withContext(NonCancellable) { gate.await() }
            if (!pageLands) throw AccessException("offline", IOException("no route"))
            return ItemsPage(items = listOf(testVideo("nnnnnnnnnnn")), next = null)
        }
    }

    /** The recorded first page; every later page fails. */
    private class FirstPageOnly : UnsupportedExtractor("player") {
        override suspend fun playlist(playlistUrl: String) = recorded(PAGED_PLAYLIST_FLOW) { playlist(playlistUrl) }

        override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage =
            throw AccessException("offline", IOException("no route"))
    }
}

private const val PROFILE = 1L
