package com.arshad.ayn.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.C
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.LiveCard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.RecordedLivePage
import com.arshad.ayn.feature.player.sponsorBlockText
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.library.TestTime
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's live watch page (`live-phone-player` 1–3, 10–11; LIVE-D23, D24) through the shell's
 * real wiring and a real `MediaController` on the in-process session, dressed with the recorded
 * live page over an hour's live window ([com.arshad.ayn.feature.player.ScriptedPlayer.liveWindowMs]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LiveWatchTest {
    private companion object {
        const val WINDOW_MS = 3_600_000L

        /** The hour's window's end less the 10 s target offset. */
        const val EDGE_MS = 3_590_000L
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val KID = Profile(2, "Zara", ProfileTint.Amber, isOwner = false, lock = null, isKids = true)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private val stores by lazy { TestStores(session = { service.session.token }) }
    private lateinit var slot: PlayerSlot

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

    private fun open(card: VideoItem = LiveCard, profile: Profile = ARSHAD, time: ShellTime = TestTime) {
        val models = stores.models(time = time)
        compose.setContent {
            AynTheme {
                slot = rememberPlayerSlot()
                ProfileShell(ShellOpening(profile, onSwitchProfile = {}), models, slot = slot)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(card)) }
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

    private fun tapPicture() {
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /** One of the chrome's buttons, then the gathered seek's wait. */
    private fun press(label: String) {
        compose.onNode(button(label)).performClick()
        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 100)
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun textOf(matcher: SemanticsMatcher): String =
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun aLiveCardPlaysAtTheEdgeOnTheLivePage() {
        play(behindMs = 0)

        assertEquals(listOf(RecordedLiveVideo.ID), service.player.requested)
        assertTrue(shows("LIVE"))
        compose.onNodeWithText("1.2K watching · Started streaming 1 hour ago", substring = true).assertExists()
        compose.onNode(button("Comments")).assertDoesNotExist()
        compose.onNode(button("Chapters")).assertDoesNotExist()

        tapPicture()
        compose.onNode(button("Forward 10 seconds")).assertExists() // the chrome is up
        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNodeWithText("behind live", substring = true).assertDoesNotExist()
    }

    /** LIVE-D24 (Q19): a live stream lists its related videos as a video does. */
    @Test
    fun aLiveStreamListsItsRelatedVideosUnderUpNext() {
        play(behindMs = 0)

        compose.onNodeWithText("Up next").assertExists()
        compose.onNodeWithText(RecordedLivePage.relatedVideos.first().title).assertExists()
    }

    /** The page stays composed through a long stream: the start's age follows the clock. */
    @Test
    fun theStartsAgeMovesOnWhileTheStreamPlays() {
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
    fun aVideoPageThatNamesABroadcastStartShowsViewsNotTheStart() {
        service.page = RecordedEnigmaPage.copy(liveStartedAt = RecordedLiveVideo.STARTED_AT)
        service.player.liveWindowMs = null
        play(behindMs = 0, card = EnigmaCard, atMs = 60_000)

        compose.onNodeWithText("15M views", substring = true).assertExists()
        compose.onNodeWithText("Started streaming", substring = true).assertDoesNotExist()
        assertTrue(!shows("LIVE"))
    }

    /** LIVE-D23: the page decides, not the card; another card's count isn't an audience. */
    @Test
    fun aCardThatDoesntSayLiveGetsTheLivePageOnceItsPageResolvesLive() {
        play(behindMs = 0, card = EnigmaCard)

        assertTrue(shows("LIVE"))
        compose.onNodeWithText("Started streaming 1 hour ago", substring = true).assertExists()
        compose.onNodeWithText("watching", substring = true).assertDoesNotExist()
        compose.onNode(button("Comments")).assertDoesNotExist()
    }

    @Test
    fun aLiveCardWhosePageResolvesAsAVideoGetsTheVideoPage() {
        service.page = RecordedEnigmaPage
        service.player.liveWindowMs = null
        play(behindMs = 0, atMs = 60_000)

        assertTrue(!shows("LIVE"))
        compose.onNode(button("Comments")).assertExists()
    }

    @Test
    fun behindLiveTheChromeSaysHowFarAndGoLiveRejoinsTheEdge() {
        play(behindMs = 1_440_000)
        tapPicture()

        val behind = textOf(hasText(" behind live", substring = true))
        assertTrue(behind, Regex("""(23:5\d|24:00) behind live""").matches(behind))

        press("Go live")
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
        moveTo(EDGE_MS)

        compose.onNode(button("Forward 10 seconds")).assertExists() // the chrome is still up
        compose.onNode(button("Go live")).assertDoesNotExist()
        compose.onNodeWithText(" behind live", substring = true).assertDoesNotExist()
    }

    /** Once shown, behind live holds down to 15 s (the window's refreshes can't flip it). */
    @Test
    fun behindLiveHoldsUntilFifteenSecondsFromTheEdge() {
        play(behindMs = 38_000)
        tapPicture()
        compose.onNode(button("Go live")).assertExists()

        moveTo(EDGE_MS - 20_000)
        compose.onNode(button("Go live")).assertExists()
        // The bar reads as the chrome shows it.
        val bar = compose.onNodeWithTag(SCRUB_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(bar, bar.endsWith(" behind live"))

        moveTo(EDGE_MS - 10_000)
        compose.onNode(button("Forward 10 seconds")).assertExists() // the chrome is still up
        compose.onNode(button("Go live")).assertDoesNotExist()
    }

    /** Q20: Back 10 from the edge lands ten seconds back; Forward from there rejoins the edge. */
    @Test
    fun back10FromTheEdgeGoesTenSecondsBackAndForwardRejoins() {
        play(behindMs = 0)
        tapPicture()

        press("Back 10 seconds")
        val seek = service.player.seeks.single()
        assertTrue("$seek", seek in EDGE_MS - 10_000..EDGE_MS - 8_000)

        moveTo(seek)
        press("Forward 10 seconds")
        assertEquals(C.TIME_UNSET, service.player.seeks.last())
    }

    @Test
    fun aDoubleTapForwardAtTheEdgeSaysLiveAndRejoinsIt() {
        play(behindMs = 0)

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.8f, height / 2f)) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        assertTrue(shows("Live"))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 400)
        compose.waitForIdle()
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
    }

    /** Far behind, a forward step lands ten seconds on; 20 s behind it rejoins the edge. */
    @Test
    fun aDoubleTapForwardFarBehindSteps10SecondsOn() {
        play(behindMs = 1_440_000)

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.8f, height / 2f)) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        assertTrue(shows("+10 seconds"))
        assertTrue(!shows("Live"))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 400)
        compose.waitForIdle()
        assertEquals((EDGE_MS - 1_430_000).toDouble(), service.player.seeks.single().toDouble(), 2_000.0)
    }

    @Test
    fun aDoubleTapForwardTwentySecondsBehindSaysLiveAndRejoins() {
        play(behindMs = 20_000)

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.8f, height / 2f)) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        assertTrue(shows("Live"))

        compose.mainClock.advanceTimeBy(PlayerChromePolicy.SEEK_GATHER_MS + 400)
        compose.waitForIdle()
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)
    }

    @Test
    fun aDoubleTapBackFromTheEdgeSaysHowFar() {
        play(behindMs = 0)

        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { doubleClick(Offset(width * 0.2f, height / 2f)) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()

        assertTrue(shows("−10 seconds"))
        assertTrue(!shows("Live"))
    }

    /** The bar stops at the edge: a seek to its end rejoins it; one to the middle lands there. */
    @Test
    fun theBarsSeekActionStopsAtTheEdge() {
        play(behindMs = 0)
        tapPicture()
        assertEquals("Live", compose.onNodeWithTag(SCRUB_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        compose.onNodeWithTag(SCRUB_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        assertEquals(listOf(C.TIME_UNSET), service.player.seeks)

        compose.onNodeWithTag(SCRUB_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        assertEquals(WINDOW_MS / 2.0, service.player.seeks.last().toDouble(), 2_000.0)
    }

    @Test
    fun aDragOnTheBarSaysHowFarBehindLiveItGoes() {
        play(behindMs = 0)
        tapPicture()

        compose.onNodeWithTag(SCRUB_TAG).performTouchInput {
            down(Offset(width * 0.99f, height / 2f))
            moveTo(Offset(width * 0.7f, height / 2f))
            moveTo(Offset(width * 0.5f, height / 2f))
        }
        compose.waitForIdle()

        // Half the hour's window is 29:50 short of its edge.
        val bubble = textOf(hasText("\u221229:", substring = true))
        assertTrue(bubble, Regex("""\u221229:[45]\d""").matches(bubble))
        compose.onNodeWithText("behind live").assertExists()

        compose.onNodeWithTag(SCRUB_TAG).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(WINDOW_MS / 2.0, service.player.seeks.single().toDouble(), 20_000.0)
    }

    /** It plays at 1× (LIVE-D23); no SponsorBlock on a live stream, even with segments for its id. */
    @Test
    fun thePlaybackSheetHasNoSpeedAndNoSponsorBlockOnALiveStream() {
        service.segments = runBlocking { RecordedSegments.lookup() }
        play(behindMs = 0)
        tapPicture()

        compose.onNode(button("Playback settings")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Quality").assertExists()
        compose.onNodeWithText("Speed").assertDoesNotExist()
        compose.onNodeWithText("SponsorBlock").assertExists()
        compose.onNodeWithText(sponsorBlockText(service.segments)).assertDoesNotExist()
        compose.onNodeWithText("None for this video").assertExists() // the Captions line: "Off" below is SponsorBlock's
        compose.onNodeWithText("Off").assertExists()
    }

    /** Q21: the statement, not the end card; the page keeps its Up next; Back closes. */
    @Test
    fun theStreamEndingSaysSoOverUpNextAndBackClosesThePlayer() {
        play(behindMs = 0)

        compose.runOnUiThread { service.player.end() }
        compose.waitForIdle()

        compose.onNodeWithText("This stream has ended").assertExists()
        compose.onNodeWithText("ended just now", substring = true).assertExists()
        compose.onNodeWithText("recording usually appears on the channel", substring = true).assertExists()
        // Started 23:50:06Z, ended at TestTime (01:00Z).
        compose.onNodeWithText("streamed for 1 hour", substring = true).assertExists()
        compose.onNodeWithText("Up next").assertExists()
        compose.onNode(button("Cancel")).assertDoesNotExist()
        compose.onNode(button("Try again")).assertDoesNotExist()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(slot.route)
    }

    @Test
    fun aLiveStreamYouTubeOfferedNothingForSaysSoAndTryAgainAsksAfresh() {
        open()
        waitFor { service.player.requested.isNotEmpty() }

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, positionMs = 0) }
        waitFor { shows("This live stream can’t play right now") }

        assertTrue(shows("${LiveCard.uploaderName} · 1.2K watching"))
        compose.onNode(button("Try again")).performClick()
        waitFor { service.player.requested.size == 2 }
    }

    @Test
    fun aKidsProfilesLiveCardStopsBeforeAnythingIsAsked() {
        open(profile = KID)

        compose.onNodeWithText("Live streams are off for kids").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
        repeat(20) { compose.waitForIdle() } // as long as a request takes to arrive elsewhere
        assertTrue(service.player.requested.isEmpty())
    }

    @Test
    fun aKidsProfileWhoseResolveFoundALivePageSaysLiveStreamsAreOffForKids() {
        open(card = EnigmaCard, profile = KID)
        waitFor { service.player.requested.isNotEmpty() }

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_LIVE, positionMs = 0) }
        waitFor { shows("Live streams are off for kids") }

        compose.onNodeWithText("watching", substring = true).assertDoesNotExist()
        compose.onNode(button("Try again")).assertDoesNotExist()
    }
}

/** A clock the test moves by hand. */
private class SettableClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}
