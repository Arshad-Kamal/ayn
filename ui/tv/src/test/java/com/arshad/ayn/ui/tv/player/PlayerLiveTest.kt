package com.arshad.ayn.ui.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.C
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.ui.tv.TestKidOpening
import com.arshad.ayn.ui.tv.TestOpening
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.TestTime
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.testKidsShell
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
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
 * The live player (`live-tv-player` live-edge … live-kids, LIVE-D23) through the real shell and a
 * real `MediaController` on the in-process session, dressed with the recorded live page over an
 * hour's live window ([com.arshad.ayn.feature.player.ScriptedPlayer.liveWindowMs]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerLiveTest {

    private companion object {
        const val WINDOW_MS = 3_600_000L

        /** The hour's window's end less the 10 s target offset. */
        const val EDGE_MS = 3_590_000L
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController

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

    private fun open(card: VideoItem = LiveCard, kids: Boolean = false, time: ShellTime = TestTime) {
        compose.setContent {
            navController = rememberNavController()
            TestShell(
                ChannelExtractor(),
                playerSession = { service.session.token },
                opening = if (kids) TestKidOpening else TestOpening,
                time = time,
                navController = navController,
                kids = if (kids) testKidsShell() else null,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlayerRoute(card)) }
        compose.waitForIdle()
    }

    /** Session and controller hop through the paused main looper: drain it until [what] holds. */
    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    /** Opens [card] and lets the stream land [behindMs] behind the edge ([atMs] on a video). */
    private fun play(behindMs: Long, card: VideoItem = LiveCard, atMs: Long = EDGE_MS - behindMs, time: ShellTime = TestTime) {
        open(card, time = time)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        moveTo(atMs)
    }

    /** The player now at [atMs] (a seek, or the service rejoining the edge), past a tick or two. */
    private fun moveTo(atMs: Long) {
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun row(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun textOf(matcher: SemanticsMatcher): String =
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun onPlayer(): Boolean = navController.currentBackStackEntry?.destination?.route?.contains("PlayerRoute") == true

    @Test
    fun `a live card plays at the edge under the live chrome`() {
        play(behindMs = 0)

        assertEquals(listOf(RecordedLiveVideo.ID), service.player.requested)
        compose.onNodeWithText("LIVE").assertExists()
        compose.onNodeWithText("Last hour").assertExists()
        compose.onNodeWithText("1.2K watching", substring = true).assertExists()
        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNode(button("Speed")).assertDoesNotExist()
        compose.onNode(button("Comments")).assertDoesNotExist()
    }

    /** LIVE-D24: the page's start (23:50:06Z) at [TestTime] (01:00Z the next day). */
    @Test
    fun `the channel line says how long the stream has run`() {
        play(behindMs = 0)

        compose.onNodeWithText("1.2K watching · Started streaming 1 hour ago", substring = true).assertExists()
    }

    /** The chrome stays composed through a long stream: the start's age follows the clock. */
    @Test
    fun `the start's age moves on while the stream plays`() {
        val clock = SettableClock(TestTime.clock.now())
        play(behindMs = 0, time = TestTime.copy(clock = clock))
        compose.onNodeWithText("Started streaming 1 hour ago", substring = true).assertExists()

        clock.now += 1.hours
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()

        compose.onNodeWithText("Started streaming 2 hours ago", substring = true).assertExists()
    }

    /** Only a live page's start shows: a premiere or an ended stream names one too. */
    @Test
    fun `a video page that names a broadcast start shows views and age, not the start`() {
        service.page = RecordedEnigmaPage.copy(liveStartedAt = RecordedLiveVideo.STARTED_AT)
        service.player.liveWindowMs = null
        play(behindMs = 0, card = EnigmaCard, atMs = 60_000)

        compose.onNodeWithText("${EnigmaCard.uploaderName}  15M views · 12 days ago").assertExists()
        compose.onNodeWithText("Started streaming", substring = true).assertDoesNotExist()
    }

    /** LIVE-D24 (Q19): a live stream keeps the Related peek and row a video has. */
    @Test
    fun `DOWN from the live controls opens the Related row, and OK plays its first video`() {
        play(behindMs = 0)
        val first = RecordedLivePage.relatedVideos.first()
        compose.onNodeWithText("Related").assertExists()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(first.title).assertExists()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(RecordedLiveVideo.ID, first.id.value), service.player.requested)
    }

    /** Q20: Forward stays at the edge; a press there only rejoins it. */
    @Test
    fun `at the edge Forward stays and a press rejoins the edge`() {
        play(behindMs = 0)

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(button("Forward 10 seconds")).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }

        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
    }

    /** LIVE-D23: the page decides, not the card: a card that doesn't say live, resolving to the
     *  live page, gets the live chrome; its views aren't counted as watching. */
    @Test
    fun `a card that doesn't say live gets the live chrome once its page resolves live`() {
        play(behindMs = 0, card = EnigmaCard)

        compose.onNodeWithText("LIVE").assertExists()
        compose.onNodeWithText("Last hour").assertExists()
        compose.onNode(button("Speed")).assertDoesNotExist()
        compose.onNodeWithText("watching", substring = true).assertDoesNotExist()
        compose.onNodeWithText("${EnigmaCard.uploaderName}  Started streaming 1 hour ago").assertExists()
    }

    @Test
    fun `a live card whose page resolves as a video gets the video chrome`() {
        service.page = RecordedEnigmaPage
        service.player.liveWindowMs = null
        play(behindMs = 0, atMs = 60_000)

        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNode(button("Speed")).assertExists()
        compose.onNode(button("Comments")).assertExists()
    }

    @Test
    fun `behind live the tag says how far, and Go live rejoins the edge with Pause focused`() {
        play(behindMs = 1_440_000)

        // The times row's (the scrub bubble's "behind live" stands alone, unseen until focused).
        val behind = textOf(hasText(" behind live", substring = true))
        assertTrue(behind, Regex("""(23:5\d|24:00) behind live""").matches(behind))

        compose.onNode(button("Go live")).dpadSelect()
        waitFor { C.TIME_UNSET in service.player.seeks }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()

        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNodeWithText(" behind live", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Back 10 from the edge goes ten seconds back`() {
        play(behindMs = 0)

        compose.onNode(button("Back 10 seconds")).dpadSelect()
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }

        val seek = service.player.seeks.single()
        assertTrue("$seek", seek in EDGE_MS - 10_000..EDGE_MS - 8_000)

        // Ten seconds back, Forward steps up again and rejoins the edge.
        moveTo(seek)
        compose.onNode(button("Forward 10 seconds")).dpadSelect()
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.size == 2 }
        assertEquals(C.TIME_UNSET, service.player.seeks.last())
    }

    /** Once shown, behind live holds down to 15 s (the window's refreshes can't flip it), also
     *  across the chrome hiding and showing again. */
    @Test
    fun `behind live holds until fifteen seconds from the edge`() {
        play(behindMs = 38_000)
        compose.onNode(button("Go live")).assertExists()

        moveTo(EDGE_MS - 20_000)
        compose.pressBack()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(button("Go live")).assertExists()

        moveTo(EDGE_MS - 10_000)
        compose.onNode(button("Go live")).assertDoesNotExist()
    }

    /** The service rejoining the edge by itself (behind the live window) takes Go live away under
     *  focus: focus moves to Pause. */
    @Test
    fun `Go live leaving under focus without a click hands focus to Pause`() {
        play(behindMs = 600_000)
        compose.onNode(button("Go live")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(button("Go live")).assertIsFocused()

        moveTo(EDGE_MS)

        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
    }

    @Test
    fun `the hidden chrome's one step back says how far behind live`() {
        play(behindMs = 0)
        compose.pressBack()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        val nudge = textOf(hasText("\u22120:1", substring = true) or hasText("\u22120:09"))
        assertTrue(nudge, nudge == "\u22120:10" || nudge == "\u22120:09")
    }

    /** No SponsorBlock on a live stream, even with segments looked up for its id. */
    @Test
    fun `the Playback sheet says SponsorBlock is off on a live stream`() {
        service.segments = runBlocking { RecordedSegments.lookup() }
        play(behindMs = 0)

        compose.onNode(hasContentDescription("More") and hasClickAction()).dpadSelect()
        compose.waitForIdle()

        compose.onNode(row("SponsorBlock")).assert(hasText("Off"))
    }

    @Test
    fun `Forward from behind live rejoins the edge and keeps focus`() {
        play(behindMs = 38_000)

        compose.onNode(button("Forward 10 seconds")).dpadSelect()
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
        moveTo(EDGE_MS)

        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNode(button("Forward 10 seconds")).assertIsFocused()
    }

    /** The nudge follows where the gathered seek lands: Left, Left, Right from the edge nets
     *  10 s back. */
    @Test
    fun `the hidden chrome's steps that net back say how far behind live`() {
        play(behindMs = 0)
        compose.pressBack()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Live").assertDoesNotExist()
        val nudge = textOf(hasText("\u22120:1", substring = true) or hasText("\u22120:09"))
        assertTrue(nudge, nudge == "\u22120:10" || nudge == "\u22120:09")
    }

    @Test
    fun `the hidden chrome's step forward that rejoins the edge says Live`() {
        play(behindMs = 20_000)
        compose.pressBack()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Live").assertExists()
    }

    @Test
    fun `a scrub on a live stream says how far behind live it goes, and OK seeks there`() {
        play(behindMs = 0)
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP) // Pause to the scrub bar
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) // nothing lies past the edge
        repeat(6) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT) }

        val bubble = textOf(hasText("\u22121:0", substring = true) or hasText("\u22120:59"))
        assertTrue(bubble, bubble == "\u22121:00" || bubble == "\u22120:59")
        compose.onNodeWithText("behind live").assertExists()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.seeks.isNotEmpty() }
        val seek = service.player.seeks.single()
        assertTrue("$seek", seek in EDGE_MS - 60_000..EDGE_MS - 58_000)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 2)
        compose.waitForIdle()
        compose.onNode(button("Go live")).assertExists()
    }

    @Test
    fun `the hidden chrome's Right at the edge says Live`() {
        play(behindMs = 0)
        compose.pressBack() // hides the chrome

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Live").assertExists()
    }

    @Test
    fun `the hidden chrome's Left says how far behind live the picture goes`() {
        play(behindMs = 0)
        compose.pressBack() // hides the chrome

        repeat(4) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT) }

        // The nudge's target (the unseen scrub bubble holds the hidden chrome's −0:00).
        val nudge = textOf(hasText("\u22120:4", substring = true) or hasText("\u22120:39"))
        assertTrue(nudge, nudge == "\u22120:40" || nudge == "\u22120:39")
    }

    @Test
    fun `buffering at the edge says it is catching up to live`() {
        play(behindMs = 0)

        compose.runOnUiThread { service.player.buffering() }
        compose.waitForIdle()

        compose.onNodeWithText("Catching up to live").assertExists()
    }

    @Test
    fun `buffering behind live loads as a video does`() {
        play(behindMs = 600_000)

        compose.runOnUiThread { service.player.buffering() }
        compose.waitForIdle()

        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithText("Catching up to live").assertDoesNotExist()
    }

    @Test
    fun `the Playback sheet has no Speed on a live stream`() {
        play(behindMs = 0)

        compose.onNode(hasContentDescription("More") and hasClickAction()).dpadSelect()
        compose.waitForIdle()

        compose.onNode(row("Quality")).assertIsFocused()
        compose.onNode(row("Speed")).assertDoesNotExist()
    }

    @Test
    fun `the stream ending says so with Back, and BACK leaves the player`() {
        play(behindMs = 0)
        assertTrue("Up next would have related videos to offer", RecordedLivePage.relatedVideos.isNotEmpty())

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()

        compose.onNodeWithText("This stream has ended").assertExists()
        compose.onNodeWithText("ended just now", substring = true).assertExists()
        compose.onNodeWithText("recording usually appears on the channel", substring = true).assertExists()
        compose.onNode(button("Back")).assertIsFocused()
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNodeWithText("Up next").assertDoesNotExist()

        compose.pressBack()
        assertFalse(onPlayer())
    }

    @Test
    fun `a live stream YouTube offered nothing for says so, and Try again asks afresh`() {
        open()
        waitFor { service.player.requested.isNotEmpty() }

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, positionMs = 0) }
        compose.waitForIdle()

        compose.onNodeWithText("This live stream can’t play right now").assertExists()
        compose.onNodeWithText("1.2K watching", substring = true).assertExists()
        compose.onNode(button("Try again")).assertIsFocused().dpadSelect()
        waitFor { service.player.requested.size == 2 }
    }

    @Test
    fun `a kids profile's live card stops before anything is asked`() {
        open(kids = true)

        compose.onNodeWithText("Live streams are off for kids").assertExists()
        compose.onNode(button("Back")).assertIsFocused()
        compose.onNode(button("Try again")).assertDoesNotExist()
        assertTrue(service.player.requested.isEmpty())
    }

    @Test
    fun `a kids profile whose resolve found a live page says live streams are off for kids`() {
        open(card = EnigmaCard, kids = true)
        waitFor { service.player.requested.isNotEmpty() }

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, positionMs = 0) }
        compose.waitForIdle()

        compose.onNodeWithText("Live streams are off for kids").assertExists()
        compose.onNodeWithText("watching", substring = true).assertDoesNotExist()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }
}

/** A clock the test moves by hand. */
private class SettableClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}
