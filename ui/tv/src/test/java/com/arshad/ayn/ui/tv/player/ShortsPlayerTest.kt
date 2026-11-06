package com.arshad.ayn.ui.tv.player

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.media3.common.VideoSize
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.playerViewModel
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.testBranding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shorts player (`b3-player` short and the M7.8m states) through the real shell: a run opened
 * from the recorded Shorts tab of a channel page (48 Shorts), or a lone Short, over a real
 * `MediaController` on the in-process session ([FakePlaybackService], whose resolve dresses every
 * item with the recorded Enigma page: Veritasium's). Its keys (Q36, Q38, Q48), its buttons, its
 * stops (Q49) and YouTube's face (Q40).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ShortsPlayerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = ChannelExtractor()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private val store = FakeSubscriptionStore()
    private var branding: CardBranding? = null
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController
    private val shortsTab: ItemsPage by lazy { recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) } }
    private val first: VideoItem get() = shortsTab.shorts[0]
    private val second: VideoItem get() = shortsTab.shorts[1]

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        extractor.tabs[ChannelTab.SHORTS] = shortsTab
    }

    @After
    fun tearDown() {
        service.release()
        brandingScope.cancel()
    }

    private fun launch(session: Boolean = true) {
        compose.setContent {
            navController = rememberNavController()
            TestShell(
                extractor,
                subscriptionStore = store,
                profileSettings = settings,
                branding = branding,
                playerSession = if (session) service.token else NoPlayerSession,
                navController = navController,
            )
        }
        compose.waitForIdle()
    }

    /** The channel page's first Short opened, so the player runs through the tab's Shorts. */
    private fun openRun() {
        launch()
        compose.runOnUiThread { navController.navigate(ChannelRoute(RECORDED_CHANNEL_URL)) }
        compose.waitForIdle()
        compose.onNode(button("Videos")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(button("Shorts")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val card = shortCards().minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        compose.onNode(SemanticsMatcher("the first Short") { it.id == card.id }).dpadSelect()
        compose.waitForIdle()
        assertEquals(true, navController.playerRoute()?.short)
        waitFor { service.player.requested.isNotEmpty() }
    }

    /** The tab's cards: clickable nodes a Short card wide and taller than wide. */
    private fun shortCards(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width in SHORT_CARD_WIDTH_DP - 1f..SHORT_CARD_WIDTH_DP * 1.1f && it.boundsInRoot.height > SHORT_CARD_WIDTH_DP }

    private fun openAlone(card: VideoItem, session: Boolean = true) {
        launch(session)
        compose.runOnUiThread { navController.navigate(PlayerRoute(card, short = true)) }
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun ready(atMs: Long = 0) {
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.waitForIdle()
    }

    private fun press(code: Int) {
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code)))
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code)))
        compose.waitForIdle()
    }

    private fun stop(reason: String) {
        compose.runOnUiThread {
            service.session.setSessionExtras(Bundle().apply { putString(PlaybackContract.EXTRA_STOP_REASON, reason) })
        }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    /** One seek, to [targetMs] ([scrubbable] starts paused, so the target is exact). */
    private fun assertSoughtOnceTo(targetMs: Long) {
        assertEquals(listOf(targetMs), service.player.seeks)
    }

    /** The run opened, its Short [atMs] in and paused: a playing one's position runs on in real
     *  time on the controller until the pause lands, which would blur the step's target. */
    private fun scrubbable(atMs: Long = 20_000) {
        openRun()
        ready(atMs)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { !service.player.isPlayWhenReady }
    }

    private companion object {
        const val SHORT_CARD_WIDTH_DP = 115f
        const val SHORT_STEP_DP = SHORT_CARD_WIDTH_DP + 17f
        const val CONTENT_START_DP = 126f
        // Folded, the tab area starts at 360 − 265 = 95; the cards 28 + 11 below it; half the
        // picture (115 × 16/9 laid out as 204) below that, less the 3 dp lift.
        const val FOLDED_SHORT_CENTER_Y_DP = 95f + 28f + 11f + 204f / 2 - 3f
        const val FOCUS_LIFT_DP = 3f

        /** When the held key in [aHeldKeyStepsAtMostEvery150MsThenEvery60Ms] went down. */
        const val HELD_DOWN_AT = 100_000L
    }

    private fun onPlayer(): Boolean = navController.currentBackStackEntry?.destination?.hasRoute(PlayerRoute::class) == true

    @Test
    fun aShortStartsOnItsCardWithSaveAloneThenPlaysWithItsChannel() {
        openRun()

        compose.onNodeWithText("Starting").assertExists()
        compose.onNodeWithText(first.title).assertExists()
        compose.onNodeWithText("11M views").assertExists()
        compose.onNode(button("Save")).assertExists()
        compose.onNode(button(first.uploaderName!!)).assertDoesNotExist()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        assertEquals(listOf(first.id.value), service.player.requested)

        ready()

        compose.onNodeWithText("Starting").assertDoesNotExist()
        compose.onNode(button(first.uploaderName!!)).assertExists()
        compose.onNode(button("Subscribe")).assertExists()
        listOf("Previous", "Next Short", "Pause").forEach { compose.onNodeWithText(it).assertExists() }
    }

    /**
     * A Short of [size] playing: its surface is [width] × [height], centred; the stage's picture
     * window (overlays, the backdrop's cut-out) is as wide and full height; the column clears both.
     */
    private fun assertPlaysInFrame(size: VideoSize, width: Float, height: Float) {
        compose.runOnUiThread { service.player.pictureSize = size }
        openRun()
        ready()

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText(first.title).getUnclippedBoundsInRoot()
        val surface = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(width, surface.width.value, 1f)
        assertEquals(height, surface.height.value, 1f)
        assertEquals((screen.height - surface.height).value / 2, surface.top.value, 1f)
        assertEquals((screen.width - surface.width).value / 2, surface.left.value, 1f)
        val window = compose.onNodeWithTag(SHORT_PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(width, window.width.value, 1f)
        assertEquals(screen.height.value, window.height.value, 1f)
        assertEquals(surface.left.value, window.left.value, 1f)
        assertTrue("the column starts right of the picture", title.left >= window.right)
    }

    // The 960 × 540 screen's 9:16 frame is 540 × 9/16 = 303.75 wide.
    @Test
    fun aSquareShortFitsTheFrameWidthWithBarsAboveAndBelow() = assertPlaysInFrame(VideoSize(1080, 1080), 303.75f, 303.75f)

    @Test
    fun aLandscapeShortFitsTheFrameWidthWithBarsAboveAndBelow() = assertPlaysInFrame(VideoSize(1920, 1080), 303.75f, 303.75f * 9 / 16)

    @Test
    fun aShortNarrowerThanTheFrameKeepsFullHeight() = assertPlaysInFrame(VideoSize(1080, 2400), 540f * 1080 / 2400, 540f)

    @Test
    fun downAndUpMoveThroughTheRunAndUpOnTheFirstDoesNothing() {
        openRun()
        ready()

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf(first.id.value), service.player.requested)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        waitFor { service.player.requested.size == 2 }
        assertEquals(second.id.value, service.player.requested.last())
        compose.onNodeWithText(second.title).assertExists()
        assertEquals(1, navController.playerViewModel().uiState.value.shorts!!.index)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        waitFor { service.player.requested.size == 3 }

        assertEquals(listOf(first.id.value, second.id.value, first.id.value), service.player.requested)
    }

    @Test
    fun okPausesAndPlaysTheShort() {
        openRun()
        ready()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { !service.player.isPlayWhenReady }
        compose.onNodeWithText("Play").assertExists()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.isPlayWhenReady }
        compose.onNodeWithText("Pause").assertExists()
    }

    /** SSC-D2: Left on a playing Short pauses it in scrub mode. */
    @Test
    fun leftOnAPlayingShortPausesItInScrubMode() {
        openRun()
        ready(atMs = 20_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        waitFor { !service.player.isPlayWhenReady }
        compose.onNodeWithText("Play from here").assertExists()
    }

    /** SSC-D2: Left enters scrub mode one 5 s step back; OK plays from there. */
    @Test
    fun leftEntersScrubModeOneStepBackAndOkPlaysFromThere() {
        scrubbable()

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText("5 seconds").assertExists()
        compose.onNodeWithText("Play from here").assertExists()
        compose.onNodeWithText("0:15 / 47:41").assertExists()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.isPlayWhenReady }
        assertSoughtOnceTo(15_000L)
        compose.onNodeWithText("Play from here").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertExists()
    }

    /** PERF-D6 B1 for Shorts: the steps show at once and gather into one seek after the last. */
    @Test
    fun scrubStepsGatherIntoOneSeek() {
        scrubbable()
        compose.mainClock.autoAdvance = false

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("0:25 / 47:41").assertExists()
        assertTrue("nothing sought yet", service.player.seeks.isEmpty())

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)
        waitFor { service.player.seeks.isNotEmpty() }
        assertSoughtOnceTo(25_000L)
        assertFalse("still paused in scrub mode", service.player.isPlayWhenReady)
    }

    /** SSC-D2: a held key's repeats step every 150 ms for its first second, then every 60 ms. */
    @Test
    fun aHeldKeyStepsAtMostEvery150MsThenEvery60Ms() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT) // 15 s

        // Down at 0, then repeats: 400 steps, 500 is too soon (under 150 ms), 550 steps; past a second 1100
        // steps, 1150 is too soon, 1170 steps.
        listOf(0L to 0, 400L to 1, 500L to 2, 550L to 3, 1_100L to 4, 1_150L to 5, 1_170L to 6).forEach { (at, repeat) ->
            val down = AndroidKeyEvent(HELD_DOWN_AT, HELD_DOWN_AT + at, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            compose.onRoot().performKeyPress(KeyEvent(down))
        }
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(HELD_DOWN_AT, HELD_DOWN_AT + 1_200, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, 0)))
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertSoughtOnceTo(40_000L)
    }

    @Test
    fun playPauseInScrubModePlaysFromThere() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        press(AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        waitFor { service.player.isPlayWhenReady }
        assertSoughtOnceTo(15_000L)
    }

    /** OK after the gathered seek landed plays on without seeking again. */
    @Test
    fun okAfterTheGatheredSeekLandedSeeksNoMore() {
        scrubbable()
        compose.mainClock.autoAdvance = false
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)
        waitFor { service.player.seeks.isNotEmpty() }

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)

        assertSoughtOnceTo(15_000L)
        assertTrue(service.player.isPlayWhenReady)
    }

    /** A held Left walking back from the buttons stops on the Short; only a fresh press scrubs. */
    @Test
    fun aHeldLeftFromTheButtonsDoesntEnterScrubMode() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) // the channel

        listOf(0L to 0, 400L to 1, 450L to 2).forEach { (at, repeat) ->
            val down = AndroidKeyEvent(HELD_DOWN_AT, HELD_DOWN_AT + at, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_LEFT, repeat)
            compose.onRoot().performKeyPress(KeyEvent(down))
        }
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(HELD_DOWN_AT, HELD_DOWN_AT + 500, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_LEFT, 0)))
        compose.waitForIdle()

        assertTrue("no step taken", service.player.seeks.isEmpty())
        compose.onNodeWithText("Play from here").assertDoesNotExist()
    }

    /** A stop ends scrub mode; Down moves on again. */
    @Test
    fun aStopEndsScrubMode() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNodeWithText("Play from here").assertDoesNotExist()
        waitFor { service.player.requested.size == 2 }
    }

    @Test
    fun upAndDownDoNothingInScrubMode() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)

        assertEquals(1, service.player.requested.size)
        compose.onNodeWithText("Play from here").assertExists()
    }

    @Test
    fun backInScrubModePlaysFromThereAndStaysInThePlayer() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        compose.pressBack()

        waitFor { service.player.isPlayWhenReady }
        assertSoughtOnceTo(15_000L)
        assertTrue(onPlayer())
    }

    /** SSC-D2 (Arshad Q1): Seek, after Save, enters scrub mode without a step; the keys then step. */
    @Test
    fun theSeekButtonEntersScrubModeWithoutAStep() {
        scrubbable()
        repeat(4) { press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) } // Channel, Subscribe, Save, Seek
        compose.onNode(button("Seek")).assertIsFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Play from here").assertExists()
        compose.onNodeWithText("0:20 / 47:41").assertExists()
        compose.onNode(button("Seek")).assertIsNotFocused() // back on the Short
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertSoughtOnceTo(25_000L)
        assertTrue(service.player.isPlayWhenReady)
    }

    /** SSC-D1: every Short steps 5 s, a short one too. */
    @Test
    fun aShortUnderTwentySecondsStepsFiveSecondsToo() {
        service.player.videoDurationMs = 16_000
        scrubbable(atMs = 10_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText("5 seconds").assertExists()
        compose.onNodeWithText("0:05 / 0:16").assertExists()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertSoughtOnceTo(5_000L)
    }

    /** The remote's Play reaching the session ends scrub mode too. */
    @Test
    fun playingAgainFromElsewhereEndsScrubMode() {
        scrubbable()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        compose.runOnUiThread { service.player.play() }
        compose.waitForIdle()

        compose.onNodeWithText("Play from here").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertExists()
    }

    @Test
    fun rightReachesTheButtonsAndLeftOrBackReturnsToTheShort() {
        openRun()
        ready()
        val channel = button(first.uploaderName!!)

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(channel).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(button("Subscribe")).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNode(channel).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNode(channel).assertIsNotFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER) // on the Short again: OK pauses
        waitFor { !service.player.isPlayWhenReady }

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(channel).assertIsFocused()
        compose.pressBack()
        compose.onNode(channel).assertIsNotFocused()
        assertTrue("BACK from the buttons stays in the player", onPlayer())

        compose.pressBack()
        assertFalse("BACK on the Short leaves", onPlayer())
    }

    @Test
    fun downFromTheButtonsMovesOnWithFocusBackOnTheShort() {
        openRun()
        ready()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        waitFor { service.player.requested.size == 2 }

        compose.onNode(button("Save")).assertIsNotFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(button("Save")).assertIsFocused() // starting: Save alone
    }

    @Test
    fun theChannelButtonOpensTheShortsChannel() {
        openRun()
        ready()

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        val entry = checkNotNull(navController.currentBackStackEntry)
        assertTrue(entry.destination.hasRoute(ChannelRoute::class))
        assertEquals(first.uploaderUrl, entry.toRoute<ChannelRoute>().channelUrl)
    }

    @Test
    fun subscribeStoresTheShortsChannel() {
        openRun()
        ready()

        compose.onNode(button("Subscribe")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Subscribed")).assertExists()
        assertTrue(store.isSubscribed(first.uploaderUrl!!))
    }

    @Test
    fun saveOpensTheSaveSheetAloneAndBackReturnsToSave() {
        openRun()
        ready()

        compose.onNode(button("Save")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(button("Watch later")).assertExists()
        compose.onNode(button("New playlist")).assertExists()
        compose.onNode(button("Play")).assertDoesNotExist()

        compose.pressBack()

        compose.onNode(button("Watch later")).assertDoesNotExist()
        compose.onNode(button("Save")).assertIsFocused()
        assertTrue(onPlayer())
    }

    @Test
    fun aShortThatCouldntStartSaysWhyWithTryAgainAndDownMovesOn() {
        openRun()
        stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE)

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("This Short couldn’t play").assertExists()
        compose.onNodeWithText("Check that the Shield is online, then try again. Up and Down move to another Short.").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
        compose.onNode(button("Save")).assertDoesNotExist()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }
        assertEquals(listOf(first.id.value, first.id.value), service.player.requested)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        waitFor { service.player.requested.size == 3 }
        assertEquals(second.id.value, service.player.requested.last())
        // The stop was the last Short's: the next one starts.
        compose.onNodeWithText("This Short couldn’t play").assertDoesNotExist()
        compose.onNodeWithText("Starting").assertExists()
    }

    @Test
    fun aStopThatTakesTheFocusedButtonAwayStillLetsDownMoveOn() {
        openRun()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(button("Save")).assertIsFocused()

        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        waitFor { service.player.requested.size == 2 }
        assertEquals(second.id.value, service.player.requested.last())
    }

    @Test
    fun aRetryWhileStartingShowsOnTheShort() {
        openRun()
        compose.runOnUiThread {
            service.session.setSessionExtras(
                Bundle().apply {
                    putString(PlaybackContract.EXTRA_RETRY_KIND, PlaybackContract.RETRY_RECOVERABLE)
                    putInt(PlaybackContract.EXTRA_ATTEMPT, 2)
                    putInt(PlaybackContract.EXTRA_MAX, 6)
                },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Connection dropped · retrying").assertExists()
        compose.onNodeWithText("2 of 6").assertExists()
        compose.onNodeWithText("Starting").assertDoesNotExist()
    }

    @Test
    fun aRefusedShortOffersNoTryAgainAndUpDownStillMove() {
        openRun()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)

        compose.onNodeWithText("YouTube refused to play it").assertExists()
        compose.onNodeWithText("This Short can’t play in Ayn").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNode(button("Back")).assertDoesNotExist()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        waitFor { service.player.requested.size == 2 }
        compose.pressBack()
        assertFalse(onPlayer())
    }

    @Test
    fun aMadeForKidsShortKeepsTheMadeForKidsTitle() {
        openRun()
        stop(PlayerPolicy.TERMINAL_MADE_FOR_KIDS)

        compose.onNodeWithText("This made-for-kids video can’t play in Ayn").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    @Test
    fun backFocusesTheLastPlayedShortOnTheChannelWithItsRowScrolledUp() {
        openRun()
        ready()
        val player = navController.playerViewModel()
        compose.runOnUiThread { repeat(13) { player.nextShort() } }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.pressBack()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val placed = shortCards().associate { it.id to it.boundsInRoot.center.y }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // Six across: Short 13 is the third row's second card, its row where D-pad leaves a
        // focused Short's.
        assertFalse(onPlayer())
        val focusedNode = compose.onNode(isFocused()).fetchSemanticsNode()
        val focused = focusedNode.boundsInRoot
        assertTrue("a Short holds focus", focused.height > SHORT_CARD_WIDTH_DP)
        assertEquals(CONTENT_START_DP + SHORT_STEP_DP + SHORT_CARD_WIDTH_DP / 2, focused.center.x, 0.5f)
        assertEquals(FOLDED_SHORT_CENTER_Y_DP, focused.center.y, 0.5f)
        // Two frames in, before focus lifts it: in place, so no scroll follows the focus.
        assertEquals(FOLDED_SHORT_CENTER_Y_DP + FOCUS_LIFT_DP, checkNotNull(placed[focusedNode.id]), 0.5f)
        val title = shortsTab.shorts[13].title
        assertTrue(
            "it is Short 13",
            compose.onAllNodesWithText(title).fetchSemanticsNodes().any { it.boundsInRoot.center.x in focused.left..focused.right && it.boundsInRoot.top >= focused.top },
        )
    }

    @Test
    fun aPlayerThatDoesntAnswerSaysSoInTheColumn() {
        openAlone(first, session = false)

        compose.onNodeWithText("The player didn’t answer").assertExists()
        compose.onNodeWithText("This Short couldn’t play").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun aSearchShortGainsItsChannelOnceItPlaysAndShowsYouTubesTitle() {
        branding = testBranding(brandingScope) // DeArrow has a title for this video; a Short never asks
        val searchShort = first.copy(id = VideoId(CHAPTERS_VIDEO_ID), uploaderName = null, uploaderUrl = null)
        openAlone(searchShort)
        waitFor { service.player.requested.isNotEmpty() }
        compose.onNode(button(RecordedEnigmaPage.uploaderName!!)).assertDoesNotExist()

        ready()

        compose.onNode(button(RecordedEnigmaPage.uploaderName!!)).assertExists()
        compose.onNodeWithText(searchShort.title).assertExists()
        compose.onNodeWithText("The Enigma machine, explained").assertDoesNotExist()
        val position = navController.playerViewModel().uiState.value.shorts!!
        assertFalse(position.hasPrevious || position.hasNext)
        assertNull(navController.playerViewModel().uiState.value.playlist)
    }
}
