package com.arshad.ayn.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import com.arshad.ayn.core.access.newpipe.ChatServer
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveChat
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
 * An ended stream's kept chat on the phone (`live-phone-player` 12, 16; LIVE-D7, D28) through the
 * shell's real wiring and a real `MediaController` on the in-process session: a video page carrying
 * a replay seed, its Chat chip right after Comments, the recorded first replay page through the real
 * seam, a jump finding the chat afresh, and sideways the stream's view over the picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LiveChatReplayTest {
    private companion object {
        const val SIDEWAYS = "w891dp-h411dp-land"
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        val KICKER = "${RecordedEnigmaPage.uploaderName} · 15M views · Streamed 2 days ago"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var slot: PlayerSlot

    /** The recorded seam answering replay pages; [play]'s default. */
    private val recorded: LiveChat
        get() = NewPipeLiveChat(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST)), io = Dispatchers.Unconfined)

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity).apply {
            page = RecordedEnigmaPage.copy(liveChat = LiveChatSeed(RecordedLiveChat.NEXT_REPLAY_SEED, replay = true))
        }
    }

    @After
    fun tearDown() {
        service.release()
    }

    private fun play(chat: LiveChat = recorded, placement: ChatPlacement = ChatPlacement.Beside, atMs: Long = 30_000, profile: Profile = ARSHAD) {
        val stores = TestStores(session = { service.session.token }, liveChat = chat)
        stores.settings.byProfile.value = mapOf(profile.id to ProfileSettings(chatPlacement = placement))
        val models = stores.models()
        compose.setContent {
            AynTheme {
                slot = rememberPlayerSlot()
                ProfileShell(ShellOpening(profile, onSwitchProfile = {}), models, slot = slot)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(EnigmaCard.copy(textualUploadDate = "Streamed 2 days ago"))) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()
    }

    /** A position tick or two reach the replay. */
    private fun tick() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        tick()
    }

    private fun tapPicture() {
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        tick()
    }

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private val panel = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Chat replay")

    @Test
    fun anEndedStreamWithAKeptReplayHasTheChatChipRightAfterComments() {
        play()

        val left = listOf("Save", "Comments", "Chat", "Chapters").map { compose.onNode(button(it)).getUnclippedBoundsInRoot().left }

        assertEquals(left.sorted(), left)
    }

    /** A kids profile can play the ended stream as a video; its chat stays off (KP-D11, LIVE-D13). */
    @Test
    fun aKidsProfileHasNoChatOnAKeptReplay() {
        play(profile = ZAINAB)

        assertEquals(0, compose.onAllNodes(button("Chat")).fetchSemanticsNodes().size)
    }

    @Test
    fun aVideoWithoutAKeptReplayHasNoChat() {
        service.page = RecordedEnigmaPage
        play()

        assertEquals(0, compose.onAllNodes(button("Chat")).fetchSemanticsNodes().size)
        compose.onNode(button("Comments")).assertExists()
    }

    /** At 0:30 the recorded page's lines sent by 0:13 show, each with its time. */
    @Test
    fun theChatChipOpensTheRecordedReplayUnderThePictureItsLinesTimedToThePosition() {
        play()

        tap("Chat")
        waitFor { shows("Man noone cares abt yt") }

        compose.onNode(panel).assertExists()
        compose.onNode(hasText(KICKER) and hasAnyAncestor(panel)).assertExists()
        assertTrue(shows("revenue from livestreams  0:13"))
        assertFalse(shows("woohoo"))
    }

    /** A double-tap back from 0:30 is a jump back: the panel lets its lines go and finds 0:20. */
    @Test
    fun aJumpEmptiesTheListAndFindsTheChatAtTheNewMoment() {
        val chat = FakeLiveChat(page = { _, atMs ->
            if (atMs < 25_000) awaitCancellation() else ReplayPage(listOf(ReplayItem(1_000, line("a", "hello from the start"))), "next", "seek", null, untilMs = 90_000)
        })
        play(chat)
        tap("Chat")
        waitFor { shows("hello from the start") }

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.2f, height / 2f)) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
        tick()

        assertEquals("seek", chat.replays.last().first)
        assertTrue(shows("Finding the chat at 0:20"))
        assertFalse(shows("hello from the start"))
        compose.onNode(panel).assertExists()
    }

    /** No paging while paused (LIVE-D7): the recorded page reaches 1:05, under a minute ahead. */
    @Test
    fun pausedTheNextPageIsntAsked() {
        val server = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST))
        play(NewPipeLiveChat(server, io = Dispatchers.Unconfined))
        compose.runOnUiThread { service.player.pause() }
        compose.waitForIdle()

        tap("Chat")
        tick()
        tick()

        assertEquals(1, server.requests.size)
    }

    /** Playing at 0:30 the recorded page reaches only 1:05, under a minute ahead: the next is asked. */
    @Test
    fun playingTheNextPageIsAskedAMinuteAhead() {
        val server = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST))
        play(NewPipeLiveChat(server, io = Dispatchers.Unconfined))

        tap("Chat")
        tick()
        tick()

        assertEquals(2, server.requests.size)
    }

    /** Closed, the chat follows the position no more: a jump asks nothing. */
    @Test
    fun aClosedReplayAsksNothingOnAJump() {
        val chat = FakeLiveChat(page = { _, _ ->
            ReplayPage(listOf(ReplayItem(1_000, line("a", "hello from the start"))), "next", "seek", null, untilMs = 90_000)
        })
        play(chat)
        tap("Chat")
        waitFor { shows("hello from the start") }
        tap("Close")
        val asked = chat.replays.size

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.2f, height / 2f)) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
        tick()

        assertEquals(asked, chat.replays.size)
        compose.onNode(panel).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = SIDEWAYS)
    fun sidewaysTheChromesChatSitsRightAfterComments() {
        play()
        tapPicture()

        val left = listOf("Comments", "Chat", "Captions").map { compose.onNode(button(it)).getUnclippedBoundsInRoot().left }

        assertEquals(left.sorted(), left)
    }

    /** `live-phone-player` 16: sideways the chrome's Chat opens the replay beside the picture. */
    @Test
    @Config(qualifiers = SIDEWAYS)
    fun sidewaysChatOpensTheReplayBesideThePicture() {
        play()
        tapPicture()

        tap("Chat")
        waitFor { shows("Man noone cares abt yt") }

        compose.onNode(panel).assertExists()
        assertTrue(shows("revenue from livestreams  0:13"))
    }

    @Test
    @Config(qualifiers = SIDEWAYS)
    fun sidewaysTheReplayShowsOverThePictureInTheStreamsViewWithoutItsTimes() {
        play(placement = ChatPlacement.Side)
        tick()
        waitFor { shows("Man noone cares abt yt") }

        val words = compose.onAllNodes(hasText("revenue from livestreams", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }.map { it.text }

        assertTrue(words.toString(), words.isNotEmpty() && words.none { "0:13" in it })
        assertTrue(shows("Chat replay  · Top chat"))
        compose.onNode(panel).assertDoesNotExist()
    }

    /** Sideways the stream's view stays over the picture into a new turn of the same video (its own
     *  row in Up next), where the page stays resolved and the player keeps its item: that turn's
     *  replay starts afresh at the position. */
    @Test
    @Config(qualifiers = SIDEWAYS)
    fun aNewTurnOfTheSameVideoWithTheReplayOverThePictureStartsItsChatAfresh() {
        val again = EnigmaCard.copy(title = "The same stream again")
        service.page = service.page.copy(relatedVideos = listOf(again) + RecordedEnigmaPage.relatedVideos)
        val chat = FakeLiveChat(page = { _, _ ->
            ReplayPage(listOf(ReplayItem(1_000, line("a", "hello from the start"))), null, "seek", null, untilMs = 90_000)
        })
        val playingAtMs = 30_000L
        play(chat, placement = ChatPlacement.Side, atMs = playingAtMs)
        tick()
        waitFor { shows("hello from the start") }
        tapPicture()
        tap("Up next")
        val asked = chat.replays.size

        compose.onNode(hasText(again.title, substring = true) and hasClickAction()).performClick()
        tick()

        assertEquals(asked + 1, chat.replays.size)
        val (token, atMs) = chat.replays.last()
        assertEquals(RecordedLiveChat.NEXT_REPLAY_SEED, token)
        assertTrue("asked at $atMs", atMs >= playingAtMs)
    }

    /** Chat over a showing view opens the Chat sheet, under the replay's kicker. */
    @Test
    @Config(qualifiers = SIDEWAYS)
    fun chatOverThePictureOpensTheChatSheetOnAReplay() {
        play(placement = ChatPlacement.Side)
        tick()
        tapPicture()

        tap("Chat")

        assertTrue(shows(KICKER))
        compose.onNode(button("Hide chat")).assertExists()
    }

    /** A jump's panel: the switch stays, the skeleton with the finding line under it. */
    @Test
    fun thePanelFindingTheChatAfterAJumpKeepsTheSwitchAndSaysWhere() {
        val calls = ChatCalls(open = { _, _ -> }, shown = {}, chooseView = {}, retry = {}, tick = { _, _, _ -> })
        compose.setContent {
            AynTheme {
                ChatPanel(
                    chat = PlayerChat(views = ChatViews("top", "live"), status = ChatStatus.Finding(3_720_000)),
                    kicker = "NASA · Streamed 2 days ago",
                    calls = calls,
                    beside = false,
                    onView = {},
                    onClose = {},
                    replay = true,
                )
            }
        }
        compose.waitForIdle()

        assertTrue(shows("Finding the chat at 1:02:00"))
        compose.onNode(button("Top chat")).assertExists()
        compose.onNode(panel).assertExists()
    }

    private fun line(id: String, text: String) = ChatAction.Add(
        ChatMessage(id, ChatKind.TEXT, author = "@viewer-$id", authorChannelId = "UC$id", authorPhotoUrl = null, badges = emptySet(), runs = listOf(ChatRun.Text(text))),
    )
}
