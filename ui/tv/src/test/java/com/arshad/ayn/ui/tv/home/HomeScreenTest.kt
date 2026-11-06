package com.arshad.ayn.ui.tv.home

import com.arshad.ayn.feature.home.SHOWN_AFTER_MS
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FEED_NEWEST_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.KIOSK_FLOWS
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.newpipe.RecordedFeedNewestVideo
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.filter.ContentFilters
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.LibraryRoute
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.common.ACTION_TOAST_MS
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.ui.tv.testBranding
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Home through the real shell and [com.arshad.ayn.feature.home.HomeViewModel] on recorded
 * payloads (the Rick Astley feed, its newest upload's page, the Live kiosk): each `b3-home`
 * state's content and where focus lands; BACK, select and a DOWN between rows are driven here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = HomeExtractor()
    private val feedPage = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
    private val heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(RecordedChannel.FEED_ENTRIES.first().first)) }
    private val livePage = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val related = FakeRelatedListStore()
    private val forYouStore = FakeForYouStore()
    private val settings = FakeProfileSettingsStore(selectedId = OWNER.id)
    private val filters = FakeFilterStore()
    // No profiles until a test adds them: no Block rows.
    private val people = FakeProfileStore()
    private val locks = FakeProfileLocks().apply {
        ownerId = OWNER.id
        sessionId = OWNER.id
    }
    private val contentFilter = ContentFilters(ProfileSession().apply { select(OWNER.id) }, settings, filters).current
    private lateinit var nav: NavHostController
    private var branding: CardBranding? = null
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pictures: MutableList<String> = CopyOnWriteArrayList()

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity, pictures)
    }

    @After
    fun tearDown() {
        brandingScope.cancel()
    }

    private var device = DeviceSettings()

    private fun launch(vararg subscriptions: String) {
        val clock = FixedClock(RecordedChannel.FEED_CLOCK)
        compose.setContent {
            nav = rememberNavController()
            TestShell(
                extractor,
                SubscriptionsFeed(extractor, clock),
                FakeSubscriptionStore(*subscriptions),
                watchStore = watch,
                playlistStore = playlists,
                relatedLists = related,
                forYouStore = forYouStore,
                profileSettings = settings,
                branding = branding,
                navController = nav,
                deviceSettings = device,
                menuBlocking = MenuBlocking(filters, people, locks, TimeSource.Monotonic),
                contentFilter = contentFilter,
            )
        }
        compose.waitForIdle()
    }

    private fun recordedHome() {
        extractor.feed = feedPage
        extractor.heroPage = heroPage
        extractor.live = livePage
        launch(RECORDED_CHANNEL_URL)
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    // The rail's Search item matches too; the page composes before the rail.
    private fun pageSearchButton() = compose.onAllNodes(button("Search")).onFirst()

    /** FY-D9's trigger: Home's start ranks For you, whose first pick takes the hero. */
    @Test
    fun forYousFirstPickIsTheHeroWhenHomeOpens() {
        keepForYou(forYouPick("fypick00001", "A For you pick"))

        recordedHome()

        compose.waitUntil(5_000) { shows("A For you pick") }
    }

    /** FY-D9: back from the player is a visit; For you ranks again, and the opened hero hands over. */
    @Test
    fun backFromThePlayerRanksForYouAgain() {
        val first = forYouPick("fypick00001", "A For you pick")
        keepForYou(first, forYouPick("fypick00002", "The next pick"))
        recordedHome()
        compose.waitUntil(5_000) { shows("A For you pick") }

        compose.onNode(button("Play")).dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        watch.entries.value += WatchEntry(first.id, first.title, null, null, null, 0, null, watched = false, WATCHED_AT, WATCHED_AT)
        compose.pressBack()

        compose.waitUntil(5_000) { shows("The next pick") }
    }

    /** A hero from a channel not followed has no avatar to show: its monogram stands in. */
    @Test
    fun forYousHeroFromAnUnfollowedChannelShowsItsMonogram() {
        keepForYou(forYouPick("fypick00001", "A For you pick"))

        recordedHome()

        compose.waitUntil(5_000) { shows("A For you pick") }
        compose.onNodeWithText("PF", useUnmergedTree = true).assertExists()
    }

    /** `b3-home` fy-rest: the hero wears "For you", the row under it holds the picks after it. */
    @Test
    fun forYousHeroWearsItsChipAndItsRowHoldsTheNextPicks() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"))

        recordedHome()

        compose.waitUntil(5_000) { shows("The next pick") }
        compose.onAllNodesWithText("For you").assertCountEquals(2)
        compose.onNode(button("Play")).assertIsFocused()
    }

    /** FY-D8, FY-D11: the menu says why; Not interested takes the card out, Undo focused, Undo brings it back. */
    @Test
    fun notInterestedHidesTheCardWithUndoFocusedAndUndoBringsItBack() {
        showThreePicks()

        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNodeWithText("Because you watched A seed").assertExists()
        compose.onNode(button("Not interested")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("The next pick").assertDoesNotExist()
        compose.onNodeWithText("You won’t see this video in For you", substring = true).assertExists()
        compose.onNode(button("Undo")).assertIsFocused()
        assertEquals(setOf(VideoId("fypick00002")), forYouStore.hiddenVideoIds.value)

        compose.onNode(button("Undo")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("The next pick").assertExists()
        cardOf("The next pick").assertIsFocused()
        assertEquals(emptySet<VideoId>(), forYouStore.hiddenVideoIds.value)
    }

    /** `kp-filters` cardmenu → blocksheet → blocked (KP-D19): the owner blocks a pick's channel in all
     *  profiles; its card leaves For you at once with Undo focused, and Undo brings it back. */
    @Test
    fun blockingAPicksChannelTakesItsCardOutWithUndoFocusedAndUndoBringsItBack() {
        people.rows.value = listOf(OWNER, MIRA)
        showThreePicks()

        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Block Picker fypick00002")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Block in which profiles?").assertExists()
        compose.onNode(button("All profiles")).assertIsFocused()
        compose.onNode(button("All profiles")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("The next pick").assertDoesNotExist()
        compose.onNodeWithText("Picker fypick00002 blocked", substring = true).assertExists()
        compose.onNode(button("Undo")).assertIsFocused()
        assertEquals(listOf(false), filters.rulesById.value[OWNER.id]?.rules?.map { it.byOwner })
        assertEquals(listOf(true), filters.rulesById.value[MIRA.id]?.rules?.map { it.byOwner })

        compose.onNode(button("Undo")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("The next pick").assertExists()
        assertEquals(emptyList<Any>(), filters.rulesById.value[OWNER.id]?.rules.orEmpty())
    }

    /** A blocked video is listed by the title its card showed (DeArrow's), not YouTube's. */
    @Test
    fun blockingAVideoKeepsTheTitleItsCardShows() {
        people.rows.value = listOf(OWNER, MIRA)
        branding = testBranding(
            brandingScope,
            BrandingSource { id, _ -> if (id.value == "fypick00002") BrandingLookup.Found(Branding(SHOWN_PICK, null)) else BrandingLookup.None },
        )
        showThreePicks()
        compose.waitUntil(5_000) { shows(SHOWN_PICK) }

        cardOf(SHOWN_PICK).dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Block this video")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(button("All profiles")).dpadSelect()
        compose.waitForIdle()

        assertEquals(listOf(SHOWN_PICK), filters.rulesById.value[OWNER.id]?.rules?.map { (it.rule as BlockedVideo).title })
    }

    /** KP-D19 (Q20): unanswered, the blocked notice goes and focus lands on the card that took the blocked one's place. */
    @Test
    fun afterTheBlockedNoticeClosesFocusTakesTheNextCard() {
        people.rows.value = listOf(OWNER, MIRA)
        showThreePicks()
        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Block Picker fypick00002")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(button("All profiles")).dpadSelect()
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS + 500)
        compose.waitForIdle()

        compose.onNode(button("Undo")).assertDoesNotExist()
        cardOf("The last pick").assertIsFocused()
    }

    /** FY-D6, FY-D11: Don't recommend this channel takes out every card of the channel and names it in the notice. */
    @Test
    fun dontRecommendThisChannelTakesOutItsCardsAndSaysSo() {
        val shared = "https://www.youtube.com/channel/UCshared"
        keepForYou(
            forYouPick("fypick00001", "A For you pick"),
            forYouPick("fypick00002", "The next pick").copy(uploaderName = "Shared Channel", uploaderUrl = shared),
            forYouPick("fypick00003", "The last pick"),
            forYouPick("fypick00004", "Its other pick").copy(uploaderName = "Shared Channel", uploaderUrl = shared),
        )
        recordedHome()
        compose.waitUntil(5_000) { shows("Its other pick") }

        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Don’t recommend this channel")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("The next pick").assertDoesNotExist()
        compose.onNodeWithText("Its other pick").assertDoesNotExist()
        compose.onNodeWithText("The last pick").assertExists()
        compose.onNodeWithText("Shared Channel won’t be recommended in For you", substring = true).assertExists()
        compose.onNode(button("Undo")).assertIsFocused()
        assertEquals(setOf(canonicalChannelUrl(shared)), forYouStore.hiddenChannelUrls.value)
    }

    /** `b3-home` nohero: For you landing after the feed's rows took focus takes it to its first pick. */
    @Test
    fun withHeroOffForYouLandingAfterTheFeedTakesFocusToItsFirstPick() {
        val gate = heroOffWithForYouHeldBack()

        gate.complete(Unit)
        compose.waitUntil(5_000) { shows("A For you pick") }
        compose.waitForIdle()

        cardOf("A For you pick").assertIsFocused()
        assertEquals(32f, compose.onNodeWithText("For you").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun withHeroOffAKeyPressedBeforeForYouLandsKeepsFocusWhereItIs() {
        val gate = heroOffWithForYouHeldBack()
        compose.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        gate.complete(Unit)
        compose.waitUntil(5_000) { shows("A For you pick") }
        compose.waitForIdle()

        cardOf(HERO_TITLE).assertIsFocused()
    }

    /** CMP-FOCUS-05: For you's first picks landing on a return to Home leave the card BACK restored. */
    @Test
    fun withHeroOffForYousFirstPicksLandingOnAReturnKeepTheReturnedFocus() {
        device = DeviceSettings(heroOnHome = false)
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"))
        val list = related.kept.toMap()
        related.kept.clear()
        recordedHome()
        compose.onNode(hasText("3:51") and hasClickAction() and isFocused()).dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        related.kept.putAll(list)
        compose.pressBack()
        compose.waitUntil(5_000) { shows("A For you pick") }
        compose.waitForIdle()

        cardOf(HERO_TITLE).assertIsFocused()
    }

    /** Hero off, the feed's rows shown with focus on the newest upload's card, For you's ranking held. */
    private fun heroOffWithForYouHeldBack(): CompletableDeferred<Unit> {
        device = DeviceSettings(heroOnHome = false)
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"))
        val gate = CompletableDeferred<Unit>()
        forYouStore.hiddenChannelsGate = gate
        recordedHome()
        compose.onNode(hasText("3:51") and hasClickAction() and isFocused()).assertExists()
        return gate
    }

    /** FY-D11: unanswered, the notice goes after 6 s and focus lands on the card that took the place. */
    @Test
    fun theNoticeClosesOnItsOwnAndFocusTakesTheNextCard() {
        showThreePicks()
        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Not interested")).dpadSelect()
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS + 500)
        compose.waitForIdle()

        compose.onNode(button("Undo")).assertDoesNotExist()
        cardOf("The last pick").assertIsFocused()
    }

    @Test
    fun anyOtherKeyClosesTheNotice() {
        showThreePicks()
        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Not interested")).dpadSelect()
        compose.waitForIdle()

        compose.press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Undo")).assertDoesNotExist()
        cardOf("The last pick").assertIsFocused()
        assertEquals(setOf(VideoId("fypick00002")), forYouStore.hiddenVideoIds.value)
    }

    /** FY-D3: the hero (top focused) and the row's cards in view count as shown after 2 s, not before. */
    @Test
    fun forYousCardsInViewCountAsShownAfterTwoSeconds() {
        showThreePicks()
        compose.mainClock.autoAdvance = false

        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS - 300)
        compose.waitForIdle()
        assertEquals(emptyList<VideoItem>(), forYouStore.shown.flatten())

        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val shown = forYouStore.shown.flatten().map { it.id.value }
        assertTrue("hero and row shown: $shown", shown.containsAll(listOf("fypick00001", "fypick00002")))
    }

    /** FY-D3: a lower row focused puts the hero and the For you row out of view; neither counts. */
    @Test
    fun aLowerRowFocusedCountsNoForYouCard() {
        showThreePicks()

        compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onAllNodes(button("Play") and isFocused()).assertCountEquals(0)
        listOf("The next pick", "The last pick").forEach { cardOf(it).assertIsNotFocused() }
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 100)
        compose.waitForIdle()

        assertEquals(emptyList<VideoItem>(), forYouStore.shown.flatten())
    }

    @Test
    fun playingThePickCountsAsOpened() {
        showThreePicks()

        compose.onNode(button("Play")).dpadSelect()
        compose.waitForIdle()

        assertEquals(listOf("fypick00001"), forYouStore.opened.map { it.id.value })
    }

    /** FY-D3: each start of Home is a visit whose cards count again; the timers don't run across a stop. */
    @Test
    fun aReturnToHomeCountsItsCardsAsShownAgain() {
        showThreePicks()
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 100)
        compose.waitForIdle()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SHOWN_AFTER_MS + 100)
        compose.waitForIdle()

        assertEquals(2, forYouStore.shown.flatten().count { it.id.value == "fypick00001" })
    }

    /** FY-D11: BACK closes the notice like any other key; the hide stands and Home stays. */
    @Test
    fun backClosesTheNoticeAndTheHideStands() {
        showThreePicks()
        cardOf("The next pick").dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Not interested")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Undo")).assertDoesNotExist()
        cardOf("The last pick").assertIsFocused()
        assertTrue(nav.currentDestination!!.hasRoute(HomeRoute::class))
        assertEquals(setOf(VideoId("fypick00002")), forYouStore.hiddenVideoIds.value)
    }

    /** Arshad Q2: Not interested from the hero's ⋯ hands the hero to the next pick; Undo brings it back on Play. */
    @Test
    fun undoOfAHiddenHeroBringsItBackWithPlayFocused() {
        showThreePicks()
        hideTheHeroFromItsMenu()
        compose.onNodeWithText("A For you pick").assertDoesNotExist()

        compose.onNode(button("Undo")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("A For you pick").assertExists()
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun aHiddenHerosNoticeClosesOnTheNextPicksPlay() {
        showThreePicks()
        hideTheHeroFromItsMenu()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS + 500)
        compose.waitForIdle()

        compose.onNode(button("Undo")).assertDoesNotExist()
        compose.onNode(button("Play")).assertIsFocused()
        assertEquals(setOf(VideoId("fypick00001")), forYouStore.hiddenVideoIds.value)
    }

    private fun hideTheHeroFromItsMenu() {
        compose.onAllNodes(hasContentDescription("More") and hasClickAction()).onFirst().dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Because you watched A seed").assertExists()
        compose.onNode(button("Not interested")).dpadSelect()
        compose.waitForIdle()
    }

    private fun showThreePicks() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"), forYouPick("fypick00003", "The last pick"))
        recordedHome()
        compose.waitUntil(5_000) { shows("The last pick") }
    }

    /**
     * The clickable card whose title is [title] (titles sit under the picture, outside it), found by
     * unclipped bounds: Home's list clips a row hanging off the screen's foot.
     */
    private fun cardOf(title: String): SemanticsNodeInteraction {
        val text = compose.onNodeWithText(title).fetchSemanticsNode().unclippedBounds()
        val card = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().first {
            val box = it.unclippedBounds()
            box.left <= text.center.x && box.right >= text.center.x && box.bottom <= text.top + 1 && box.bottom > text.top - 40
        }
        return compose.onNode(SemanticsMatcher("card ${card.id}") { it.id == card.id })
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

    /** D-pad down, row by row, until [text] is composed (Home composes only the rows near view). */
    private fun downTo(text: String) {
        repeat(MAX_ROWS) {
            if (shows(text)) return
            compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theNewestUploadIsTheHeroWithItsPagesLengthViewsAndFormatAndPlayFocused() {
        recordedHome()

        compose.onNodeWithText(HERO_TITLE).assertExists()
        compose.onNodeWithText("3:51", substring = true).assertExists()
        compose.onNodeWithText("100K views", substring = true).assertExists()
        compose.onNodeWithText("1080p · 25 fps", substring = true).assertExists()
        compose.onNodeWithText(RecordedFeedNewestVideo.DESCRIPTION_PREFIX, substring = true).assertExists()
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun withTheTvsHeroOffTheHerosVideoLeadsTheRowsAndTheFirstCardHasFocus() {
        device = DeviceSettings(heroOnHome = false)
        recordedHome()

        compose.onNode(button("Play")).assertDoesNotExist()
        compose.onNodeWithText(RecordedFeedNewestVideo.DESCRIPTION_PREFIX, substring = true).assertDoesNotExist()
        compose.onNodeWithText(HERO_TITLE).assertExists()
        // The hero's card leads the rows: its length is its page's (the feed has none).
        compose.onNode(hasText("3:51") and hasClickAction() and isFocused()).assertExists()
        assertEquals(32f, compose.onNodeWithText("New from your subscriptions").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun withTheTvsHeroOffAFocusedLowerRowSettlesWhereTheFirstOneStarts() {
        device = DeviceSettings(heroOnHome = false)
        recordedHome()

        compose.onNode(hasText("3:51") and hasClickAction() and isFocused()).performKeyPress(
            KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN)),
        )
        compose.waitForIdle()

        assertEquals(32f, compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    /** PERF-D10: a row below the screen is composed only as focus nears it; a focused row stops 160 dp down. */
    @Test
    fun aRowBelowTheScreenIsComposedOnlyAsFocusNearsItAndAFocusedRowStopsUnderTheHero() {
        recordedHome()
        compose.onNodeWithText("Your channels").assertDoesNotExist()

        compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)

        assertEquals(160f, compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithText("Your channels").assertExists()
    }

    /** PERF-D10: a row's DeArrow faces are asked for while Home shows, before the row is composed. */
    @Test
    fun aRowBelowTheScreenHasItsFacesAskedForBeforeItIsComposed() {
        val asked = CopyOnWriteArrayList<VideoId>()
        branding = testBranding(
            brandingScope,
            BrandingSource { id, _ ->
                asked += id
                BrandingLookup.None
            },
        )
        watch.entries.value = continuing("First")
        val firstLive = livePage.items.filterIsInstance<VideoItem>().first().id
        recordedHome()

        compose.waitUntil(PICTURE_WAIT_MS) { firstLive in asked }
        compose.onNodeWithText("Live now").assertDoesNotExist()
    }

    /** PERF-D10: a sideways move in a lower row keeps the list at its stop, even under TV's pivot default. */
    @Test
    fun aSidewaysMoveInALowerRowKeepsItsStopOnATv() {
        shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        recordedHome()
        compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)

        repeat(2) { compose.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }

        assertEquals(160f, compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    /** M7-D8, Q34: the hero's title and backdrop are held while DeArrow is asked, never YouTube's first. */
    @Test
    fun theHerosTitleAndBackdropWaitForDeArrowThenShowDeArrows() {
        val gate = CompletableDeferred<Unit>()
        val hero = VideoId(RecordedChannel.FEED_ENTRIES.first().first)
        branding = testBranding(
            brandingScope,
            BrandingSource { id, _ ->
                gate.await()
                if (id == hero) BrandingLookup.Found(Branding(HERO_DEARROW_TITLE, HERO_DEARROW_FRAME)) else BrandingLookup.None
            },
        )
        recordedHome()

        compose.onNodeWithText(HERO_TITLE).assertDoesNotExist()
        compose.onNodeWithText(HERO_DEARROW_TITLE).assertDoesNotExist()
        compose.onNode(button("Play")).assertIsFocused()
        assertTrue("no backdrop before DeArrow answers", pictures.none { hero.value in it })

        gate.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText(HERO_DEARROW_TITLE).assertExists()
        compose.onNodeWithText(HERO_TITLE).assertDoesNotExist()
        compose.waitUntil(PICTURE_WAIT_MS) { HERO_DEARROW_FRAME in pictures }
        assertTrue("YouTube's picture is never asked for", pictures.none { hero.value in it })
    }

    @Test
    fun recordedRowsShowLiveNowWithLiveBadgesAndYourChannelsAndLeaveOutAnEmptyRow() {
        recordedHome()

        compose.onNodeWithText("Live now").assertExists()
        assertTrue("the row's visible cards wear LIVE", compose.onAllNodesWithText("LIVE").fetchSemanticsNodes().isNotEmpty())
        assertTrue("live cards show their audience", compose.onAllNodesWithText("watching", substring = true).fetchSemanticsNodes().isNotEmpty())
        downTo("Your channels")
        compose.onNodeWithText("Channel 1").assertExists()
        // One channel: its newest upload is the hero, so New from your subscriptions has nothing.
        compose.onNodeWithText("New from your subscriptions").assertDoesNotExist()
    }

    @Test
    fun aHeroWhosePageIsALiveStreamShowsLiveInPlaceOfALength() {
        extractor.feed = feedPage
        extractor.heroPage = recorded(LIVE_VIDEO_FLOW) { video(VideoId(RecordedLiveVideo.ID)) }
        extractor.live = null // no Live now row: the only LIVE is the hero's
        launch(RECORDED_CHANNEL_URL)

        compose.onAllNodesWithText("LIVE").assertCountEquals(1)
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun whileLoadingNothingTakesFocusAndPlayIsFocusedOnceContentLands() {
        val gate = CompletableDeferred<Unit>()
        extractor.feedGate = gate
        recordedHome()

        compose.onNode(button("Play")).assertDoesNotExist()
        compose.onAllNodes(isFocused()).assertCountEquals(0)

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun everythingFailingOfflineIsTheNoConnectionStatementWithTryAgainFocused() {
        extractor.live = null
        extractor.liveFailure = UnknownHostException("www.youtube.com")
        launch()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Home couldn’t load").assertExists()
        compose.onNodeWithText("Last attempt", substring = true).assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun everythingFailingOnlineIsTheYouTubeChangedSomethingStatement() {
        extractor.live = null
        launch()

        compose.onNodeWithText("YouTube changed something").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun tryAgainReloadsAndFocusMovesToWhatLoaded() {
        extractor.live = null
        launch()

        extractor.live = livePage
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Follow channels to fill Home").assertExists()
        pageSearchButton().assertIsFocused()
    }

    @Test
    fun noSubscriptionsIsTheBlankSlateWithSearchFocusedAndLiveNowBelow() {
        extractor.live = livePage
        launch()

        compose.onNodeWithText("No subscriptions yet").assertExists()
        compose.onNodeWithText("Live now").assertExists()
        pageSearchButton().assertIsFocused()
    }

    @Test
    fun theBlankSlatesSearchOpensSearch() {
        launch()

        pageSearchButton().dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("Search") and !hasClickAction()).assertExists()
    }

    @Test
    fun everyFeedFailingIsTheCompactStatementWithYourChannelsByNameOnly() {
        extractor.live = livePage
        launch(RECORDED_CHANNEL_URL, "https://www.youtube.com/channel/UCsecond")

        compose.onNodeWithText("Couldn’t load your subscriptions").assertExists()
        compose.onNodeWithText("None of your 2 channels’ feeds came back. Try again, or open a channel below.").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
        compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("a focused row stops under the compact statement", 192f, compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top, 2f)
        downTo("Channel 2")
        compose.onAllNodesWithText("ago", substring = true).assertCountEquals(0)
    }

    @Test
    fun continueWatchingLeadsTheRowsWithEachVideosBarAndWhatsLeft() {
        watch.entries.value = listOf(
            WatchEntry(
                videoId = VideoId("JsBZOcqZerk"),
                title = "History and operating principles of the Enigma decryption machine from World War 2",
                uploaderName = "Veritasium",
                uploaderUrl = null,
                thumbnailUrl = null,
                positionMs = 2_091_000,
                durationMs = 2_862_000,
                watched = false,
                lastWatchedAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
        recordedHome()

        compose.onNodeWithText("Veritasium · 12:51 left").assertExists()
        compose.onNode(button("Play")).assertIsFocused()
        downTo("Live now")
        val continueTop = compose.onNodeWithText("Continue watching").fetchSemanticsNode().boundsInRoot.top
        val liveTop = compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Continue watching leads the rows", continueTop < liveTop)
    }

    @Test
    fun theHerosWatchLaterSavesItAndSaysSo() {
        recordedHome()

        compose.onNode(button("Watch later")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("In Watch later")).assertIsFocused()
        val saved = runBlocking { playlists.watchLater().first() }.map { it.video.id.value }
        assertEquals(listOf(RecordedChannel.FEED_ENTRIES.first().first), saved)
    }

    @Test
    fun theHerosMoreOpensItsCardMenu() {
        recordedHome()

        compose.onNode(hasContentDescription("More") and hasClickAction()).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Save to playlist")).assertExists()
    }

    @Test
    fun theErrorStatementSaysTheLibraryStillWorksAndOpensIt() {
        extractor.live = null
        extractor.liveFailure = UnknownHostException("www.youtube.com")
        launch()

        compose.onNodeWithText("Your library and settings still work offline.", substring = true).assertExists()
        compose.onNode(button("Open Library")).dpadSelect()
        compose.waitForIdle()

        assertTrue(nav.currentDestination!!.hasRoute(LibraryRoute::class))
    }

    @Test
    fun theUnreadableErrorSaysTheLibraryStillWorks() {
        extractor.live = null
        launch()

        compose.onNodeWithText("This usually needs an Ayn update. Your library and settings still work.", substring = true).assertExists()
        compose.onNodeWithText("still work offline", substring = true).assertDoesNotExist()
        compose.onNode(button("Open Library")).assertExists()
    }

    @Test
    fun backFromAVideoReturnsFocusToItsCard() {
        recordedHome()
        val liveCards = hasClickAction() and hasText("LIVE")
        val opened = compose.onAllNodes(liveCards)[1]
        val openedCenterX = opened.fetchSemanticsNode().boundsInRoot.center.x
        opened.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        compose.pressBack()

        // The focused card grows and draws above its row, so it is found by place, not order.
        val focused = compose.onNode(liveCards and isFocused()).fetchSemanticsNode()
        assertEquals(openedCenterX, focused.boundsInRoot.center.x, 0.5f)
    }

    /** PERF-D10: BACK to a row that was below the screen at rest brings it back at its stop, its card focused. */
    @Test
    fun backFromAVideoInALowerRowReturnsToItsStopWithItsCardFocused() {
        watch.entries.value = continuing("First")
        recordedHome()
        compose.onNodeWithText("Live now").assertDoesNotExist()
        repeat(2) { compose.press(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        val liveCards = hasClickAction() and hasText("LIVE")
        val opened = compose.onNode(liveCards and isFocused())
        val openedCenterX = opened.fetchSemanticsNode().boundsInRoot.center.x
        opened.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        compose.pressBack()

        assertEquals(openedCenterX, compose.onNode(liveCards and isFocused()).fetchSemanticsNode().boundsInRoot.center.x, 0.5f)
        assertEquals(160f, compose.onNodeWithText("Live now").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    /** A video finished from Continue watching leaves the row; BACK focuses the card that took its place. */
    @Test
    fun backAfterAFinishedContinueWatchingVideoFocusesTheCardNowInItsPlace() {
        watch.entries.value = continuing("First", "Second", "Third")
        recordedHome()
        cardOf("Second to continue").dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        finish("Second")

        compose.pressBack()

        cardOf("Third to continue").assertIsFocused()
    }

    @Test
    fun backAfterTheLastContinueWatchingVideoFinishedFocusesTheCardBeforeIt() {
        watch.entries.value = continuing("First", "Second", "Third")
        recordedHome()
        cardOf("Third to continue").dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        finish("Third")

        compose.pressBack()

        cardOf("Second to continue").assertIsFocused()
    }

    /** A video left part-way leads the row on return, the row scrolled past it: the row comes back showing it, focused. */
    @Test
    fun backAfterAPartWatchedVideoFarAlongContinueWatchingFocusesItAtTheRowsStart() {
        watch.entries.value = continuing("First", "Second", "Third", "Fourth", "Fifth")
        recordedHome()
        cardOf("First to continue").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(4) { compose.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
        cardOf("Fifth to continue").assertIsFocused()
        compose.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.assertPlayerShows()
        watch.entries.value = listOf(resumable("Fifth", WATCHED_AT + 1)) + watch.entries.value.filterNot { it.videoId == continueId("Fifth") }

        compose.pressBack()

        cardOf("Fifth to continue").assertIsFocused()
        val rowStart = compose.onNodeWithText("Continue watching").fetchSemanticsNode().boundsInRoot.left
        assertTrue("the row shows its start", cardOf("Fifth to continue").fetchSemanticsNode().boundsInRoot.left < rowStart + 20)
    }

    /** Continue watching entries titled "<name> to continue", most recently watched first. */
    private fun continuing(vararg names: String): List<WatchEntry> = names.mapIndexed { i, name -> resumable(name, WATCHED_AT - i) }

    private fun resumable(name: String, at: Long): WatchEntry =
        WatchEntry(
            videoId = continueId(name),
            title = "$name to continue",
            uploaderName = "Resumer",
            uploaderUrl = null,
            thumbnailUrl = null,
            positionMs = 60_000,
            durationMs = 600_000,
            watched = false,
            lastWatchedAtMillis = at,
            updatedAtMillis = at,
        )

    private fun continueId(name: String): VideoId = VideoId("cw$name".padEnd(11, '0'))

    /** [name]'s video played to its end: it leads the history, watched, and leaves Continue watching. */
    private fun finish(name: String) {
        val (done, rest) = watch.entries.value.partition { it.videoId == continueId(name) }
        watch.entries.value = done.map { it.copy(positionMs = 600_000, watched = true, lastWatchedAtMillis = WATCHED_AT + 1) } + rest
    }
}

private fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot, size.toSize())

private val WATCHED_AT = (RecordedChannel.FEED_CLOCK - 1.hours).toEpochMilliseconds()

private val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
private val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)

/** The recorded feed's newest upload, as YouTube titles it. */
private const val HERO_TITLE = "Rick Astley - Angels On My Side (Live at The O2, London, April 2026)"
private const val HERO_DEARROW_TITLE = "Rick Astley performs Angels On My Side live at The O2"
private const val SHOWN_PICK = "The next pick, as DeArrow names it"
/** A DeArrow frame URL without the video id, so YouTube's picture is told apart by the id. */
private const val HERO_DEARROW_FRAME = "https://dearrow-thumb.test/hero-frame.webp"

/** More rows than any test's Home has. */
private const val MAX_ROWS = 6

/** Coil asks for pictures off the main thread. */
private const val PICTURE_WAIT_MS = 5_000L
