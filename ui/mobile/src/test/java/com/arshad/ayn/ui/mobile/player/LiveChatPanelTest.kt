package com.arshad.ayn.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatBadge
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.newpipe.ChatServer
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
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
 * The phone's live chat panel (`live-phone-player` 4–9, 14; LIVE-D15, D27) through the shell's real
 * wiring, a real `MediaController` on the in-process session and the recorded live page, whose seed
 * opens the chat: the recorded first answer through the real seam, then scripted answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LiveChatPanelTest {
    private companion object {
        const val WINDOW_MS = 3_600_000L
        const val EDGE_MS = 3_590_000L
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var slot: PlayerSlot

    private val answers = mutableListOf<Channel<LiveChatUpdate>>()
    private var stopped = 0
    private val scripted = FakeLiveChat {
        val answer = Channel<LiveChatUpdate>(Channel.UNLIMITED).also(answers::add)
        answer.consumeAsFlow().onCompletion { stopped++ }
    }
    private val views = ChatViews(top = "top-token", live = "live-token")

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

    private fun play(chat: LiveChat = scripted) {
        val models = TestStores(session = { service.session.token }, liveChat = chat).models()
        compose.setContent {
            AynTheme {
                slot = rememberPlayerSlot()
                ProfileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), models, slot = slot)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(LiveCard)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(EDGE_MS) }
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

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        settle()
    }

    private fun openChat() {
        tap("Chat")
        waitFor { answers.isNotEmpty() }
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

    @Test
    fun theChatChipOpensTheRecordedFirstAnswerUnderThePictureWithTopChatPicked() {
        val seam = NewPipeLiveChat(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.FIRST)), io = Dispatchers.Unconfined)
        val chat = FakeLiveChat { seam.live(it) }
        play(chat)

        tap("Chat")
        waitFor { shows("zup velli se unvelli kese bane") }

        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED), chat.tokens)
        compose.onNodeWithText("Pinned by Lofi Girl").assertExists()
        compose.onNode(hasText("1.2K watching") and hasAnyAncestor(panel)).assertExists()
        compose.onNode(button("Top chat")).assertIsSelected()
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val shown = compose.onNode(panel).fetchSemanticsNode().boundsInRoot
        assertTrue("under the picture", shown.top > screen.height * 0.25f && shown.left == 0f)
    }

    @Test
    fun liveChatInTheSwitchAsksForItsViewsToken() {
        play()
        openChat()
        send(lines(1..3))

        tap("Live chat")

        assertEquals("live-token", scripted.tokens.last())
        compose.onNode(button("Live chat")).assertIsSelected()
    }

    @Test
    fun aSuperChatAMembershipAndARemovedLineEachShowAsDrawn() {
        play()
        openChat()
        val paid = ChatMessage("p1", ChatKind.SUPER_CHAT, "Mariam Haddad", "UCp1", null, emptySet(), listOf(ChatRun.Text("My daughter waves")), amount = "\$10.00", tierColor = 0xFFFFCA28.toInt())
        val member = ChatMessage("j1", ChatKind.MEMBERSHIP, "Diego Ramirez", "UCj1", null, setOf(ChatBadge.MEMBER), listOf(ChatRun.Text("Welcome to Space Station Crew!")), headline = "New member")
        send(LiveChatUpdate.Batch(listOf(line("a", "first"), ChatAction.Add(paid), ChatAction.Add(member), line("b", "to go")), views))

        send(LiveChatUpdate.Batch(listOf(ChatAction.Remove("b")), null))

        listOf("Mariam Haddad", "\$10.00", "My daughter waves", "Diego Ramirez", "Welcome to Space Station Crew!").forEach { assertTrue(it, displayed(it)) }
        // Its own line only on the membership card (a text line runs it on after the words).
        assertTrue(compose.onAllNodes(hasText("New member"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(displayed("Message removed by a moderator"))
        assertFalse(shows("to go"))
    }

    @Test
    fun aRemovedSuperChatLeavesItsMarkedPlace() {
        play()
        openChat()
        val paid = ChatMessage("p1", ChatKind.SUPER_CHAT, "Mariam Haddad", "UCp1", null, emptySet(), listOf(ChatRun.Text("My daughter waves")), amount = "\$10.00", tierColor = 0xFFFFCA28.toInt())
        send(LiveChatUpdate.Batch(listOf(line("a", "first"), ChatAction.Add(paid)), views))

        send(LiveChatUpdate.Batch(listOf(ChatAction.Remove("p1")), null))

        assertFalse(shows("\$10.00"))
        assertTrue(displayed("Message removed by a moderator"))
    }

    @Test
    fun aDroppedConnectionSaysItIsReconnectingUnderTheNewestLine() {
        play()
        openChat()
        send(lines(1..3))

        send(LiveChatUpdate.Reconnecting)

        assertTrue(displayed("Reconnecting to chat"))
        assertTrue(displayed("line 3"))
    }

    @Test
    fun anEndedChatSaysSoUnderItsLines() {
        play()
        openChat()
        // More than the panel holds, so the row shows only if the list follows it.
        send(lines(1..40))

        compose.runOnUiThread { answers.last().close() }
        settle()

        assertTrue(displayed("Chat has ended"))
        assertTrue(displayed("line 40"))
    }

    @Test
    fun aFirstAnswerThatFailsSaysSoAndTryAgainAsksOnceMore() {
        var calls = 0
        val chat = FakeLiveChat {
            calls++
            if (calls == 1) flow { throw AccessException("offline", java.io.IOException("offline"), AccessFailure.OFFLINE) } else Channel<LiveChatUpdate>(Channel.UNLIMITED).consumeAsFlow()
        }
        play(chat)
        tap("Chat")
        waitFor { shows("Chat didn’t load") }
        assertTrue(displayed("Check that the phone is online"))

        tap("Try again")

        assertEquals(2, chat.tokens.size)
        assertFalse(shows("Chat didn’t load"))
    }

    /** The chat open with 40 lines, then dragged up off the newest. */
    private fun readBack() {
        play()
        openChat()
        send(lines(1..40))
        list().performTouchInput { swipeDown() }
        settle()
    }

    private fun list() = compose.onNode(hasScrollAction() and hasAnyAncestor(panel))

    @Test
    fun aDragUpLeavesTheNewestLine() {
        play()
        openChat()
        send(lines(1..40))

        list().performTouchInput { swipeDown() }
        settle()

        assertFalse(displayed("line 40"))
    }

    @Test
    fun whileReadingBackTheListHoldsAndThePillCountsWhatArrives() {
        readBack()

        send(lines(41..43))

        assertTrue(displayed("3 new messages"))
        assertFalse(displayed("line 43"))
    }

    @Test
    fun aTapOnThePillFollowsTheNewestAgain() {
        readBack()
        send(lines(41..43))

        tap("3 new messages")

        assertTrue(displayed("line 43"))
        assertFalse(shows("new messages"))
    }

    @Test
    fun afterThePillsReturnADragReadsBackAgain() {
        readBack()
        send(lines(41..43))
        tap("3 new messages")

        list().performTouchInput { swipeDown() }
        settle()
        send(lines(44..46))

        assertTrue(displayed("3 new messages"))
        assertFalse(displayed("line 46"))
    }

    @Test
    fun aDragCuttingThePillsReturnShortReadsBack() {
        readBack()
        send(lines(41..43))
        compose.mainClock.autoAdvance = false
        compose.onNode(button("3 new messages")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        list().performTouchInput { swipeDown() }
        compose.mainClock.autoAdvance = true
        settle()
        send(lines(44..46))

        assertTrue(displayed("new messages"))
        assertFalse(displayed("line 46"))
    }

    @Test
    fun aDragBackToTheFootFollowsTheNewestAgain() {
        readBack()
        list().performTouchInput { swipeUp() }
        list().performTouchInput { swipeUp() }
        settle()

        send(lines(41..45))

        assertTrue(displayed("line 45"))
        assertFalse(shows("new message"))
    }

    @Test
    fun aScreenReadersScrollReadsBackTooAndTheListHolds() {
        play()
        openChat()
        send(lines(1..40))
        list().performScrollToIndex(20)
        settle()

        send(lines(41..43))

        assertTrue(displayed("3 new messages"))
        assertFalse(displayed("line 43"))
    }

    @Test
    fun aLineArrivingWhileFollowingIsShownAtTheFoot() {
        play()
        openChat()
        send(lines(1..40))

        send(lines(41..45))

        assertTrue(displayed("line 45"))
        assertFalse(shows("new message"))
    }

    @Test
    fun closingThePanelStopsItsRequestsAndStartsNoOther() {
        play()
        openChat()
        send(lines(1..3))

        tap("Close")

        assertEquals(1, stopped)
        assertEquals(1, answers.size)
    }

    @Test
    fun reopeningThePanelAsksAgain() {
        play()
        openChat()
        tap("Close")

        tap("Chat")

        assertEquals(2, answers.size)
    }

    @Test
    fun dockingThePageStopsItsRequests() {
        play()
        openChat()
        send(lines(1..3))

        compose.runOnUiThread { slot.dock() }
        settle()

        assertEquals(1, stopped)
    }

    @Test
    fun expandingTheDockedPageAsksAgain() {
        play()
        openChat()
        compose.runOnUiThread { slot.dock() }
        settle()

        compose.runOnUiThread { slot.expand() }
        settle()

        assertEquals(2, answers.size)
    }

    @Test
    fun theAppStoppingStopsItsRequests() {
        play()
        openChat()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()

        assertEquals(1, stopped)
    }

    @Test
    fun theAppStartingAgainAsksAgain() {
        play()
        openChat()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()

        assertEquals(2, answers.size)
    }

    @Test
    fun whileTheFirstAnswerLoadsNoSwitchShows() {
        play()
        openChat()

        assertFalse(shows("Top chat"))
    }

    @Test
    fun anAnswerNamingNoViewsLeavesTheSwitchHidden() {
        play()
        openChat()

        send(LiveChatUpdate.Batch(listOf(line("a", "hello")), null))

        assertTrue(displayed("hello"))
        assertFalse(shows("Top chat"))
    }

    @Test
    fun aModeratorsLineSaysItsRole() {
        play()
        openChat()
        val mod = ChatMessage("k", ChatKind.TEXT, "Kevin Tran", "UCk", null, setOf(ChatBadge.MODERATOR), listOf(ChatRun.Text("about 28,000 km/h")))

        send(LiveChatUpdate.Batch(listOf(ChatAction.Add(mod)), views))

        assertTrue(shows("Kevin Tran Moderator"))
    }

    @Test
    fun aPageWithoutChatDimsTheChipAndATapOpensNothing() {
        service.page = RecordedLivePage.copy(liveChat = null)
        play()

        compose.onNode(button("Chat")).assertIsNotEnabled()
        compose.onNode(button("Chat")).performClick()
        settle()

        compose.onNode(panel).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
    fun sidewaysAPageWithoutChatDimsTheChromesChatPill() {
        service.page = RecordedLivePage.copy(liveChat = null)
        play()
        tap("Video")

        compose.onNode(button("Chat")).assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
    fun sidewaysTheChromesChatOpensThePanelBesideThePicture() {
        play()
        tap("Video")

        openChat()
        send(lines(1..3))

        assertTrue(displayed("line 3"))
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val shown = compose.onNode(panel).fetchSemanticsNode().boundsInRoot
        assertTrue("beside the picture", shown.left > screen.width / 2 && shown.top == 0f)
    }
}
