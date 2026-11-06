package com.arshad.ayn.ui.tv.player

import androidx.activity.ComponentActivity
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.PlayerChat
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.newpipe.ChatServer
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveChat
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
import kotlinx.coroutines.Dispatchers
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
 * An ended stream's kept chat on TV (`live-tv-player` replay, replay-seek; LIVE-D7, D28) through
 * the real shell and session: a video page carrying a replay seed, its Chat right after Comments,
 * the recorded first replay page through the real seam, a jump finding the chat afresh, and the
 * stream's view over the picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerChatReplayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController
    private val settings = FakeProfileSettingsStore(selectedId = 1)

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

    private fun play(chat: LiveChat = recorded, placement: ChatPlacement = ChatPlacement.Beside, atMs: Long = 30_000) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(chatPlacement = placement))
        compose.setContent {
            navController = rememberNavController()
            TestShell(ChannelExtractor(), liveChat = chat, playerSession = { service.session.token }, navController = navController, profileSettings = settings)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlayerRoute(EnigmaCard.copy(textualUploadDate = "Streamed 2 days ago"))) }
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

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an ended stream with a kept replay has Chat right after Comments`() {
        play()

        val left = listOf("Comments", "Chat", "Captions").map { compose.onNode(button(it)).getUnclippedBoundsInRoot().left }

        assertEquals(left.sorted(), left)
    }

    @Test
    fun `a video without a kept replay has no Chat`() {
        service.page = RecordedEnigmaPage
        play()

        assertEquals(0, compose.onAllNodes(button("Chat")).fetchSemanticsNodes().size)
    }

    /** At 0:30 the recorded page's 53 lines sent by 0:13 show, each with its time. */
    @Test
    fun `Chat opens the recorded replay beside the picture, its lines timed to the position`() {
        play()

        compose.onNode(button("Chat")).dpadSelect()
        tick()
        waitFor { shows("Man noone cares abt yt") }

        compose.onNodeWithText("Chat replay").assertExists()
        assertTrue(shows("${RecordedEnigmaPage.uploaderName} · 15M views · Streamed 2 days ago"))
        assertTrue(shows("revenue from livestreams  0:13"))
        assertFalse(shows("woohoo"))
    }

    /** Back 10 from 0:30 is a jump back: the chat over the picture lets its lines go and finds 0:20. */
    @Test
    fun `a jump empties the list and finds the chat at the new moment`() {
        val chat = FakeLiveChat(page = { _, atMs ->
            if (atMs < 25_000) awaitCancellation() else ReplayPage(listOf(ReplayItem(1_000, line("a", "hello from the start"))), "next", "seek", null, untilMs = 90_000)
        })
        play(chat, placement = ChatPlacement.Side)
        tick()
        waitFor { shows("hello from the start") }

        compose.pressBack() // the chrome away, Left steps the video
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
        tick()

        assertEquals("seek", chat.replays.last().first)
        assertTrue(shows("Finding the chat at 0:20"))
        assertFalse(shows("hello from the start"))
    }

    /** No paging while paused (LIVE-D7): the recorded page reaches 1:05, under a minute ahead. */
    @Test
    fun `paused, the next page isn't asked`() {
        val server = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST))
        play(NewPipeLiveChat(server, io = Dispatchers.Unconfined))
        compose.runOnUiThread { service.player.pause() }
        compose.waitForIdle()

        compose.onNode(button("Chat")).dpadSelect()
        tick()
        tick()

        assertEquals(1, server.requests.size)
    }

    @Test
    fun `over the picture the replay shows in the stream's view, without its times`() {
        play(placement = ChatPlacement.Side)
        tick()
        waitFor { shows("Man noone cares abt yt") }

        val words = compose.onAllNodes(hasText("revenue from livestreams", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }.map { it.text }

        assertTrue(words.toString(), words.isNotEmpty() && words.none { "0:13" in it })
        assertTrue(shows("Chat replay  · Top chat"))
    }

    /** Chat over a showing view opens the Chat sheet, under the replay's kicker. */
    @Test
    fun `Chat over the picture opens the Chat sheet on a replay`() {
        play(placement = ChatPlacement.Side)
        tick()

        compose.onNode(button("Chat")).dpadSelect()

        assertTrue(shows("${RecordedEnigmaPage.uploaderName} · 15M views · Streamed 2 days ago"))
    }

    /** `replay-seek`: the switch stays, the skeleton with the finding line under it. */
    @Test
    fun `the panel finding the chat after a jump keeps the switch and says where`() {
        val calls = ChatCalls(
            open = { _, _ -> },
            shown = {},
            chooseView = {},
            retry = {},
            choosePlacement = {},
            hide = {},
            show = {},
            scroll = {},
            endScroll = {},
            tick = { _, _, _ -> },
        )
        compose.setContent {
            ChatPanel(
                open = true,
                chat = PlayerChat(views = ChatViews("top", "live"), status = ChatStatus.Finding(3_720_000)),
                kicker = "NASA · Streamed 2 days ago",
                calls = calls,
                onView = {},
                replay = true,
            )
        }
        compose.waitForIdle()

        assertTrue(shows("Finding the chat at 1:02:00"))
        compose.onNode(button("Top chat")).assertExists()
        compose.onNodeWithText("Chat replay").assertExists()
    }

    private fun line(id: String, text: String) = ChatAction.Add(
        ChatMessage(id, ChatKind.TEXT, author = "@viewer-$id", authorChannelId = "UC$id", authorPhotoUrl = null, badges = emptySet(), runs = listOf(ChatRun.Text(text))),
    )
}
