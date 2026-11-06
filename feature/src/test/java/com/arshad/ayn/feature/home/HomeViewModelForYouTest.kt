package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.FetchAttempt
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.library.watchEntry
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * Home's For you (FY-D7, FY-D9, FY-D10, FY-D5) against fakes: ranked when Home becomes visible and
 * never in between, its first pick the pinned hero, the subscription rows giving way, the
 * profile's switch, the hero's page as one budgeted request, and the top-up of seeds without a
 * list ([com.arshad.ayn.feature.foryou.ForYouRefresher]). One watched seed's related list
 * ranks in its own order (every channel new, places decaying), so expected picks read off the list.
 */
class HomeViewModelForYouTest {

    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakeHomeExtractor()
    private val feeds = FakeFeedExtractor()
    private val store = FakeSubscriptionStore()
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val related = FakeRelatedListStore()
    private val forYouStore = FakeForYouStore()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private val clock = TestClock(NOW)
    private val attempts = FakeFetchAttemptLog(clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun forYouWaitsForHomeToBecomeVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.forYou)
    }

    /** PAF-D5: a new Home holds its hero until For you's first ranking lands. */
    @Test
    fun aNewHomeHoldsTheHeroUntilForYousFirstRanking() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()
        advanceTimeBy(HomeViewModel.HeroHoldMs - 1)
        assertEquals("aaaaaaaaaa1", ready(viewModel.uiState.value).hero!!.video.id.value)
        assertEquals(HomeLayout.Loading, homeLayout(viewModel.uiState.value))

        viewModel.onVisible()
        runCurrent()

        assertFalse(viewModel.uiState.value.heroHeld)
        assertEquals("aaaaaaaaaa1", ((homeLayout(viewModel.uiState.value) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video.id.value)
    }

    @Test
    fun withoutARankingTheHeroHoldsForASecond() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()

        advanceTimeBy(HomeViewModel.HeroHoldMs - 1)
        assertTrue(viewModel.uiState.value.heroHeld)
        advanceTimeBy(2)

        assertFalse(viewModel.uiState.value.heroHeld)
        assertEquals("aaaaaaaaaa1", ((homeLayout(viewModel.uiState.value) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video.id.value)
    }

    @Test
    fun aHeldHomeOpensOnForYousHeroWithoutTheSubscriptionHeroFirst() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = newViewModel()
        advanceTimeBy(HomeViewModel.HeroHoldMs - 1)
        assertEquals(HomeLayout.Loading, homeLayout(viewModel.uiState.value))

        viewModel.onVisible()
        runCurrent()

        assertEquals("fy000000001", ((homeLayout(viewModel.uiState.value) as HomeLayout.Browse).top as HomeTop.HeroTop).hero.video.id.value)
    }

    @Test
    fun theFirstPickIsTheHeroAndTheRestTheRow() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")

        val forYou = visible().uiState.value.forYou!!

        assertEquals("fy000000001", forYou.hero.video.id.value)
        assertEquals(ForYouReason(VideoId(SEED), SeedKind.Watched, "title $SEED"), forYou.reason)
        assertEquals(listOf("fy000000002", "fy000000003"), forYou.row.map { it.video.id.value })
    }

    @Test
    fun forYouHoldsTheHeroAndTheNewestUploadLeadsNewFromSubscriptions() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))

        val state = visible().uiState.value

        assertEquals("fy000000001", state.hero!!.video.id.value)
        assertNull(ready(state).hero)
        assertEquals(listOf("aaaaaaaaaa1", "bbbbbbbbbb1"), ready(state).newFromSubscriptions.map { it.id.value })
    }

    @Test
    fun newFromSubscriptionsPassesOverWhatForYouShows() = runTest(dispatcher) {
        watchedSeed(SEED, "aaaaaaaaaa1", "bbbbbbbbbb1")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-25T10:00:00Z"))
        subscribe(CHANNEL_B, upload("bbbbbbbbbb1", "2026-06-26T10:00:00Z"))

        val subscriptions = ready(visible().uiState.value)

        assertEquals("the hero's channel shows its next upload; the row's has none left", listOf("aaaaaaaaaa2"), subscriptions.newFromSubscriptions.map { it.id.value })
    }

    @Test
    fun noCandidatesLeaveTodaysHero() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED)) // a seed without a kept list
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))

        val state = visible().uiState.value

        assertNull(state.forYou)
        assertEquals("aaaaaaaaaa1", state.hero!!.video.id.value)
    }

    @Test
    fun forYouShowsWithNoSubscriptions() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")

        val state = visible().uiState.value

        assertEquals(SubscriptionsState.NoSubscriptions, state.subscriptions)
        assertEquals("fy000000001", state.hero!!.video.id.value)
    }

    @Test
    fun aListThatArrivesWhileHomeShowsWaitsForTheNextVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        keep(SEED, "fy000000001", "fy000000003", "fy000000002")
        advanceUntilIdle()
        assertEquals(listOf("fy000000002"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })

        viewModel.onVisible()
        advanceUntilIdle()
        assertEquals(listOf("fy000000003", "fy000000002"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
    }

    @Test
    fun theHeroStaysPinnedWhenABetterPickArrives() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        keep(SEED, "fy000000009", "fy000000001", "fy000000002")
        viewModel.onVisible()
        advanceUntilIdle()

        val forYou = viewModel.uiState.value.forYou!!
        assertEquals("fy000000001", forYou.hero.video.id.value)
        assertEquals(listOf("fy000000009", "fy000000002"), forYou.row.map { it.video.id.value })
    }

    @Test
    fun theHeroHandsOverOnceOpenedAtTheNextVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        watch.entries.value += watchEntry("fy000000001", lastWatchedAtMillis = NOW.toEpochMilliseconds())
        advanceUntilIdle()
        assertEquals("fy000000001", viewModel.uiState.value.forYou!!.hero.video.id.value)

        viewModel.onVisible()
        advanceUntilIdle()
        assertEquals("fy000000002", viewModel.uiState.value.forYou!!.hero.video.id.value)
    }

    @Test
    fun theHeroHandsOverAfterSixHours() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()
        keep(SEED, "fy000000009", "fy000000001", "fy000000002")

        clock.current = NOW + 6.hours
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals("fy000000009", viewModel.uiState.value.forYou!!.hero.video.id.value)
    }

    @Test
    fun aHiddenVideoLeavesForYou() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        forYouStore.hiddenVideoIds.value = setOf(VideoId("fy000000001"))

        assertEquals("fy000000002", visible().uiState.value.forYou!!.hero.video.id.value)
    }

    @Test
    fun forYouOffShowsNoneAndAsksNothing() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        settings.byProfile.value = mapOf(1L to ProfileSettings(forYou = false))
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))

        val state = visible().uiState.value

        assertNull(state.forYou)
        assertEquals("aaaaaaaaaa1", state.hero!!.video.id.value)
        assertEquals("only the subscription hero's page", listOf("aaaaaaaaaa1"), extractor.asked)
        assertEquals(emptyList<FetchAttempt>(), attempts.attempts)
    }

    /** FY-D5, FY-D9: a ranking's seed without a list is fetched once For you shows, without
     *  holding it up; the list waits for the next ranking. */
    @Test
    fun aSeedWithoutAListIsFetchedAfterForYouShows() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED), seedEntry(OTHER_SEED))
        keep(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001")
        extractor.relatedAnswers[OTHER_SEED] = Result.success(listOf(testVideo("fy000000009")))
        val gate = CompletableDeferred<Unit>()
        extractor.relatedGate = gate

        val viewModel = visible()
        val shown = viewModel.uiState.value.forYou!!.let { listOf(it.hero.video.id.value) + it.row.map { pick -> pick.video.id.value } }
        assertEquals(listOf("fy000000001", "fy000000002"), shown)
        assertEquals(listOf(OTHER_SEED), extractor.relatedAsked)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(VideoId(OTHER_SEED) to listOf(testVideo("fy000000009")) in related.recorded)
        assertEquals(listOf(FetchOutcome.Fetched, FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
        val after = viewModel.uiState.value.forYou!!.let { listOf(it.hero.video.id.value) + it.row.map { pick -> pick.video.id.value } }
        assertEquals(shown, after)
    }

    /** The one-at-a-time guard holds only while a run is active. */
    @Test
    fun aVisitAfterATopUpFinishedStartsTheNext() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        extractor.relatedAnswers[SEED] = Result.success(listOf(testVideo("fy000000001")))
        extractor.relatedAnswers[OTHER_SEED] = Result.success(listOf(testVideo("fy000000002")))
        val viewModel = visible()

        keep(SEED, "fy000000001")
        watch.entries.value += seedEntry(OTHER_SEED)
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals(listOf(SEED, OTHER_SEED), extractor.relatedAsked)
    }

    @Test
    fun forYouOffFetchesNoList() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        extractor.relatedAnswers[SEED] = Result.success(listOf(testVideo("fy000000001")))
        settings.byProfile.value = mapOf(1L to ProfileSettings(forYou = false))

        visible()

        assertEquals(emptyList<String>(), extractor.relatedAsked)
    }

    /** A visit while a top-up is in flight neither cuts it off (a failure on the log) nor starts
     *  a second one. */
    @Test
    fun aNewVisitLeavesATopUpInFlightAlone() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        extractor.relatedAnswers[SEED] = Result.success(listOf(testVideo("fy000000001")))
        val gate = CompletableDeferred<Unit>()
        extractor.relatedGate = gate
        val viewModel = visible()

        viewModel.onVisible()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(SEED), extractor.relatedAsked)
        assertEquals(listOf(FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
    }

    @Test
    fun turningForYouOffTakesItAwayAtTheNextVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = visible()

        settings.byProfile.value = mapOf(1L to ProfileSettings(forYou = false))
        viewModel.onVisible()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.forYou)
        assertEquals("aaaaaaaaaa1", viewModel.uiState.value.hero!!.video.id.value)
    }

    /** FY-D7: For you never waits on the subscription feed; the feed's rows give way when it lands. */
    @Test
    fun forYouShowsWhileTheFeedLoadsAndTheFeedsRowsGiveWayWhenItLands() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "aaaaaaaaaa1")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"), upload("aaaaaaaaaa2", "2026-06-25T10:00:00Z"))
        val gate = CompletableDeferred<Unit>()
        feeds.gateCall = 1
        feeds.gate = gate
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onVisible()
        advanceUntilIdle()
        assertEquals(SubscriptionsState.Loading, viewModel.uiState.value.subscriptions)
        assertEquals("fy000000001", viewModel.uiState.value.hero!!.video.id.value)

        gate.complete(Unit)
        advanceUntilIdle()
        val subscriptions = ready(viewModel.uiState.value)
        assertNull(subscriptions.hero)
        assertEquals(listOf("aaaaaaaaaa2"), subscriptions.newFromSubscriptions.map { it.id.value })
    }

    /** A ranking that fails isn't one of FY-D7's endings: the pinned hero comes back. */
    @Test
    fun aFailedRankingKeepsThePinnedHero() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()
        keep(SEED, "fy000000009", "fy000000001", "fy000000002")

        forYouStore.failReads = true
        viewModel.onVisible()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.forYou)
        forYouStore.failReads = false
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals("fy000000001", viewModel.uiState.value.forYou!!.hero.video.id.value)
    }

    @Test
    fun anUnreadableStoreShowsNoForYou() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        forYouStore.failReads = true

        assertNull(visible().uiState.value.forYou)
    }

    /** The ViewModel's hero is what shows: Watch later never saves a refused video. */
    @Test
    fun aHeroWhosePageTheFilterRefusesGivesWayToTheNextPick() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        extractor.filtered += "fy000000001"
        extractor.pages["fy000000002"] = videoPage("fy000000002", description = "two")

        val viewModel = visible()
        val forYou = viewModel.uiState.value.forYou!!
        // Shown long enough to count: an unpinned hero would rotate out at the next visit (FY-D7).
        viewModel.onForYouShown(forYou.hero.video)
        advanceUntilIdle()
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals("fy000000002", forYou.hero.video.id.value)
        assertEquals(HeroPage.Ready(description = "two", format = null), forYou.hero.page)
        assertEquals("the next visit leaves it out", "fy000000002", viewModel.uiState.value.forYou!!.hero.video.id.value)
        assertEquals(1, extractor.asked.count { it == "fy000000001" })
    }

    @Test
    fun theHerosPageIsOneRecordedRequest() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        val relatedVideos = listOf(testVideo("rrrrrrrrrr1"))
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb", relatedVideos = relatedVideos)

        val hero = visible().uiState.value.forYou!!.hero

        assertEquals(HeroPage.Ready(description = "blurb", format = null), hero.page)
        assertEquals(listOf(FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
        assertEquals(listOf(VideoId("fy000000001") to relatedVideos), related.recorded)
    }

    @Test
    fun aFailedHeroPageIsRecordedAndUnavailable() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001") // no page: the fetch fails

        val hero = visible().uiState.value.forYou!!.hero

        assertEquals(HeroPage.Unavailable, hero.page)
        assertEquals(listOf(FetchOutcome.Failed), attempts.attempts.map { it.outcome })
    }

    @Test
    fun aStoppedBudgetSkipsTheHerosPage() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb")
        attempts.attempts += FetchAttempt(NOW - 10.minutes, FetchOutcome.RateLimited)

        val hero = visible().uiState.value.forYou!!.hero

        assertEquals(HeroPage.Unavailable, hero.page)
        assertEquals(emptyList<String>(), extractor.asked)
        assertEquals(1, attempts.attempts.size)
    }

    @Test
    fun anUnreadableAttemptLogSkipsTheHerosPage() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb")
        attempts.failReads = true

        val hero = visible().uiState.value.forYou!!.hero

        assertEquals(HeroPage.Unavailable, hero.page)
        assertEquals(emptyList<String>(), extractor.asked)
        assertEquals(emptyList<FetchAttempt>(), attempts.attempts)
    }

    /** The replaced hero's request finishes and its page is dropped; only the new hero's page lands. */
    @Test
    fun aHeroReplacedWhileItsPageLoadsIsDressedWithItsOwnPage() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "one")
        extractor.pages["fy000000002"] = videoPage("fy000000002", description = "two")
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate
        val viewModel = visible()

        watch.entries.value += watchEntry("fy000000001", lastWatchedAtMillis = NOW.toEpochMilliseconds())
        viewModel.onVisible()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val hero = viewModel.uiState.value.forYou!!.hero
        assertEquals("fy000000002", hero.video.id.value)
        assertEquals(HeroPage.Ready(description = "two", format = null), hero.page)
        assertEquals(listOf(FetchOutcome.Fetched, FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
    }

    @Test
    fun aKeptHeroKeepsItsPageWithoutAnotherRequest() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb")
        val viewModel = visible()

        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals(HeroPage.Ready(description = "blurb", format = null), viewModel.uiState.value.forYou!!.hero.page)
        assertEquals(1, attempts.attempts.size)
    }

    @Test
    fun aFailedHeroPageIsTriedAgainAtTheNextVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        val viewModel = visible()

        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb")
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals(HeroPage.Ready(description = "blurb", format = null), viewModel.uiState.value.forYou!!.hero.page)
        assertEquals(listOf(FetchOutcome.Failed, FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
    }

    @Test
    fun watchLaterFollowsForYousHero() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        val viewModel = visible()

        viewModel.onHeroWatchLater()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.heroInWatchLater)
        assertEquals(listOf("fy000000001"), playlists.watchLater().first().map { it.video.id.value })
    }

    /** FY-D2: with under 5 watches, the loaded feed's uploads seed For you. */
    @Test
    fun theLoadedFeedsUploadsSeedAProfileWithFewWatches() = runTest(dispatcher) {
        subscribe(CHANNEL_A, upload("aaaaaaaaaa1", "2026-06-27T10:00:00Z"))
        keep("aaaaaaaaaa1", "fy000000001")

        val forYou = visible().uiState.value.forYou!!

        assertEquals("fy000000001", forYou.hero.video.id.value)
        assertEquals(ForYouReason(VideoId("aaaaaaaaaa1"), SeedKind.Followed, "uploader"), forYou.reason)
    }

    /** The fake dates saves from 1970, so the saved seed weighs next to nothing: its card comes
     *  after the watched seed's. */
    @Test
    fun aPlaylistsVideosSeedForYou() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        playlists.seed("Trips", testVideo("pppppppppp1"))
        keep("pppppppppp1", "fy000000009")

        val row = visible().uiState.value.forYou!!.row

        assertEquals(listOf("fy000000009"), row.map { it.video.id.value })
        assertEquals(ForYouReason(VideoId("pppppppppp1"), SeedKind.Saved, "title pppppppppp1"), row.single().reason)
    }

    /** FY-D11: Not interested takes the card out at once; the next pick takes its place. */
    @Test
    fun notInterestedTakesTheCardOutAndShowsItsNotice() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000002"), ForYouHide.Video)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("fy000000003"), state.forYou!!.row.map { it.video.id.value })
        assertEquals(VideoId("fy000000003"), state.forYouHidden!!.takesPlace)
        assertEquals(setOf(VideoId("fy000000002")), forYouStore.hiddenVideoIds.value)
    }

    @Test
    fun hidingTheLastCardHandsItsPlaceToTheOneBefore() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000003"), ForYouHide.Video)

        assertEquals(VideoId("fy000000002"), viewModel.uiState.value.forYouHidden!!.takesPlace)
    }

    @Test
    fun hidingTheHeroHandsTheHeroToTheNextPick() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        advanceUntilIdle()

        val forYou = viewModel.uiState.value.forYou!!
        assertEquals("fy000000002", forYou.hero.video.id.value)
        assertEquals(listOf("fy000000003"), forYou.row.map { it.video.id.value })
        assertEquals(VideoId("fy000000002"), viewModel.uiState.value.forYouHidden!!.takesPlace)
    }

    @Test
    fun dontRecommendThisChannelTakesOutEveryCardOfTheChannel() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        related.kept[VideoId(SEED)] = RelatedList(
            listOf(testVideo("fy000000001"), onChannel("fy000000002", CHANNEL_A), testVideo("fy000000003"), onChannel("fy000000004", CHANNEL_A)),
            fetchedAt = NOW,
            failedAt = null,
        )
        val viewModel = visible()

        viewModel.onHide(onChannel("fy000000002", CHANNEL_A), ForYouHide.Channel)
        advanceUntilIdle()

        assertEquals(listOf("fy000000003"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
        assertEquals(VideoId("fy000000003"), viewModel.uiState.value.forYouHidden!!.takesPlace)
        assertEquals(setOf(CHANNEL_A), forYouStore.hiddenChannelUrls.value)
    }

    @Test
    fun undoOfAChannelHideBringsBackItsCardsAndUnhidesIt() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        related.kept[VideoId(SEED)] = RelatedList(
            listOf(testVideo("fy000000001"), onChannel("fy000000002", CHANNEL_A), testVideo("fy000000003"), onChannel("fy000000004", CHANNEL_A)),
            fetchedAt = NOW,
            failedAt = null,
        )
        val viewModel = visible()

        viewModel.onHide(onChannel("fy000000002", CHANNEL_A), ForYouHide.Channel)
        advanceUntilIdle()
        viewModel.onUndoHide()
        advanceUntilIdle()

        assertEquals(listOf("fy000000002", "fy000000003", "fy000000004"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
        assertEquals(emptySet<String>(), forYouStore.hiddenChannelUrls.value)
    }

    @Test
    fun hidingTheOnlyPickLeavesNoForYou() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.forYou)
        assertNull(viewModel.uiState.value.forYouHidden!!.takesPlace)
    }

    /** The pin isn't touched by a hide: the next ranking drops the hidden hero itself. */
    @Test
    fun aHiddenHeroIsNotPinnedAtTheNextVisible() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        viewModel.onHiddenNoticeClosed()
        advanceUntilIdle()
        viewModel.onVisible()
        advanceUntilIdle()

        assertEquals("fy000000002", viewModel.uiState.value.forYou!!.hero.video.id.value)
    }

    /** A ranking that read the hidden videos before a hide was written still leaves the hidden card out. */
    @Test
    fun aHideMadeWhileARankingRunsHolds() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        val viewModel = visible()
        val gate = CompletableDeferred<Unit>()
        forYouStore.hiddenChannelsGate = gate
        viewModel.onVisible()
        advanceUntilIdle() // the ranking has read the hidden videos and waits on the hidden channels

        viewModel.onHide(testVideo("fy000000002"), ForYouHide.Video)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fy000000003"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
    }

    @Test
    fun undoPutsTheCardBackAndUnhidesIt() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002", "fy000000003")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "blurb")
        val viewModel = visible()
        val before = viewModel.uiState.value.forYou

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        viewModel.onUndoHide()
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.forYou)
        assertNull(viewModel.uiState.value.forYouHidden)
        assertEquals(emptySet<VideoId>(), forYouStore.hiddenVideoIds.value)
    }

    /** A ranking that read the hidden channels after the hide's write already leaves them out; Undo still brings them back. */
    @Test
    fun undoOfAHideWrittenBeforeARankingReadItBringsTheCardsBack() = runTest(dispatcher) {
        watch.entries.value = listOf(seedEntry(SEED))
        related.kept[VideoId(SEED)] = RelatedList(
            listOf(testVideo("fy000000001"), onChannel("fy000000002", CHANNEL_A), testVideo("fy000000003"), onChannel("fy000000004", CHANNEL_A)),
            fetchedAt = NOW,
            failedAt = null,
        )
        val viewModel = visible()
        val gate = CompletableDeferred<Unit>()
        forYouStore.hiddenChannelsGate = gate
        viewModel.onVisible()
        advanceUntilIdle() // the ranking waits on the hidden channels
        viewModel.onHide(onChannel("fy000000002", CHANNEL_A), ForYouHide.Channel)
        advanceUntilIdle() // the hide is written before the ranking reads it
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.onUndoHide()
        advanceUntilIdle()

        assertEquals(listOf("fy000000002", "fy000000003", "fy000000004"), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
    }

    /** A hero hidden while its own page loads gets that page for its Undo: no second request. */
    @Test
    fun undoOfAHeroHiddenWhileItsPageLoadedNeedsNoSecondRequest() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "one")
        extractor.pages["fy000000002"] = videoPage("fy000000002", description = "two")
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate
        val viewModel = visible() // the hero's page waits on the gate

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        gate.complete(Unit)
        advanceUntilIdle()
        viewModel.onUndoHide()
        advanceUntilIdle()

        assertEquals(HeroPage.Ready(description = "one", format = null), viewModel.uiState.value.forYou!!.hero.page)
        assertEquals(2, attempts.attempts.size)
    }

    /** Undo after the next hero was dressed brings the hidden hero back with its own page, no request again. */
    @Test
    fun undoAfterTheNextHeroWasDressedRestoresTheHeroWithItsPage() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "one")
        extractor.pages["fy000000002"] = videoPage("fy000000002", description = "two")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        advanceUntilIdle()
        viewModel.onUndoHide()
        advanceUntilIdle()

        val hero = viewModel.uiState.value.forYou!!.hero
        assertEquals("fy000000001", hero.video.id.value)
        assertEquals(HeroPage.Ready(description = "one", format = null), hero.page)
        assertEquals(2, attempts.attempts.size)
    }

    /** Undo while the next hero's page loads lets that request finish: no failed attempt toward the stop. */
    @Test
    fun undoWhileTheNextHerosPageLoadsCountsNoFailedAttempt() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        extractor.pages["fy000000001"] = videoPage("fy000000001", description = "one")
        extractor.pages["fy000000002"] = videoPage("fy000000002", description = "two")
        val viewModel = visible()
        val gate = CompletableDeferred<Unit>()
        extractor.videoGate = gate

        viewModel.onHide(testVideo("fy000000001"), ForYouHide.Video)
        advanceUntilIdle() // the next hero's page waits on the gate
        viewModel.onUndoHide()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(FetchOutcome.Fetched, FetchOutcome.Fetched), attempts.attempts.map { it.outcome })
    }

    /** Undo waits for the hide's own write, so the store ends unhidden. */
    @Test
    fun undoLandsAfterTheHideItTakesBack() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()
        val gate = CompletableDeferred<Unit>()
        forYouStore.hideGate = gate

        viewModel.onHide(testVideo("fy000000002"), ForYouHide.Video)
        viewModel.onUndoHide()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptySet<VideoId>(), forYouStore.hiddenVideoIds.value)
    }

    @Test
    fun theNoticeClosesWithoutTakingTheHideBack() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000002"), ForYouHide.Video)
        viewModel.onHiddenNoticeClosed()
        viewModel.onUndoHide()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.forYou!!.row.map { it.video.id.value })
        assertEquals(setOf(VideoId("fy000000002")), forYouStore.hiddenVideoIds.value)
    }

    /** FY-D3: a card is recorded as shown once per Home visit; videos For you doesn't show aren't. */
    @Test
    fun showsAreRecordedOncePerVisit() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        viewModel.onForYouShown(testVideo("fy000000001"))
        viewModel.onForYouShown(testVideo("zzzzzzzzzz1"))
        viewModel.onForYouShown(testVideo("fy000000001"))
        viewModel.onForYouShown(testVideo("fy000000002"))
        advanceUntilIdle()
        viewModel.onVisible()
        advanceUntilIdle()
        viewModel.onForYouShown(testVideo("fy000000001"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("fy000000001"), listOf("fy000000002"), listOf("fy000000001")), forYouStore.shown.map { cards -> cards.map { it.id.value } })
    }

    @Test
    fun openingAPickIsRecordedAndAnyOtherVideoIsNot() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        viewModel.onOpen(testVideo("fy000000002"))
        viewModel.onOpen(testVideo("zzzzzzzzzz1"))
        advanceUntilIdle()

        assertEquals(listOf("fy000000002"), forYouStore.opened.map { it.id.value })
    }

    @Test
    fun becomingVisibleDropsTheNotice() = runTest(dispatcher) {
        watchedSeed(SEED, "fy000000001", "fy000000002")
        val viewModel = visible()

        viewModel.onHide(testVideo("fy000000002"), ForYouHide.Video)
        viewModel.onVisible()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.forYouHidden)
    }

    private fun onChannel(id: String, channelUrl: String): VideoItem = testVideo(id).copy(uploaderUrl = channelUrl)

    /** Home after its first load, then made visible. */
    private fun TestScope.visible(): HomeViewModel {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onVisible()
        advanceUntilIdle()
        return viewModel
    }

    private fun newViewModel(): HomeViewModel =
        homeViewModel(extractor, SubscriptionsFeed(feeds, clock), store, watch, playlists, related, clock, forYouStore, settings, attempts)

    /** [seedId] watched an hour ago, its related list holding [candidates] in order. */
    private fun watchedSeed(seedId: String, vararg candidates: String) {
        watch.entries.value = listOf(seedEntry(seedId))
        keep(seedId, *candidates)
    }

    private fun keep(seedId: String, vararg candidates: String) {
        related.kept[VideoId(seedId)] = RelatedList(candidates.map(::testVideo), fetchedAt = NOW, failedAt = null)
    }

    private fun subscribe(url: String, vararg uploads: VideoItem) {
        feeds.feeds[url] = ItemsPage(uploads.toList(), next = null)
        store.rows.value = store.rows.value + testSubscription(url, name = url.substringAfterLast('/'))
    }

    private fun ready(state: HomeUiState): SubscriptionsState.Ready = state.subscriptions as SubscriptionsState.Ready

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-28T00:00:00Z")
        const val SEED = "seed0000001"
        const val OTHER_SEED = "seed0000002"
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCb"

        fun seedEntry(id: String) =
            watchEntry(id, lastWatchedAtMillis = (NOW - 1.hours).toEpochMilliseconds(), durationMs = 600_000, watched = true)

        fun upload(id: String, uploaded: String): VideoItem =
            testVideo(id).copy(uploadDate = Instant.parse(uploaded), durationSeconds = -1)

        fun videoPage(id: String, description: String? = null, relatedVideos: List<VideoItem> = emptyList()) = VideoMetadata(
            id = VideoId(id), title = "title $id", uploaderName = "uploader", durationSeconds = 600,
            description = description, relatedVideos = relatedVideos, hlsUrl = null, dashUrl = null, streams = emptyList(),
        )
    }
}
