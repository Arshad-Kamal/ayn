package com.arshad.ayn.ui.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.HistoryRoute
import com.arshad.ayn.feature.nav.LibraryRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.testBranding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A hub card is found by its length badge (its title sits outside the focusable picture). */
private const val BRIDGES_BADGE = "15:00"
private const val POLE_LAP_BADGE = "2:05"

/**
 * The card menu through the real shell, opened by holding OK (`b3-library` menu, menulater,
 * menupl, addto, newplsave): its rows per video and per where it was listed, what each leads to,
 * the Save to playlist sheet's switches and Create and save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class VideoMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private lateinit var navController: NavHostController

    private val channelUrl = RECORDED_CHANNEL_URL
    private val builtOnTime = savedVideo("CBXkSJT1a7w", "Built on time", "The B1M", 600)
    private val bridges = savedVideo("Ve8wdT2Lb5k", "Bridges", "Practical Engineering", 900).copy(uploaderUrl = channelUrl)

    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    @After
    fun tearDown() {
        brandingScope.cancel()
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun clickable(label: String): SemanticsNodeInteraction = compose.onNode(button(label))

    // Pages compose before the rail and the sheet over them, so the later match is the overlay's.
    private fun last(label: String): SemanticsNodeInteraction = compose.onAllNodes(button(label)).onLast()

    private fun show(vararg routes: Any, branding: CardBranding? = null) {
        compose.setContent {
            navController = rememberNavController()
            // Go to channel opens the recorded channel.
            TestShell(
                extractor = ChannelExtractor(),
                watchStore = watch,
                playlistStore = playlists,
                branding = branding,
                navController = navController,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { routes.forEach { navController.navigate(it) } }
        compose.waitForIdle()
    }

    /** Holds OK on a list row (by its title) or a hub card (by its length badge). */
    private fun hold(label: String) {
        clickable(label).dpadLongPress(compose::waitForIdle)
    }

    private fun route(): String = navController.currentBackStackEntry!!.destination.route!!

    private fun later(): List<String> = runBlocking { playlists.watchLater().first().map { it.video.title } }

    @Test
    fun holdingOkOnAHistoryRowOpensItsMenuOnResumeAndTheReleaseClicksNothing() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)

        hold(Enigma.title!!)

        clickable("Resume from 34:51").assertIsFocused()
        listOf("Play from the start", "Save to playlist", "Remove from history").forEach { clickable(it).assertExists() }
        // The history entry keeps no channel link: no channel row.
        clickable("Go to channel").assertDoesNotExist()
        assertNull(navController.playerRoute())
    }

    /** Q34: the menu's head shows the face the row showed, DeArrow's title here. */
    @Test
    fun theMenuHeadShowsDeArrowsTitleWhereTheRowDid() {
        val deArrowTitle = "How the Enigma machine worked"
        val enigma = VideoId("JsBZOcqZerk")
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute, branding = testBranding(brandingScope, BrandingSource { id, _ ->
            if (id == enigma) BrandingLookup.Found(Branding(deArrowTitle, null)) else BrandingLookup.None
        }))

        hold(deArrowTitle)

        clickable("Resume from 34:51").assertIsFocused()
        compose.onAllNodesWithText(deArrowTitle).assertCountEquals(2)
        compose.onNodeWithText(Enigma.title!!).assertDoesNotExist()
    }

    @Test
    fun theMenuKeepsThePageAndTheRailFromFocusAndBackReturnsToTheRow() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)
        hold(Civilizations.title!!)

        clickable("Clear history").performSemanticsAction(SemanticsActions.RequestFocus)
        clickable("Clear history").assertIsNotFocused()
        last("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        last("Library").assertIsNotFocused()

        compose.pressBack()

        clickable("Resume from 5:10").assertDoesNotExist()
        clickable(Civilizations.title!!).assertIsFocused()
    }

    @Test
    fun afterTheMenuClosesOnePressOfOkPlaysTheRowThatHeldIt() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)
        hold(Civilizations.title!!)
        compose.pressBack()

        clickable(Civilizations.title!!).dpadSelect()
        compose.waitForIdle()

        assertEquals(Civilizations.videoId.value, navController.playerRoute()?.videoId)
    }

    @Test
    fun resumePlaysTheVideoFromItsPlaceAndBackReturnsToTheRowWithTheMenuClosed() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)
        hold(Enigma.title!!)

        clickable("Resume from 34:51").dpadSelect()
        compose.waitForIdle()

        val played = navController.playerRoute()!!
        assertEquals(Enigma.videoId.value, played.videoId)
        assertFalse(played.startOver)

        compose.pressBack()
        clickable("Resume from 34:51").assertDoesNotExist()
        clickable(Enigma.title!!).assertIsFocused()
    }

    @Test
    fun playFromTheStartAsksThePlayerToStartOver() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)
        hold(Enigma.title!!)

        clickable("Play from the start").dpadSelect()
        compose.waitForIdle()

        val played = navController.playerRoute()!!
        assertEquals(Enigma.videoId.value, played.videoId)
        assertTrue(played.startOver)
    }

    @Test
    fun removeFromHistoryTakesTheVideoOutAndClosesTheMenu() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute, HistoryRoute)
        hold(Enigma.title!!)

        clickable("Remove from history").dpadSelect()
        compose.waitForIdle()

        assertEquals(RecordedHistory - Enigma, watch.entries.value)
        clickable("Remove from history").assertDoesNotExist()
        clickable(PoleLap.title!!).assertIsFocused()
    }

    @Test
    fun aWatchLaterCardOnTheHubOffersPlayGoToChannelAndRemoveFromWatchLater() {
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)

        hold(BRIDGES_BADGE)

        clickable("Play").assertIsFocused()
        compose.onNode(hasText("Resume from", substring = true)).assertDoesNotExist()
        clickable("Play from the start").assertDoesNotExist()
        clickable("Go to channel").assertExists()

        clickable("Remove from Watch later").dpadSelect()
        compose.waitForIdle()

        assertTrue(later().isEmpty())
        clickable("Remove from Watch later").assertDoesNotExist()
    }

    @Test
    fun aHubHistoryCardsMenuRemovesFromHistoryAndBackReturnsToTheCard() {
        watch.entries.value = RecordedHistory
        show(LibraryRoute)

        // Not the hub's first card, which focus would fall back to anyway.
        hold(POLE_LAP_BADGE)

        clickable("Play").assertIsFocused()
        clickable("Remove from history").assertExists()
        compose.pressBack()
        clickable(POLE_LAP_BADGE).assertIsFocused()
    }

    @Test
    fun aSaveSheetWithManyPlaylistsScrollsToItsLastRow() {
        repeat(12) { playlists.seed("Playlist ${it + 1}") }
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()

        last("New playlist").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        last("New playlist").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun goToChannelOpensTheVideosChannel() {
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)

        clickable("Go to channel").dpadSelect()
        compose.waitForIdle()

        assertTrue(route().contains("ChannelRoute"))
        assertEquals(channelUrl, navController.currentBackStackEntry!!.toRoute<ChannelRoute>().channelUrl)
    }

    @Test
    fun saveToPlaylistShowsWhereTheVideoIsSavedWithWatchLaterFocused() {
        playlists.seed("Engineering deep dives", bridges)
        playlists.seed("Weekend cooking")
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)

        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Save to playlist").assertExists()
        clickable("Watch later").assertIsFocused().assertIsOn()
        clickable("Engineering deep dives").assertIsOn()
        clickable("Weekend cooking").assertIsOff()
        clickable("Weekend cooking").assert(hasText("No videos"))
    }

    @Test
    fun theSwitchesSaveAndRemoveTheVideo() {
        val cooking = playlists.seed("Weekend cooking")
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()

        clickable("Weekend cooking").dpadSelect()
        compose.waitForIdle()

        clickable("Weekend cooking").assertIsOn()
        assertEquals(listOf("Bridges"), runBlocking { playlists.videos(cooking).first().map { it.video.title } })

        clickable("Watch later").dpadSelect()
        compose.waitForIdle()

        assertTrue(later().isEmpty())
    }

    @Test
    fun backFromTheSaveSheetReturnsToTheMenuOnSaveToPlaylist() {
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        clickable("Watch later").assertDoesNotExist()
        clickable("Save to playlist").assertIsFocused()
    }

    @Test
    fun newPlaylistNamesOneForTheVideoAndCreateAndSavePutsItInFirst() {
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()

        last("New playlist").dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Bridges goes in first.").assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextReplacement("Code breakers")
        clickable("Create and save").dpadSelect()
        compose.waitForIdle()

        val created = runBlocking { playlists.playlists().first() }.single()
        assertEquals("Code breakers", created.name)
        assertEquals(listOf("Bridges"), runBlocking { playlists.videos(created.id).first().map { it.video.title } })
        clickable("Code breakers").assertIsOn()
        last("New playlist").assertIsFocused()
    }

    @Test
    fun backWhileNamingReturnsToTheSaveSheetOnNewPlaylistWithNothingCreated() {
        runBlocking { playlists.addToWatchLater(bridges) }
        show(LibraryRoute)
        hold(BRIDGES_BADGE)
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()
        last("New playlist").dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        last("New playlist").assertIsFocused()
        assertTrue(runBlocking { playlists.playlists().first() }.isEmpty())
    }

    @Test
    fun aSavedPlaylistRowsMenuPlaysOnThroughThePlaylistAndRemovesFromIt() {
        val id = playlists.seed("Engineering deep dives", builtOnTime, bridges)
        show(LibraryRoute, SavedPlaylistRoute(id))

        hold("Built on time")
        clickable("Play").dpadSelect()
        compose.waitForIdle()

        val played = navController.playerRoute()!!
        assertEquals(builtOnTime.id.value, played.videoId)
        assertEquals(id, played.savedPlaylistId)

        compose.pressBack()
        hold("Bridges")
        compose.pressBack()
        // Back to the row that held it, not the page's first control.
        clickable("Bridges").assertIsFocused()

        hold("Built on time")
        clickable("Remove from this playlist").dpadSelect()
        compose.waitForIdle()

        assertEquals(listOf("Bridges"), runBlocking { playlists.videos(id).first().map { it.video.title } })
        clickable("Remove from this playlist").assertDoesNotExist()
    }

    @Test
    fun aSavedPlaylistRowWithAResumePointPlaysFromTheStartThroughThePlaylist() {
        val id = playlists.seed("Engineering deep dives", builtOnTime, bridges)
        watch.entries.value = listOf(watched(bridges.id.value, "Bridges", "Practical Engineering", LocalDateTime.of(2026, 10, 3, 20, 0), 120_000, 900_000))
        show(LibraryRoute, SavedPlaylistRoute(id))
        hold("Bridges")

        clickable("Play from the start").dpadSelect()
        compose.waitForIdle()

        val played = navController.playerRoute()!!
        assertEquals(bridges.id.value, played.videoId)
        assertEquals(id, played.savedPlaylistId)
        assertTrue(played.startOver)
    }
}
