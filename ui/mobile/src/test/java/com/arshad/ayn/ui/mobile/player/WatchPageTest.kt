package com.arshad.ayn.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.ScriptedPlayer
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.Enigma
import com.arshad.ayn.ui.mobile.library.PoleLap
import com.arshad.ayn.ui.mobile.library.RecordedHistory
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
 * The phone's watch page and mini player (`m6-player` 1–8, 11–12, 26–28, 35–36; M6-D16) through
 * the shell's real wiring ([ProfileShell]), a real `MediaController` on an in-process session whose
 * resolve dresses the item with the recorded Enigma page ([FakePlaybackService]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class WatchPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private var session = true
    private val stores by lazy { TestStores(session = { if (session) service.session.token else NoPlayerSession(it) }, playback = { service.end() }) }

    /** This phone's Background audio setting (M6-D5). */
    private var backgroundAudio = true

    /** The open profile's filter, as `MobileApp` provides it; `null` = still loading. */
    private var filter: ContentFilter? = null
    private lateinit var slot: PlayerSlot
    private val kidsClock = FakeKidsClock()

    /** The playback notification's tap, as `MainActivity` hands it over. */
    private var expandPlayer by mutableStateOf(false)

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

    private fun launch(profile: Profile = ARSHAD) {
        val models = stores.models(kidsClock = kidsClock)
        compose.setContent {
            AynTheme {
                CompositionLocalProvider(
                    LocalDeviceSettings provides DeviceSettings(backgroundAudio = backgroundAudio),
                    LocalContentFilter provides filter,
                ) {
                    slot = rememberPlayerSlot()
                    ProfileShell(
                        ShellOpening(profile, onSwitchProfile = {}),
                        models,
                        slot = slot,
                        expandPlayer = expandPlayer,
                        onPlayerExpanded = { expandPlayer = false },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun open(route: PlayerRoute = PlayerRoute(EnigmaCard), profile: Profile = ARSHAD) {
        launch(profile)
        compose.runOnUiThread { slot.open(route) }
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

    private fun play(atMs: Long = 0, route: PlayerRoute = PlayerRoute(EnigmaCard), profile: Profile = ARSHAD) {
        open(route, profile)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun tapPicture() {
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun stop(reason: String, positionMs: Long = 0, detail: String? = null) {
        compose.runOnUiThread { service.stop(reason, positionMs, detail) }
        waitFor {
            shows("Try again", substring = true) || shows("can’t play", substring = true) ||
                shows("Not started yet")
        }
    }

    @Test
    fun aHistoryCardsPlayOpensTheWatchPageOverTheShell() {
        stores.watch.entries.value = RecordedHistory
        launch()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()

        compose.onNode(hasText(Enigma.title!!) and hasClickAction(), useUnmergedTree = false).performClick()
        waitFor { service.player.requested.isNotEmpty() }

        assertEquals(listOf(Enigma.videoId.value), service.player.requested)
        assertTrue(slot.expanded)
        compose.onNodeWithText("Starting").assertExists()
    }

    @Test
    fun theCardShowsWhileTheVideoStartsThenThePageFillsIn() {
        open()
        waitFor { service.player.requested.isNotEmpty() }
        compose.onNodeWithText("Starting").assertExists()
        compose.onNodeWithText(EnigmaCard.title).assertExists()
        compose.onNodeWithTag(PICTURE_TAG).assertExists() // composed under the opening (M3-UI-01)
        compose.onNodeWithText("Up next").assertExists()

        compose.runOnUiThread { service.player.ready() }
        compose.waitForIdle()

        compose.onNodeWithText("Starting").assertDoesNotExist()
        compose.onNodeWithText("Chapter 1 · ${RecordedEnigmaPage.chapters.first().title}").assertExists()
        compose.onNode(button("Subscribe")).assertExists()
        compose.onNode(button("Save")).assertExists()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText(related.first().title).assertExists()
        compose.onAllNodes(button("More")).onFirst().assertExists()
    }

    @Test
    fun playFromTheStartAsksFrom0AndATryAgainAfterItPlayedResumes() {
        stores.watch.resumePointsMs[EnigmaCard.id] = 1_234_000L
        play(route = PlayerRoute(EnigmaCard, startOver = true))
        assertEquals(listOf(0L), service.player.starts)

        stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = 1_500_000)
        stores.watch.resumePointsMs[EnigmaCard.id] = 1_500_000L
        compose.onNode(button("Try again from 25:00")).performClick()
        waitFor { service.player.requested.size == 2 }
        assertEquals(1_500_000L, service.player.starts.last())
    }

    @Test
    fun theBarsSeekActionSeeksForAScreenReader() {
        play(atMs = 60_000)
        tapPicture()
        compose.onNodeWithTag(SCRUB_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()

        assertEquals(DURATION / 2.0, service.player.seeks.single().toDouble(), DURATION * 0.02)
    }

    @Test
    fun aDragUpOnTheMiniPlayerExpandsIt() {
        play()
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()

        compose.onNode(hasText(EnigmaCard.title) and hasClickAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(slot.expanded)
    }

    @Test
    fun comingBackToAPausedNeverStartedVideoStartsIt() {
        backgroundAudio = false
        open()
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertFalse(service.player.isPlayWhenReady)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { service.player.isPlayWhenReady }
    }

    @Test
    fun aVideoWithAResumePointStartsThereAndSaysSoWithStartOver() {
        stores.watch.resumePointsMs[EnigmaCard.id] = 1_234_000L
        play(atMs = 1_234_000L)

        assertEquals(listOf(1_234_000L), service.player.starts)
        compose.onNodeWithText("Resumed at 20:34").assertExists()
        compose.onNode(button("Start over")).performClick()
        compose.waitForIdle()
        assertEquals(0L, service.player.seeks.last())
        compose.onNodeWithText("Resumed at 20:34").assertDoesNotExist()
    }

    @Test
    fun aTapShowsTheChromeAndPauseKeepsItUp() {
        play()
        compose.onNode(button("Pause")).assertDoesNotExist()

        tapPicture()
        compose.onNode(button("Pause")).performClick()
        compose.waitForIdle()

        assertFalse(service.player.isPlayWhenReady)
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 500)
        compose.waitForIdle()
        compose.onNode(button("Play")).assertExists()
    }

    @Test
    fun theChromeRecedesAfterItsIdleTimeWhilePlaying() {
        play()
        tapPicture()
        compose.onNode(button("Pause")).assertExists()

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.HIDE_AFTER_IDLE_MS + 500)
        compose.waitForIdle()
        compose.onNode(button("Pause")).assertDoesNotExist()
    }

    @Test
    fun doubleTapsOnASideGatherIntoOneSeek() {
        play(atMs = 60_000)
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.8f, height / 2f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(Offset(width * 0.8f, height / 2f)) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        assertTrue(shows("+20 seconds"))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 400)
        compose.waitForIdle()
        assertEquals(1, service.player.seeks.size)
        assertEquals(80_000.0, service.player.seeks.single().toDouble(), 1_000.0) // +20 s from where it played
    }

    @Test
    fun aSingleVideosEndOffersUpNextAndNothingStartsByItself() {
        play()
        compose.runOnUiThread { service.player.end() }
        waitFor { shows("Cancel") }
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()

        assertEquals(1, service.player.requested.size)
        compose.onNode(button("Play") and hasText("Play")).performClick()
        waitFor { service.player.requested.size == 2 }
        assertEquals(related.first().id.value, service.player.requested.last())
    }

    @Test
    fun cancelOnTheEndCardLeavesReplay() {
        play()
        compose.runOnUiThread { service.player.end() }
        waitFor { shows("Cancel") }

        compose.onNode(button("Cancel")).performClick()
        compose.waitForIdle()
        compose.onNode(button("Replay")).assertExists()
    }

    @Test
    fun anUpNextRowPlaysInPlace() {
        play()
        val next = related[1]
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(next.title))
        compose.onNode(hasText(next.title) and hasClickAction()).performClick()
        waitFor { service.player.requested.size == 2 }

        assertEquals(next.id.value, service.player.requested.last())
        assertTrue(slot.expanded)
    }

    @Test
    fun playAllLeadsWithThePlaylistAndItsPlace() {
        val listed = listOf(EnigmaCard, EnigmaCard.copy(id = VideoId("bbbbbbbbbbb"), title = "Second video"))
        val id = stores.playlists.seed("Engineering deep dives", *listed.toTypedArray())
        play(route = PlayerRoute(EnigmaCard, PlayAll.Saved(id, "Engineering deep dives", 2, null)))
        waitFor { shows("1 of 2", substring = true) }

        compose.onNodeWithText("Engineering deep dives", substring = true).assertExists()
    }

    @Test
    fun underRestrictedModeYouTubesRefusalNamesItTooAndOnlyThatRefusal() {
        val restricted = "It may be private, removed, age-restricted, members-only, blocked in your region, or hidden by Restricted Mode."

        assertEquals(restricted, stopCopy(PlayerPolicy.TERMINAL_UNAVAILABLE, 0, restricted = true).body)
        assertEquals(restricted, stopCopy(PlayerPolicy.TERMINAL_PROTOCOL, 0, restricted = true).forShort(canMove = true).body)
        assertEquals("It may be private, removed, age-restricted, members-only, or blocked in your region.", stopCopy(PlayerPolicy.TERMINAL_UNAVAILABLE, 0).body)
        assertEquals(stopCopy(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0), stopCopy(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, 0, restricted = true))
        assertEquals(stopCopy(PlayerPolicy.TERMINAL_FILTERED, 0), stopCopy(PlayerPolicy.TERMINAL_FILTERED, 0, restricted = true))
    }

    @Test
    fun aRefusalUnderRestrictedModeNamesIt() {
        filter = ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules())
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)
        compose.onNodeWithText("or hidden by Restricted Mode", substring = true).assertExists()
    }

    @Test
    fun aRefusalWithRestrictedModeOffDoesNotNameIt() {
        filter = ContentFilter.of(ProfileSettings(), ProfileRules())
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)
        compose.onNodeWithText("blocked in your region.", substring = true).assertExists()
        compose.onNodeWithText("Restricted Mode", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRefusedVideoSaysWhyWithoutTryAgainAndCloseLeaves() {
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)

        compose.onNodeWithText("This video can’t play here").assertExists()
        compose.onNodeWithText("YouTube refused to play it").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNode(button("Close")).performClick()
        compose.waitForIdle()
        assertNull(slot.route)
    }

    @Test
    fun aMadeForKidsRefusalNamesMadeForKidsVideosWithoutTryAgain() {
        play()
        stop(PlayerPolicy.TERMINAL_MADE_FOR_KIDS)

        compose.onNodeWithText("This made-for-kids video can’t play in Ayn").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    @Test
    fun aNotStartedVideoShowsYouTubesSentenceWithoutTryAgain() {
        play()
        stop(PlayerPolicy.TERMINAL_NOT_STARTED, detail = "This live event will begin in 5 days.")

        compose.onNodeWithText("This video hasn’t started yet").assertExists()
        compose.onNodeWithText("This live event will begin in 5 days.").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }

    @Test
    fun aVideoStoppedAfterItsTriesOffersTryAgainFromItsPlace() {
        play()
        stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = 2_091_000)
        stores.watch.resumePointsMs[EnigmaCard.id] = 2_091_000 // the service saved the place it stopped at

        compose.onNodeWithText("This video stopped playing").assertExists()
        compose.onNode(button("Try again from 34:51")).performClick()
        waitFor { service.player.requested.size == 2 }
        assertEquals(2_091_000L, service.player.starts.last())
    }

    @Test
    fun backOnAStatementClosesThePlayer() {
        play()
        stop(PlayerPolicy.TERMINAL_UNAVAILABLE)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(slot.route)
    }

    @Test
    fun aPlayerThatDoesntAnswerSaysSo() {
        session = false
        open()
        waitFor { shows("The player didn’t answer") }

        session = true
        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.isNotEmpty() }
        assertEquals(listOf(EnigmaCard.id.value), service.player.requested)
    }

    @Test
    fun backDocksIntoTheMiniPlayerWhichExpandsAgainAndKeepsPlaying() {
        play()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse(slot.expanded)
        assertTrue("docked, the video plays on", service.player.isPlayWhenReady)
        compose.onNode(button("Close the player")).assertExists()
        compose.onNode(hasText(EnigmaCard.title) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(slot.expanded)
    }

    /** The notification's tap shows the docked video's watch page, once. */
    @Test
    fun theNotificationsTapExpandsTheDockedPlayer() {
        play()
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()
        assertFalse(slot.expanded)

        compose.runOnUiThread { expandPlayer = true }
        compose.waitForIdle()

        assertTrue(slot.expanded)
        assertFalse("consumed", expandPlayer)
        assertTrue(service.player.isPlayWhenReady)
    }

    @Test
    fun theNotificationsTapWithoutAVideoOpensNothing() {
        launch()
        compose.runOnUiThread { expandPlayer = true }
        compose.waitForIdle()

        assertNull(slot.route)
        assertFalse("consumed", expandPlayer)
    }

    /** `m6-kids` 7: a kid's time note stays shown while the watch page covers the shell. */
    @Test
    fun aKidsTimeNoteShowsOverTheWatchPage() {
        play(profile = KID)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { kidsClock.warning.value = 5 }
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue(slot.expanded)
        compose.onNode(hasText("5 minutes left today")).assertIsDisplayed()
    }

    @Test
    fun draggingThePictureDownDocks() {
        play()
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { swipeDown(startY = top + 10f, endY = top + 600f) }
        compose.waitForIdle()

        assertFalse(slot.expanded)
        compose.onNode(button("Close the player")).assertExists()
    }

    @Test
    fun aShortDragSpringsBack() {
        play()
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { swipeDown(startY = top + 10f, endY = top + 80f) }
        compose.waitForIdle()

        assertTrue(slot.expanded)
    }

    @Test
    fun theMiniPlayersCloseEndsThePlayer() {
        play()
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()

        compose.onNode(button("Close the player")).performClick()
        compose.waitForIdle()

        assertNull(slot.route)
        assertFalse("nothing plays once closed", service.player.isPlayWhenReady)
        assertNull("the service holds nothing", service.player.currentMediaItem)
    }

    /** M6-D5: a phone keeps playing when Ayn leaves the screen or the phone locks. */
    @Test
    fun leavingTheAppKeepsPlaying() {
        play()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        assertTrue(service.player.isPlayWhenReady)
    }

    @Test
    fun withBackgroundAudioOffLeavingTheAppPauses() {
        backgroundAudio = false
        play()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        assertFalse(service.player.isPlayWhenReady)
    }

    /** M6-D9: a kids profile gets no background audio. */
    @Test
    fun aKidsProfilePausesWhenTheAppIsLeft() {
        play(profile = KID)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        assertFalse(service.player.isPlayWhenReady)
    }

    /** PAF-D1: the card goes into History once the video plays, not as the page opens. */
    @Test
    fun theCardGoesIntoHistoryOnceTheVideoPlays() {
        open(PlayerRoute(EnigmaCard), ARSHAD)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        assertTrue(stores.watch.opened.isEmpty())

        compose.runOnUiThread { service.player.ready() }
        waitFor { stores.watch.opened.map { it.id } == listOf(EnigmaCard.id) }
    }

    /** Away nothing recomposes: Play all still moves on to its next video. */
    @Test
    fun playAllMovesOnWhileTheAppIsAway() {
        val second = EnigmaCard.copy(id = VideoId("bbbbbbbbbbb"), title = "Second video")
        val id = stores.playlists.seed("Engineering deep dives", EnigmaCard, second)
        play(route = PlayerRoute(EnigmaCard, PlayAll.Saved(id, "Engineering deep dives", 2, null)))
        waitFor { shows("1 of 2", substring = true) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 2 }

        assertEquals(second.id.value, service.player.requested.last())
        assertTrue(service.player.isPlayWhenReady)

        // Ended away too: back on screen it isn't asked again (an ended video would replay).
        // PAF-D1: the away turn's start puts its card in History while it plays.
        compose.runOnUiThread { service.player.ready() }
        waitFor { stores.watch.opened.map { it.id } == listOf(EnigmaCard.id, second.id) }
        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { shows("2 of 2", substring = true) }
        repeat(3) { compose.waitForIdle() }
        assertEquals("back on screen nothing is asked again", 2, service.player.requested.size)
    }

    /** After a Try again the opening's count of tries is higher, yet a turn asked away still isn't asked again. */
    @Test
    fun aTryAgainBeforeLeavingDoesntReaskWhatPlayedAway() {
        val second = EnigmaCard.copy(id = VideoId("bbbbbbbbbbb"), title = "Second video")
        val id = stores.playlists.seed("Engineering deep dives", EnigmaCard, second)
        play(route = PlayerRoute(EnigmaCard, PlayAll.Saved(id, "Engineering deep dives", 2, null)))
        stop(PlayerPolicy.TERMINAL_EXHAUSTED)
        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread {
            service.session.setSessionExtras(android.os.Bundle.EMPTY)
            service.player.ready()
        }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()

        compose.runOnUiThread { service.player.end() }
        waitFor { service.player.requested.size == 3 }
        compose.runOnUiThread {
            service.player.ready()
            service.player.end()
        }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { shows("2 of 2", substring = true) }
        repeat(3) { compose.waitForIdle() }
        assertEquals(3, service.player.requested.size)
    }

    /** A single video ending away asks for nothing (Q27); Up next waits for the return. */
    @Test
    fun aSingleVideoEndingAwayAsksForNothing() {
        play()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.runOnUiThread { service.player.end() }
        repeat(3) { compose.waitForIdle() }
        assertEquals(1, service.player.requested.size)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { shows("Cancel") }
        assertEquals(1, service.player.requested.size)
    }

    /** A video that failed isn't asked again when Ayn is left on its statement. */
    @Test
    fun leavingOnAStatementDoesntRetry() {
        play()
        compose.runOnUiThread { service.player.clear() } // the service cleared the item for its failure
        stop(PlayerPolicy.TERMINAL_EXHAUSTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repeat(3) { compose.waitForIdle() }

        assertEquals(1, service.player.requested.size)
    }

    /** Another video from a card ends the last (its stop note goes with it); the same video plays on. */
    @Test
    fun aCardsPlayEndsTheLastVideoUnlessItIsTheSame() {
        stores.watch.entries.value = RecordedHistory
        launch()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(Enigma.title!!) and hasClickAction()).performClick()
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread {
            service.player.ready()
            slot.dock()
        }
        compose.waitForIdle()

        val serial = slot.serial
        compose.onAllNodes(hasText(Enigma.title!!) and hasClickAction()).onFirst().performClick()
        repeat(3) { compose.waitForIdle() }
        assertEquals("the card opened it again", serial + 1, slot.serial)
        assertEquals("the same video plays on", 1, service.player.requested.size)

        stop(PlayerPolicy.TERMINAL_EXHAUSTED)
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()
        compose.onNode(hasText(PoleLap.title!!) and hasClickAction()).performClick()
        waitFor { service.player.requested.size == 2 }
        assertFalse("the last stop note went with it", shows("This video stopped playing"))
    }

    /** Moved on in place (Up next), the opening's first video is another video: its card ends the one playing. */
    @Test
    fun aCardForTheFirstVideoAfterUpNextEndsTheOnePlaying() {
        stores.watch.entries.value = RecordedHistory
        launch()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(Enigma.title!!) and hasClickAction()).performClick()
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready() }
        compose.waitForIdle()
        val next = related[1]
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(next.title))
        compose.onNode(hasText(next.title) and hasClickAction()).performClick()
        waitFor { service.player.requested.size == 2 }
        compose.runOnUiThread { service.player.ready() }
        stop(PlayerPolicy.TERMINAL_EXHAUSTED)
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()

        compose.onNode(hasText(Enigma.title!!) and hasClickAction()).performClick()
        waitFor { service.player.requested.size == 3 }
        assertFalse("the moved-on video's stop note went with it", shows("This video stopped playing"))
    }

    @Test
    fun theMiniPlayersPausePausesWhileDocked() {
        play()
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()

        compose.onNode(button("Pause")).performClick()
        compose.waitForIdle()
        assertFalse(service.player.isPlayWhenReady)
    }

    @Test
    fun anotherOpeningReplacesTheDockedVideo() {
        play()
        compose.runOnUiThread { slot.dock() }
        compose.waitForIdle()

        val next = related.first()
        compose.runOnUiThread { slot.open(PlayerRoute(next)) }
        waitFor { service.player.requested.size == 2 && service.player.isPlayWhenReady }

        assertEquals(next.id.value, service.player.requested.last())
        assertTrue(slot.expanded)
        compose.onNodeWithText(next.title).assertExists()
    }

    @Test
    fun aKidsProfilesPageHasNoSubscribeNoCommentsAndNoCardMenus() {
        play(profile = KID)

        compose.onNode(button("Subscribe")).assertDoesNotExist()
        compose.onNode(button("Save")).assertExists()
        compose.onNode(button("Comments")).assertDoesNotExist()
        compose.onNodeWithText(related.first().title).assertExists()
        compose.onNode(button("More")).assertDoesNotExist()
    }

    @Test
    fun aVideoAskedForWhileTheAppIsAwayStartsOnItsReturn() {
        backgroundAudio = false
        launch()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { slot.open(PlayerRoute(EnigmaCard)) }
        repeat(3) { compose.waitForIdle() }
        assertTrue("nothing asked for behind the app", service.player.requested.isEmpty())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
    }

    @Test
    fun theControlsShowForAScreenReaderThroughThePicturesAction() {
        play()
        compose.onNode(SemanticsMatcher("shows the controls") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Show the controls" })
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNode(button("Pause")).assertExists()
    }

    @Test
    fun draggingTheBarSeeksWhereItIsLetGo() {
        play(atMs = 60_000)
        tapPicture()
        compose.onNodeWithTag(SCRUB_TAG).performTouchInput { swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.5f, height / 2f), durationMillis = 300) }
        compose.waitForIdle()

        assertEquals(1, service.player.seeks.size)
        assertEquals(DURATION / 2.0, service.player.seeks.single().toDouble(), DURATION * 0.02)
        assertTrue("a sideways drag doesn't dock", slot.expanded)
    }

    @Test
    fun theBarTakesNoTouchesWhileTheChromeIsHidden() {
        play(atMs = 60_000)
        compose.onNodeWithTag(SCRUB_TAG).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        assertTrue(service.player.seeks.isEmpty())
    }

    @Test
    fun aTapOnTheBarSeeksThere() {
        play(atMs = 60_000)
        tapPicture()
        compose.onNodeWithTag(SCRUB_TAG).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        compose.waitForIdle()

        assertEquals(DURATION / 4.0, service.player.seeks.single().toDouble(), DURATION * 0.02)
    }

    private companion object {
        const val DURATION = ScriptedPlayer.DURATION_MS.toDouble()
        val KID = Profile(2, "Zara", ProfileTint.Amber, isOwner = false, lock = null, isKids = true)
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
    }
}
