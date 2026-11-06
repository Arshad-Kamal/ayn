package com.arshad.ayn.ui.tv.player

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.Lifecycle
import androidx.media3.common.VideoSize
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.ScriptedPlayer
import com.arshad.ayn.feature.player.playOrder
import com.arshad.ayn.feature.player.remainingText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.NoVotes
import com.arshad.ayn.ui.tv.TestKidOpening
import com.arshad.ayn.ui.tv.TestOpening
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.common.ACTION_TOAST_MS
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.testBranding
import com.arshad.ayn.ui.tv.testKidsShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.GraphicsMode

/**
 * The player through the real shell, [com.arshad.ayn.feature.player.PlayerViewModel] and a
 * real `MediaController` on an in-process session whose resolve dresses the item with the recorded
 * Enigma page ([FakePlaybackService]). Each `b3-player` state the screen reaches, the requests it
 * makes (from the watch store's resume point), its keys and BACK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerScreenTest {

    private companion object {
        const val RESUME_MS = 1_234_000L
        const val RESUMED_TEXT = "Resumed at 20:34"
        const val PROMPT_SKIP = "Skip to 2:14"
        const val OUTRO_SKIP = "Skip to 11:37"
        const val SKIPPED_TEXT = "Skipped interaction reminder · 6 seconds"
        const val CHROME_SKIP = "Skip preview and recap"
        const val PROMPT_SKIPPED_TEXT = "Skipped preview and recap, intro animation and sponsor"
        const val DEARROW_TITLE = "The Enigma machine, explained"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = ChannelExtractor()
    private val store = FakeSubscriptionStore()
    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private var device = DeviceSettings()
    private var votes: VoteSource = NoVotes
    private var branding: CardBranding? = null
    private val hiding = MutableStateFlow(blockingVideos())
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController

    /** The recorded page's related videos, as the row and Up next list them. */
    private val related = RecordedEnigmaPage.relatedVideos.distinctBy { it.id }

    private val card = VideoItem(
        id = VideoId(CHAPTERS_VIDEO_ID),
        title = RecordedEnigmaPage.title,
        uploaderName = RecordedEnigmaPage.uploaderName,
        uploaderUrl = RecordedEnigmaPage.uploaderUrl,
        durationSeconds = 2861,
        viewCount = 15_000_000,
        textualUploadDate = "12 days ago",
        uploadDate = null,
        thumbnailUrl = null,
        isLive = false,
    )

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
        brandingScope.cancel()
    }

    /** Opens [route] in the shell (a kids profile's when [kids]). */
    private fun open(route: PlayerRoute, session: Boolean = true, kids: Boolean = false) {
        compose.setContent {
            navController = rememberNavController()
            TestShell(
                extractor,
                subscriptionStore = store,
                watchStore = watch,
                playlistStore = playlists,
                profileSettings = settings,
                votes = votes,
                branding = branding,
                // Read on each connect, so a test can replace the service.
                playerSession = if (session) { _ -> service.session.token } else NoPlayerSession,
                opening = if (kids) TestKidOpening else TestOpening,
                navController = navController,
                deviceSettings = device,
                contentFilter = hiding,
                kids = if (kids) testKidsShell() else null,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(route) }
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

    /** Opens [route] and lets the stream land, playing [atMs] in. */
    private fun play(atMs: Long = 0, route: PlayerRoute = PlayerRoute(card), kids: Boolean = false) {
        open(route, kids = kids)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun press(code: Int) {
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code)))
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code)))
        compose.waitForIdle()
    }

    private fun stop(reason: String, positionMs: Long, detail: String? = null) {
        compose.runOnUiThread { service.stop(reason, positionMs, detail) }
        compose.waitForIdle()
    }

    private fun onPlayer(): Boolean = navController.currentBackStackEntry?.destination?.route?.contains("PlayerRoute") == true

    @Test
    fun `the card shows while the video starts, then the chrome with Pause focused`() {
        open(PlayerRoute(card))
        waitFor { service.player.requested.isNotEmpty() }

        compose.onNodeWithText("Starting").assertExists()
        compose.onNodeWithText(card.title).assertExists()
        compose.onNodeWithTag(PICTURE_TAG).assertExists() // composed under the opening (M3-UI-01)
        assertEquals(listOf(CHAPTERS_VIDEO_ID), service.player.requested)

        compose.runOnUiThread {
            service.player.frameRate = 24f
            service.player.ready()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Starting").assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNodeWithText("2160p · 24 fps").assertExists()
        assertTrue(compose.onAllNodesWithText(RecordedEnigmaPage.chapters.first().title, substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the chrome shows the clock beside the format badge`() {
        playAt24Fps()

        compose.onNodeWithText("2160p · 24 fps").assertExists()
        compose.onNodeWithTag(CLOCK_TAG).assertExists()
    }

    @Test
    fun `with the TV's Clock in the player off the chrome shows the badge alone`() {
        device = DeviceSettings(clockInPlayer = false)
        playAt24Fps()

        compose.onNodeWithText("2160p · 24 fps").assertExists()
        compose.onNodeWithTag(CLOCK_TAG).assertDoesNotExist()
    }

    private fun playAt24Fps() {
        open(PlayerRoute(card))
        waitFor { service.player.requested.isNotEmpty() }
        compose.runOnUiThread {
            service.player.frameRate = 24f
            service.player.ready()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a square video keeps its shape, centred across the screen`() {
        compose.runOnUiThread { service.player.pictureSize = VideoSize(1080, 1080) }
        play()

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(screen.height.value, picture.height.value, 1f)
        assertEquals(picture.height.value, picture.width.value, 1f)
        assertEquals((screen.width - picture.width).value / 2, picture.left.value, 1f)
    }

    @Test
    fun `a video wider than the screen keeps its shape, centred down the screen`() {
        compose.runOnUiThread { service.player.pictureSize = VideoSize(1920, 800) }
        play()

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(screen.width.value, picture.width.value, 1f)
        assertEquals(screen.width.value * 800 / 1920, picture.height.value, 1f)
        assertEquals((screen.height - picture.height).value / 2, picture.top.value, 1f)
    }

    @Test
    fun `a video with a resume point asks for nothing until it loads, then starts there`() {
        watch.resumePointsMs[card.id] = 1_234_000L
        val gate = CompletableDeferred<Unit>().also { watch.resumeGate = it }
        open(PlayerRoute(card))
        waitFor { service.session.connectedControllers.isNotEmpty() }
        repeat(3) { compose.waitForIdle() }
        assertTrue("no request before the kept position loads", service.player.requested.isEmpty())

        gate.complete(Unit)
        waitFor { service.player.requested.isNotEmpty() }

        assertEquals(listOf(CHAPTERS_VIDEO_ID), service.player.requested)
        assertEquals(listOf(1_234_000L), service.player.starts)
    }

    @Test
    fun `leaving the app while the resume point loads asks for nothing`() {
        val gate = CompletableDeferred<Unit>().also { watch.resumeGate = it }
        open(PlayerRoute(card))
        waitFor { service.session.connectedControllers.isNotEmpty() }
        repeat(3) { compose.waitForIdle() }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        gate.complete(Unit)
        repeat(3) { compose.waitForIdle() }

        assertTrue(service.player.requested.isEmpty())
        assertFalse("no video plays outside the screen", service.player.isPlayWhenReady)
    }

    @Test
    fun `back on a player whose video was replaced asks again from where it was left`() {
        play(atMs = 60_000)
        val other = card.copy(id = VideoId("bbbbbbbbbbb"))
        compose.runOnUiThread { navController.navigate(PlayerRoute(other)) }
        waitFor { service.player.requested.size == 2 }
        watch.resumePointsMs[card.id] = 1_500_000L // the service saved the place it was left at

        compose.runOnUiThread { navController.popBackStack() }
        waitFor { service.player.requested.size == 3 }

        assertEquals(listOf(card.id.value, other.id.value, card.id.value), service.player.requested)
        assertEquals(1_500_000L, service.player.starts.last())
    }

    @Test
    fun `Play from the start asks from 0 past the resume point`() {
        watch.resumePointsMs[card.id] = 1_234_000L

        open(PlayerRoute(card, startOver = true))
        waitFor { service.player.requested.isNotEmpty() }

        assertEquals(listOf(0L), service.player.starts)
    }

    @Test
    fun `Play from the start of the paused video asks afresh, and a return once it played resumes`() {
        play(atMs = 60_000)
        compose.runOnUiThread { navController.popBackStack() } // the item stays loaded, paused
        compose.waitForIdle()

        compose.runOnUiThread { navController.navigate(PlayerRoute(card, startOver = true)) }
        waitFor { service.player.requested.size == 2 && service.player.isPlayWhenReady }
        assertEquals(0L, service.player.starts.last())
        compose.runOnUiThread { service.player.ready(0) }
        compose.waitForIdle()

        val other = card.copy(id = VideoId("bbbbbbbbbbb"))
        compose.runOnUiThread { navController.navigate(PlayerRoute(other)) }
        waitFor { service.player.requested.size == 3 }
        watch.resumePointsMs[card.id] = 1_500_000L
        compose.runOnUiThread { navController.popBackStack() }
        waitFor { service.player.requested.size == 4 }

        assertEquals(1_500_000L, service.player.starts.last())
    }

    @Test
    fun `a reconnect after starting over resumes instead of starting over again`() {
        watch.resumePointsMs[card.id] = 1_234_000L
        play(route = PlayerRoute(card, startOver = true))
        assertEquals(listOf(0L), service.player.starts)

        service.release() // the player service goes away mid-video
        service = FakePlaybackService(compose.activity)
        waitFor { compose.onAllNodesWithText("The player didn’t answer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(button("Try again")).assertIsFocused().dpadSelect()
        waitFor { service.player.requested.isNotEmpty() }

        assertEquals(listOf(1_234_000L), service.player.starts)
    }

    @Test
    fun `Shuffle plays a saved playlist in the order its seed deals`() {
        val listed = (0 until 3).map { card.copy(id = VideoId("video${it}xxxxx"), title = "Saved video $it") }
        val id = playlists.seed("Engineering deep dives", *listed.toTypedArray())
        val dealt = playOrder(listed, shuffleSeed = -3L)
        play(route = PlayerRoute(dealt[0], PlayAll.Saved(id, "Engineering deep dives", 3, shuffleSeed = -3L)))
        compose.onNodeWithText("1 of 3").assertExists()

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 2 }

        assertEquals(dealt.take(2).map { it.id.value }, service.player.requested)
    }

    @Test
    fun `a stop names the kept place and Try again asks for the video again`() {
        play(atMs = 60_000)
        stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = 2_091_000)

        compose.onNodeWithText("Stopped after 6 tries").assertExists()
        compose.onNodeWithText("YouTube kept dropping the connection. Your place is saved at 34:51.").assertExists()
        compose.onNode(button("Try again from 34:51")).assertIsFocused().dpadSelect()
        compose.waitForIdle()

        waitFor { service.player.requested.size == 2 }
        assertEquals(listOf(CHAPTERS_VIDEO_ID, CHAPTERS_VIDEO_ID), service.player.requested)
    }

    @Test
    fun `under Restricted Mode a refusal names it as a cause`() {
        hiding.value = ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules())
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE, positionMs = 0)

        compose.onNodeWithText("or hidden by Restricted Mode", substring = true).assertExists()
    }

    @Test
    fun `a refusal offers Back only, which leaves the player`() {
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE, positionMs = 0)

        compose.onNodeWithText("This video can’t play in Ayn").assertExists()
        compose.onNodeWithText("Restricted Mode", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(PICTURE_TAG).assertExists() // composed under the statement (M3-UI-01)
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNode(button("Back")).assertIsFocused().dpadSelect()
        compose.waitForIdle()

        assertTrue(navController.currentBackStackEntry?.destination?.route?.contains("HomeRoute") == true)
    }

    @Test
    fun `a not-started stop shows YouTube's sentence from the session and offers Back only`() {
        play()
        stop(PlayerPolicy.TERMINAL_NOT_STARTED, positionMs = 0, detail = "This live event will begin in 5 days.")

        compose.onNodeWithText("This video hasn’t started yet").assertExists()
        compose.onNodeWithText("This live event will begin in 5 days.").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNode(button("Back")).assertIsFocused()
    }

    @Test
    fun `BACK hides the chrome first, then hidden Left seeks back 10 s and BACK leaves`() {
        play(atMs = 60_000)

        compose.pressBack()
        compose.onNode(button("Pause")).assertIsNotFocused()
        assertTrue(onPlayer())

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        seekGathered()
        // The scripted player's position runs on while it plays; the seek is from where it is.
        assertEquals(50_000.0, service.player.seeks.single().toDouble(), 1_000.0)

        compose.pressBack()
        assertFalse(onPlayer())
        assertFalse("no video plays outside the screen", service.player.isPlayWhenReady)
    }

    @Test
    fun `hidden, OK pauses and its key-up doesn't press the Play it reveals`() {
        play()
        compose.pressBack()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertFalse(service.player.isPlayWhenReady)
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun `hidden, Up shows the chrome on Pause and Right seeks ahead 10 s`() {
        play(atMs = 60_000)
        compose.pressBack()

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        seekGathered()
        assertEquals(70_000.0, service.player.seeks.single().toDouble(), 1_000.0)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)

        compose.onNode(button("Pause")).assertIsFocused()
        assertTrue(service.player.isPlayWhenReady)
    }

    @Test
    fun `hidden, a run of Right presses seeks once, its target on the nudge`() {
        play(atMs = 60_000)
        compose.pressBack()
        compose.mainClock.autoAdvance = false

        repeat(3) { press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS - 50)
        assertTrue("nothing sought yet", service.player.seeks.isEmpty())

        compose.mainClock.advanceTimeBy(100)
        waitFor { service.player.seeks.isNotEmpty() }
        val target = service.player.seeks.single()
        assertEquals(90_000.0, target.toDouble(), 1_000.0)
        compose.onNodeWithText(timeText(target)).assertExists()
    }

    @Test
    fun `the 10 s buttons gather too, the times on the target from the first press`() {
        play(atMs = 60_000)
        compose.mainClock.autoAdvance = false
        val forward = compose.onNode(button("Forward 10 seconds"))

        forward.dpadSelect()
        forward.dpadSelect()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("nothing sought yet", service.player.seeks.isEmpty())
        assertTrue("the times show the target", remainingShown(from = 80_000))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
        assertEquals(80_000.0, service.player.seeks.single().toDouble(), 1_000.0)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("no step back as the seek lands", remainingShown(from = 80_000))
    }

    @Test
    fun `the hidden chrome's times hold still and catch up as it shows`() {
        play(atMs = 60_000)
        compose.pressBack()

        compose.runOnUiThread { service.player.ready(300_000) }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.POSITION_TICK_MS * 3)
        compose.waitForIdle()
        assertTrue("held at the hide", remainingShown(from = 60_000))
        assertFalse("not moved on", remainingShown(from = 300_000))

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertTrue("caught up", remainingShown(from = 300_000))
    }

    /** Past the steps' gathering window, until the one seek reaches the player. */
    private fun seekGathered() {
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        waitFor { service.player.seeks.isNotEmpty() }
    }

    /** The times row's remaining time for a position a few seconds on from [from] (it plays on). */
    private fun remainingShown(from: Long): Boolean = (0..5).any { s ->
        compose.onAllNodes(hasText(remainingText(from + s * 1_000L, ScriptedPlayer.DURATION_MS))).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `leaving the app pauses the video`() {
        play()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        assertFalse(service.player.isPlayWhenReady)
    }

    /** The service retries a dropped connection (D8), 2 of 6. */
    private fun retrying() {
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
    }

    @Test
    fun `a D8 retry shows its count over the picture`() {
        play()
        retrying()

        compose.onNodeWithText("Connection dropped · retrying").assertExists()
        compose.onNodeWithText("2 of 6").assertExists()
    }

    @Test
    fun `OK on the scrub bar seeks to where Right moved its target`() {
        play(atMs = 60_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(service.player.seeks.isEmpty())
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(80_000.0, service.player.seeks.single().toDouble(), 1_000.0)
    }

    @Test
    fun `OK on the scrub bar replaces a pending 10 s step`() {
        play(atMs = 60_000)
        compose.mainClock.autoAdvance = false
        compose.onNode(button("Forward 10 seconds")).dpadSelect()

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        seekGathered()
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS * 2)
        // A late seek would hop through the session on the paused looper.
        repeat(5) { compose.waitForIdle() }

        // The bar's target moved on from the pending step's 1:10; that step never lands.
        assertEquals(80_000.0, service.player.seeks.single().toDouble(), 1_000.0)
    }

    @Test
    fun `leaving the scrub bar drops its target`() {
        play(atMs = 60_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertTrue(service.player.seeks.isEmpty())
    }

    @Test
    fun `the chrome stays while the scrub bar holds a target, then recedes once it lands`() {
        play(atMs = 60_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS * 3)
        compose.waitForIdle()
        // Still on the bar: OK seeks to the target (hidden, OK would pause).
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(80_000.0, service.player.seeks.single().toDouble(), 1_000.0)
        assertTrue(service.player.isPlayWhenReady)

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        compose.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        // Hidden again: OK paused.
        assertFalse(service.player.isPlayWhenReady)
    }

    @Test
    fun `in Play all an ended video moves on to the playlist's next`() {
        val page = extractor.playlists.getValue(PLAYLIST_URL)
        val videos = page.videos.items.filterIsInstance<VideoItem>()
        val playlist = PlaylistItem(page.details.title, PLAYLIST_URL, null, page.details.videoCount, null)
        play(route = PlayerRoute(videos[0], PlayAll.YouTube(playlist)))
        compose.onNodeWithText("1 of ${page.details.videoCount}").assertExists()

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(videos[0].id.value, videos[1].id.value), service.player.requested)
        compose.onNodeWithText(videos[1].title).assertExists()
    }

    @Test
    fun `a row's place landing late changes only the place line`() {
        val page = extractor.playlists.getValue(PLAYLIST_URL)
        val videos = page.videos.items.filterIsInstance<VideoItem>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { extractor.playlistGate = it }
        play(route = PlayerRoute(videos[1], PlayAll.YouTube(PlaylistItem(page.details.title, PLAYLIST_URL, null, page.details.videoCount, null))))
        compose.onNode(button("Pause")).dpadSelect()
        compose.waitForIdle()
        assertFalse(service.player.isPlayWhenReady)
        compose.onNodeWithText("1 of ${page.details.videoCount}").assertExists() // not landed yet

        gate.complete(Unit)
        repeat(3) { compose.waitForIdle() }

        compose.onNodeWithText("2 of ${page.details.videoCount}").assertExists()
        compose.onNodeWithText("Starting").assertDoesNotExist()
        assertFalse("the user's pause holds", service.player.isPlayWhenReady)
        assertEquals(listOf(videos[1].id.value), service.player.requested)
    }

    @Test
    fun `a video listed twice in a row plays twice`() {
        val page = extractor.playlists.getValue(PLAYLIST_URL)
        val videos = page.videos.items.filterIsInstance<VideoItem>()
        extractor.playlists[PLAYLIST_URL] = page.copy(videos = page.videos.copy(items = listOf(videos[0], videos[1], videos[1], videos[2])))
        play(route = PlayerRoute(videos[1], PlayAll.YouTube(PlaylistItem(page.details.title, PLAYLIST_URL, null, 4, null))))

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread { service.player.ready() }
        repeat(3) { compose.waitForIdle() }

        assertEquals(listOf(videos[1].id.value, videos[1].id.value), service.player.requested)
        compose.onNodeWithText("3 of ${page.details.videoCount}").assertExists()
        compose.onNodeWithText("Starting").assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
    }

    /** Plays the video [atMs] in, then lets it end. */
    private fun playToEnd(atMs: Long = 60_000) {
        play(atMs = atMs)
        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()
    }

    /** Opens the video with a stored resume point and lets it land there. */
    private fun playResumed() {
        watch.resumePointsMs[card.id] = RESUME_MS
        play(atMs = RESUME_MS)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real font metrics: the layout depends on text heights
    @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun `at Interface size 100 percent Up next's row stays at the screen's foot, focused or not`() {
        playToEnd()
        compose.mainClock.advanceTimeBy(1_000)
        val resting = compose.onNodeWithText("Related").getBoundsInRoot()
        // Where the end screen's Box put the heading before M7P-4 (this fixture, the Shield's density).
        assertEquals(311.5f, resting.top.value, 0.5f)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNode(button("Play")).assertIsNotFocused()
        assertTrue("focus is on a card in the row", compose.onNode(isFocused()).getBoundsInRoot().top > resting.bottom)
        assertEquals("nothing lifts", resting.top, compose.onNodeWithText("Related").getBoundsInRoot().top)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun `at Interface size 120 percent Up next's row follows Play and Cancel, and lifts into view on focus`() {
        device = DeviceSettings(interfaceScalePercent = 120)
        playToEnd()
        compose.mainClock.advanceTimeBy(1_000)
        val cancel = compose.onNode(button("Cancel")).getBoundsInRoot()
        val resting = compose.onNodeWithText("Related").getBoundsInRoot()
        assertTrue("Cancel ends at ${cancel.bottom}, the row starts at ${resting.top}", resting.top >= cancel.bottom + 31.dp)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(1_000)

        // The screen holds 450 dp at 120%; the card's title clears the 28 dp safe line.
        val lifted = compose.onNodeWithText("Related").getBoundsInRoot()
        val title = compose.onAllNodes(hasText(related[1].title)).onFirst().getBoundsInRoot()
        assertTrue("the row lifted from ${resting.top} to ${lifted.top}", lifted.top < resting.top && lifted.top >= 0.dp)
        assertTrue("the focused card's title ends at ${title.bottom}", title.bottom <= 422.dp)
        val liftedCancel = compose.onNode(button("Cancel")).getBoundsInRoot()
        assertEquals("Play and Cancel lift with the row", (resting.top - lifted.top).value, (cancel.top - liftedCancel.top).value, 0.5f)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNode(button("Play")).assertIsFocused()
        assertEquals("back down", resting.top, compose.onNodeWithText("Related").getBoundsInRoot().top)
        assertEquals(cancel.top, compose.onNode(button("Cancel")).getBoundsInRoot().top)
    }

    @Test
    fun `Up next with one related video has no row, and DOWN keeps Play`() {
        service.page = RecordedEnigmaPage.copy(relatedVideos = related.take(1))
        playToEnd()

        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText("Related").assertDoesNotExist()
        compose.onNode(button("Play")).assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun `an ended single video offers Up next with Play focused, and nothing starts on its own`() {
        playToEnd()

        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText(related[0].title).assertExists()
        compose.onNode(button("Play")).assertIsFocused()
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        assertEquals(listOf(CHAPTERS_VIDEO_ID), service.player.requested)
    }

    @Test
    fun `a related video the profile's filter now hides leaves Up next at once`() {
        playToEnd()
        compose.onNodeWithText(related[0].title).assertExists()

        hiding.value = blockingVideos(related[0].id.value)
        compose.waitForIdle()

        compose.onNodeWithText(related[0].title).assertDoesNotExist()
        compose.onNodeWithText(related[1].title).assertExists()
        compose.onNode(button("Play")).dpadSelect()
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[1].id.value), service.player.requested)
    }

    @Test
    fun `Up next's Play plays the first related video in place`() {
        playToEnd()

        compose.onNode(button("Play")).dpadSelect()
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[0].id.value), service.player.requested)
        assertTrue(onPlayer())
    }

    @Test
    fun `Cancel on Up next holds the ended player on Replay, and an end after replaying offers Up next again`() {
        playToEnd()

        compose.onNode(button("Cancel")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNode(button("Replay")).assertIsFocused().dpadSelect()
        compose.waitForIdle()
        assertEquals(listOf(0L), service.player.seeks)
        assertEquals(listOf(CHAPTERS_VIDEO_ID), service.player.requested)

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun `BACK on Up next holds the ended player over the Related peek`() {
        playToEnd()

        compose.pressBack()

        assertTrue(onPlayer())
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNode(button("Replay")).assertIsFocused()
        compose.onNodeWithText("Related").assertExists()
    }

    @Test
    fun `on Up next's row, BACK closes the card menu only and focus returns to the held card`() {
        playToEnd()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) // Play to the row's first card, related[1]
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNode(isFocused()).dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Save to playlist")).assertExists()
        compose.pressBack()
        compose.onNode(button("Save to playlist")).assertDoesNotExist()
        compose.onNodeWithText("Up next").assertExists()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[2].id.value), service.player.requested)
    }

    @Test
    fun `a video without related videos ends on Replay, with no peek and no Up next`() {
        service.page = RecordedEnigmaPage.copy(relatedVideos = emptyList())
        play(atMs = 60_000)
        compose.onNodeWithText("Related").assertDoesNotExist()
        compose.pressBack() // the end shows the hidden chrome

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()

        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNode(button("Replay")).assertIsFocused()
    }

    @Test
    fun `Play all's last video ends at Up next`() {
        val page = extractor.playlists.getValue(PLAYLIST_URL)
        val videos = page.videos.items.filterIsInstance<VideoItem>()
        extractor.playlists[PLAYLIST_URL] = page.copy(videos = page.videos.copy(items = videos.take(2), next = null))
        play(route = PlayerRoute(videos[1], PlayAll.YouTube(PlaylistItem(page.details.title, PLAYLIST_URL, null, 2, null))))

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()

        compose.onNodeWithText("Up next").assertExists()
        assertEquals(listOf(videos[1].id.value), service.player.requested)
    }

    @Test
    fun `a video resumed from its place says so over the hidden chrome, and DOWN starts it over`() {
        playResumed()
        compose.onNodeWithText(RESUMED_TEXT).assertExists()
        compose.onNode(button("Pause")).assertIsNotFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        assertEquals(0.0, service.player.seeks.single().toDouble(), 1_000.0)
        compose.onNodeWithText(RESUMED_TEXT).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsNotFocused()
    }

    @Test
    fun `another key drops the resumed toast and acts as usual`() {
        playResumed()

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)

        compose.onNodeWithText(RESUMED_TEXT).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
        assertTrue(service.player.seeks.isEmpty())
    }

    @Test
    fun `the resumed toast stays 6 s`() {
        playResumed()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS - 1_000)
        compose.waitForIdle()
        compose.onNodeWithText(RESUMED_TEXT).assertExists()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText(RESUMED_TEXT).assertDoesNotExist()
    }

    @Test
    fun `Play from the start shows no resumed toast`() {
        watch.resumePointsMs[card.id] = RESUME_MS
        play(route = PlayerRoute(card, startOver = true))

        compose.onNodeWithText("Resumed at", substring = true).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
    }

    @Test
    fun `the resumed toast shows once, not again on the ended player after Up next`() {
        playResumed()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP) // drops it
        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()

        compose.onNode(button("Cancel")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Replay")).assertIsFocused()
        compose.onNodeWithText("Resumed at", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Try again shows no resumed toast, and a later turn's resume still does`() {
        watch.resumePointsMs[card.id] = RESUME_MS
        open(PlayerRoute(card))
        waitFor { service.player.requested.isNotEmpty() }
        stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = RESUME_MS)
        compose.onNode(button("Try again from 20:34")).dpadSelect()
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread {
            service.session.setSessionExtras(Bundle.EMPTY)
            service.player.ready(RESUME_MS)
        }
        repeat(3) { compose.waitForIdle() }
        compose.onNodeWithText("Resumed at", substring = true).assertDoesNotExist()

        watch.resumePointsMs[related[0].id] = 61_000L
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 3 }
        compose.runOnUiThread { service.player.ready(61_000L) }
        repeat(3) { compose.waitForIdle() }

        compose.onNodeWithText("Resumed at 1:01").assertExists()
    }

    /** Plays with SponsorBlock's recorded answer for `q4FQa7N4kBg` on the item (default actions:
     *  preview, intro and sponsor prompt as one span to 2:14; interaction 11:21.274–11:27.665 skips;
     *  outro prompts to 11:37), [atMs] in. */
    private fun playWithSegments(atMs: Long) {
        service.segments = runBlocking { RecordedSegments.lookup() }
        play(atMs)
    }

    @Test
    fun `a segment's prompt waits for the chrome to hide, then OK skips to its end with the chrome still hidden`() {
        playWithSegments(atMs = 60_000)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()

        compose.pressBack()
        compose.onNodeWithText(PROMPT_SKIP).assertExists()
        listOf("Preview and recap", "Intro animation", "Sponsor").forEach { compose.onNodeWithText(it).assertExists() }
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(listOf(134_034L), service.player.seeks)
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsNotFocused()
        assertTrue("OK skipped instead of pausing", service.player.isPlayWhenReady)
    }

    /** BACK as the remote sends it: its key-down through the screen's keys, frames later the
     *  platform's back dispatch, then its key-up. */
    private fun remoteBack() {
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK)))
        compose.waitForIdle()
        compose.pressBack()
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_BACK)))
        compose.waitForIdle()
    }

    /** PAF-D2: the prompt's 8 s count only once it shows. */
    @Test
    fun `playback under the chrome doesn't use up the prompt's time`() {
        playWithSegments(atMs = 60_000)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
        compose.runOnUiThread { service.player.ready(75_000) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.pressBack()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNodeWithText(PROMPT_SKIP).assertExists()
        compose.runOnUiThread { service.player.ready(120_000) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
    }

    @Test
    fun `BACK hides the prompt and stays on the player`() {
        playWithSegments(atMs = 60_000)
        compose.pressBack()
        compose.onNodeWithText(PROMPT_SKIP).assertExists()

        remoteBack()

        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
        assertTrue(onPlayer())
        assertTrue(service.player.seeks.isEmpty())
    }

    @Test
    fun `an automatic skip jumps past its segment, and Undo on its note goes back to the start`() {
        playWithSegments(atMs = 682_000)
        assertEquals(listOf(687_665L), service.player.seeks)

        compose.pressBack()
        compose.onNodeWithText(SKIPPED_TEXT).assertExists()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(681_274L, service.player.seeks.last())
        compose.onNodeWithText(SKIPPED_TEXT).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsNotFocused()
    }

    @Test
    fun `OK on a segment's prompt skips with a note, and Undo goes back to the start unprompted`() {
        playWithSegments(atMs = 60_000)
        compose.pressBack()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(134_034L), service.player.seeks)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText(PROMPT_SKIPPED_TEXT, substring = true).assertExists()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        assertEquals(0L, service.player.seeks.last())
        compose.onNodeWithText(PROMPT_SKIPPED_TEXT, substring = true).assertDoesNotExist()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
    }

    @Test
    fun `inside a prompted span the chrome offers Skip, which jumps to its end with the note`() {
        playWithSegments(atMs = 60_000)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNode(button(CHROME_SKIP)).dpadSelect()
        compose.waitForIdle()

        assertEquals(listOf(134_034L), service.player.seeks)
        // The note shows only with the chrome away.
        compose.onNodeWithText(PROMPT_SKIPPED_TEXT, substring = true).assertExists()
    }

    @Test
    fun `outside a prompted span the chrome has no Skip`() {
        playWithSegments(atMs = 300_000)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNode(button("Pause")).assertExists()
        compose.onNode(button(CHROME_SKIP)).assertDoesNotExist()
    }

    @Test
    fun `another key drops the skip note and acts as usual`() {
        playWithSegments(atMs = 682_000)
        compose.pressBack()

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText(SKIPPED_TEXT).assertDoesNotExist()
        assertEquals(697_665.0, service.player.seeks.last().toDouble(), 1_000.0)
    }

    @Test
    fun `with the chrome up OK presses its control, not the hidden note's Undo`() {
        playWithSegments(atMs = 682_000)
        compose.onNodeWithText(SKIPPED_TEXT).assertDoesNotExist()

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertFalse(service.player.isPlayWhenReady)
        assertEquals(listOf(687_665L), service.player.seeks)
    }

    @Test
    fun `the resumed toast holds back the skip prompt until it goes`() {
        watch.resumePointsMs[card.id] = 60_000
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText("Resumed at 1:00").assertExists()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS + 500)
        compose.waitForIdle()

        compose.onNodeWithText("Resumed at 1:00").assertDoesNotExist()
        compose.onNodeWithText(PROMPT_SKIP).assertExists()
    }

    @Test
    fun `BACK with the resumed toast up leaves the player, past a skip note it hid`() {
        watch.resumePointsMs[card.id] = 682_000
        playWithSegments(atMs = 682_000)
        assertEquals(listOf(687_665L), service.player.seeks)

        remoteBack()

        assertFalse(onPlayer())
    }

    @Test
    fun `BACK hides the skip note and stays on the player`() {
        playWithSegments(atMs = 682_000)
        compose.pressBack()
        compose.onNodeWithText(SKIPPED_TEXT).assertExists()

        remoteBack()

        compose.onNodeWithText(SKIPPED_TEXT).assertDoesNotExist()
        assertTrue(onPlayer())
    }

    @Test
    fun `the skip note stays 6 s, then the prompt it held back shows`() {
        playWithSegments(atMs = 682_000)
        compose.pressBack()

        compose.mainClock.advanceTimeBy(ACTION_TOAST_MS - 1_000)
        compose.waitForIdle()
        compose.onNodeWithText(SKIPPED_TEXT).assertExists()
        compose.onNodeWithText(OUTRO_SKIP).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        compose.onNodeWithText(SKIPPED_TEXT).assertDoesNotExist()
        compose.onNodeWithText(OUTRO_SKIP).assertExists()
        compose.onNodeWithText("Endcards and credits").assertExists()
    }

    @Test
    fun `DOWN from the controls opens the Related row on its first video, and OK plays it in place`() {
        play(atMs = 60_000)
        compose.onNodeWithText(related[0].title).assertDoesNotExist() // the peek shows pictures only

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(related[0].title).assertExists()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[0].id.value), service.player.requested)
        assertTrue(onPlayer())
    }

    @Test
    fun `DOWN from the scrub bar goes to the controls, not the Related row`() {
        play(atMs = 60_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_UP) // Pause to the scrub bar
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNodeWithText(related[0].title).assertDoesNotExist()
    }

    @Test
    fun `UP or BACK closes the Related row onto Pause`() {
        play(atMs = 60_000)

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNodeWithText(related[0].title).assertDoesNotExist()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressBack()
        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNodeWithText(related[0].title).assertDoesNotExist()
        assertTrue(onPlayer())
    }

    @Test
    fun `the chrome recedes after its idle time, but not while the Related row is open`() {
        play(atMs = 60_000)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        compose.waitForIdle()
        compose.onNode(button("Pause")).assertIsNotFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_UP) // shows the chrome on Pause
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) // opens the row
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        compose.waitForIdle()

        compose.onNodeWithText(related[0].title).assertExists()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(button("Pause")).assertIsFocused()
    }

    @Test
    fun `a key restarts the chrome's idle wait, and a paused chrome stays`() {
        play(atMs = 60_000)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS - 1_000)
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT) // Pause to Back 10 seconds
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNode(button("Back 10 seconds")).assertIsFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER) // pauses
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 1_000)
        compose.waitForIdle()

        compose.onNode(button("Play")).assertIsFocused()
    }

    @Test
    fun `holding OK on a Related card opens the card menu, and BACK returns to that card`() {
        play(atMs = 60_000)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNode(isFocused()).dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Save to playlist")).assertExists()
        compose.pressBack()
        compose.onNode(button("Save to playlist")).assertDoesNotExist()
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[1].id.value), service.player.requested)
    }

    @Test
    fun `the card menu's Play plays the related video in place`() {
        play(atMs = 60_000)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNode(isFocused()).dpadLongPress(compose::waitForIdle)
        compose.onNode(button("Play")).dpadSelect()
        waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(CHAPTERS_VIDEO_ID, related[1].id.value), service.player.requested)
        assertTrue(onPlayer())
    }

    @Test
    fun `a card menu left open while Play all moves on gives focus to the new video's chrome as it closes`() {
        val page = extractor.playlists.getValue(PLAYLIST_URL)
        val videos = page.videos.items.filterIsInstance<VideoItem>()
        play(route = PlayerRoute(videos[0], PlayAll.YouTube(PlaylistItem(page.details.title, PLAYLIST_URL, null, page.details.videoCount, null))))
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(isFocused()).dpadLongPress(compose::waitForIdle)

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread { service.player.ready() }
        repeat(3) { compose.waitForIdle() }
        compose.pressBack()

        compose.onNode(button("Save to playlist")).assertDoesNotExist()
        compose.onNode(button("Pause")).assertIsFocused()
    }

    @Test
    fun `Subscribe in the chrome stores the playing video's channel`() {
        play()

        compose.onNode(button("Subscribe")).dpadSelect()
        compose.waitForIdle()

        assertTrue(store.isSubscribed(checkNotNull(card.uploaderUrl)))
        // The resolve's avatar, which lands after the chrome's actions were built.
        assertEquals(
            checkNotNull(RecordedEnigmaPage.uploaderAvatarUrl),
            runBlocking { store.subscription(checkNotNull(card.uploaderUrl)).first() }?.avatarUrl,
        )
        compose.onNode(button("Subscribed")).assertExists()
    }

    @Test
    fun `a kids profile's player has no Subscribe in the chrome or in Details, and no Comments`() {
        service.page = RecordedEnigmaPage.copy(chapters = emptyList())
        play(atMs = 300_000, kids = true)

        compose.onNode(button("Pause")).assertIsFocused()
        compose.onNode(button("Subscribe")).assertDoesNotExist()
        compose.onNode(button("Subscribed")).assertDoesNotExist()
        compose.onNode(button("Comments")).assertDoesNotExist()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) // Pause to Forward 10 seconds
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(button("Captions")).assertIsFocused()

        compose.onNode(moreControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Video details")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Details").assertExists()
        compose.onNode(button("Subscribe")).assertDoesNotExist()
        compose.onNode(button("Subscribed")).assertDoesNotExist()
    }

    /** PAF-D1: the card goes into History once the video plays, not as the player opens. */
    @Test
    fun `the card goes into History once the video plays`() {
        open(PlayerRoute(card))
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        assertTrue(watch.opened.isEmpty())

        compose.runOnUiThread { service.player.ready(0) }
        compose.waitForIdle()

        assertEquals(listOf(card.id), watch.opened.map { it.id })
    }

    @Test
    fun `a player service that can't be reached says the player didn't answer`() {
        open(PlayerRoute(card), session = false)

        compose.onNodeWithText("The player didn’t answer").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun `the rail is gone while the player shows`() {
        open(PlayerRoute(card))
        waitFor { service.player.requested.isNotEmpty() }

        compose.onNode(hasText("Home")).assertDoesNotExist()
    }

    /** A sheet row or a chapter by its label (the chrome's round controls carry theirs as a description). */
    private fun row(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private val moreControl = hasContentDescription("More") and hasClickAction()
    private val speedControl = hasContentDescription("Speed") and hasClickAction()

    /** Plays [atMs] in, then OK on the chrome's ⋯: the Playback sheet. */
    private fun openPlaybackSheet(atMs: Long = 300_000) {
        play(atMs)
        compose.onNode(moreControl).dpadSelect()
        compose.waitForIdle()
    }

    /** The second chapter of the recorded page, and Details opened 5 s into it. */
    private fun openDetailsInSecondChapter(): Chapter {
        val chapter = RecordedEnigmaPage.chapters[1]
        openPlaybackSheet(atMs = chapter.startSeconds * 1000 + 5_000)
        compose.onNode(row("Video details")).dpadSelect()
        compose.waitForIdle()
        return chapter
    }

    @Test
    fun `More opens the Playback sheet on Quality with this video's values, and BACK returns to the chrome on More`() {
        service.player.frameRate = 24f // the stream's selected VP9 track
        openPlaybackSheet()

        compose.onNodeWithText("Playing now").assertExists()
        compose.onNode(row("Quality")).assertIsFocused().assert(hasText("2160p · VP9"))
        compose.onNode(row("Speed")).assert(hasText("Normal"))
        compose.onNode(row("Captions")).assert(hasText("Off"))
        compose.onNode(row("SponsorBlock")).assert(hasText("Off"))
        compose.onNode(button("Pause")).assertIsNotFocused()

        compose.pressBack()

        compose.onNodeWithText("Playing now").assertDoesNotExist()
        compose.onNode(moreControl).assertIsFocused()
        assertTrue(onPlayer())
    }

    @Test
    fun `the Playback sheet lists SponsorBlock's segments by category and start, in time order`() {
        service.segments = runBlocking { RecordedSegments.lookup() }
        openPlaybackSheet()

        compose.onNode(row("SponsorBlock")).assert(
            hasText("Preview and recap 0:00 · Intro animation 0:51 · Sponsor 0:56 · Interaction reminder 11:21 · Endcards and credits 11:27"),
        )
    }

    @Test
    fun `the Playback sheet says when SponsorBlock's segments didn't load`() {
        service.segments = SegmentLookup.Failed
        openPlaybackSheet()

        compose.onNode(row("SponsorBlock")).assert(hasText("Segments didn’t load for this video; it plays without them"))
    }

    @Test
    fun `a sheet keeps the skip prompt back, and the chrome it returns to holds it too`() {
        service.segments = runBlocking { RecordedSegments.lookup() }
        openPlaybackSheet(atMs = 60_000)

        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
        compose.pressBack()
        compose.onNodeWithText(PROMPT_SKIP).assertDoesNotExist()
        compose.onNode(moreControl).assertIsFocused()
    }

    @Test
    fun `Speed in the Playback sheet opens on Normal, and a pick sets this video's speed and returns to the chrome on More`() {
        openPlaybackSheet()
        compose.onNode(row("Speed")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Pitch stays natural at every speed.").assertExists()
        compose.onNode(row("Normal")).assertIsFocused()

        compose.onNode(row("1.5×")).dpadSelect()
        waitFor { service.player.speed == 1.5f }

        compose.onNodeWithText("Pitch stays natural at every speed.").assertDoesNotExist()
        compose.onNode(moreControl).assertIsFocused().dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Speed")).assert(hasText("1.5×")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("1.5×")).assertIsFocused()
    }

    @Test
    fun `Try again on the same video keeps its speed`() {
        play(atMs = 60_000)
        compose.onNode(speedControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("1.5×")).dpadSelect()
        waitFor { service.player.speed == 1.5f }

        stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = 60_000)
        compose.onNode(button("Try again from 1:00")).dpadSelect()
        waitFor { service.player.requested.size == 2 }

        assertEquals(1.5f, service.player.speed)
    }

    @Test
    fun `a video ending under the sheet with nothing to follow leaves focus in the sheet`() {
        service.page = RecordedEnigmaPage.copy(relatedVideos = emptyList())
        openPlaybackSheet()

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)

        compose.onNode(row("Quality")).assertIsFocused()
        compose.onNode(button("Replay")).assertIsNotFocused()
    }

    @Test
    fun `the video ending under Details closes it for Up next, with the picture full again`() {
        openDetailsInSecondChapter()

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText("Details").assertDoesNotExist()
        assertEquals(960f, compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot().width.value, 1f)
        compose.onNode(button("Cancel")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithText("Playing now").assertDoesNotExist()
        compose.onNode(button("Replay")).assertIsFocused()
    }

    @Test
    fun `Details clears the retry note off the picture`() {
        openDetailsInSecondChapter()

        retrying()

        compose.onNodeWithText("Connection dropped · retrying").assertDoesNotExist()
    }

    @Test
    fun `Details without chapters opens on Subscribe`() {
        service.page = RecordedEnigmaPage.copy(chapters = emptyList())
        openPlaybackSheet()
        compose.onNode(row("Video details")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Chapters", substring = true).assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Subscribe"))
    }

    @Test
    fun `Details with neither chapters nor a channel holds focus on the page`() {
        // A card without a channel takes the page's; this page names none either.
        service.page = RecordedEnigmaPage.copy(chapters = emptyList(), uploaderUrl = null)
        play(atMs = 300_000, route = PlayerRoute(card.copy(uploaderUrl = null)))
        compose.onNode(moreControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Video details")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Details").assertExists()
        compose.onNode(isFocused()).assertExists()
        compose.onNode(isFocused() and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `BACK from Speed returns to the Playback sheet on its Speed row`() {
        openPlaybackSheet()
        compose.onNode(row("Speed")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Playing now").assertExists()
        compose.onNode(row("Speed")).assertIsFocused()
        assertEquals(1f, service.player.speed)
    }

    @Test
    fun `the chrome's Speed opens the Speed sheet, and BACK returns to it`() {
        play(atMs = 60_000)
        compose.onNode(speedControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Normal")).assertIsFocused()

        compose.pressBack()

        compose.onNodeWithText("Pitch stays natural at every speed.").assertDoesNotExist()
        compose.onNode(speedControl).assertIsFocused()
    }

    @Test
    fun `with the profile at Normal, another video starts at Normal speed`() {
        play(atMs = 60_000)
        compose.onNode(speedControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("2×")).dpadSelect()
        waitFor { service.player.speed == 2f }

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) // the Related row
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(1f, service.player.speed)
    }

    @Test
    fun `each video starts at the profile's speed, the next one too after a pick`() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(speedPercent = 150))
        play(atMs = 60_000)
        assertEquals(1.5f, service.player.speed)
        compose.onNode(speedControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("2×")).dpadSelect()
        waitFor { service.player.speed == 2f }

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) // the Related row
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { service.player.requested.size == 2 }

        assertEquals(1.5f, service.player.speed)
    }

    @Test
    fun `Video details shows the video's details with the playing chapter focused`() {
        val chapter = openDetailsInSecondChapter()

        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("21M subscribers").assertExists() // the page's "21.3m"
        compose.onNodeWithText("15M views · 12 days ago").assertExists()
        compose.onNodeWithText("121K").assertExists() // the page's 121,954 likes
        compose.onNodeWithText("No dislike data for this video").assertExists()
        compose.onNodeWithText("Chapters  ${RecordedEnigmaPage.chapters.size}").assertExists()
        compose.onNode(isFocused()).assert(hasText(chapter.title)).assert(hasText("Now"))
    }

    @Test
    fun `Details shows Return YouTube Dislike's count, the ratio and its credit`() {
        votes = VoteSource { VoteLookup.Found(1_300) }
        openDetailsInSecondChapter()

        compose.onNodeWithText("1.3K").assertExists()
        compose.onNodeWithText("98.9% liked").assertExists() // 121,954 of 123,254
        compose.onNodeWithText("Dislikes are an estimate from Return YouTube Dislike.").assertExists()
    }

    @Test
    fun `with a DeArrow title the opening holds the title, then the chrome shows DeArrow's and Details credits YouTube's`() {
        val gate = CompletableDeferred<Unit>()
        branding = testBranding(
            brandingScope,
            BrandingSource { id, _ ->
                gate.await()
                if (id == card.id) BrandingLookup.Found(Branding(DEARROW_TITLE, null)) else BrandingLookup.None
            },
        )
        open(PlayerRoute(card))
        compose.onNodeWithText("Starting").assertExists()
        compose.onNodeWithText(card.title).assertDoesNotExist()
        compose.onNodeWithText(DEARROW_TITLE).assertDoesNotExist()

        gate.complete(Unit)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(300_000) }
        compose.waitForIdle()

        compose.onNodeWithText(DEARROW_TITLE).assertExists()
        compose.onNodeWithText(card.title).assertDoesNotExist()

        compose.onNode(moreControl).dpadSelect()
        compose.onNode(row("Video details")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Title from DeArrow · Original: \u201C${card.title}\u201D").assertExists()
        compose.onNodeWithText(card.title).assertDoesNotExist()
    }

    /** A DeArrow thumbnail under YouTube's own title: nothing to credit. */
    @Test
    fun `Details credits no one when the title is YouTube's`() {
        branding = testBranding(brandingScope, BrandingSource { _, _ -> BrandingLookup.Found(Branding(null, "https://example.invalid/frame.webp")) })
        openDetailsInSecondChapter()

        assertTrue(compose.onAllNodesWithText(card.title).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Title from DeArrow", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the picture plays in Details' corner, and BACK returns to the Playback sheet on Video details`() {
        openDetailsInSecondChapter()
        val picture = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(960f - 58f, picture.right.value, 1f)
        assertEquals(28f, picture.top.value, 1f)
        assertEquals(345.6f, picture.width.value, 1f)

        compose.pressBack()

        compose.onNode(row("Video details")).assertIsFocused()
        assertEquals(960f, compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot().width.value, 1f)
    }

    @Test
    fun `OK on a chapter plays from its start and returns to the chrome on More`() {
        openDetailsInSecondChapter()
        val next = RecordedEnigmaPage.chapters[2]

        compose.onNode(row(next.title)).dpadSelect()
        compose.waitForIdle()

        assertEquals(next.startSeconds * 1000, service.player.seeks.last())
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNode(moreControl).assertIsFocused()
    }
}
