package com.arshad.ayn.ui.mobile.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.media3.common.C
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.QUALITY_SHEET_SUB
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sideways watch page (`m6-player` 29\u201334; M6-D2, Q29, Q39) through the shell's real wiring and
 * a real `MediaController` on an in-process session dressed with the recorded Enigma page. The
 * phone's turn is the configuration's orientation, flipped in place ([landscape]) as the activity
 * would see it (it handles the change itself rather than being recreated).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class LandscapeWatchTest {
    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val KID = Profile(2, "Zara", ProfileTint.Amber, isOwner = false, lock = null, isKids = true)
        const val PROMPT_SKIP = "Skip to 2:14"
        const val CHROME_SKIP = "Skip preview and recap"
        const val LIVE_WINDOW_MS = 3_600_000L
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private val stores by lazy { TestStores(session = { service.session.token }) }
    private lateinit var slot: PlayerSlot
    private var landscape by mutableStateOf(true)
    private val related = RecordedEnigmaPage.relatedVideos.distinctBy { it.id }

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    private fun play(ready: Boolean = true, profile: Profile = ARSHAD, card: VideoItem = EnigmaCard, atMs: Long = 60_000) {
        val models = stores.models()
        compose.setContent {
            val base = LocalConfiguration.current
            val turned = Configuration(base).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides turned) {
                AynTheme {
                    slot = rememberPlayerSlot()
                    ProfileShell(ShellOpening(profile, onSwitchProfile = {}), models, slot = slot)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(card)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        if (ready) compose.runOnUiThread { service.player.ready(atMs) }
        settle()
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

    private fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun has(label: String): Boolean = compose.onAllNodes(button(label)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        settle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun turn(sideways: Boolean) {
        landscape = sideways
        settle()
    }

    private fun showChrome() = tap("Video")

    private fun pane(title: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private val orientation get() = compose.activity.requestedOrientation

    @Test
    fun sidewaysThePictureFillsTheScreenUnderTheTvChromeWithNoInfoUnderIt() {
        play()
        showChrome()

        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val picture = compose.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.height, picture.height, 1f)
        assertTrue(shows("${EnigmaCard.uploaderName}  ${subscribersText(RecordedEnigmaPage.uploaderSubscriberCount!!)}"))
        listOf("Minimize", "Back 10 seconds", "Pause", "Forward 10 seconds", "Comments", "Captions", "Up next", "Playback settings", "Exit full screen")
            .forEach { assertTrue(it, has(it)) }
        compose.onNode(button("Speed")).assert(hasText(speedLabel(100)))
        assertFalse("no chips sideways", has("Save"))
        assertFalse("no Subscribe sideways", has("Subscribe"))
    }

    /** PAF-D12 (`m6-player` 29b, 30b): inside a prompted span the Skip pill leads the foot row; a tap
     *  jumps to the span's end, the chrome goes and the note says what it skipped. */
    @Test
    fun sidewaysInsideAPromptedSpanTheSkipPillLeadsTheFootAndJumpsToItsEndWithTheNote() {
        service.segments = runBlocking { RecordedSegments.lookup() }
        play()
        showChrome()

        val skip = compose.onNode(button(CHROME_SKIP)).getUnclippedBoundsInRoot()
        assertTrue("Skip before Comments", skip.right < compose.onNode(button("Comments")).getUnclippedBoundsInRoot().left)
        tap(CHROME_SKIP)

        assertEquals(listOf(134_034L), service.player.seeks)
        assertFalse("the chrome stayed", has("Exit full screen"))
        assertTrue(
            "no note after the skip",
            compose.onAllNodesWithText("Skipped preview and recap, intro animation and sponsor", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        showChrome()
        assertFalse("Skip past the span", has(CHROME_SKIP))
    }

    /** Q34: a screen reader's chrome stays after the Skip pill's jump. */
    @Test
    fun sidewaysAScreenReadersChromeStaysAfterTheSkipPill() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        service.segments = runBlocking { RecordedSegments.lookup() }
        play()
        showChrome()

        tap(CHROME_SKIP)

        assertEquals(listOf(134_034L), service.player.seeks)
        assertTrue("the chrome went", has("Exit full screen"))
    }

    /** `live-phone-player` 13: the channel's audience, LIVE and the window; no Speed or Comments, Up next
     *  as on a video (LIVE-D24). */
    @Test
    fun sidewaysALiveStreamHasTheLiveChromeWithoutSpeed() {
        service.page = RecordedLivePage
        service.player.liveWindowMs = LIVE_WINDOW_MS
        play(card = LiveCard, atMs = LIVE_WINDOW_MS - PlayerPolicy.LIVE_OFFSET_MS)
        showChrome()

        assertTrue(shows("${LiveCard.uploaderName}  1.2K watching"))
        assertTrue(shows("LIVE"))
        assertTrue(shows("Last hour"))
        listOf("Captions", "Up next", "Forward 10 seconds").forEach { assertTrue(it, has(it)) }
        listOf("Speed", "Comments", "Go live").forEach { assertFalse(it, has(it)) }
    }

    /** `live-phone-player` 15: behind live, how far and Go live in place of the window. */
    @Test
    fun sidewaysBehindLiveGoLiveRejoinsTheEdge() {
        service.page = RecordedLivePage
        service.player.liveWindowMs = LIVE_WINDOW_MS
        play(card = LiveCard, atMs = 1_000_000)
        showChrome()

        assertTrue(compose.onAllNodesWithText(" behind live", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse(shows("Last hour"))
        tap("Go live")
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS)
        compose.waitForIdle()
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
    }

    /** Q34, Q58: sideways a screen reader's corner rises over the foot block; turned upright it rests and Full screen gives way. */
    @Test
    fun sidewaysAScreenReadersPromptRisesOverTheFootBlockAndRestsWhenTurnedUpright() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        service.segments = runBlocking { RecordedSegments.lookup() }
        play()
        val rest = compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot().bottom

        showChrome()
        assertEquals((rest - OverLandChrome).value, compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot().bottom.value, 1f)

        turn(sideways = false)
        assertTrue("the prompt went", has(PROMPT_SKIP))
        assertFalse("Full screen stayed under the corner", has("Full screen"))
    }

    @Test
    fun aKidsProfilesSidewaysChromeHasNoComments() {
        play(profile = KID)
        showChrome()

        assertTrue(has("Captions"))
        assertFalse(has("Comments"))
    }

    @Test
    fun theShownPageFollowsTheSensorAndExitHoldsPortraitWithThePageKept() {
        play()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation)

        showChrome()
        tap("Exit full screen")

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
        assertTrue(slot.expanded)
    }

    @Test
    fun closingThePlayerTurnsTheAppBackToPortrait() {
        play()

        compose.runOnUiThread { slot.close() }
        settle()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
    }

    @Test
    fun backSidewaysWhileTheVideoStartsTurnsUpright() {
        play(ready = false)
        assertTrue(shows("Starting"))

        back()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
        assertTrue(slot.expanded)
    }

    @Test
    fun backOnASidewaysStatementClosesThePlayer() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_UNAVAILABLE, 0) }
        waitFor { shows("Try again") || compose.onAllNodesWithText("can’t play here", substring = true).fetchSemanticsNodes().isNotEmpty() }

        back()

        assertNull(slot.route)
    }

    @Test
    fun backSidewaysTurnsUprightInsteadOfDocking() {
        play()

        back()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
        assertTrue(slot.expanded)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun uprightFullScreenHoldsLandscapeAndDockingLetsGoOfIt() {
        landscape = false
        play()
        showChrome()

        tap("Full screen")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation)

        compose.runOnUiThread { slot.dock() }
        settle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
        compose.runOnUiThread { slot.expand() }
        settle()
        assertEquals("the hold went with the dock", ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation)
    }

    @Test
    fun commentsOpenBesideThePictureWhichMovesIntoTheFreeSideWithTheChromeAway() {
        play()
        showChrome()

        tap("Comments")

        assertTrue(shows("Top comments"))
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        val side = compose.onNode(pane("Comments")).fetchSemanticsNode().boundsInRoot
        val besidePx = with(compose.density) { BesideWidth.toPx() }
        assertEquals(screen.right - besidePx, side.left, 2f)
        val picture = compose.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("whole, in the free side", picture.right <= side.left + 2f && picture.left >= -1f)
        assertFalse("the chrome stays away", has("Exit full screen"))
        val video = compose.onNode(hasContentDescription("Video")).fetchSemanticsNode()
        assertFalse("a tap on the picture does nothing", SemanticsActions.OnClick in video.config)
        compose.onNode(hasContentDescription("Video")).performTouchInput { click(Offset(width * 0.3f, height * 0.5f)) }
        compose.mainClock.advanceTimeBy(500)
        settle()
        assertTrue("the video plays on", service.player.isPlayWhenReady)

        compose.onNode(pane("Comments")).performTouchInput { swipeRight() }
        settle()
        assertFalse(shows("Top comments"))
        assertTrue("the chrome returns as it was: the tap beside changed nothing", has("Exit full screen"))
        val whole = compose.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.height, whole.height, 1f)
    }

    @Test
    fun upNextBesideListsTheRelatedVideosAndATapPlaysOne() {
        play()
        showChrome()

        tap("Up next")
        val next = related[1]
        assertTrue(shows(related.first().title))
        compose.onNode(hasText(next.title, substring = true) and hasClickAction()).performClick()
        settle()

        assertEquals(next.id.value, service.player.requested.last())
    }

    @Test
    fun playbackBesideOpensItsStagesInPlaceWithBackAndCloseTakesThemAll() {
        play()
        showChrome()

        tap("Playback settings")
        assertTrue(shows("Playing now"))
        assertFalse("the first stage has no back", has("Back"))
        tap("Quality")
        assertTrue(shows(QUALITY_SHEET_SUB))
        assertTrue(has("Back"))

        tap("Back")
        assertTrue(shows("Playing now"))
        tap("Quality")
        back()
        assertTrue("Back steps back a stage", shows("Playing now"))

        tap("Close")
        assertFalse(shows("Playing now"))
        assertEquals("the sheets' Back didn't turn the page", ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation)
    }

    @Test
    fun theSpeedPillOpensSpeedAloneAndAPickHoldsItForThisVideo() {
        play()
        showChrome()

        tap("Speed")
        assertTrue(shows("Pitch stays natural at every speed."))
        assertFalse(has("Back"))
        tap("1.5×")

        assertEquals(1.5f, service.player.speed)
        assertFalse(shows("Pitch stays natural at every speed."))
    }

    @Test
    fun videoDetailsFromPlaybackOpenBeside() {
        play()
        showChrome()
        tap("Playback settings")

        compose.onNode(button("Video details")).performScrollTo().performClick()
        settle()

        compose.onNode(pane("Video details")).assert(hasAnyDescendant(hasText("Video details")))
        assertFalse(shows("Playing now"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aTurnKeepsThePictureAndItsChromeAsTheyWere() {
        landscape = false
        play()
        showChrome()
        val surface = compose.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().id

        turn(sideways = true)

        assertTrue("the chrome stays up", has("Exit full screen"))
        assertEquals("the same surface", surface, compose.onNodeWithTag(PICTURE_TAG).fetchSemanticsNode().id)
        turn(sideways = false)
        assertTrue(has("Full screen"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun uprightAnOpenPanelLeavesThePictureTappable() {
        landscape = false
        play()

        tap("Comments")

        assertTrue(shows("Top comments"))
        val video = compose.onNode(hasContentDescription("Video")).fetchSemanticsNode()
        assertTrue(SemanticsActions.OnClick in video.config)
        showChrome()
        assertTrue(has("Full screen"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aPanelOrSheetOpenUprightCarriesOverWhenTurnedAndUpNextClosesUpright() {
        landscape = false
        play()
        tap("Comments")
        assertTrue("upright", shows("Top comments"))
        turn(sideways = true)
        assertTrue(shows("Top comments"))
        val besidePx = with(compose.density) { BesideWidth.toPx() }
        val screen = compose.onNode(isRoot()).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.right - besidePx, compose.onNode(pane("Comments")).fetchSemanticsNode().boundsInRoot.left, 2f)
        tap("Close")

        showChrome()
        tap("Up next")
        turn(sideways = false)
        assertFalse("Up next's rows are the page's upright", compose.onAllNodes(pane("Up next")).fetchSemanticsNodes().isNotEmpty())

        showChrome()
        tap("Playback settings")
        turn(sideways = true)
        assertEquals("one Playback, beside", 1, compose.onAllNodesWithText("Playing now").fetchSemanticsNodes().size)
        compose.onNode(pane("Playback")).assert(hasAnyDescendant(hasText("Playing now")))
    }

    @Test
    fun aSingleVideosEndSidewaysOffersUpNextAndNothingStartsByItself() {
        play()
        val asked = service.player.requested.size

        compose.runOnUiThread { service.player.end() }
        settle()

        assertTrue(shows(related.first().title))
        assertEquals(asked, service.player.requested.size)
        tap("Play")
        assertEquals(related.first().id.value, service.player.requested.last())
    }
}
