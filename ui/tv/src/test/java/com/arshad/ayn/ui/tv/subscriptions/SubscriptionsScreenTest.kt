package com.arshad.ayn.ui.tv.subscriptions

import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.playerViewModel
import com.arshad.ayn.ui.tv.TestShell
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.SubscriptionsExtractor
import com.arshad.ayn.ui.tv.RailEdgeDp
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.ui.tv.theme.weightSpan
import java.net.UnknownHostException
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import com.arshad.ayn.ui.tv.common.GridCellWidth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Subscriptions through the real shell and [com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel]
 * on the recorded Rick Astley feed (published 06-26, -24, -22, -19; the clock at 2026-06-28 16:00
 * UTC, a Sunday) beside a failing and an empty channel: each `b3-subscriptions` state's content and
 * where focus lands. The device's zone is pinned to UTC; Robolectric's en-US weeks start on
 * Sunday, so the feed reads Last week (three) then June (one). Spatial D-pad moves are
 * device-only; focus, select and BACK are driven here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SubscriptionsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = SubscriptionsExtractor()
    private val feedPage = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
    private val zone = TimeZone.getDefault()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        installOfflineImageLoader(compose.activity)
        extractor.feeds[RECORDED_CHANNEL_URL] = feedPage
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(zone)
    }

    /** Launches the shell subscribed to [channels] ("Channel 1", "Channel 2" …) and opens Subscriptions. */
    private fun open(vararg channels: String) {
        val clock = FixedClock(RecordedChannel.FEED_CLOCK)
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor, SubscriptionsFeed(extractor, clock), FakeSubscriptionStore(*channels), profileSettings = settings, navController = navController)
        }
        compose.waitForIdle()
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Subscriptions").dpadSelect()
        compose.waitForIdle()
    }

    // Pages compose before the rail, so the rail's item is the last match.
    private fun rail(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onLast()

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun avatar(name: String): SemanticsMatcher = hasContentDescription(name) and hasClickAction()

    /** Into the strip (focus entering it lands on the selection, All), then along it to [name]. */
    private fun focusAvatar(name: String) {
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(avatar(name)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aChannelWithoutAPictureShowsItsMonogramInTheStrip() {
        open(RECORDED_CHANNEL_URL)

        compose.onNode(hasText("C1") and hasAnyAncestor(avatar("Channel 1")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun withShowShortsOnEveryChannelsShortsFollowTheFirstDay() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)

        compose.onNodeWithText("From your channels").assertExists()
        // Last week's three uploads fill one row; the shelf comes next, before June.
        assertTrue("the shelf follows Last week", top("Last week") < top("Shorts"))
        assertTrue(top("Shorts") > compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.width >= 179.5f }.minOf { it.boundsInRoot.bottom })
        compose.onNodeWithText("June").assertDoesNotExist() // below the shelf, out of view
        assertFirstCardFocused()
    }

    @Test
    fun aPickedChannelsShortsFollowItsFirstRowAndOneOpensARunThroughThem() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)
        focusAvatar("Channel 1")

        compose.onNodeWithText("From Channel 1").assertExists()
        val firstVideo = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width >= 179.5f && it.boundsInRoot.top > top("Latest from Channel 1") }
            .minBy { it.boundsInRoot.top }
        assertTrue("the shelf follows the first row", top("Shorts") > firstVideo.boundsInRoot.bottom)
        // Clipped by the screen's bottom, so found by width and place.
        val shorts = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width in 114f..127f && it.boundsInRoot.top > top("Shorts") }
            .sortedBy { it.boundsInRoot.left }
        assertTrue("its Shorts are listed", shorts.isNotEmpty())

        compose.onNode(SemanticsMatcher("the newest Short") { it.id == shorts.first().id }).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // A focused row settles under the top fade with room for a heading above it.
        assertTrue("the shelf's heading stays in view", top("Shorts") >= FeedTopDp + FadeDp)

        compose.onNode(SemanticsMatcher("the newest Short") { it.id == shorts.first().id }).dpadSelect()
        compose.waitForIdle()

        assertEquals(RecordedChannel.FEED_SHORTS.first().first, navController.playerRoute()?.videoId)
        assertEquals(true, navController.playerRoute()?.short)
        assertTrue(checkNotNull(navController.playerViewModel().uiState.value.shorts).hasNext)
    }

    /** The shelf's newest Short opened, the run moved on to Short [last], then BACK. */
    private fun returnFromAShortsRun(last: Int) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)
        val newest = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width in 114f..127f && it.boundsInRoot.top > top("Shorts") }
            .minBy { it.boundsInRoot.left }
        compose.onNode(SemanticsMatcher("the newest Short") { it.id == newest.id }).dpadSelect()
        compose.waitForIdle()
        val player = navController.playerViewModel()
        compose.runOnUiThread { repeat(last) { player.nextShort() } }
        compose.waitForIdle()
        compose.pressBack()
    }

    @Test
    fun backFromAShortsRunFocusesTheLastPlayedShortWithTheShelfScrolledToIt() {
        returnFromAShortsRun(last = 8)

        // Short 8 is past the six shown slots: it comes back on the last one, where D-pad brings it.
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("a Short holds focus", focused.height > ShortCardWidthDp)
        assertEquals(ContentStartDp + 5 * ShelfStepDp + ShortCardWidthDp / 2, focused.center.x, 0.5f)
        val title = feedPage.shorts.first { it.id.value == RecordedChannel.FEED_SHORTS[8].first }.title
        assertTrue(
            "it is Short 8",
            compose.onAllNodesWithText(title).fetchSemanticsNodes().any { it.boundsInRoot.center.x in focused.left..focused.right },
        )
    }

    @Test
    fun afterAShortsReturnBackFromAVideoReturnsToItsCard() {
        returnFromAShortsRun(last = 8)
        // Up to Last week's videos, above the shelf.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        compose.waitForIdle()
        val video = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().first { it.boundsInRoot.width >= 179.5f }
        val videoCard = compose.onNode(SemanticsMatcher("a feed video") { it.id == video.id })
        videoCard.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val opened = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center

        videoCard.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        compose.pressBack()

        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(opened.x, focused.x, 0.5f)
        assertEquals(opened.y, focused.y, 0.5f)
    }

    @Test
    fun theFeedGroupsByUploadDayWithAgesAndTheFirstCardFocused() {
        open(RECORDED_CHANNEL_URL)

        assertTrue("Last week heads the feed, June follows", top("Last week") < top("June"))
        compose.onNodeWithText("1 channel · updated", substring = true).assertExists()
        compose.onNodeWithText("· 1 day ago", substring = true).assertExists() // 06-26 16:00:07, 7 s short of two days
        assertFirstCardFocused()
        compose.onNodeWithText("Shorts").assertDoesNotExist() // Show Shorts is off
        assertEquals("the strip's avatars keep their size", 38f, compose.onNode(avatar("Channel 1")).fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    @Test
    fun thePartialNoteNamesTheFailedChannelAndTryAgainBringsItBack() {
        extractor.failures[FAILING] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL, FAILING)

        val note = "1 channel didn’t refresh: Channel 2. Its videos are missing until it answers."
        compose.onNodeWithText(note).assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
        val lead = compose.onNodeWithText(note).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            .spanStyles.single { it.start == 0 }
        assertEquals("the lead's weight moves the variable font", weightSpan(640).fontFamily, lead.item.fontFamily)
        assertEquals(FontWeight(640), lead.item.fontWeight)

        extractor.failures.clear()
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("didn’t refresh", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2 channels · updated", substring = true).assertExists()
        assertFirstCardFocused()
    }

    @Test
    fun focusingAChannelNarrowsTheFeedToItsLatestVideosAndAllRestoresIt() {
        extractor.failures[FAILING] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL, FAILING, EMPTY)

        focusAvatar("Channel 1")
        compose.onNodeWithText("Latest from Channel 1").assertExists()
        compose.onNodeWithText("Channel 1 · 4 latest videos").assertExists()
        compose.onNodeWithText("Last week").assertDoesNotExist()

        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithText("Last week").assertExists()
        compose.onNodeWithText("3 channels · updated", substring = true).assertExists()
    }

    @Test
    fun aChannelFocusOnlyPassesOverIsNeverSelectedAndTheOneItRestsOnIs() {
        extractor.failures[FAILING] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL, EMPTY, FAILING)
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNode(avatar("Channel 2")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(avatar("Channel 3")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs - 40)

        compose.onNodeWithText("Last week").assertExists()
        compose.onNodeWithText("Nothing from Channel 2 yet").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Nothing to show from Channel 3").assertExists()
        compose.onNode(avatar("Channel 3")).assertIsFocused()
    }

    @Test
    fun focusLeavingTheStripBeforeItRestsSelectsNothing() {
        open(RECORDED_CHANNEL_URL, EMPTY)
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val card = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .first { it.boundsInRoot.width >= 179.5f && it.boundsInRoot.top > FeedTopDp }
        compose.mainClock.autoAdvance = false

        compose.onNode(avatar("Channel 2")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(SemanticsMatcher("a feed card") { it.id == card.id }).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        compose.onNodeWithText("Last week").assertExists()
        compose.onNodeWithText("Nothing from Channel 2 yet").assertDoesNotExist()
        compose.onNode(SemanticsMatcher("a feed card") { it.id == card.id }).assertIsFocused()
    }

    @Test
    fun okOnAChannelSelectsItWithoutWaiting() {
        open(RECORDED_CHANNEL_URL, EMPTY)
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNode(avatar("Channel 2")).dpadSelect()
        compose.mainClock.advanceTimeBy(SelectDwellMs / 3)

        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()
    }

    @Test
    fun focusComingBackToTheSelectionBeforeItRestsSelectsNothingNew() {
        open(RECORDED_CHANNEL_URL, EMPTY)
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNode(avatar("Channel 2")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        compose.onNodeWithText("Last week").assertExists()
        compose.onNodeWithText("Nothing from Channel 2 yet").assertDoesNotExist()
    }

    @Test
    fun allIsSelectedOnceFocusRestsOnIt() {
        open(RECORDED_CHANNEL_URL, EMPTY)
        focusAvatar("Channel 2")
        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()
        compose.mainClock.autoAdvance = false

        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs - 40)
        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()

        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Last week").assertExists()
    }

    @Test
    fun oneFeedGridServesEverySelectionAndEachStartsAtItsTop() {
        // With the shelf, every channel's feed and Channel 1's both run past the screen.
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)
        val grid = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val allGrid = compose.onNode(grid).fetchSemanticsNode().id
        val headingTop = top("Last week")
        scrollFeedToItsEnd(last = 6)
        compose.onNodeWithText("Last week").assertDoesNotExist()

        focusAvatar("Channel 1")
        assertEquals("the grid is the same node", allGrid, compose.onNode(grid).fetchSemanticsNode().id)
        assertEquals(headingTop, top("Latest from Channel 1"), 0.5f)
        scrollFeedToItsEnd(last = 5)
        compose.onNodeWithText("Latest from Channel 1").assertDoesNotExist()

        compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertEquals(allGrid, compose.onNode(grid).fetchSemanticsNode().id)
        assertEquals(headingTop, top("Last week"), 0.5f)
    }

    /** A selection's rows join one per frame, so no frame builds the whole screen of cards: its
     *  first row (four videos) lands with the heading, the Shorts shelf below it a few frames on. */
    @Test
    fun aNewSelectionsRowsJoinOnePerFrame() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)

        assertRowsJoinAfterSelecting("Channel 1")
    }

    /** A channel showing a statement in between doesn't make the next feed count as the first. */
    @Test
    fun aFeedAfterAStatementStillJoinsRowByRow() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL, EMPTY)
        focusAvatar("Channel 2")
        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()

        assertRowsJoinAfterSelecting("Channel 1", fromAll = false)
    }

    /** Arriving from the rail, the feed lands whole: focus may be coming back to any card. */
    @Test
    fun theFirstFeedShownLandsWithEveryRow() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor, SubscriptionsFeed(extractor, FixedClock(RecordedChannel.FEED_CLOCK)), FakeSubscriptionStore(RECORDED_CHANNEL_URL), profileSettings = settings, navController = navController)
        }
        compose.waitForIdle()
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        rail("Subscriptions").dpadSelect()
        advanceUntilShown("Last week")

        compose.onNodeWithText("From your channels").assertExists()
    }

    private fun assertRowsJoinAfterSelecting(channel: String, fromAll: Boolean = true) {
        if (fromAll) {
            compose.onNode(hasText("All") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
        }
        compose.mainClock.autoAdvance = false

        compose.onNode(avatar(channel)).dpadSelect()
        advanceUntilShown("Latest from $channel")

        val cardWidth = with(compose.density) { GridCellWidth.toPx() } - 0.5f
        val cards = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().count { it.boundsInRoot.width >= cardWidth }
        assertEquals("only the first row's cards are built (and focusable)", 4, cards)
        compose.onNodeWithText("From $channel").assertDoesNotExist()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("From $channel").assertExists()
    }

    private fun advanceUntilShown(text: String) {
        var frames = 0
        while (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            check(++frames < 60) { "\"$text\" never showed" }
        }
    }

    @Test
    fun anotherSelectionsShelfStartsAtItsFirstShort() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)
        val newest = feedPage.shorts.first { it.id.value == RecordedChannel.FEED_SHORTS.first().first }.title
        val shelf = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().single { it.boundsInRoot.top > FeedTopDp }
        compose.onNode(SemanticsMatcher("the shelf") { it.id == shelf.id }).performScrollToIndex(RecordedChannel.FEED_SHORTS.lastIndex)
        compose.waitForIdle()
        compose.onNodeWithText(newest).assertDoesNotExist()

        focusAvatar("Channel 1")

        compose.onNodeWithText("From Channel 1").assertExists()
        compose.onNodeWithText(newest).assertExists()
    }

    @Test
    fun backFromAVideoFarDownTheFeedReturnsFocusToItsCard() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        open(RECORDED_CHANNEL_URL)
        scrollFeedToItsEnd(last = 6)
        // June's one upload, below the shelf: out of view until the feed scrolls.
        val june = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width >= 179.5f && it.boundsInRoot.top > top("June") }
            .minBy { it.boundsInRoot.top }
        val card = compose.onNode(SemanticsMatcher("June's card") { it.id == june.id })
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val opened = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center

        card.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        compose.pressBack()

        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(opened.x, focused.x, 0.5f)
        assertEquals(opened.y, focused.y, 0.5f)
    }

    /** To the feed grid's [last] item: every channel's is 6 (Last week's heading and three
     *  uploads, the shelf, June's heading and upload), Channel 1's 5 (its heading, four uploads, the shelf). */
    private fun scrollFeedToItsEnd(last: Int) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(last)
        compose.waitForIdle()
    }

    @Test
    fun aFocusedChannelThatFailedSaysSoWithTryAgain() {
        extractor.failures[FAILING] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL, FAILING)

        focusAvatar("Channel 2")

        compose.onNodeWithText("Nothing to show from Channel 2").assertExists()
        compose.onNodeWithText("Channel 2 · didn’t refresh").assertExists()
        compose.onNode(button("Try again")).assertExists()
        compose.onNode(avatar("Channel 2")).assertIsFocused()
    }

    @Test
    fun aFocusedChannelWithNoVideosOffersItsPage() {
        open(RECORDED_CHANNEL_URL, EMPTY)
        focusAvatar("Channel 2")

        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()
        compose.onNodeWithText("Channel 2 · no videos").assertExists()
        compose.onNode(button("Open channel")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("This channel couldn’t load").assertExists()
        assertEquals(EMPTY, navController.currentBackStackEntry?.toRoute<ChannelRoute>()?.channelUrl)
    }

    @Test
    fun everyChannelOfflineIsTheNoConnectionStatementWithTryAgainFocused() {
        extractor.failures[RECORDED_CHANNEL_URL] = UnknownHostException("www.youtube.com")
        extractor.failures[FAILING] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL, FAILING)

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("None of your 2 channels answered. Your list is safe on this Shield; try again in a moment.").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun anUnreadableReplySaysYouTubeChangedSomething() {
        extractor.failures[RECORDED_CHANNEL_URL] = IllegalStateException("unreadable")
        open(RECORDED_CHANNEL_URL)

        compose.onNodeWithText("YouTube changed something").assertExists()
        compose.onNodeWithText("Subscriptions couldn’t refresh").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun noSubscriptionsIsTheBlankSlateAndFindAChannelOpensSearch() {
        open()

        compose.onNodeWithText("Follow channels without an account").assertExists()
        compose.onNode(button("Find a channel")).assertIsFocused()

        compose.onNode(button("Find a channel")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun arrivingFromTheRailWhileLoadingKeepsFocusThereUntilTheFirstCardTakesIt() {
        val gate = CompletableDeferred<Unit>()
        extractor.feedGate = gate
        open(RECORDED_CHANNEL_URL)

        compose.onNodeWithText("1 channel").assertExists()
        rail("Subscriptions").assertIsFocused()

        gate.complete(Unit)
        compose.waitForIdle()
        assertFirstCardFocused()
    }

    @Test
    fun backFromAVideoReturnsFocusToItsCard() {
        open(RECORDED_CHANNEL_URL)
        // The second card, so landing back on the first (the screen's initial focus) can't pass.
        val second = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > FeedTopDp && it.boundsInRoot.left > RailEdgeDp }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))[1]
        val opened = compose.onNode(SemanticsMatcher("the second card") { it.id == second.id })
        opened.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val openedCenter = opened.fetchSemanticsNode().boundsInRoot.center
        opened.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        compose.pressBack()

        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(openedCenter.x, focused.x, 0.5f)
        assertEquals(openedCenter.y, focused.y, 0.5f)
    }

    /** The first grid slot (mdpi: 1 dp = 1 px): focus scales a card about its centre and lifts it. */
    private fun assertFirstCardFocused() {
        val center = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(FirstCardCenterX, center.x, 0.5f)
        assertEquals(FirstCardCenterY, center.y, 0.5f)
    }

    private companion object {
        // FeedGrid's 14 dp top fade under the feed's top.
        const val FadeDp = 14f
        const val FAILING = "https://www.youtube.com/channel/UCfailing"
        const val EMPTY = "https://www.youtube.com/channel/UCempty"
        const val FeedTopDp = 145f
        const val FocusLift = 3f
        const val ContentStartDp = 126f
        const val ShortCardWidthDp = 115f
        const val ShelfStepDp = ShortCardWidthDp + 17f
        // ContentStart + half a 180 card; feed top 145 + grid top 13 + the first heading (20 + 11)
        // + half the thumb (101.25 laid out as 101), less the lift.
        const val FirstCardCenterX = 126f + 90f
        const val FirstCardCenterY = 145f + 13f + 31f + 101f / 2 - FocusLift
    }
}
