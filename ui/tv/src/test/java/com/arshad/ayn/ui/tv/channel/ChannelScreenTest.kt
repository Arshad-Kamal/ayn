package com.arshad.ayn.ui.tv.channel

import com.arshad.ayn.ui.tv.TestKidOpening
import com.arshad.ayn.ui.tv.TestOpening
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.testKidsShell
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.newpipe.RecordedShortsTab
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.playerViewModel
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.core.access.recorded
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A channel page through the real shell and [com.arshad.ayn.feature.channel.ChannelViewModel]
 * on the recorded Rick Astley channel (header "4.55m subscribers"; tabs Videos, Shorts, Live,
 * Releases, Playlists; 30 uploads, the first four "3 months ago"): each `b3-channel` state's
 * content and where focus lands, the header folding while the cards hold focus, and BACK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ChannelScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = ChannelExtractor()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private lateinit var store: FakeSubscriptionStore
    private lateinit var navController: NavHostController
    private lateinit var focusManager: FocusManager

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    /** Launches the shell (subscribed to the recorded channel when [subscribed]; a kids profile's
     *  when [kids]) and opens its page. */
    private fun open(subscribed: Boolean = true, kids: Boolean = false) {
        store = if (subscribed) FakeSubscriptionStore(RECORDED_CHANNEL_URL) else FakeSubscriptionStore()
        compose.setContent {
            navController = rememberNavController()
            focusManager = LocalFocusManager.current
            TestShell(
                extractor,
                subscriptionStore = store,
                profileSettings = settings,
                opening = if (kids) TestKidOpening else TestOpening,
                navController = navController,
                kids = if (kids) testKidsShell() else null,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(ChannelRoute(RECORDED_CHANNEL_URL)) }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun focus(label: String) {
        compose.onNode(button(label)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    /** Into the tabs (focus entering them lands on the selected one, Videos), then along them to [label]. */
    private fun focusTab(label: String) {
        focus("Videos")
        focus(label)
    }

    /** The tab grid's cards in reading order: the clickable nodes a card wide (tabs and buttons are narrower). */
    private fun cards(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width >= CardWidthDp }
        .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

    private fun focusCard(index: Int): SemanticsNode {
        val card = cards()[index]
        compose.onNode(SemanticsMatcher("card $index") { it.id == card.id }).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return card
    }

    private fun focusedCenter() = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center

    /** The Shorts tab's cards in reading order: the clickable nodes a Short card wide and tall. */
    private fun shortCards(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width in ShortCardWidthDp - 1f..ShortCardWidthDp * FocusScaleMax && it.boundsInRoot.height > ShortCardWidthDp }
        .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

    private fun withRecordedShortsTab() {
        extractor.tabs[ChannelTab.SHORTS] = recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }
    }

    @Test
    fun thePageShowsTheHeaderTabsAndUploadsWithSubscribedFocused() {
        open()

        compose.onNodeWithText("Rick Astley").assertExists()
        // 4.55m, abbreviated without rounding up; the Videos tab's first page counted.
        compose.onNodeWithText("4.5M subscribers · ${RecordedChannel.UPLOADS_TAB_VIDEOS} latest videos").assertExists()
        listOf("Videos", "Live", "Releases", "Playlists").forEach { compose.onNode(button(it)).assertExists() }
        compose.onNode(button("Shorts")).assertDoesNotExist()
        compose.onNodeWithText("Rick Astley’s Swinging Christmas 2026", substring = true).assertExists()
        assertTrue("the uploads' ages show", compose.onAllNodes(hasText("3 months ago")).fetchSemanticsNodes().size >= 4)
        compose.onNode(button("Subscribed")).assertIsFocused()
    }

    @Test
    fun subscribingFlipsTheButtonAndUnsubscribingIsConfirmedUntilUndone() {
        open(subscribed = false)
        compose.onNode(button("Subscribe")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Subscribed")).assertIsFocused()
        assertTrue(store.isSubscribed(RECORDED_CHANNEL_URL))
        compose.onNodeWithText(UnsubscribedToast, substring = true).assertDoesNotExist()

        compose.onNode(button("Subscribed")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(button("Subscribe")).assertExists()
        assertFalse(store.isSubscribed(RECORDED_CHANNEL_URL))
        compose.onNodeWithText("$UnsubscribedToast · press Subscribe to undo").assertExists()

        compose.onNode(button("Subscribe")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText(UnsubscribedToast, substring = true).assertDoesNotExist()
    }

    @Test
    fun focusingATabSelectsItAndAPlaylistCardOpensItsPage() {
        extractor.playlistGate = CompletableDeferred()
        open()
        focusTab("Playlists")

        val (listId, title, count) = RecordedChannel.PLAYLISTS_TAB_FIRST.first()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("$count videos").assertExists()
        compose.onNodeWithText("3 months ago").assertDoesNotExist()

        compose.onNode(SemanticsMatcher("the first playlist") { it.id == cards().first().id }).dpadSelect()
        compose.waitForIdle()
        // The page's header comes from the card while the playlist loads.
        compose.onNodeWithText("Playlist").assertExists()
        compose.onNodeWithText(title).assertExists()
        val route = navController.currentBackStackEntry?.toRoute<PlaylistRoute>()
        assertTrue("opens ${route?.url}", route?.url?.endsWith("list=$listId") == true)
        assertEquals(title, route?.title)
    }

    @Test
    fun aTabFocusOnlyPassesOverIsNeverFetchedAndTheOneItRestsOnIs() {
        open()
        focus("Videos")
        // Home behind the page asks for the subscribed channel's uploads too.
        extractor.tabCalls.clear()
        compose.mainClock.autoAdvance = false

        compose.onNode(button("Live")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(button("Releases")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(button("Playlists")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs - 40)

        assertEquals(emptyList<ChannelTab>(), extractor.tabCalls)
        assertTrue("Videos' cards still show", compose.onAllNodes(hasText("3 months ago")).fetchSemanticsNodes().isNotEmpty())

        compose.mainClock.advanceTimeBy(80)
        assertEquals(listOf(ChannelTab.PLAYLISTS), extractor.tabCalls)
        compose.onNodeWithText(RecordedChannel.PLAYLISTS_TAB_FIRST.first().second).assertExists()
        compose.onNode(button("Playlists")).assertIsFocused()
    }

    @Test
    fun focusLeavingTheTabsBeforeItRestsSelectsNothing() {
        open()
        focus("Videos")
        extractor.tabCalls.clear()
        compose.mainClock.autoAdvance = false

        compose.onNode(button("Playlists")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(button("Subscribed")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        assertEquals(emptyList<ChannelTab>(), extractor.tabCalls)
        assertTrue("Videos' cards still show", compose.onAllNodes(hasText("3 months ago")).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(button("Subscribed")).assertIsFocused()
    }

    @Test
    fun focusComingBackToTheSelectedTabBeforeItRestsSelectsNothingNew() {
        open()
        focus("Videos")
        extractor.tabCalls.clear()
        compose.mainClock.autoAdvance = false

        compose.onNode(button("Playlists")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        compose.onNode(button("Videos")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        assertEquals(emptyList<ChannelTab>(), extractor.tabCalls)
        compose.onNodeWithText(RecordedChannel.PLAYLISTS_TAB_FIRST.first().second).assertDoesNotExist()
    }

    @Test
    fun okOnATabSelectsItWithoutWaiting() {
        open()
        focus("Videos")
        extractor.tabCalls.clear()
        compose.mainClock.autoAdvance = false

        compose.onNode(button("Playlists")).dpadSelect()
        compose.mainClock.advanceTimeBy(SelectDwellMs / 3)

        assertEquals(listOf(ChannelTab.PLAYLISTS), extractor.tabCalls)
        compose.onNodeWithText(RecordedChannel.PLAYLISTS_TAB_FIRST.first().second).assertExists()
    }

    @Test
    fun tabsOfCardsShareOneGrid() {
        open()
        focusTab("Playlists")
        focus("Videos")
        val grid = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val videosGrid = compose.onNode(grid).fetchSemanticsNode().id

        focus("Playlists")

        compose.onNodeWithText(RecordedChannel.PLAYLISTS_TAB_FIRST.first().second).assertExists()
        assertEquals("the grid is the same node", videosGrid, compose.onNode(grid).fetchSemanticsNode().id)
    }

    @Test
    fun focusComingDownFromSubscribeLandsOnTheSelectedTab() {
        open()
        focusTab("Playlists")
        focus("Subscribed")

        compose.runOnUiThread { focusManager.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()

        compose.onNode(button("Playlists")).assertIsFocused()
    }

    @Test
    fun upFromTheFoldedCardsLandsOnTheSelectedTabAndUnfolds() {
        open()
        focusCard(3)

        compose.runOnUiThread { focusManager.moveFocus(FocusDirection.Up) }
        compose.waitForIdle()

        compose.onNode(button("Videos")).assertIsFocused()
        assertEquals(TabCenterY, focusedCenter().y, 0.5f)
        assertTrue("Videos' cards still show", compose.onAllNodes(hasText("3 months ago")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aNewlySelectedTabThatFailsFailsThePageWithTryAgainFocused() {
        extractor.tabs.remove(ChannelTab.LIVE)
        open()
        focusTab("Live")

        compose.onNodeWithText("This channel couldn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun aTabPickedForTheFirstTimeWaitsAsSkeletonsWithFocusOnIt() {
        open()
        val gate = CompletableDeferred<Unit>()
        extractor.tabGate = gate
        focusTab("Playlists")

        val title = RecordedChannel.PLAYLISTS_TAB_FIRST.first().second
        compose.onNodeWithText(title).assertDoesNotExist()
        assertTrue("no cards while the tab loads", cards().isEmpty())
        compose.onNode(button("Playlists")).assertIsFocused()

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(title).assertExists()
        compose.onNode(button("Playlists")).assertIsFocused()
    }

    @Test
    fun anEmptyTabSaysSoWithFocusOnIt() {
        extractor.tabs[ChannelTab.LIVE] = ChannelExtractor.Empty
        open()
        focusTab("Live")

        compose.onNodeWithText("Nothing in Live yet").assertExists()
        compose.onNodeWithText("This channel hasn’t put anything here. Its other tabs may have more.").assertExists()
        compose.onNode(button("Live")).assertIsFocused()
    }

    @Test
    fun aChannelWithNoTabsOpensOnAnEmptyVideosThatPointsNowhereElse() {
        // A Topic channel: no tabs in its header, nothing in Videos (finding B14-3).
        extractor.header = ChannelExtractor.RecordedHeader.copy(tabs = emptyList())
        extractor.tabs[ChannelTab.VIDEOS] = ChannelExtractor.Empty
        open()

        compose.onNodeWithText("Nothing in Videos yet").assertExists()
        compose.onNodeWithText("This channel hasn’t put anything here.").assertExists()
    }

    @Test
    fun focusingACardFoldsTheHeaderAndTheTabsBringItBack() {
        open()
        focusCard(0)

        // The page has risen: the first row settles under the folded tabs' place, TopAlign's
        // known gap (the focused thumb's scale and lift, 7 dp) lower.
        val folded = focusedCenter()
        assertEquals(FirstCardCenterX, folded.x, 0.5f)
        assertEquals(FoldedFirstCardCenterY, folded.y, 0.5f)

        focus("Videos")
        assertEquals(TabCenterY, focusedCenter().y, 0.5f)
        assertEquals(UnfoldedFirstRowTop, cards().first().boundsInRoot.top, 0.5f)
    }

    @Test
    fun backFromAVideoReturnsFocusToItsCardWithTheHeaderStillFolded() {
        open()
        focusCard(0)
        // The second card, so landing back on the first can't pass.
        focusCard(1)
        val opened = focusedCenter()
        compose.onNode(isFocused()).dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        // The page comes back folded: the card is in place before any fold could animate.
        compose.mainClock.autoAdvance = false
        compose.pressBack()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val first = cards()[1].boundsInRoot.center
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(opened.y, first.y, FocusLiftDp + 0.5f)
        val back = focusedCenter()
        assertEquals(opened.x, back.x, 0.5f)
        assertEquals(opened.y, back.y, 0.5f)
    }

    @Test
    fun whileThePageLoadsItOffersNothingToFocusUntilSubscribedTakesFocus() {
        val gate = CompletableDeferred<Unit>()
        extractor.headerGate = gate
        open()

        compose.onNode(button("Subscribed")).assertDoesNotExist()
        compose.onNode(button("Videos")).assertDoesNotExist()
        assertTrue("no cards while loading", cards().isEmpty())

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNode(button("Subscribed")).assertIsFocused()
    }

    @Test
    fun offlineSaysNoConnectionAndBackReturnsWhereTheUserWas() {
        extractor.header = null
        extractor.failure = UnknownHostException("www.youtube.com")
        open()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("This channel couldn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        compose.onNode(button("Back")).dpadSelect()
        compose.waitForIdle()
        assertTrue(navController.currentBackStackEntry?.destination?.route?.contains(HomeRoute::class.qualifiedName!!) == true)
    }

    @Test
    fun anUnreadableReplySaysYouTubeChangedSomethingAndTryAgainLoadsThePage() {
        extractor.header = null
        open()

        compose.onNodeWithText("YouTube changed something").assertExists()
        extractor.header = ChannelExtractor.RecordedHeader
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Rick Astley").assertExists()
        compose.onNode(button("Subscribed")).assertIsFocused()
    }

    @Test
    fun aShortsOnlyChannelOffersTurnOnShortsWhichShowsItsShortsTab() {
        extractor.header = ChannelExtractor.RecordedHeader.copy(tabs = listOf(ChannelTab.SHORTS))
        withRecordedShortsTab()
        open()

        compose.onNodeWithText("Shorts are turned off").assertExists()
        compose.onNodeWithText("This channel only posts Shorts").assertExists()
        compose.onNode(button("Back")).assertExists()
        compose.onNode(button("Videos")).assertDoesNotExist()
        compose.onNode(button("Subscribed")).assertIsFocused()

        compose.onNode(button("Turn on Shorts")).dpadSelect()
        compose.waitForIdle()

        assertTrue("the profile's Show Shorts is on", settings.of(1).showShorts)
        compose.onNode(button("Shorts")).assertExists()
        compose.onNodeWithText(RecordedShortsTab.FIRST_TITLE).assertExists()
        compose.onNode(button("Turn on Shorts")).assertDoesNotExist()
        compose.onNode(button("Subscribed")).assertIsFocused()
    }

    @Test
    fun aKidsProfilesPageOffersNeitherSubscribeNorTurnOnShorts() {
        extractor.header = ChannelExtractor.RecordedHeader.copy(tabs = listOf(ChannelTab.SHORTS))
        open(subscribed = false, kids = true)

        compose.onNodeWithText("Rick Astley").assertExists()
        compose.onNodeWithText("Shorts don’t show in a kids profile.").assertExists()
        compose.onNode(button("Subscribe")).assertDoesNotExist()
        compose.onNode(button("Subscribed")).assertDoesNotExist()
        compose.onNode(button("Turn on Shorts")).assertDoesNotExist()
        compose.onNode(button("Back")).assertIsFocused()
    }

    @Test
    fun theShortsTabListsShortsSixAcrossAndOneOpensARunThroughThem() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        withRecordedShortsTab()
        open()
        focusTab("Shorts")

        val cards = shortCards()
        val firstRow = cards.takeWhile { it.boundsInRoot.top == cards.first().boundsInRoot.top }
        assertEquals(6, firstRow.size)
        assertEquals(ShortCardWidthDp + ShortColumnGapDp, firstRow[1].boundsInRoot.left - firstRow[0].boundsInRoot.left, 0.5f)
        compose.onNodeWithText(RecordedShortsTab.FIRST_TITLE).assertExists()
        compose.onNodeWithText("11M views").assertExists()

        val first = shortCards().first()
        compose.onNode(SemanticsMatcher("the first Short") { it.id == first.id }).dpadSelect()
        compose.waitForIdle()

        assertEquals(true, navController.playerRoute()?.short)
        assertEquals(RecordedShortsTab.FIRST_ID, navController.playerRoute()?.videoId)
        val run = checkNotNull(navController.playerViewModel().uiState.value.shorts)
        assertEquals(0, run.index)
        assertTrue("a list was handed over: more Shorts follow", run.hasNext)
    }

    @Test
    fun holdingOkOnAShortOffersPlayWhichOpensItsRun() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        withRecordedShortsTab()
        open()
        focusTab("Shorts")
        val first = shortCards().first()

        compose.onNode(SemanticsMatcher("the first Short") { it.id == first.id }).dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Play")).dpadSelect()
        compose.waitForIdle()

        assertEquals(true, navController.playerRoute()?.short)
        assertTrue(checkNotNull(navController.playerViewModel().uiState.value.shorts).hasNext)
    }

    private companion object {
        const val UnsubscribedToast = "Unsubscribed from Rick Astley"
        const val CardWidthDp = 180f - 0.5f
        const val ShortCardWidthDp = 115f
        const val ShortColumnGapDp = 17f
        const val FocusScaleMax = 1.1f
        // The focused card's lift; a card coming back unfocused rests that much lower.
        const val FocusLiftDp = 3f
        // mdpi (1 dp = 1 px). ContentStart + half a 180 card. Folded, the tab area starts at
        // 360 − 265 = 95 and the focused row at the tabs' bottom (+28) plus TopAlign's gap, half
        // the thumb (101.25 laid out as 101) below that, less the 3 dp lift.
        const val FirstCardCenterX = 126f + 90f
        const val FoldedFirstCardCenterY = 95f + 28f + 7f + 101f / 2 - 3f
        const val TabCenterY = 360f + 14f
        const val UnfoldedFirstRowTop = 360f + 28f + 11f
    }
}
