package com.arshad.ayn.ui.tv.player

import androidx.activity.ComponentActivity
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.ChatPlace
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import org.robolectric.annotation.GraphicsMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.feature.player.PlaybackDressing
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.ChatAction
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
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
import java.io.IOException
import kotlinx.coroutines.Dispatchers
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
 * The live chat beside the picture (`live-tv-player` chat-*, LIVE-D14, D15) through the real
 * shell, a real `MediaController` on the in-process session and the recorded live page, whose seed
 * opens the chat: the recorded first answer through the real seam, then scripted answers; and the
 * chat over the picture (`ov-side`, `ov-br`, `ov-chrome`, LIVE-D21, D22) from the profile's view
 * (whose captions are English, so a page with tracks shows them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerChatTest {

    private companion object {
        const val WINDOW_MS = 3_600_000L
        const val EDGE_MS = 3_590_000L
        const val CUE_MS = 19_000L
        const val CUE = "♪ We're no strangers to love ♪"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController

    private val answers = mutableListOf<Channel<LiveChatUpdate>>()
    private var stopped = 0
    private val scripted = FakeLiveChat {
        val answer = Channel<LiveChatUpdate>(Channel.UNLIMITED).also(answers::add)
        answer.consumeAsFlow().onCompletion { stopped++ }
    }

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

    private val settings = FakeProfileSettingsStore(selectedId = 1)

    private fun play(
        chat: LiveChat = scripted,
        placement: ChatPlacement = ChatPlacement.Beside,
        captions: CaptionSource = RecordedCaptions.source(),
        atMs: Long = EDGE_MS,
    ) {
        // Captions in English, so a page with tracks shows them.
        settings.byProfile.value = mapOf(1L to ProfileSettings(chatPlacement = placement, captionsLanguage = "en"))
        compose.setContent {
            navController = rememberNavController()
            TestShell(ChannelExtractor(), liveChat = chat, playerSession = { service.session.token }, navController = navController, profileSettings = settings, captions = captions)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlayerRoute(LiveCard)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()
    }

    private fun openChat() {
        compose.onNode(button("Chat")).dpadSelect()
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun send(update: LiveChatUpdate) {
        compose.runOnUiThread { answers.last().trySend(update) }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun displayed(text: String): Boolean {
        val nodes = compose.onAllNodes(hasText(text, substring = true))
        return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun line(id: String, text: String) = ChatAction.Add(
        ChatMessage(id, ChatKind.TEXT, author = "@viewer-$id", authorChannelId = "UC$id", authorPhotoUrl = null, badges = emptySet(), runs = listOf(ChatRun.Text(text))),
    )

    private val views = ChatViews(top = "top-token", live = "live-token")

    @Test
    fun `Chat opens the recorded answer beside the picture, the pin above and Top chat focused`() {
        val seam = NewPipeLiveChat(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.FIRST)), io = Dispatchers.Unconfined)
        val chat = FakeLiveChat { seam.live(it) }
        play(chat)

        openChat()
        waitFor { shows("zup velli se unvelli kese bane") }

        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED), chat.tokens)
        compose.onNodeWithText("Pinned by Lofi Girl").assertExists()
        compose.onNodeWithText("1.2K watching").assertExists()
        compose.onNode(button("Top chat")).assertIsFocused()
        compose.onNode(button("Live chat")).assertExists()
        // The free side's window under the framed picture, besides the hidden chrome's.
        compose.onAllNodes(hasText("Last hour")).assertCountEquals(2)
    }

    @Test
    fun `Live chat reloads from its own token, and BACK stops the chat and returns to Chat`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))
        compose.onNodeWithText("hello from Busan", substring = true).assertExists()

        compose.onNode(button("Live chat")).dpadSelect()
        compose.waitForIdle()

        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED, "live-token"), scripted.tokens)
        compose.onNodeWithText("hello from Busan", substring = true).assertDoesNotExist()

        compose.pressBack()

        assertEquals(2, stopped)
        compose.onNodeWithText("Pinned by", substring = true).assertDoesNotExist()
        compose.onNode(button("Chat")).assertIsFocused()
    }

    @Test
    fun `up the list it holds still and counts what arrives, and OK goes back to following`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(listOf(line("a", "first line"), line("b", "second line"), line("c", "newest line")), views))

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("newest line", substring = true)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(hasText("second line", substring = true)).assertIsFocused()

        send(LiveChatUpdate.Batch(listOf(line("d", "one more"), line("e", "and another")), null))
        compose.onNodeWithText("2 new messages").assertExists()
        compose.onNode(hasText("second line", substring = true)).assertIsFocused()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Top chat")).assertIsFocused()
        compose.onNodeWithText("2 new messages").assertDoesNotExist()
        compose.onNodeWithText("and another", substring = true).assertExists()
    }

    @Test
    fun `a removed line keeps its place, marked, and a dropped connection says so under the lines`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(listOf(line("a", "kept line"), line("b", "rude line")), views))

        send(LiveChatUpdate.Batch(listOf(ChatAction.Remove("b")), null))
        send(LiveChatUpdate.Reconnecting)

        compose.onNodeWithText("rude line", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Message removed by a moderator").assertExists()
        compose.onNodeWithText("Reconnecting to chat").assertExists()
        compose.onNodeWithText("kept line", substring = true).assertExists()
    }

    @Test
    fun `a chat that didn't load says so with Try again focused, which asks again`() {
        play()
        openChat()
        compose.runOnUiThread { answers.last().close(AccessException("offline", IOException("down"), AccessFailure.OFFLINE)) }
        compose.waitForIdle()

        compose.onNodeWithText("Chat didn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED, RecordedLiveVideo.CHAT_SEED), scripted.tokens)
        compose.onNodeWithText("Chat didn’t load").assertDoesNotExist()
    }

    private fun lines(from: Int, count: Int) = (from until from + count).map { line("n$it", "message number $it") }

    @Test
    fun `DOWN on the newest line goes back to following`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(lines(0, 30), views))

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("message number 29", substring = true)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        val reading = hasText("message number 28", substring = true)
        val before = compose.onNode(reading).fetchSemanticsNode().boundsInRoot
        send(LiveChatUpdate.Batch(lines(30, 1), null))
        compose.onNodeWithText("1 new message").assertExists()
        // The list holds still under the reader.
        assertEquals(before, compose.onNode(reading).fetchSemanticsNode().boundsInRoot)
        // Down to 29, then to the newest (30), then past it.
        repeat(2) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(hasText("message number 30", substring = true)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Top chat")).assertIsFocused()
        compose.onNodeWithText("new message", substring = true).assertDoesNotExist()
        send(LiveChatUpdate.Batch(lines(31, 1), null))
        assertTrue(displayed("message number 31"))
    }

    /** The oldest lines leave past the cap (300): a reader on one is put back on the switch. */
    @Test
    fun `a line leaving past the cap while focused hands focus back to the switch`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(lines(0, 5), views))
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        repeat(4) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP) }
        compose.onNode(hasText("message number 0", substring = true)).assertIsFocused()

        send(LiveChatUpdate.Batch(lines(5, 300), null))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Top chat")).assertIsFocused()
        compose.onNodeWithText("new message", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a failure while reading puts focus on Try again, and the retried chat follows`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(lines(0, 3), views))
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("message number 2", substring = true)).assertIsFocused()
        compose.runOnUiThread { answers.last().close(AccessException("offline", IOException("down"), AccessFailure.OFFLINE)) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(button("Try again")).assertIsFocused()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        send(LiveChatUpdate.Batch(lines(10, 3), views))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(button("Top chat")).assertIsFocused()
        compose.onNodeWithText("new message", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a full list follows the ended row in`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(lines(0, 30), views))
        assertFalse(displayed("message number 0"))

        compose.runOnUiThread { answers.last().close() }
        compose.waitForIdle()

        compose.onNodeWithText("Chat has ended").assertIsDisplayed()
    }

    /** A chat picture loads over https alone: an http emoji shows its shortcut, never loaded. */
    @Test
    fun `an http emoji shows its label and is never loaded`() {
        val requested = mutableListOf<String>()
        installOfflineImageLoader(compose.activity, requested)
        play()
        openChat()
        val emoji = ChatAction.Add(
            ChatMessage("e", ChatKind.TEXT, "@emoji", "UCe", null, emptySet(), listOf(ChatRun.CustomEmoji(":gar:", "http://example.com/gar.png"))),
        )
        send(LiveChatUpdate.Batch(listOf(emoji), views))

        compose.onNodeWithText(":gar:", substring = true).assertIsDisplayed()
        assertFalse(requested.toString(), requested.any { it.startsWith("http://example.com") })
    }

    /** The stop arrives as the lifecycle's own callback, with no recomposition between. */
    @Test
    fun `the lifecycle stopping hides the chat at once`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val shown = mutableListOf<Boolean>()
        val calls = ChatCalls(
            open = { _, _ -> },
            shown = { shown += it },
            chooseView = {},
            retry = {},
            choosePlacement = {},
            hide = {},
            show = {},
            scroll = {},
            endScroll = {},
            tick = { _, _, _ -> },
        )
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ChatShownEffect(open = true, turn = 0, dressing = PlaybackDressing(null, emptyList(), null, liveChat = LiveChatSeed("s", replay = false)), calls = calls)
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(true), shown)

        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(listOf(true, false), shown)
        }
    }

    /** LIVE-D6: the chat polls only while the app is started; it starts again from the newest. */
    @Test
    fun `the chat stops while the app is away and starts again on its return`() {
        play()
        openChat()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals(1, stopped)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED, RecordedLiveVideo.CHAT_SEED), scripted.tokens)
    }

    /** LIVE-D25 drops the seed under Restricted Mode, as a page without chat has none. */
    @Test
    fun `a stream without chat dims Chat, which focus passes over`() {
        service.page = RecordedLivePage.copy(liveChat = null)
        play()

        compose.onNode(button("Chat")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.onNode(button("Chat")).assertIsNotFocused()
        assertEquals(emptyList<String>(), scripted.tokens)
    }

    /** LIVE-D15: beside the picture the chat waits for Chat. */
    @Test
    fun `beside the picture nothing is asked until Chat`() {
        play()

        assertEquals(emptyList<String>(), scripted.tokens)
    }

    /** LIVE-D21: in a view over the picture the chat starts with the stream. */
    @Test
    fun `over the picture the chat starts with the stream`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }

        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))

        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED), scripted.tokens)
        compose.onNode(hasText("hello from Busan", substring = true)).assertExists()
        compose.onNode(hasText("Live chat", substring = true) and hasText("Top chat", substring = true)).assertExists()
    }

    /** LIVE-D22: over the picture the chat is read-only; nothing in it takes focus. */
    @Test
    fun `over the picture no line can take focus`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))

        val focusable = hasText("hello from Busan", substring = true) and
            (SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused) or hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)))

        assertEquals(0, compose.onAllNodes(focusable).fetchSemanticsNodes().size)
    }

    /** Stepping aside under the chrome doesn't restart the chat. */
    @Test
    fun `over the picture the chat polls on under the chrome`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNode(button("Pause")).assertIsFocused()
        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED) to 0, scripted.tokens to stopped)
    }

    /** The view turning Beside takes chat off the picture, and it stops asking. */
    @Test
    fun `chat leaving the picture stops polling`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }

        compose.runOnUiThread { settings.byProfile.value = mapOf(1L to ProfileSettings(chatPlacement = ChatPlacement.Beside)) }
        compose.waitForIdle()

        assertEquals(1, stopped)
    }

    /** `ov-chrome`: the chrome brings chat down to nothing under it (200 ms fade), and it comes
     *  back as the chrome goes. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `chat over the picture steps aside under the chrome and comes back after it`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch((1..20).map { line("$it", "line number $it") }, views))
        // The chrome shows as the stream starts; the chat shows once it recedes.
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        val shown = brightest("line number 14")

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.advanceTimeBy(1_000)
        val aside = brightest("line number 14")
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        val back = brightest("line number 14")

        assertTrue("shown: $shown, aside: $aside, back: $back", shown > 0.6f && aside < 0.1f && back > 0.6f)
    }

    /** LIVE-D22: a sheet over the picture takes chat off it too. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `chat over the picture steps aside under a sheet`() {
        play(placement = ChatPlacement.TopLeft)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        val shown = brightest("hello from Busan")

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(button("More")).dpadSelect()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Playback").assertExists()
        val aside = brightest("hello from Busan")
        assertTrue("shown: $shown, aside: $aside", shown > 0.6f && aside < 0.1f)
    }

    /** The bottom-right corner goes to a note there (here the failed captions'). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a note in the bottom-right corner takes it from chat there`() {
        assertTrue(noteCoversChat(ChatPlacement.BottomRight))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a note in the bottom-right corner leaves chat in the bottom-left`() {
        assertFalse(noteCoversChat(ChatPlacement.BottomLeft))
    }

    /** With [placement] and the captions' failed note up, whether chat stepped aside. */
    private fun noteCoversChat(placement: ChatPlacement): Boolean {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks)
        play(placement = placement, captions = CaptionSource { CaptionLookup.Failed })
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch((1..8).map { line("$it", "line number $it, a line long enough to take two lines") }, views))
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        compose.onNodeWithText("English captions didn’t load", substring = true).assertExists()
        // Above the note's own corner.
        return brightest("line number 6,") < 0.1f
    }

    /** The cues rise above a bottom corner's chat box (220 dp over the 28 dp safe line), 36 dp
     *  clear as above the chrome; the text sits inside its well's 2.5 dp foot. */
    @Test
    fun `captions rise above chat in a bottom corner`() {
        assertEquals(540f - 248f - 36f - 2.5f, cueBottom(chat = true), 1f)
    }

    @Test
    fun `captions stay low over a bottom corner when chat is off`() {
        assertEquals(540f - 46f - 2.5f, cueBottom(chat = false), 1f)
    }

    private fun cueBottom(chat: Boolean): Float {
        service.page = RecordedLivePage.copy(captionTracks = RecordedEnigmaPage.captionTracks, liveChat = RecordedLivePage.liveChat.takeIf { chat })
        play(placement = ChatPlacement.BottomLeft, atMs = CUE_MS)
        if (chat) waitFor { scripted.tokens.isNotEmpty() }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        return compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom.value
    }

    @Test
    fun `over the picture a loading chat shows its head alone`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }

        compose.onNode(hasText("Top chat", substring = true)).assertExists()
        assertFalse(shows("Reconnecting") || shows("@viewer"))
    }

    @Test
    fun `over the picture a dropped connection says so under the lines`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))

        send(LiveChatUpdate.Reconnecting)

        compose.onNodeWithText("Reconnecting to chat").assertExists()
    }

    /** Try again lives in the panel beside the picture; over it the chat only says so. */
    @Test
    fun `over the picture a chat that didn't load says so, with no Try again`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }

        compose.runOnUiThread { answers.last().close(AccessException("offline", IOException("down"), AccessFailure.OFFLINE)) }
        compose.waitForIdle()

        compose.onNodeWithText("Chat didn’t load").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    private fun brightest(text: String): Float = compose.onNode(hasText(text, substring = true)).captureToImage().toPixelMap().let { pixels ->
        (0 until pixels.width).maxOf { x -> (0 until pixels.height).maxOf { y -> pixels[x, y].luminance() } }
    }

    /** `ov-br`: a corner holds the newest few lines. */
    @Test
    fun `a corner shows the newest eight lines`() {
        play(placement = ChatPlacement.BottomRight)
        waitFor { scripted.tokens.isNotEmpty() }

        send(LiveChatUpdate.Batch((1..12).map { line("$it", "line number $it.") }, views))

        assertEquals((5..12).map { "line number $it." }, (1..12).map { "line number $it." }.filter(::shows))
    }

    @Test
    fun `chat that is off shows nothing over the picture`() {
        service.page = RecordedLivePage.copy(liveChat = null)
        play(placement = ChatPlacement.Side)

        assertFalse(shows("Top chat"))
        assertEquals(emptyList<String>(), scripted.tokens)
    }

    /** LIVE-D26: Chat over a showing view opens the Chat sheet on the view showing, with Show, Scroll chat and Hide chat. */
    @Test
    fun `Chat over the picture opens the Chat sheet on the view showing`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))

        openChat()

        compose.onNode(button("Over the right side")).assertIsFocused()
        compose.onNode(button("Top chat")).assertExists()
        compose.onNode(button("Scroll chat")).assertExists()
        compose.onNode(button("Hide chat")).assertExists()
    }

    /** `chat-sheet`: OK on a view moves chat there and keeps the sheet up to try another. */
    @Test
    fun `a view picked in the Chat sheet is chosen and the sheet stays`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        openChat()

        compose.onNode(button("Top-left corner")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Top-left corner") and isSelected()).assertExists()
        compose.onNode(button("Hide chat")).assertExists()
        assertEquals(0, stopped)
    }

    /** Show waits for an answer naming both views. */
    @Test
    fun `the Chat sheet has no Show before the views are named`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }

        openChat()

        compose.onNode(button("Over the right side")).assertIsFocused()
        compose.onNodeWithText("Show").assertDoesNotExist()
        compose.onNode(button("Top chat")).assertDoesNotExist()
    }

    /** LIVE-D26: Hide chat takes chat off the picture and stops its requests; focus goes back to Chat. */
    @Test
    fun `Hide chat stops it and gives focus back to Chat`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        openChat()

        compose.onNode(button("Hide chat")).dpadSelect()
        compose.waitForIdle()

        assertEquals(1, stopped)
        compose.onNode(button("Hide chat")).assertDoesNotExist()
        compose.onNode(button("Chat")).assertIsFocused()
    }

    /** LIVE-D26: after Hide chat, Chat shows it in the stream's view at once, asking again. */
    @Test
    fun `Chat after Hide shows chat again over the picture`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        openChat()
        compose.onNode(button("Hide chat")).dpadSelect()
        compose.waitForIdle()

        openChat()

        compose.onNode(button("Hide chat")).assertDoesNotExist()
        assertEquals(2, scripted.tokens.size)
    }

    /** Hide chat from the sheet View opened beside the picture closes both; focus goes back to Chat. */
    @Test
    fun `Hide chat from the panel's sheet closes the panel too`() {
        play()
        openChat()
        compose.onNode(button("View")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Hide chat")).dpadSelect()
        compose.waitForIdle()

        assertEquals(1, stopped)
        compose.onNode(button("View")).assertDoesNotExist()
        compose.onNode(button("Chat")).assertIsFocused()
    }

    /** Beside the picture, Chat after Hide opens the panel again. */
    @Test
    fun `Chat after Hide beside the picture opens the panel again`() {
        play()
        openChat()
        compose.onNode(button("View")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(button("Hide chat")).dpadSelect()
        compose.waitForIdle()

        openChat()

        compose.onNode(button("View")).assertExists()
        assertEquals(2, scripted.tokens.size)
    }

    /** A panel without the switch rests on View. */
    @Test
    fun `a panel whose views aren't named yet rests on View`() {
        play()

        openChat()

        compose.onNode(button("View")).assertIsFocused()
    }

    /** Back from the sheet View opened, focus is on View again. */
    @Test
    fun `BACK from the panel's sheet gives focus back to View`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))
        compose.onNode(button("View")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNode(button("View")).assertIsFocused()
    }

    /** LIVE-D22 (`chat-scroll-temp`): Scroll chat opens the panel on the newest line, for now. */
    @Test
    fun `Scroll chat opens the panel on the newest line, for now`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "first line"), line("b", "newest line")), views))
        openChat()

        compose.onNode(button("Scroll chat")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("newest line", substring = true) and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("For now · BACK puts chat back over the picture").assertExists()
    }

    /** LIVE-D22: BACK in Scroll chat's panel puts chat back over the picture, its requests never stopped. */
    @Test
    fun `BACK in Scroll chat puts chat back over the picture`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "first line"), line("b", "newest line")), views))
        openChat()
        compose.onNode(button("Scroll chat")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("For now · BACK puts chat back over the picture").assertDoesNotExist()
        compose.onNode(hasText("newest line", substring = true) and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("newest line", substring = true)).assertExists()
        assertEquals(listOf(RecordedLiveVideo.CHAT_SEED) to 0, scripted.tokens to stopped)
    }

    /** `chat-viewbtn`: View beside the switch opens the same sheet (no Scroll chat beside the
     *  picture); a view over the picture closes the panel under it. */
    @Test
    fun `View in the panel opens the sheet, and a view over the picture closes the panel`() {
        play()
        openChat()
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))

        compose.onNode(button("View")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Beside the video")).assertIsFocused()
        compose.onNode(button("Scroll chat")).assertDoesNotExist()

        compose.onNode(button("Over the right side")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()

        compose.onNode(button("Over the right side")).assertDoesNotExist()
        compose.onNode(hasText("hello from Busan", substring = true) and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("hello from Busan", substring = true)).assertExists()
        assertEquals(0, stopped)
    }

    /** Picking Beside from over the picture puts the panel under the sheet: BACK shows it. */
    @Test
    fun `Beside picked in the sheet shows the panel once the sheet closes`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "hello from Busan")), views))
        openChat()

        compose.onNode(button("Beside the video")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()

        // Back from the sheet, as from View's.
        compose.onNode(button("View")).assertIsFocused()
        compose.onNode(button("Top chat")).assertExists()
        assertEquals(0, stopped)
    }


    /** A batch landing after Scroll chat opened the panel but before its first frame: focus still
     *  lands on the newest line, the one that just came. */
    @Test
    fun `Scroll chat opens on the line that came as the panel opened`() {
        play(placement = ChatPlacement.Side)
        waitFor { scripted.tokens.isNotEmpty() }
        send(LiveChatUpdate.Batch(listOf(line("a", "first line"), line("b", "older newest")), views))
        openChat()
        compose.onNode(button("Scroll chat")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { answers.last().trySend(LiveChatUpdate.Batch(listOf(line("c", "just came")), views)) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNode(hasText("just came", substring = true) and hasClickAction()).assertIsFocused()
    }

    /** A Scroll chat whose panel closed without BACK (a stop) is put back over the picture by Chat. */
    @Test
    fun `Chat after Scroll chat's panel closed without BACK ends the scroll`() {
        val ended = mutableListOf<Unit>()
        val stack = PanelStack()
        val calls = ChatCalls(
            open = { _, _ -> },
            shown = {},
            chooseView = {},
            retry = {},
            choosePlacement = {},
            hide = {},
            show = {},
            scroll = {},
            endScroll = { ended += Unit },
            tick = { _, _, _ -> },
        )

        openChat(ChatPlace(ChatPlacement.Side, scrolling = true), stack, calls)

        assertEquals(1 to null, ended.size to stack.top)
    }

}
