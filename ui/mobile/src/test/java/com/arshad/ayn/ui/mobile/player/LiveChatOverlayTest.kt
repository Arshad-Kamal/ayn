package com.arshad.ayn.ui.mobile.player

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.ChatLine
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import org.junit.After
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
 * The phone's live chat over the sideways picture and its Chat sheet (`live-phone-player` 17–22;
 * LIVE-D21, D22, D26, D27) through the shell's real wiring, a real `MediaController` on the in-process session and the
 * recorded live page, whose seed opens the chat; scripted answers. The phone's turn is the
 * configuration's orientation, flipped in place as the activity sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class LiveChatOverlayTest {
    private companion object {
        const val WINDOW_MS = 3_600_000L
        const val EDGE_MS = 3_590_000L
        const val CUE_MS = 19_000L
        const val CUE = "♪ We're no strangers to love ♪"
        const val FAILED = "English captions didn’t load"
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var slot: PlayerSlot
    private var landscape by mutableStateOf(true)

    private val answers = mutableListOf<Channel<LiveChatUpdate>>()
    private var stopped = 0
    private val scripted = FakeLiveChat {
        val answer = Channel<LiveChatUpdate>(Channel.UNLIMITED).also(answers::add)
        answer.consumeAsFlow().onCompletion { stopped++ }
    }
    private val views = ChatViews(top = "top-token", live = "live-token")
    private var captions: CaptionSource = RecordedCaptions.source()
    private val stores by lazy { TestStores(session = { service.session.token }, liveChat = scripted, captions = { track -> captions.cues(track) }) }

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity).apply {
            page = RecordedLivePage
            player.liveWindowMs = WINDOW_MS
        }
    }

    @After
    fun tearDown() {
        service.release()
    }

    private fun play(placement: ChatPlacement, captionsLanguage: String? = null, atMs: Long = EDGE_MS) {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(chatPlacement = placement, captionsLanguage = captionsLanguage))
        val models = stores.models()
        compose.setContent {
            val base = LocalConfiguration.current
            val turned = Configuration(base).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides turned) {
                AynTheme {
                    slot = rememberPlayerSlot()
                    ProfileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), models, slot = slot)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(LiveCard)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun has(label: String): Boolean = compose.onAllNodes(button(label)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        settle()
    }

    private fun tapPicture() {
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        settle()
    }

    private fun turn(sideways: Boolean) {
        landscape = sideways
        settle()
    }

    private fun send(update: LiveChatUpdate) {
        compose.runOnUiThread { answers.last().trySend(update) }
        settle()
    }

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun displayed(text: String): Boolean {
        val nodes = compose.onAllNodes(hasText(text, substring = true))
        return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }

    private fun line(id: String, text: String) = ChatAction.Add(
        ChatMessage(id, ChatKind.TEXT, author = "@viewer-$id", authorChannelId = "UC$id", authorPhotoUrl = null, badges = emptySet(), runs = listOf(ChatRun.Text(text))),
    )

    private fun lines(range: IntRange) = LiveChatUpdate.Batch(range.map { line("m$it", "line $it") }, views)

    private val panel = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Live chat")

    /** The overlay showing [placement] with lines 1–[count]. */
    private fun shown(placement: ChatPlacement, count: Int = 3) {
        play(placement)
        waitFor { answers.isNotEmpty() }
        send(lines(1..count))
    }

    @Test
    fun sidewaysTheStreamsViewShowsChatOverThePictureWithoutAPanel() {
        shown(ChatPlacement.Side)

        assertTrue(displayed("line 3"))
        assertTrue(shows("Live chat  · Top chat"))
        compose.onNode(panel).assertDoesNotExist()
    }

    @Test
    fun alongTheRightSideItsLinesSitAtThePicturesRightEdge() {
        shown(ChatPlacement.Side)

        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        val shown = compose.onNodeWithText("line 3", substring = true).getUnclippedBoundsInRoot()
        assertTrue("right half: $shown", shown.left > picture.right / 2)
        assertTrue("its foot: $shown", shown.bottom > picture.bottom - 60.dp)
        // The full-height column: its head at the top, where a corner box's would sit 186 dp up.
        val head = compose.onNodeWithText("Live chat  · Top chat").getUnclippedBoundsInRoot()
        assertTrue("head at the top: $head", head.top < picture.top + 40.dp)
    }

    @Test
    fun aBottomLeftCornerHoldsItsBoxAtTheLeftFoot() {
        shown(ChatPlacement.BottomLeft)

        val shown = compose.onNodeWithText("line 3", substring = true).getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertTrue("left: $shown", shown.right < picture.right / 2)
        assertTrue("foot: $shown", shown.bottom > picture.bottom - 60.dp)
    }

    @Test
    fun besideTheProfilesDefaultLeavesThePictureClearAndAsksNothing() {
        play(ChatPlacement.Beside)
        settle()

        assertTrue(answers.isEmpty())
        assertFalse(shows("Top chat"))
    }

    @Test
    fun uprightTheViewOverThePictureNeitherShowsNorAsks() {
        landscape = false
        play(ChatPlacement.Side)
        settle()

        assertTrue(answers.isEmpty())
        assertFalse(shows("Top chat"))
    }

    @Test
    fun whileTheFirstAnswerLoadsOnlyItsHeadShows() {
        play(ChatPlacement.Side)
        waitFor { answers.isNotEmpty() }

        assertTrue(shows("Live chat  · Top chat"))
        assertFalse(shows("line"))
    }

    @Test
    fun aDroppedConnectionSaysSoUnderTheNewestLine() {
        shown(ChatPlacement.Side)

        send(LiveChatUpdate.Reconnecting)

        assertTrue(displayed("Reconnecting to chat"))
        assertTrue(displayed("line 3"))
    }

    @Test
    fun aTapOnThePictureBringsTheChromeAndTheChatStepsAside() {
        shown(ChatPlacement.Side)

        tapPicture()

        assertTrue(has("Chat"))
        assertFalse(shows("line 3"))
    }

    @Test
    fun aTapOnTheChatStillBringsTheChrome() {
        shown(ChatPlacement.Side)

        compose.onNodeWithText("line 3", substring = true).performTouchInput { click(center) }
        settle()

        assertTrue(has("Chat"))
    }

    @Test
    fun aDoubleTapOnTheChatStillSeeksThePicture() {
        shown(ChatPlacement.Side)

        // At the edge a forward step rejoins it.
        compose.onNodeWithText("line 3", substring = true).performTouchInput { doubleClick(center) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 400)
        compose.waitForIdle()

        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
    }

    @Test
    fun aBottomLeftBoxStepsAsideForANoteInItsCorner() {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks)
        captions = CaptionSource { CaptionLookup.Failed }
        play(ChatPlacement.BottomLeft, captionsLanguage = "en")
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        repeat(10) { if (!shows(FAILED)) settle() }

        assertTrue(shows(FAILED))
        assertFalse(shows("line 3"))
    }

    @Test
    fun theSideColumnKeepsItsLinesBesideANoteInTheBottomLeft() {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks)
        captions = CaptionSource { CaptionLookup.Failed }
        play(ChatPlacement.Side, captionsLanguage = "en")
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        repeat(10) { if (!shows(FAILED)) settle() }

        assertTrue(shows(FAILED))
        assertTrue(displayed("line 3"))
    }

    @Test
    fun theChatComesBackAsTheChromeGoes() {
        shown(ChatPlacement.Side)
        tapPicture()

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS)
        settle()

        assertTrue(displayed("line 3"))
    }

    /** The overlay showing [placement], the chrome's Chat opening the sheet over it. */
    private fun sheetOver(placement: ChatPlacement = ChatPlacement.Side) {
        shown(placement)
        tapPicture()
        tap("Chat")
    }

    /** Hide chat, at the foot of the sheet's stage: scrolled to first, as a large font pushes it below the fold. */
    private fun hideChat() {
        compose.onNode(button("Hide chat")).performScrollTo().performClick()
        settle()
    }

    /** The chrome gone after its idle time (it comes back as a sheet closes). */
    private fun chromeGone() {
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS)
        settle()
    }

    @Test
    fun theChromesChatOverAShowingViewOpensTheChatSheetAndTheOverlayStepsAside() {
        sheetOver()

        assertTrue(shows("${LiveCard.uploaderName} · 1.2K watching"))
        assertTrue(has("Hide chat"))
        compose.onNode(button("Over the right side")).assertIsSelected()
        compose.onNode(button("Bottom-left corner")).assertIsNotSelected()
        compose.onNode(panel).assertDoesNotExist()
        assertFalse(displayed("line 3"))
        assertEquals("the same chat polls under the sheet", 1, answers.size)
        assertEquals(0, stopped)
    }

    @Test
    fun aViewPickedInTheSheetMovesTheChatThereForThisStreamAndClosesIt() {
        sheetOver()

        tap("Bottom-left corner")
        chromeGone()

        assertFalse(has("Hide chat"))
        val shown = compose.onNodeWithText("line 3", substring = true).getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertTrue("left: $shown", shown.right < picture.right / 2)
        assertEquals("one chat throughout", 1, answers.size)
        assertEquals(ChatPlacement.Side, stores.settings.byProfile.value.getValue(ARSHAD.id).chatPlacement)
    }

    @Test
    fun besidePickedOverThePictureOpensThePanelBeside() {
        sheetOver()

        tap("Beside the video")

        compose.onNode(panel).assertExists()
        assertTrue(displayed("line 3"))
        assertEquals(1, answers.size)
    }

    @Test
    fun showInTheSheetSwitchesToLiveChatAndKeepsItUp() {
        sheetOver()

        tap("Live chat")

        assertTrue(has("Hide chat"))
        assertEquals("the switch reloads the chat", 2, answers.size)
        assertEquals("live-token", scripted.tokens.last())
    }

    @Test
    fun showJoinsTheSheetOnceAnAnswerNamesBothViews() {
        play(ChatPlacement.Side)
        waitFor { answers.isNotEmpty() }
        tapPicture()
        tap("Chat")
        assertFalse(has("Live chat"))

        send(lines(1..3))

        assertTrue(has("Live chat"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aPanelThatGaveWayThenReopenedBesideStopsAskingOnceClosed() {
        uprightPanel()
        turn(sideways = true)
        tapPicture()
        tap("Chat")
        tap("Beside the video")

        tap("Close")
        turn(sideways = false)

        assertEquals("its requests stop", 1, stopped)
        compose.onNode(panel).assertDoesNotExist()
    }

    @Test
    fun hideChatTakesItOffThePictureAndStopsItsRequests() {
        sheetOver()

        hideChat()
        chromeGone()

        assertFalse(shows("line 3"))
        assertEquals("its requests stop", 1, stopped)
    }

    @Test
    fun theChromesChatShowsAHiddenChatAgainInItsView() {
        sheetOver()
        hideChat()
        chromeGone()

        tapPicture()
        tap("Chat")
        waitFor { answers.size == 2 }
        send(lines(4..6))
        chromeGone()

        assertFalse("no sheet: chat shows at once", has("Hide chat"))
        assertTrue(displayed("line 6"))
    }

    /** Upright, the Chip's Chat panel open with lines 1–3. */
    private fun uprightPanel() {
        landscape = false
        play(ChatPlacement.Side)
        tap("Chat")
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun uprightThePanelsViewOpensTheSheet() {
        uprightPanel()

        tap("View")

        assertTrue(has("Hide chat"))
        assertTrue(has("Bottom-right corner"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun uprightHideChatClosesThePanelAndStopsItsRequests() {
        uprightPanel()
        tap("View")

        hideChat()

        compose.onNode(panel).assertDoesNotExist()
        assertEquals(1, stopped)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun uprightTheChipsChatShowsAHiddenChatAgain() {
        uprightPanel()
        tap("View")
        hideChat()

        tap("Chat")

        compose.onNode(panel).assertExists()
        waitFor { answers.size == 2 }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aChatHiddenSidewaysDoesNotBringBackThePanelUpright() {
        uprightPanel()
        turn(sideways = true)
        tapPicture()
        tap("Chat")

        hideChat()
        turn(sideways = false)

        compose.onNode(panel).assertDoesNotExist()
        assertEquals(1, stopped)
    }

    /** The overlay with 40 lines, dragged back off the newest. */
    private fun readBack() {
        shown(ChatPlacement.Side, count = 40)
        compose.onNodeWithText("line 40", substring = true).performTouchInput { swipeDown(startY = centerY, endY = centerY + 200f) }
        settle()
    }

    @Test
    fun aVerticalDragOnTheChatScrollsItBack() {
        readBack()

        assertFalse(displayed("line 40"))
        assertFalse("a drag isn't a tap", has("Chat"))
    }

    @Test
    fun whileScrolledBackItHoldsAndThePillCountsWhatArrives() {
        readBack()

        send(lines(41..43))

        assertTrue(displayed("3 new messages"))
        assertFalse(displayed("line 43"))
    }

    @Test
    fun aTapOnThePillReturnsToTheNewest() {
        readBack()
        send(lines(41..43))

        tap("3 new messages")

        assertTrue(displayed("line 43"))
        assertFalse(shows("new messages"))
    }

    /** A reload (Try again, a view switched) empties the list: its first answer arrives with the
     *  list, in place, rather than rising in as new lines. */
    @Test
    fun theFirstAnswerAfterAReloadDoesNotRiseIn() {
        fun flowing(range: IntRange) = PlayerChat(lines = range.map { ChatLine(line("m$it", "line $it").message) }, status = ChatStatus.Flowing)
        val chat = mutableStateOf(flowing(1..3))
        compose.setContent {
            AynTheme { Box(Modifier.fillMaxSize()) { ChatOverlay(ChatPlacement.Side, chat.value, aside = false) } }
        }
        settle()
        chat.value = PlayerChat(status = ChatStatus.Loading)
        settle()

        compose.mainClock.autoAdvance = false
        chat.value = flowing(4..6)
        // The first frame it shows in: a line rising in would still sit 7 dp low.
        repeat(6) { if (!shows("line 6")) compose.mainClock.advanceTimeByFrame() }
        val arriving = compose.onNodeWithText("line 6", substring = true).getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = true
        settle()
        val settled = compose.onNodeWithText("line 6", substring = true).getUnclippedBoundsInRoot()

        assertEquals(settled.top.value, arriving.top.value, 0.5f)
    }

    /** Composed alone with its lines already there: there its effects can start ahead of its
     *  SideEffects, which once left the first follow's fence up, so the first drag didn't read back. */
    @Test
    fun aDragReadsBackOnAnOverlayComposedWithItsLines() {
        val chat = mutableStateOf(PlayerChat(lines = (1..40).map { ChatLine((line("m$it", "line $it")).message) }, status = ChatStatus.Flowing))
        compose.setContent {
            AynTheme { Box(Modifier.fillMaxSize()) { ChatOverlay(ChatPlacement.Side, chat.value, aside = false) } }
        }
        settle()

        compose.onNodeWithText("line 40", substring = true).performTouchInput { swipeDown(startY = centerY, endY = centerY + 200f) }
        settle()
        chat.value = chat.value.copy(lines = chat.value.lines + ChatLine(line("m41", "line 41").message))
        settle()

        assertTrue(displayed("1 new message"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun anOpenChatPanelGivesWayAsThePhoneTurnsAndComesBackUpright() {
        landscape = false
        play(ChatPlacement.Side)
        tap("Chat")
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        compose.onNode(panel).assertExists()

        turn(sideways = true)

        compose.onNode(panel).assertDoesNotExist()
        assertTrue(displayed("line 3"))

        turn(sideways = false)

        compose.onNode(panel).assertExists()
        assertEquals("one chat throughout", 1, answers.size)
        assertEquals(0, stopped)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aStopWhileThePanelIsAwayKeepsItClosedUpright() {
        landscape = false
        play(ChatPlacement.Side)
        tap("Chat")
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        turn(sideways = true)

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, positionMs = 0) }
        settle()
        turn(sideways = false)
        tap("Try again")
        // The service clears its stop as the new request plays.
        compose.runOnUiThread {
            service.session.setSessionExtras(Bundle.EMPTY)
            service.player.ready(EDGE_MS)
        }
        settle()

        assertFalse("the stream plays again", shows("Try again"))
        compose.onNode(panel).assertDoesNotExist()
    }

    @Test
    fun turningUprightStopsTheOverlaysRequests() {
        shown(ChatPlacement.Side)

        turn(sideways = false)

        assertEquals(1, stopped)
        assertFalse(shows("line 3"))
    }

    @Test
    fun dockingThePageStopsTheOverlaysRequests() {
        shown(ChatPlacement.Side)

        compose.runOnUiThread { slot.dock() }
        settle()

        assertEquals(1, stopped)
    }

    @Test
    fun theCuesRiseAboveABottomCornersBox() {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks)
        play(ChatPlacement.BottomLeft, captionsLanguage = "en", atMs = CUE_MS)
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        repeat(10) { if (!shows(CUE)) settle() }

        val cue = compose.onNodeWithText(CUE).getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        // The mockup's box: 170 dp tall, 16 dp off the foot.
        assertTrue("the cue $cue stays above the box's top", cue.bottom <= picture.bottom - 186.dp)
    }

    @Test
    fun theCuesStayAtRestBesideTheSideColumn() {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks)
        play(ChatPlacement.Side, captionsLanguage = "en", atMs = CUE_MS)
        waitFor { answers.isNotEmpty() }
        send(lines(1..3))
        repeat(10) { if (!shows(CUE)) settle() }

        val cue = compose.onNodeWithText(CUE).getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertTrue("the cue $cue rose", cue.bottom > picture.bottom - 60.dp)
    }
}
