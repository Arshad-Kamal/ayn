package com.arshad.ayn.ui.tv.shell

import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.feature.settings.LAUNCH_NOTICE_MS
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import com.arshad.ayn.ui.tv.TestProfile
import com.arshad.ayn.ui.tv.TestKidOpening
import com.arshad.ayn.ui.tv.testKidsShell
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.ImportResult
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.testSettings
import android.net.Uri
import com.arshad.ayn.ui.tv.profiles.ShellOpening
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import com.arshad.ayn.core.access.SearchExtractor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.feature.nav.SubscriptionsRoute
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.theme.KeTheme
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.ui.tv.common.TOAST_MS
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.flowOf
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.FakeKidsTimeStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.FakeKidsReviewKeeps
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.home.KidsPicks
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real shell: the rail lists only built destinations, a destination reached from outside
 * the rail takes focus itself, the closed rail offers only its current item (on a channel or
 * playlist page, the destination beneath it), and BACK unwinds Search → Home → out of the app
 * with no extra stops. Home runs on a seam with nothing subscribed and no live
 * streams (its blank slate).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class TvShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun clickable(label: String): SemanticsMatcher = hasText(label) and hasClickAction()
    private fun pageTitle(title: String): SemanticsMatcher = hasText(title) and !hasClickAction()

    private val homeShown: SemanticsMatcher = hasText("Follow channels to fill Home")

    // Home's blank slate has a Search button too; the rail composes after the page.
    private fun rail(label: String): SemanticsNodeInteraction = compose.onAllNodes(clickable(label)).onLast()

    /** Moves focus into the rail through the [current] page's item (the closed rail offers only
     *  that one; it opens, every item takes focus), then selects [label]. */
    private fun selectInRail(label: String, current: String = "Home") {
        rail(current).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail(current).assertIsFocused()
        rail(label).dpadSelect()
        compose.waitForIdle()
    }

    private lateinit var navController: NavHostController

    private fun launch(extractor: Extractor = HomeExtractor(), syncNotice: SyncLaunchNotice? = null) {
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor, navController = navController, syncNotice = syncNotice)
        }
        compose.waitForIdle()
    }

    @Test
    fun railListsOnlyBuiltDestinations() {
        launch()

        assertEquals(listOf("Home", "Search", "Subscriptions", "Library", "Settings"), railDestinations.map { it.label })
        listOf("Home", "Search", "Subscriptions", "Library", "Settings").forEach { rail(it).assertExists() }
    }

    @Test
    fun aDestinationReachedFromAPageTakesFocusItself() {
        launch()
        // Focus starts in a page (Home's Search button), outside the rail.
        compose.onAllNodes(clickable("Search")).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnUiThread { navController.navigate(SubscriptionsRoute) }
        compose.waitForIdle()

        compose.onNode(pageTitle("Subscriptions")).assertExists()
        compose.onNode(clickable("Find a channel")).assertIsFocused()
    }

    @Test
    fun theClosedRailLetsOnlyItsCurrentItemTakeFocus() {
        compose.setContent {
            KeTheme {
                KeRail(
                    destinations = railDestinations,
                    current = railDestinations.first(),
                    open = false,
                    currentItem = remember { FocusRequester() },
                    page = remember { FocusRequester() },
                    profile = TestProfile,
                    onSelect = {},
                    onProfile = {},
                    onOpenChange = {},
                )
            }
        }

        compose.onNode(clickable("Search")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(clickable("Search")).assertIsNotFocused()
        compose.onNode(clickable("Home")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(clickable("Home")).assertIsFocused()
    }

    @Test
    fun anInertRailTakesNoFocus() {
        compose.setContent {
            KeTheme {
                KeRail(
                    destinations = railDestinations,
                    current = railDestinations.first(),
                    open = false,
                    currentItem = remember { FocusRequester() },
                    page = remember { FocusRequester() },
                    profile = TestProfile,
                    inert = true,
                    onSelect = {},
                    onProfile = {},
                    onOpenChange = {},
                )
            }
        }

        compose.onNode(clickable("Home")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(clickable("Home")).assertIsNotFocused()
    }

    /** Q9: a shell opened by a Settings restore lands on Settings' restore results, Home beneath it. */
    @Test
    fun aRestoredShellLandsOnSettingsWithHomeBeneath() {
        var shown = 0
        val restored = RestoredBackup("Ayn-backup.json", exportedAtMillis = 0, result = RestoreResult.Restored(ownerId = TestProfile.id))
        val opening = ShellOpening(TestProfile, restored, settingsPickWaiting = false, onRestoredShown = { shown++ }, onRailProfile = {})
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            TestShell(opening = opening)
        }
        compose.waitForIdle()

        compose.onNode(hasText("Backup restored")).assertExists()
        compose.onNode(clickable("Done")).assertIsFocused()
        assertEquals(1, shown)
        // Under the results the rail is hidden.
        rail("Settings").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Settings").assertIsNotFocused()
        compose.onNode(clickable("Done")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.onNode(homeShown).assertExists()
    }

    /** M5-D20: a pick made in Settings before KE's process went is taken once the shell opens. */
    @Test
    fun aShellWithASettingsPickWaitingGoesToSettingsWhereThePickIsTaken() {
        val takeout = Uri.parse("content://downloads/subscriptions.csv")
        val picks = TestPicks().apply { waiting.value = PickedFile(PickRequest(PickKind.Import, TestProfile.id), takeout, "subscriptions.csv") }
        val backups = FakeBackupStore().apply { importResult = ImportResult.Imported(added = 1, alreadySubscribed = 0) }
        val settings = testSettings(backups, picks)
        val opening = ShellOpening(TestProfile, restored = null, settingsPickWaiting = true, onRestoredShown = {}, onRailProfile = {})
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            TestShell(settingsModels = settings, opening = opening)
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText("1 channel added", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        assertEquals(listOf(takeout), backups.imported)
        assertEquals(listOf(TestProfile.id), backups.importedInto)
        compose.onNode(clickable("Backup & restore")).assertIsFocused()
        compose.pressBack()
        compose.onNode(homeShown).assertExists()
    }

    /** The usual case: the pick lands while Settings shows, which takes it where it is. */
    @Test
    fun aPickLandingWhileSettingsShowsIsTakenOnTheSameSettingsEntry() {
        val takeout = Uri.parse("content://downloads/subscriptions.csv")
        val picks = TestPicks()
        val backups = FakeBackupStore().apply { importResult = ImportResult.Imported(added = 1, alreadySubscribed = 0) }
        val settings = testSettings(backups, picks)
        var waiting by mutableStateOf(false)
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            navController = rememberNavController()
            val opening = ShellOpening(TestProfile, restored = null, settingsPickWaiting = waiting, onRestoredShown = {}, onRailProfile = {})
            TestShell(settingsModels = settings, opening = opening, navController = navController)
        }
        compose.waitForIdle()
        selectInRail("Settings")
        compose.onNode(clickable("Backup & restore")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(clickable("Import subscriptions from YouTube")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val entry = navController.currentBackStackEntry

        compose.runOnUiThread {
            picks.waiting.value = PickedFile(PickRequest(PickKind.Import, TestProfile.id), takeout, "subscriptions.csv")
            waiting = true
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText("1 channel added", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        assertSame(entry, navController.currentBackStackEntry)
        compose.onNode(clickable("Import subscriptions from YouTube")).assertIsFocused()
        assertEquals(listOf(takeout), backups.imported)
    }

    @Test
    fun backUnwindsFromATopLevelPageToHomeThenLeavesTheApp() {
        launch()
        selectInRail("Search")
        compose.onNode(pageTitle("Search")).assertExists()

        compose.pressBack()
        compose.onNode(homeShown).assertExists()
        compose.onNode(pageTitle("Search")).assertDoesNotExist()
        compose.onAllNodes(clickable("Search")).onFirst().assertIsFocused() // Home's own Search button
        assertFalse(compose.activity.isFinishing)

        compose.pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun switchingTopLevelPagesNeverStacksThem() {
        launch()
        selectInRail("Search")
        selectInRail("Subscriptions", current = "Search")
        compose.onNode(pageTitle("Subscriptions")).assertExists()

        compose.pressBack()
        compose.onNode(homeShown).assertExists()
        compose.onNode(pageTitle("Search")).assertDoesNotExist()
    }

    /** On the recorded channel's seam: Search, then the channel's page opened from it. */
    private fun openChannelFromSearch() {
        launch(ChannelExtractor())
        selectInRail("Search")
        compose.runOnUiThread { navController.navigate(ChannelRoute(RECORDED_CHANNEL_URL)) }
        compose.waitForIdle()
        compose.onNode(clickable("Subscribe")).assertIsFocused()
    }

    @Test
    fun leftFromAChannelPageReachesTheRailThroughTheDestinationItWasOpenedFrom() {
        openChannelFromSearch()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        rail("Search").assertIsFocused()
    }

    @Test
    fun selectingTheRailItemUnderAChannelPageReturnsToThatDestination() {
        openChannelFromSearch()

        selectInRail("Search", current = "Search")

        compose.onNode(pageTitle("Search")).assertExists()
        compose.onNode(clickable("Subscribe")).assertDoesNotExist()
        compose.pressBack()
        compose.onNode(homeShown).assertExists()
    }

    @Test
    fun aRestoredStackLightsTheDestinationBeneathItsPage() {
        openChannelFromSearch()
        selectInRail("Subscriptions", current = "Search")
        compose.onNode(pageTitle("Subscriptions")).assertExists()
        // Search's saved stack comes back with the channel page on top; Search never shows.
        selectInRail("Search", current = "Subscriptions")
        compose.onNode(clickable("Subscribe")).assertIsFocused()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        rail("Search").assertIsFocused()
    }

    @Test
    fun leftFromAPlaylistOpenedFromHomeReachesHome() {
        launch(ChannelExtractor())
        compose.runOnUiThread {
            navController.navigate(ChannelRoute(RECORDED_CHANNEL_URL))
            navController.navigate(PlaylistRoute(PLAYLIST_URL, "Playlist", null, 3, null))
        }
        compose.waitForIdle()
        compose.onNode(clickable("Play all")).assertIsFocused()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        rail("Home").assertIsFocused()
    }

    @Test
    fun rightFromTheRailReturnsToThePage() {
        launch()
        val pageSearch = compose.onAllNodes(clickable("Search")).onFirst()
        pageSearch.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        rail("Home").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        pageSearch.assertIsFocused()
    }

    @Test
    fun rightFromTheRailReturnsToTheItemThePageLastHeld() {
        openChannelFromSearch()
        compose.onNode(clickable("Videos")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(clickable("Live")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(clickable("Live")).assertIsFocused()

        rail("Search").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Search").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNode(clickable("Live")).assertIsFocused()
    }

    @Test
    fun rightFromTheRailStaysThereWhileThePageHasNothingToFocus() {
        installOfflineImageLoader(compose.activity)
        val extractor = HomeExtractor().apply { feedGate = CompletableDeferred() }
        compose.setContent {
            TestShell(extractor, subscriptionStore = remember { FakeSubscriptionStore(RECORDED_CHANNEL_URL) })
        }
        compose.waitForIdle()

        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Home").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        rail("Home").assertIsFocused()
    }

    @Test
    fun rightFromAnOpenRailItemOrTheProfileSlotReturnsToThePage() {
        launch()
        val pageSearch = compose.onAllNodes(clickable("Search")).onFirst()
        pageSearch.performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Library").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        pageSearch.assertIsFocused()

        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(clickable(TestProfile.name)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(clickable(TestProfile.name)).assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        pageSearch.assertIsFocused()
    }

    /**
     * A remote's LEFT in the Search field holding text (no suggestions) reaches the rail: the
     * field's own D-pad interceptor moves focus for a real D-pad device, as here. A virtual-device
     * key (adb `input keyevent`) moves the cursor instead.
     */
    @Test
    fun leftFromTheSearchFieldHoldingTextReachesTheRail() {
        lateinit var focusManager: FocusManager
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            focusManager = LocalFocusManager.current
            TestShell(SearchExtractor())
        }
        compose.waitForIdle()
        selectInRail("Search")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextInput(",")
        compose.waitForIdle()

        compose.runOnUiThread { assertTrue(focusManager.moveFocus(FocusDirection.Left)) }
        compose.waitForIdle()

        rail("Search").assertIsFocused()
    }

    /** LEFT in the empty Search field reaches the rail from any key source: a test key, like adb's,
     *  comes from no D-pad device, which the field's own D-pad exit ignores. */
    @Test
    fun leftFromTheEmptySearchFieldReachesTheRail() {
        launch(SearchExtractor())
        selectInRail("Search")
        compose.onNode(hasSetTextAction()).assertIsFocused()

        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        rail("Search").assertIsFocused()
    }

    @Test
    fun aKidsProfilesRailHasHomeSearchAndParent() {
        assertEquals(listOf("Home", "Search", "Parent"), kidsRailDestinations.map { it.label })
    }

    @Test
    fun aKidsTimeWarningShowsOverThePageThenIsConsumed() {
        val clock = FakeKidsClock()
        clock.time.value = KidsTime.Left(5.minutes)
        clock.warning.value = 5
        installOfflineImageLoader(compose.activity)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TestShell(
                opening = TestKidOpening,
                kids = testKidsShell(clock),
            )
        }
        compose.mainClock.advanceTimeBy(KeMotion.MOVE_MS.toLong() * 2)

        compose.onNode(hasText("5 minutes left today")).assertExists()
        compose.onNode(hasText("5 min left today")).assertExists()
        assertEquals(5, clock.warning.value)

        compose.mainClock.advanceTimeBy(TOAST_MS + KeMotion.MOVE_MS * 2L)

        assertNull(clock.warning.value)
        compose.onNode(hasText("5 minutes left today")).assertDoesNotExist()
    }

    /** The Parent page's head holds the top end (`kp-kids` parent), so the time left leaves it
     *  and comes back on Home. */
    @Test
    fun theParentPageHidesTheTimeLeft() {
        val clock = FakeKidsClock()
        clock.time.value = KidsTime.Left(48.minutes)
        val kid = TestKidOpening.profile
        val parent = ParentModels(
            page = { kidId, leave ->
                ParentViewModel(
                    kidId,
                    FakeProfileStore(listOf(TestProfile, kid)),
                    FakeProfileLocks().apply {
                        ownerId = TestProfile.id
                        kids += kid.id
                    },
                    FakeFilterStore(),
                    leave,
                    TimeSource.Monotonic,
                )
            },
            review = {
                ParentReviewViewModel(
                    ProfileSession().apply { select(kid.id) },
                    FakeFilterStore(),
                    FakeKidsTimeStore(),
                    FakeWatchStore(),
                    FakeSubscriptionStore(),
                    SubscriptionsFeed(HomeExtractor()),
                    FakeKidsReviewKeeps(),
                    ServerClock(TimeSource.Monotonic),
                )
            },
        )
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            TestShell(opening = TestKidOpening, kids = KidsShell(clock, parent) { flowOf(KidsPicks.None) })
        }
        compose.waitForIdle()
        compose.onNode(hasText("48 min left today")).assertExists()

        selectInRail("Parent")

        compose.onNode(hasText("Daily limits")).assertExists()
        compose.onNode(hasText("48 min left today")).assertDoesNotExist()

        selectInRail("Home", current = "Parent")

        compose.onNode(hasText("48 min left today")).assertExists()
    }

    @Test
    fun reselectingHomeLeavesNoStopBeforeTheAppExits() {
        launch()
        selectInRail("Search")
        selectInRail("Home", current = "Search")
        compose.onNode(homeShown).assertExists()

        compose.pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    /** M8-D11 (`m8-sync-tv` notice): the launch's failed sync is a toast over Home for 8 s; focus
     *  stays where Home put it. */
    @Test
    fun aFailedLaunchSyncIsAToastOverHomeForEightSecondsThatTakesNoFocus() {
        val status = MutableStateFlow(SyncStatus(linked = true, failure = SyncFailure.SIGN_IN_REJECTED, account = NEXTCLOUD))
        val notice = SyncLaunchNotice(status, FakeProfileStore(listOf(TestProfile)).profiles())
        launch(syncNotice = notice)
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().id
        compose.onNode(hasText(TOAST_TEXT)).assertDoesNotExist()

        status.value = status.value.copy(checks = 1)
        compose.waitForIdle()

        compose.onNode(hasText(TOAST_TEXT)).assertExists()
        assertEquals(focused, compose.onNode(isFocused()).fetchSemanticsNode().id)
        compose.mainClock.advanceTimeBy(LAUNCH_NOTICE_MS - 1_000)
        compose.waitForIdle()
        compose.onNode(hasText(TOAST_TEXT)).assertExists()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNode(hasText(TOAST_TEXT)).assertDoesNotExist()
    }

    /** A launch sync that ends while another page shows waits for Home. */
    @Test
    fun theLaunchNoticeWaitsForHome() {
        val status = MutableStateFlow(SyncStatus(linked = true, failure = SyncFailure.SIGN_IN_REJECTED, account = NEXTCLOUD))
        launch(syncNotice = SyncLaunchNotice(status, FakeProfileStore(listOf(TestProfile)).profiles()))
        selectInRail("Search")

        status.value = status.value.copy(checks = 1)
        compose.waitForIdle()
        compose.onNode(hasText(TOAST_TEXT)).assertDoesNotExist()
        selectInRail("Home", current = "Search")

        compose.onNode(hasText(TOAST_TEXT)).assertExists()
    }

    private companion object {
        val NEXTCLOUD = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad")
        const val TOAST_TEXT = "Sync didn’t work: Nextcloud says Ayn’s access was removed. Fix it in Settings › Sync"
    }
}
