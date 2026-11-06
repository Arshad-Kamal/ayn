package com.arshad.ayn.ui.mobile.player

import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.CaptionCues
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.RetryLine
import com.arshad.ayn.feature.player.SkipNote
import com.arshad.ayn.feature.player.SkipPrompt
import com.arshad.ayn.feature.player.SkipSpan
import com.arshad.ayn.feature.player.selected
import com.arshad.ayn.feature.player.vp9Ladder
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The phone player's sheets and the picture's notes (`m6-player` 13–20) through the shell's real
 * wiring, a real `MediaController` on an in-process session dressed with the recorded Enigma page
 * (its two English tracks), the recorded captions ([RecordedCaptions]: the manual track's second cue
 * runs 18.64–21.88 s) and SponsorBlock's recorded answer ([RecordedSegments]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerSheetsTest {
    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        const val CUE_MS = 19_000L
        const val CUE = "♪ We're no strangers to love ♪"
        const val FAILED = "English captions didn’t load · the video plays on"
        const val PROMPT_SKIP = "Skip to 2:14"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private var captions: CaptionSource = RecordedCaptions.source()
    private var captionRequests = 0
    private val stores by lazy {
        TestStores(
            session = { service.session.token },
            captions = { track ->
                captionRequests++
                captions.cues(track)
            },
        )
    }

    private lateinit var slot: PlayerSlot

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    private fun captionsLanguage(language: String?) {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(captionsLanguage = language))
    }

    /** Opens the Enigma video and lets the stream land [atMs] in. */
    private fun play(atMs: Long = CUE_MS) {
        val models = stores.models()
        compose.setContent {
            AynTheme {
                slot = rememberPlayerSlot()
                ProfileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), models, slot = slot)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(EnigmaCard)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        settle()
    }

    private fun playWithSegments(atMs: Long) {
        service.segments = runBlocking { RecordedSegments.lookup() }
        play(atMs)
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

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Lets time run (a sheet sliding away, a fetch, a tick) until [text] shows, or is gone. */
    private fun until(text: String, shown: Boolean = true) {
        repeat(10) {
            if (shows(text, substring = true) == shown) return
            settle()
        }
        assertEquals("\"$text\" showing", shown, shows(text, substring = true))
    }

    /** A sheet row by its label (the chrome's round controls carry theirs as a description). */
    private fun row(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun hasLabel(label: String): Boolean =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private fun tapPicture() {
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        settle()
    }

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        settle()
    }

    /** System Back on the sheet's own window. */
    private fun sheetBack() {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun openPlayback() {
        tapPicture()
        tap("Playback settings")
    }

    @Test
    fun ccOpensTheCaptionsSheetAndAPickShowsItsCueAndClosesIt() {
        play()
        assertFalse("no cue while captions are Off", shows(CUE))

        tapPicture()
        tap("Captions")
        compose.onNodeWithText("Just for this video; your default (Off) is in Settings\u00A0›\u00A0Playback.").assertExists()
        compose.onNode(button("English (auto-generated)")).assert(hasText("Made by YouTube’s speech recognition"))
        tap("English")

        until("Just for this video", shown = false)
        until(CUE)
        compose.onNodeWithContentDescription("Captions").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    }

    @Test
    fun thePlaybackSheetSaysWhatPlaysAndWhatSponsorBlockFound() {
        service.player.tracks = selected(vp9Ladder)
        playWithSegments(atMs = 5_000)
        openPlayback()

        compose.onNodeWithText("Playing now").assertExists()
        compose.onNode(button("Quality")).assert(hasText("Auto · now 2160p"))
        compose.onNode(button("Speed")).assert(hasText("Normal"))
        compose.onNode(row("Captions")).assert(hasText("Off"))
        compose.onNodeWithText("Preview and recap 0:00 · Intro animation 0:51 · Sponsor 0:56 · Interaction reminder 11:21 · Endcards and credits 11:27")
            .assertExists()
    }

    @Test
    fun aSpeedPickFromPlaybackSetsThisVideosSpeedAndClosesEverySheet() {
        play()
        openPlayback()
        tap("Speed")
        compose.onNodeWithText("Pitch stays natural at every speed.").assertExists()

        tap("1.5×")

        assertEquals(1.5f, service.player.speed)
        until("Pitch stays natural at every speed.", shown = false)
        until("Playing now", shown = false)
    }

    @Test
    fun backFromQualityReturnsToPlaybackAndAPickHoldsTheHeight() {
        service.player.tracks = selected(vp9Ladder)
        play()
        openPlayback()
        tap("Quality")
        compose.onNode(button("Auto")).assert(hasText("Now 2160p · adapts to the connection"))
        compose.onNode(button("1080p")).assert(hasText("Full HD"))

        sheetBack()
        until("Playing now")
        until("Now 2160p", shown = false)

        tap("Quality")
        tap("1080p")
        waitFor { service.player.trackSelection.overrides.isNotEmpty() }
        assertEquals(listOf(2), service.player.trackSelection.overrides.values.single().trackIndices)
        until("Playing now", shown = false)
    }

    @Test
    fun aVideoWithoutTracksDimsCcAndSaysSoInPlayback() {
        service.page = RecordedEnigmaPage.copy(captionTracks = emptyList())
        play()
        tapPicture()

        compose.onNodeWithContentDescription("Captions").assertIsNotEnabled()
        tap("Playback settings")
        compose.onNodeWithText("None for this video").assertExists()
    }

    @Test
    fun theProfilesLanguageShowsItsCueNearThePicturesFootAndItRisesOverTheChrome() {
        captionsLanguage("en")
        play()
        until(CUE)
        val picture = compose.onNodeWithContentDescription("Video").getUnclippedBoundsInRoot()
        // The text's bounds sit inside its well's 2 dp foot padding.
        assertEquals((picture.bottom - 12.dp - 2.dp).value, compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom.value, 1f)

        tapPicture()

        assertEquals((picture.bottom - 44.dp - 2.dp).value, compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom.value, 1f)
    }

    @Test
    fun aPromptedSegmentOffersSkipToItsEndAndATapSkips() {
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText("Preview and recap · Intro animation · Sponsor").assertExists()

        tap(PROMPT_SKIP)

        assertEquals(listOf(134_034L), service.player.seeks)
        assertFalse("the prompt stayed", shows(PROMPT_SKIP))
        assertTrue("no note after the tap", shows("Skipped preview and recap, intro animation and sponsor", substring = true))
        tap("Undo")
        assertEquals(0L, service.player.seeks.last())
    }

    /** PAF-D2: playback under the chrome doesn't use up the prompt's 8 s. */
    @Test
    fun thePromptsTimeHoldsWhileTheChromeCoversIt() {
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText(PROMPT_SKIP).assertExists()
        tapPicture()
        compose.runOnUiThread { service.player.ready(75_000) }
        settle()
        assertFalse("the prompt showed under the chrome", shows(PROMPT_SKIP))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 500)
        compose.waitForIdle()

        until(PROMPT_SKIP)
        compose.runOnUiThread { service.player.ready(120_000) }
        until(PROMPT_SKIP, shown = false)
    }

    @Test
    fun thePromptsTimeHoldsWhileTheAppIsAway() {
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText(PROMPT_SKIP).assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        compose.runOnUiThread { service.player.ready(75_000) }
        settle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        until(PROMPT_SKIP)
        compose.runOnUiThread { service.player.ready(120_000) }
        until(PROMPT_SKIP, shown = false)
    }

    @Test
    fun thePromptsTimeHoldsWhileThePageIsDocked() {
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText(PROMPT_SKIP).assertExists()
        compose.runOnUiThread { slot.dock() }
        settle()

        compose.runOnUiThread { service.player.ready(75_000) }
        settle()
        compose.runOnUiThread { slot.expand() }

        until(PROMPT_SKIP)
        compose.runOnUiThread { service.player.ready(120_000) }
        until(PROMPT_SKIP, shown = false)
    }

    /** Q34, Q58: a screen reader's chrome stays up, so the corner shows over it, upright at rest where
     *  the foot row's Full screen gives way, clear of the transport (native graphics: real text widths). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aScreenReadersChromeLeavesThePromptAtRestWhereTheFootRowGivesWay() = promptOverAScreenReadersChrome()

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANarrowPhoneAScreenReadersPromptStaysClearOfTheTransport() = promptOverAScreenReadersChrome()

    private fun promptOverAScreenReadersChrome() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        playWithSegments(atMs = 60_000)
        val rest = compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot()

        tapPicture()

        compose.onNodeWithContentDescription("Video").assert(
            SemanticsMatcher("offers to hide the controls") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Hide the controls" },
        )
        val prompt = compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot()
        assertEquals(rest.bottom.value, prompt.bottom.value, 1f)
        assertFalse("Full screen stayed under the corner", hasLabel("Full screen"))
        for (label in listOf("Back 10 seconds", "Forward 10 seconds")) {
            val control = compose.onNode(button(label)).getUnclippedBoundsInRoot()
            assertFalse("the prompt $prompt overlaps $label at $control", prompt.overlaps(control))
        }
    }

    /** Q58: on a narrow phone a screen reader's note at rest over the chrome stays clear of the transport,
     *  and its action takes a touch where it covers the bar's strip (native graphics: real text widths). */
    @Test
    @Config(qualifiers = "w360dp-h780dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANarrowPhoneAScreenReadersNoteStaysClearOfTheTransportAndTakesItsTouches() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        captions = CaptionSource { CaptionLookup.Failed }
        captionsLanguage("en")
        play()
        tapPicture()
        until(FAILED)
        val action = compose.onNode(button("Try again")).getUnclippedBoundsInRoot()
        for (label in listOf("Back 10 seconds", "Forward 10 seconds")) {
            val control = compose.onNode(button(label)).getUnclippedBoundsInRoot()
            assertFalse("Try again $action overlaps $label at $control", action.overlaps(control))
        }
        val asked = captionRequests
        val seeks = service.player.seeks.size

        compose.onNode(button("Try again")).performTouchInput { click(Offset(centerX, height - 2.dp.toPx())) }
        settle()

        assertEquals("the bar took the touch", seeks, service.player.seeks.size)
        assertEquals(asked + 1, captionRequests)
    }

    /** Q58: the foot row gives way to a screen reader's note and comes back once the note goes. */
    @Test
    fun overAScreenReadersChromeTheFootRowComesBackWhenTheNoteGoes() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        captions = CaptionSource { CaptionLookup.Failed }
        captionsLanguage("en")
        play()
        tapPicture()
        until(FAILED)
        assertFalse("Full screen stayed under the note", hasLabel("Full screen"))
        assertFalse("the time stayed under the note", shows(" / ", substring = true))

        compose.mainClock.advanceTimeBy(5_500)
        until(FAILED, shown = false)

        assertTrue("Full screen didn't come back", hasLabel("Full screen"))
        assertTrue("the time didn't come back", shows(" / ", substring = true))
    }

    /** Q36: the prompt's 8 s scale by the accessibility timeout, as the notes' do. */
    @Test
    fun thePromptsTimeScalesWithTheAccessibilityTimeout() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setInteractiveUiTimeout(20_000)
        playWithSegments(atMs = 60_000)

        compose.runOnUiThread { service.player.ready(75_000) }
        settle()
        assertTrue("the prompt went at 8 s", shows(PROMPT_SKIP))

        compose.runOnUiThread { service.player.ready(81_000) }
        until(PROMPT_SKIP, shown = false)
    }

    @Test
    fun theChromeTakesTheCornerFromThePrompt() {
        playWithSegments(atMs = 60_000)
        compose.onNodeWithText(PROMPT_SKIP).assertExists()

        tapPicture()

        until(PROMPT_SKIP, shown = false)
        assertTrue("Full screen gave way to a hidden corner", hasLabel("Full screen"))
    }

    /** M6-D5: away (the screen off) nothing composes, yet an automatic skip still jumps. */
    @Test
    fun anAutomaticSkipStillJumpsWhileTheAppIsAway() {
        playWithSegments(atMs = 0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        compose.runOnUiThread { service.player.ready(682_000) }
        waitFor { service.player.seeks.isNotEmpty() }
        assertEquals(listOf(687_665L), service.player.seeks)
    }

    /** PAF-D12 (`m6-player` 18b): the chrome's Skip inside a prompted span. */
    @Test
    fun insideAPromptedSpanTheChromesSkipJumpsToItsEndWithTheNote() {
        playWithSegments(atMs = 60_000)
        tapPicture()

        tap("Skip preview and recap")

        assertEquals(listOf(134_034L), service.player.seeks)
        assertTrue("no note after the skip", shows("Skipped preview and recap, intro animation and sponsor", substring = true))
    }

    @Test
    fun anAutomaticSkipSaysSoAndUndoGoesBackToItsStart() {
        playWithSegments(atMs = 682_000)
        assertEquals(listOf(687_665L), service.player.seeks)
        compose.onNodeWithText("Skipped interaction reminder · 6 seconds").assertExists()

        tap("Undo")

        assertEquals(681_274L, service.player.seeks.last())
        until("Skipped interaction reminder", shown = false)
    }

    @Test
    fun theSkipNoteGoesAfterFiveSeconds() {
        playWithSegments(atMs = 682_000)
        compose.onNodeWithText("Skipped interaction reminder · 6 seconds").assertExists()

        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        compose.onNodeWithText("Skipped interaction reminder · 6 seconds").assertExists()

        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertFalse("the note outlived its time", shows("Skipped interaction reminder", substring = true))
    }

    @Test
    fun captionsThatDidntLoadSaySoAndTryAgainAsksOnceMore() {
        captions = CaptionSource { CaptionLookup.Failed }
        captionsLanguage("en")
        play()
        until(FAILED)
        tapPicture()
        compose.onNodeWithContentDescription("Captions").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS)
        until(FAILED)
        val asked = captionRequests

        captions = RecordedCaptions.source()
        tap("Try again")

        until(CUE)
        assertEquals(asked + 1, captionRequests)
        assertFalse("the note stayed", shows(FAILED))
    }

    @Test
    fun aRetryUnderWaySaysHowFarItHasGot() {
        play()
        compose.runOnUiThread {
            service.session.setSessionExtras(
                Bundle().apply {
                    putString(PlaybackContract.EXTRA_RETRY_KIND, PlaybackContract.RETRY_RECOVERABLE)
                    putInt(PlaybackContract.EXTRA_ATTEMPT, 2)
                    putInt(PlaybackContract.EXTRA_MAX, 6)
                },
            )
        }
        until("Connection dropped · retrying 2 of 6")
    }

    /** Q34, Q58: over a screen reader's chrome the cue stands above the prompt as without it (the foot
     *  row gives way, so neither rises), and holds there when the chrome hides. */
    @Test
    fun overAScreenReadersChromeTheCueStandsAboveThePromptAndNeitherMoves() {
        shadowOf(compose.activity.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        captionsLanguage("en")
        playWithSegments(atMs = CUE_MS)
        until(CUE)
        until(PROMPT_SKIP)
        val cueAtRest = compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom

        tapPicture()
        assertTrue(
            "the cue stands above the prompt",
            compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom <= compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot().top,
        )
        assertEquals(cueAtRest.value, compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom.value, 1f)
        // The picture's centre holds Pause while the chrome shows: hide it as a screen reader does.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Video").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(100)
        assertFalse("Full screen came back over the corner as the chrome went", hasLabel("Full screen"))
        compose.mainClock.autoAdvance = true
        settle()

        assertEquals(cueAtRest.value, compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom.value, 1f)
    }

    @Test
    fun theCueRisesOverTheSkipPrompt() {
        captionsLanguage("en")
        playWithSegments(atMs = CUE_MS)
        until(CUE)
        until(PROMPT_SKIP)

        val cueBottom = compose.onNodeWithText(CUE).getUnclippedBoundsInRoot().bottom
        val promptTop = compose.onNode(button(PROMPT_SKIP)).getUnclippedBoundsInRoot().top
        // The prompt's button sits at the holder's top; its touch target may stand a little proud of it.
        val slack = 12.dp
        assertTrue("the cue $cueBottom overlaps the prompt at $promptTop", cueBottom <= promptTop - CuesOverCorner + 1.dp)
        assertTrue("the cue $cueBottom floats far over the prompt at $promptTop", cueBottom >= promptTop - CuesOverCorner - slack)
    }

    @Test
    fun theResumedNoteKeepsTheCornerThenThePromptTakesIt() {
        stores.watch.resumePointsMs[EnigmaCard.id] = 60_000L
        playWithSegments(atMs = 60_000)
        until("Resumed at 1:00")
        assertFalse("the prompt took the resumed note's corner", shows(PROMPT_SKIP))

        compose.mainClock.advanceTimeBy(5_500)
        until(PROMPT_SKIP)
        assertFalse("the resumed note stayed", shows("Resumed at 1:00"))
    }

    @Test
    fun theCaptionsNoteGoesAfterFiveSecondsAndStaysGoneThroughATryAgainOfTheVideo() {
        captions = CaptionSource { CaptionLookup.Failed }
        captionsLanguage("en")
        play()
        until(FAILED)

        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        assertTrue("the note went early", shows(FAILED))
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertFalse("the note outlived its time", shows(FAILED))

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = CUE_MS) }
        until("Try again from 0:19")
        tap("Try again from 0:19")
        waitFor { service.player.requested.size == 2 && service.player.isPlayWhenReady }
        // The service clears its stop once the new request lands.
        compose.runOnUiThread {
            service.session.setSessionExtras(Bundle.EMPTY)
            service.player.ready(CUE_MS)
        }
        settle()
        // The stage is back (its picture node), so a note could show.
        compose.onNodeWithContentDescription("Video").assertExists()
        assertFalse("the gone note came back", shows(FAILED))
    }

    @Test
    fun dockingThePageClosesItsSheets() {
        play()
        openPlayback()
        compose.onNodeWithText("Playing now").assertExists()

        compose.runOnUiThread { slot.dock() }

        until("Playing now", shown = false)
    }

    @Test
    fun aCueShowsAtMostTwoWells() {
        assertEquals(listOf("one"), cueWells("one"))
        assertEquals(listOf("one", "two"), cueWells("one\ntwo\n"))
        assertEquals(listOf("one", "two three"), cueWells("one\ntwo\nthree"))
    }

    @Test
    fun theCornerGoesToTheFirstHolderInOrderAndToNobodyWhileHidden() {
        val retry = RetryLine("Connection dropped · retrying", "1 of 6")
        val span = SkipSpan(10_000, 20_000, listOf(SegmentCategory.Sponsor))
        val note = SkipNote(span, 1)
        val prompt = SkipPrompt(span, 10_000, 18_000)
        val failed = CaptionCues.Failed(1)
        // Every holder present, then each dropped in turn: the next in line takes the corner.
        val holders = listOf("retry", "resumed", "note", "prompt", "failed")
        fun without(vararg gone: String, hidden: Boolean = false, language: String? = "English"): Corner? = cornerOf(
            hidden,
            retry.takeIf { "retry" !in gone },
            1_000L.takeIf { "resumed" !in gone },
            note.takeIf { "note" !in gone },
            prompt.takeIf { "prompt" !in gone },
            failed.takeIf { "failed" !in gone },
            language,
        )
        assertEquals(Corner.Retrying(retry), without())
        assertEquals(Corner.Resumed(1_000L), without("retry"))
        assertEquals(Corner.Skipped(note), without("retry", "resumed"))
        assertEquals(Corner.Prompt(prompt), without("retry", "resumed", "note"))
        assertEquals(Corner.CaptionsFailed("English", failed), without("retry", "resumed", "note", "prompt"))
        assertEquals(null, without(*holders.toTypedArray()))
        assertEquals(null, without(hidden = true))
        assertEquals(null, without("retry", "resumed", "note", "prompt", language = null))
    }

    @Test
    fun thePromptIsInViewOnlyWhereNothingAboveItHoldsTheCorner() {
        val span = SkipSpan(10_000, 20_000, listOf(SegmentCategory.Sponsor))
        val prompt = Corner.Prompt(SkipPrompt(span, 10_000, 18_000))

        assertTrue(promptInView(hidden = false, corner = prompt))
        assertTrue(promptInView(hidden = false, corner = null))
        assertTrue(promptInView(hidden = false, corner = Corner.CaptionsFailed("English", CaptionCues.Failed(1))))
        assertFalse(promptInView(hidden = true, corner = null))
        assertFalse(promptInView(hidden = false, corner = Corner.Retrying(RetryLine("Connection dropped · retrying", "1 of 6"))))
        assertFalse(promptInView(hidden = false, corner = Corner.Resumed(1_000L)))
        assertTrue("the note holds the skip slot", promptInView(hidden = false, corner = Corner.Skipped(SkipNote(span, 1))))
    }

    @Test
    fun aSkipNoteHeldBackByTheChromeGetsItsFiveSecondsOnceTheChromeGoes() {
        playWithSegments(atMs = 682_000)
        compose.onNodeWithText("Skipped interaction reminder · 6 seconds").assertExists()
        tapPicture()
        assertFalse("the note showed under the chrome", shows("Skipped interaction reminder", substring = true))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 2_000)
        compose.waitForIdle()
        assertTrue("the note ran out while hidden", shows("Skipped interaction reminder", substring = true))

        compose.mainClock.advanceTimeBy(5_500)
        compose.waitForIdle()
        assertFalse("the note outlived its time", shows("Skipped interaction reminder", substring = true))
    }
}
