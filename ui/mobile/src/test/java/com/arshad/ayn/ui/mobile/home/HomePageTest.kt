package com.arshad.ayn.ui.mobile.home

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FEED_NEWEST_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.KIOSK_FLOWS
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.feature.home.HomeViewModel
import com.arshad.ayn.feature.home.SHOWN_AFTER_MS
import com.arshad.ayn.feature.home.homeViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.settings.LAUNCH_NOTICE_MS
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's Home (`m6-home-and-menus` states 1–8) through the shell and the real
 * [HomeViewModel] on recorded payloads (the Rick Astley feed, its newest upload's page, the Live
 * kiosk): the hero and rows in `homeLayout`'s order, Hero on Home off, the statements and where
 * their buttons go, pull to refresh, a tap counted as opened, For you's in-view cards counted as shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomePageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = HomeExtractor()
    private val feedPage = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
    private val heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(RecordedChannel.FEED_ENTRIES.first().first)) }
    private val livePage = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
    private val watch = FakeWatchStore()
    private val related = FakeRelatedListStore()
    private val forYouStore = FakeForYouStore()
    private val playlists = FakePlaylistStore()
    private val played = mutableListOf<VideoItem>()
    private var pulls = 0
    private var fixes = 0

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun launch(
        vararg subscriptions: String,
        device: DeviceSettings = DeviceSettings(),
        profile: Profile = ARSHAD,
        syncNotice: SyncLaunchNotice? = null,
    ) {
        val clock = FixedClock(RecordedChannel.FEED_CLOCK)
        val subscriptionStore = FakeSubscriptionStore(*subscriptions)
        val settings = FakeProfileSettingsStore(selectedId = ARSHAD.id)
        compose.setContent {
            AynTheme {
                CompositionLocalProvider(LocalDeviceSettings provides device) {
                    MobileShell(ShellOpening(profile, onSwitchProfile = {})) { chrome ->
                        entry<TabKey> { key ->
                            if (key.tab == Tab.Home) {
                                HomePage(
                                    viewModel = viewModel {
                                        homeViewModel(
                                            extractor, SubscriptionsFeed(extractor, clock), subscriptionStore, watch, playlists, related, forYouStore,
                                            FakeFetchAttemptLog(Clock.System), settings, compute = Dispatchers.Main,
                                        )
                                    },
                                    menu = viewModel {
                                        VideoMenuViewModel(watch, playlists, settings, MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic))
                                    },
                                    chrome = chrome,
                                    onPlay = { video, _ -> played += video },
                                    onOpenChannel = {},
                                    onPull = { pulls++ },
                                    syncNotice = syncNotice,
                                    onFixSync = { fixes++ },
                                )
                            } else {
                                Text("${key.tab.label} page")
                            }
                        }
                        entry<SettingsKey> {}
                        entry<SearchKey> { Text("Search page") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun recordedHome(device: DeviceSettings = DeviceSettings()) {
        extractor.feed = feedPage
        extractor.heroPage = heroPage
        extractor.live = livePage
        launch(RECORDED_CHANNEL_URL, device = device)
    }

    // The bar's tabs are selectable: a page button isn't.
    private fun button(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction() and !isSelectable())

    // The page scrolls up and down; its rows sideways.
    private fun home(): SemanticsNodeInteraction = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    @Test
    fun theNewestUploadIsTheHeroAndTheRowsFollowInHomesOrderLeavingOutAnEmptyRow() {
        recordedHome()

        compose.onNodeWithText(HERO_TITLE).assertExists()
        compose.onNodeWithText("New").assertExists()
        compose.onNodeWithText("3:51", substring = true).assertExists()
        button("Play").assertExists()
        val index = home().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        // The feed's one unwatched upload is the hero, so New from your subscriptions has nothing.
        assertEquals(listOf(0, 1, 2, -1), listOf("top", "live", "channels", "new").map(index))
        home().performScrollToNode(hasText("Your channels"))
    }

    @Test
    fun withHeroOnHomeOffHomeOpensOnItsRowsTheHerosVideoFirst() {
        recordedHome(DeviceSettings(heroOnHome = false))

        compose.onNode(hasText("Play") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("New from your subscriptions").assertExists()
        compose.onNodeWithText(HERO_TITLE).assertExists()
    }

    @Test
    fun aTapPlaysTheCardAndCountsItAsOpened() {
        recordedHome()

        button("Play").performClick()
        compose.waitForIdle()

        assertEquals(listOf(HERO_TITLE), played.map { it.title })
    }

    @Test
    fun noSubscriptionsIsTheBlankSlateAndItsSearchOpensSearch() {
        launch()

        compose.onNodeWithText("No subscriptions yet").assertExists()
        button("Search").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Search page").assertExists()
    }

    @Test
    fun everyFeedFailingIsTheCompactStatementAndPullingDownLoadsTheRows() {
        extractor.live = livePage
        launch(RECORDED_CHANNEL_URL)
        compose.onNodeWithText("Couldn’t load your subscriptions").assertExists()

        extractor.feed = feedPage
        extractor.heroPage = heroPage
        home().performTouchInput { swipeDown() }
        compose.waitForIdle()

        compose.onNodeWithText(HERO_TITLE).assertExists()
        compose.onNodeWithText("Couldn’t load your subscriptions").assertDoesNotExist()
        assertEquals(1, pulls)
    }

    /** M8-D11 (`m8-sync-phone` 13): the owner reads what failed, and Fix opens Settings › Sync. */
    @Test
    fun aFailedLaunchSyncIsASnackbarWhoseFixOpensSettingsSyncForTheOwner() {
        launch(syncNotice = failedLaunch(SyncFailure.SIGN_IN_REJECTED))

        compose.onNodeWithText("Sync didn’t work · Nextcloud removed Ayn’s access").assertExists()
        button("Fix").performClick()
        compose.waitForIdle()

        assertEquals(1, fixes)
        compose.onNodeWithText("Sync didn’t work", substring = true).assertDoesNotExist()
    }

    /** Q14 (`m8-sync-phone` 15): another profile is told whom to tell, with nothing to press. */
    @Test
    fun anotherProfileIsToldWhomToTellWithoutFix() {
        launch(profile = MIRA, syncNotice = failedLaunch(SyncFailure.SIGN_IN_REJECTED))

        compose.onNodeWithText("Sync isn’t working · inform Arshad").assertExists()
        button("Fix").assertDoesNotExist()
    }

    /** `m8-sync-phone` 14: no connection retries by itself, for 8 seconds. */
    @Test
    fun noConnectionSaysAynTriesAgainAndGoesAfterEightSeconds() {
        launch(syncNotice = failedLaunch(SyncFailure.NO_CONNECTION))

        compose.onNodeWithText("Couldn’t sync · no connection, Ayn tries again").assertExists()
        button("Fix").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(LAUNCH_NOTICE_MS - 1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn’t sync", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn’t sync", substring = true).assertDoesNotExist()
    }

    /** A launch whose own sync failed: the status moves past the count it was built at. */
    private fun failedLaunch(failure: SyncFailure): SyncLaunchNotice {
        val status = MutableStateFlow(SyncStatus(linked = true, failure = failure, account = NEXTCLOUD, checks = 2))
        val notice = SyncLaunchNotice(status, FakeProfileStore(listOf(ARSHAD, MIRA)).profiles())
        status.value = status.value.copy(checks = 3)
        return notice
    }

    @Test
    fun everythingFailingOfflineIsTheNoConnectionStatementAndOpenLibraryOpensTheLibraryTab() {
        extractor.live = null
        extractor.liveFailure = UnknownHostException("www.youtube.com")
        launch()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Home couldn’t load").assertExists()
        compose.onNodeWithText("Check that the phone is online", substring = true).assertExists()
        compose.onNodeWithText("Last attempt", substring = true).assertExists()
        button("Open Library").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Library page").assertExists()
    }

    @Test
    fun tryAgainReloadsHome() {
        extractor.live = null
        launch()
        compose.onNodeWithText("YouTube changed something").assertExists()

        extractor.live = livePage
        button("Try again").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Follow channels to fill Home").assertExists()
    }

    /** FY-D3: For you's hero and its row's cards in view count as shown after two seconds. */
    @Test
    fun forYousCardsInViewCountAsShownAfterTwoSeconds() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"))
        recordedHome()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("The next pick")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false

        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS - 300)
        compose.waitForIdle()
        assertEquals(emptyList<VideoItem>(), forYouStore.shown.flatten())

        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val shown = forYouStore.shown.flatten().map { it.id.value }
        assertTrue("hero and row shown: $shown", shown.containsAll(listOf("fypick00001", "fypick00002")))
    }

    @Test
    fun aKidsProfilesFailedHomeHasNoLibraryToOffer() {
        extractor.live = null
        launch(profile = ARSHAD.copy(isOwner = false, isKids = true, lock = null))

        compose.onNodeWithText("Home couldn’t load").assertExists()
        button("Open Library").assertDoesNotExist()
    }

    @Test
    fun theHerosWatchLaterSavesItAndSaysSo() {
        recordedHome()

        button("Watch later").performClick()
        compose.waitForIdle()

        button("In Watch later").assertExists()
    }

    @Test
    fun continueWatchingShowsWhatIsLeft() {
        watch.entries.value = listOf(
            WatchEntry(VideoId("JsBZOcqZerk"), "Enigma", "Veritasium", null, null, 2_091_000, 2_862_000, watched = false, 1, 1),
        )
        recordedHome()

        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText("Veritasium · 12:51 left").assertExists()
    }

    @Test
    fun playingAForYouPickCountsAsOpened() {
        keepForYou(forYouPick("fypick00001", "A For you pick"))
        recordedHome()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("A For you pick")).fetchSemanticsNodes().isNotEmpty() }

        button("Play").performClick()
        compose.waitForIdle()

        assertEquals(listOf("fypick00001"), forYouStore.opened.map { it.id.value })
    }

    /** FY-D3: a card cut off at the lane's edge isn't in view, while the one beside it is. */
    @Test
    fun aCutOffCardCountsAsNotShown() {
        showThreePicks()
        compose.mainClock.autoAdvance = false

        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 300)
        compose.waitForIdle()

        val shown = forYouStore.shown.flatten().map { it.id.value }
        assertTrue("the whole card is shown: $shown", "fypick00002" in shown)
        assertTrue("the cut-off card isn't: $shown", "fypick00003" !in shown)
    }

    /** FY-D3: For you scrolled under the bar before two seconds starts over, so nothing counts. */
    @Test
    fun forYouScrolledAwayBeforeTwoSecondsCountsNothing() {
        showThreePicks()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(emptyList<VideoItem>(), forYouStore.shown.flatten())

        // The hero less than half shown and the For you row's top under the bar.
        val by = with(compose.density) { (HeroHeight - 30.dp).toPx() }
        home().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 300)
        compose.waitForIdle()

        assertEquals(emptyList<VideoItem>(), forYouStore.shown.flatten())
    }

    /** FY-D3: each return to Home is a new visit, whose timers count the same picks again. */
    @Test
    fun aReturnToHomeCountsItsCardsAsShownAgain() {
        showThreePicks()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 100)
        compose.waitForIdle()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 100)
        compose.waitForIdle()

        assertEquals(2, forYouStore.shown.flatten().count { it.id.value == "fypick00001" })
    }

    private fun showThreePicks() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"), forYouPick("fypick00003", "A cut-off pick"))
        recordedHome()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("A cut-off pick")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun keepForYou(vararg picks: VideoItem) {
        val seedId = VideoId("seed0000001")
        watch.entries.value = listOf(
            WatchEntry(seedId, "A seed", "Seeder", "https://www.youtube.com/channel/UCseed", null, 600_000, 600_000, watched = true, WATCHED_AT, WATCHED_AT),
        )
        related.kept[seedId] = RelatedList(picks.toList(), fetchedAt = RecordedChannel.FEED_CLOCK, failedAt = null)
    }

    private fun forYouPick(id: String, title: String): VideoItem =
        VideoItem(VideoId(id), title, "Picker $id", "https://www.youtube.com/channel/UC$id", 600, 1, null, null, null, isLive = false)

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Teal, isOwner = false, lock = null)
        val NEXTCLOUD = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad")
        const val HERO_TITLE = "Rick Astley - Angels On My Side (Live at The O2, London, April 2026)"
        val WATCHED_AT = (RecordedChannel.FEED_CLOCK - 1.hours).toEpochMilliseconds()
    }
}
