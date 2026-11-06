package com.arshad.ayn.ui.mobile.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.ScriptedPlayer
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
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
 * The phone's Shorts player (`m6-shorts` 1–13) through the shell's real wiring
 * ([ProfileShell]) on the recorded Shorts tab: a real `MediaController` on an in-process session
 * ([FakePlaybackService]); the run is handed over as a page hands it ([ShortsHandOver]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShortsPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private var session = true
    private val stores by lazy { TestStores(session = { if (session) service.session.token else NoPlayerSession(it) }, playback = { service.end() }) }
    private lateinit var slot: PlayerSlot
    private val handOver = ShortsHandOver()

    private val shorts: List<VideoItem> by lazy {
        recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }.shorts.take(3)
    }

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    /** Opens [run]`[index]`; the stream lands unless [ready] is off. */
    private fun play(index: Int = 0, ready: Boolean = true, run: ShortsList = ShortsList(shorts)) {
        val models = stores.models()
        compose.setContent {
            AynTheme {
                CompositionLocalProvider(LocalDeviceSettings provides DeviceSettings(backgroundAudio = true)) {
                    slot = rememberPlayerSlot()
                    ProfileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), models, slot = slot, handedShorts = handOver)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { handOver.play(run.shorts.value[index], run, slot::open) }
        if (!session) return
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        if (ready) {
            compose.runOnUiThread { service.player.ready() }
            compose.waitForIdle()
        }
    }

    /** Session and controller hop through the paused main looper: drain it until [what] holds. */
    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun shows(text: String): Boolean = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** A mark reading exactly [text] ("5 seconds" isn't "15 seconds"). */
    private fun marks(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun swipe(up: Boolean) {
        compose.onNodeWithContentDescription("Short").performTouchInput { if (up) swipeUp() else swipeDown() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The Short [atMs] in and paused: a playing one's position runs on in real time on the
     *  controller until the pause lands, which would blur a step's target. */
    private fun pausedAt(atMs: Long = 20_000) {
        play()
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Short").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { !service.player.isPlayWhenReady }
        compose.mainClock.autoAdvance = false
    }

    /** A double-tap [across] the Short's width, halfway down. */
    private fun doubleTap(across: Float) {
        compose.onNodeWithContentDescription("Short").performTouchInput { doubleClick(Offset(width * across, centerY)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** Past the gathering, so the steps' one seek lands. */
    private fun gathered() {
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)
        waitFor { service.player.seeks.isNotEmpty() }
    }

    @Test
    fun aShortOpensFullScreenWithItsFootAndNoWatchPage() {
        play()

        assertEquals(listOf(shorts[0].id.value), service.player.requested)
        compose.onNodeWithText(shorts[0].title).assertExists()
        compose.onNode(button("Save")).assertExists()
        compose.onNode(button("Comments")).assertExists()
        compose.onNode(button("Back")).assertExists()
        compose.onNodeWithText("Up next").assertDoesNotExist()
        assertTrue(slot.expanded)
    }

    @Test
    fun whileItStartsOnlySaveShows() {
        play(ready = false)

        compose.onNodeWithText("Starting").assertExists()
        compose.onNode(button("Save")).assertExists()
        compose.onNode(button("Comments")).assertDoesNotExist()
    }

    @Test
    fun aTapPausesAndAnotherPlays() {
        play()

        compose.onNodeWithContentDescription("Short").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        waitFor { !service.player.isPlayWhenReady }

        compose.onNodeWithContentDescription("Short").performSemanticsAction(SemanticsActions.OnClick)
        waitFor { service.player.isPlayWhenReady }
    }

    /** SSC-D4: a double-tap on the left third steps 5 s back with its mark; the steps gather into one seek. */
    @Test
    fun aDoubleTapOnTheLeftStepsBackWithItsMark() {
        pausedAt()

        doubleTap(across = 0.15f)
        assertTrue(marks("5 seconds"))
        assertTrue("nothing sought yet", service.player.seeks.isEmpty())

        gathered()
        assertEquals(listOf(15_000L), service.player.seeks)
        assertFalse("a step doesn't play", service.player.isPlayWhenReady)
        compose.mainClock.advanceTimeBy(1_000)
        assertFalse("the mark goes", marks("5 seconds"))
    }

    /** SSC-D4: taps on the lit side add steps to the same mark at once, and none of them pauses or plays. */
    @Test
    fun tapsOnTheLitSideAddToTheSameMark() {
        pausedAt()

        doubleTap(across = 0.85f)
        compose.onNodeWithContentDescription("Short").performTouchInput { click(Offset(width * 0.85f, centerY)) }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(marks("10 seconds"))

        gathered()
        assertEquals(listOf(30_000L), service.player.seeks)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(service.player.isPlayWhenReady)
    }

    /** SSC-D1: every Short steps 5 s, a short one too. */
    @Test
    fun aShortUnderTwentySecondsStepsFiveSecondsToo() {
        service.player.videoDurationMs = 16_000
        pausedAt(atMs = 10_000)

        doubleTap(across = 0.15f)
        assertTrue(marks("5 seconds"))

        gathered()
        assertEquals(listOf(5_000L), service.player.seeks)
    }

    /** The middle third keeps the tap alone: a double-tap there pauses once and steps nowhere. */
    @Test
    fun aDoubleTapInTheMiddleOnlyPauses() {
        play()
        compose.mainClock.autoAdvance = false

        doubleTap(across = 0.5f)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)
        compose.waitForIdle()

        waitFor { !service.player.isPlayWhenReady }
        assertTrue(service.player.seeks.isEmpty())
        assertFalse(shows("second"))
    }

    /** Taps on two sides within the double-tap window are one tap, not a step. */
    @Test
    fun aDoubleTapAcrossTwoThirdsOnlyPauses() {
        play()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithContentDescription("Short").performTouchInput {
            click(Offset(width * 0.15f, centerY))
            advanceEventTime(100)
            click(Offset(width * 0.85f, centerY))
        }
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 50)
        compose.waitForIdle()

        waitFor { !service.player.isPlayWhenReady }
        assertTrue(service.player.seeks.isEmpty())
    }

    /** A double-tap on the other side while a mark is lit starts that side's own mark. */
    @Test
    fun aDoubleTapOnTheOtherSideStartsItsOwnMark() {
        pausedAt()

        doubleTap(across = 0.85f)
        doubleTap(across = 0.15f)
        assertTrue(marks("5 seconds"))
        assertFalse(marks("10 seconds"))

        gathered()
        assertEquals(listOf(20_000L), service.player.seeks)
    }

    /** SSC-D4's cost: a single tap on a side still pauses, once the double-tap window has passed. */
    @Test
    fun aSingleTapOnASideStillPauses() {
        play()

        compose.onNodeWithContentDescription("Short").performTouchInput { click(Offset(width * 0.15f, centerY)) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        waitFor { !service.player.isPlayWhenReady }
        assertTrue(service.player.seeks.isEmpty())
    }

    /** SSC-D3: a sideways drag anywhere on the Short holds it with the time over the line, moving from
     *  where it was (half the width: half its length on); letting go seeks there and plays. */
    @Test
    fun draggingAcrossTheShortHoldsItAndLettingGoPlaysFromThere() {
        pausedAt(atMs = 20_000)
        compose.onNodeWithContentDescription("Short").performTouchInput {
            down(Offset(width * 0.2f, height * 0.3f))
            moveTo(Offset(width * 0.4f, height * 0.3f))
            moveTo(Offset(width * 0.7f, height * 0.3f))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)

        assertTrue(shows("24:10 / 47:41"))
        assertTrue("nothing sought while held", service.player.seeks.isEmpty())

        compose.onNodeWithContentDescription("Short").performTouchInput { up() }
        waitFor { service.player.isPlayWhenReady }
        assertEquals(20_000.0 + ScriptedPlayer.DURATION_MS / 2, service.player.seeks.single().toDouble(), 100.0)
    }

    /** A drag from a playing Short pauses it while held. */
    @Test
    fun aDragPausesAPlayingShortWhileHeld() {
        play()

        compose.onNodeWithContentDescription("Short").performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.6f, centerY))
        }

        waitFor { !service.player.isPlayWhenReady }
        assertTrue(shows("/ 47:41"))
    }

    /** A stop mid-drag ends the hold without a seek. */
    @Test
    fun aStopMidDragEndsTheHold() {
        play()
        compose.onNodeWithContentDescription("Short").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
        }
        compose.waitForIdle()
        assertTrue(shows("/ 47:41"))

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0) }
        waitFor { shows("This Short couldn’t play") }
        compose.onNodeWithContentDescription("Short").performTouchInput { up() }

        // Tried again on the same turn: the Short plays without the old hold.
        compose.onNode(button("Try again")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread {
            service.session.setSessionExtras(Bundle.EMPTY)
            service.player.ready()
        }
        waitFor { shows(shorts[0].title) && !shows("This Short couldn’t play") }
        compose.onNode(button("Save")).assertExists()
        assertFalse(shows("/ 47:41"))
        assertTrue(service.player.seeks.isEmpty())
    }

    /** A drag while a double-tap step still gathers moves from the step's target. */
    @Test
    fun aDragDuringAPendingStepMovesFromItsTarget() {
        pausedAt(atMs = 20_000)
        doubleTap(across = 0.85f)

        dragAcross(from = 0.2f, to = 0.7f)

        waitFor { service.player.isPlayWhenReady }
        assertEquals(1, service.player.seeks.size)
        assertEquals(25_000.0 + ScriptedPlayer.DURATION_MS / 2, service.player.seeks.single().toDouble(), 100.0)
    }

    /** A drag back past the start stops at 0:00. */
    @Test
    fun aDragBackPastTheStartSeeksZero() {
        pausedAt(atMs = 20_000)

        dragAcross(from = 0.8f, to = 0.3f)

        waitFor { service.player.isPlayWhenReady }
        assertEquals(listOf(0L), service.player.seeks)
    }

    /** A swipe up that drifts sideways still moves the run: its vertical slop passes first. */
    @Test
    fun aSwipeUpDriftingSidewaysStillMovesTheRun() {
        play()

        compose.onNodeWithContentDescription("Short").performTouchInput {
            swipe(start = Offset(centerX, height * 0.8f), end = Offset(centerX + width * 0.1f, height * 0.2f))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        waitFor { service.player.requested.size == 2 }
        assertTrue(service.player.seeks.isEmpty())
    }

    /** SSC-D3 "anywhere": a drag starting on Save scrubs instead of opening Save to. */
    @Test
    fun aDragStartingOnSaveScrubs() {
        pausedAt(atMs = 20_000)

        compose.onNode(button("Save")).performTouchInput {
            down(center)
            moveTo(Offset(center.x - 100f, center.y))
            moveTo(Offset(center.x - 200f, center.y))
            up()
        }
        compose.waitForIdle()

        waitFor { service.player.isPlayWhenReady }
        assertEquals(1, service.player.seeks.size)
        assertFalse(shows("Watch later"))
    }

    /** A sideways drag on the Short, halfway down, from [from] to [to] of its width, then let go. */
    private fun dragAcross(from: Float, to: Float) {
        compose.onNodeWithContentDescription("Short").performTouchInput {
            down(Offset(width * from, height * 0.3f))
            moveTo(Offset(width * (from + to) / 2, height * 0.3f))
            moveTo(Offset(width * to, height * 0.3f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun aScreenReaderSeeksWithTheLine() {
        play()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        waitFor { service.player.seeks.isNotEmpty() }
        assertEquals(listOf(1_430_500L), service.player.seeks)
    }

    @Test
    fun aSwipeUpMovesToTheNextShortAndDownBack() {
        play()

        swipe(up = true)
        waitFor { service.player.requested.size == 2 }
        assertEquals(shorts[1].id.value, service.player.requested.last())
        compose.onNodeWithText(shorts[1].title).assertExists()

        swipe(up = false)
        waitFor { service.player.requested.size == 3 }
        assertEquals(shorts[0].id.value, service.player.requested.last())
    }

    @Test
    fun aScreenReaderMovesThroughTheRunByItsActions() {
        play()
        val actions = compose.onNodeWithContentDescription("Short").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Next Short"), actions.map { it.label }) // the first has none before it

        compose.runOnUiThread { actions.single().action() }
        waitFor { service.player.requested.size == 2 }
        assertEquals(shorts[1].id.value, service.player.requested.last())
    }

    @Test
    fun swipingPastTheRunsEndSpringsBackAndSaysSo() {
        play(index = shorts.lastIndex)

        swipe(up = true)

        assertEquals(1, service.player.requested.size)
        assertTrue(shows("That’s the last Short here"))
        swipe(up = false) // the first direction still moves
        waitFor { service.player.requested.size == 2 }
    }

    @Test
    fun aStopThatCanBeTriedAgainSaysSoAndTryAgainAsksAgain() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0) }
        waitFor { shows("This Short couldn’t play") }

        compose.onNode(button("Try again")).assertExists()
        assertTrue(shows("Swipe up or down for another Short."))
        assertTrue(shows("Swipe for another Short"))
        compose.onNode(button("Save")).assertDoesNotExist()

        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.size == 2 }
        assertEquals(shorts[0].id.value, service.player.requested.last())
    }

    @Test
    fun swipingOnFromAStopLeavesItsStatementBehind() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0) }
        waitFor { shows("This Short couldn’t play") }

        swipe(up = true) // the fake never clears its extras: the old stop is still there for the next Short
        waitFor { service.player.requested.last() == shorts[1].id.value }

        assertFalse(shows("This Short couldn’t play"))
        compose.onNodeWithText(shorts[1].title).assertExists()

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0) } // a stop of its own still shows
        waitFor { shows("This Short couldn’t play") }
    }

    @Test
    fun aLoneShortsStopNamesNoSwipe() {
        play(run = ShortsList(shorts.take(1)))
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, 0) }
        waitFor { shows("This Short couldn’t play") }

        assertFalse(shows("Swipe"))
    }

    @Test
    fun aSwipeAtTheLoadedEndPagesOnInsteadOfSayingItsTheLast() {
        val paged = ShortsList(shorts.take(2), PageToken(id = "page-2")) { ItemsPage(items = emptyList(), next = null, shorts = listOf(shorts[2])) }
        play(index = 1, run = paged)

        swipe(up = true)

        waitFor { service.player.requested.last() == shorts[2].id.value }
        assertFalse(shows("That’s the last Short here"))
    }

    @Test
    fun aPlayerThatDidntAnswerSaysSoAboutTheShortAndTryAgainBindsAgain() {
        session = false
        play()
        waitFor { shows("This Short couldn’t play") }
        compose.onNodeWithText("The player didn’t answer").assertExists()

        session = true
        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.isNotEmpty() }
    }

    @Test
    fun aRefusedShortOffersNoTryAgain() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_UNAVAILABLE, 0) }
        waitFor { shows("This Short can’t play here") }

        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    /** A Short whose page resolved live but offered nothing playable says so as the watch page does. */
    @Test
    fun aLiveShortYouTubeOfferedNothingForSaysSoAndTryAgainAsksAgain() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, 0) }
        waitFor { shows("This live stream can’t play right now") }

        assertTrue(shows("LIVE"))
        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.size == 2 }
    }

    @Test
    fun aMadeForKidsShortKeepsTheMadeForKidsTitle() {
        play()
        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0) }
        waitFor { shows("This made-for-kids video can’t play in Ayn") }

        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    @Test
    fun commentsOpenOverTheShortAndBackClosesThemThenTheShort() {
        play()

        compose.onNode(button("Comments")).performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNode(button("Close")).assertExists()
        compose.onNode(button("Save")).assertDoesNotExist() // the foot steps away (and leaves the tree)

        back()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNode(button("Close")).assertDoesNotExist()
        assertTrue(slot.route != null)

        back()
        assertNull(slot.route)
    }

    @Test
    fun saveOpensSaveToPlaylistAlone() {
        play()

        compose.onNode(button("Save")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Watch later").assertExists()
        compose.onNode(button("Play")).assertDoesNotExist()
        assertTrue(service.player.isPlayWhenReady) // it plays on under the sheet
    }

    @Test
    fun backClosesTheShortWithoutAMiniPlayer() {
        play()

        back()

        assertNull(slot.route)
        compose.onNode(button("Close the player")).assertDoesNotExist()
        compose.onNodeWithText(shorts[0].title).assertDoesNotExist()
    }

    @Test
    fun itsChannelClosesTheShortThenOpensTheChannel() {
        play()

        // A card without its channel's link takes the channel the resolve names.
        val channel = shorts[0].takeIf { it.uploaderUrl != null }?.uploaderName ?: service.page.uploaderName!!
        compose.onNode(hasClickAction() and hasText(channel)).performClick()
        compose.waitForIdle()

        assertNull(slot.route)
        waitFor { shows(ChannelExtractor.RecordedHeader.name) }
    }

    @Test
    fun leavingAynPausesAShortEvenWithBackgroundAudioOn() {
        play()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        assertFalse(service.player.isPlayWhenReady)
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
    }
}
