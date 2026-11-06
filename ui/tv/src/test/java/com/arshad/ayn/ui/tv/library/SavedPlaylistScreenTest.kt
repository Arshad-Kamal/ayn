package com.arshad.ayn.ui.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.feature.nav.LibraryRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.pressBack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A saved playlist's page through the real shell, opened from its hub tile (`b3-library`
 * playlist, plempty, plmenu, plrename, pldelete): its videos in saved order, what Play all,
 * Shuffle and a row play, the ⋯ sheet's rename and delete, the empty and unreadable pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SavedPlaylistScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private lateinit var navController: NavHostController

    private val builtOnTime = savedVideo("CBXkSJT1a7w", "Built on time", "The B1M", 600)
    private val bridges = savedVideo("Ve8wdT2Lb5k", "Bridges", "Practical Engineering", 900)
    private val dams = savedVideo("4GyvpXa1Q2c", "Dams", "Practical Engineering", 300)

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun clickable(label: String): SemanticsNodeInteraction = compose.onNode(button(label))

    private fun more(): SemanticsNodeInteraction = compose.onNode(hasContentDescription("More") and hasClickAction())

    // Pages compose before the rail, so the rail's item is the last match.
    private fun rail(label: String): SemanticsNodeInteraction = compose.onAllNodes(button(label)).onLast()

    private fun showShell() {
        compose.setContent {
            navController = rememberNavController()
            TestShell(watchStore = watch, playlistStore = playlists, navController = navController)
        }
        compose.waitForIdle()
    }

    /** Library from the rail, then the playlist's tile (by its count badge). */
    private fun openFromHub(badge: String) {
        showShell()
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Library").dpadSelect()
        compose.waitForIdle()
        clickable(badge).dpadSelect()
        compose.waitForIdle()
    }

    private fun stored(): List<PlaylistSummary> = runBlocking { playlists.playlists().first() }

    private fun openSheet() {
        more().dpadSelect()
        compose.waitForIdle()
    }

    private fun topOf(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aTileOpensItsPlaylistWithTheVideosInSavedOrderAndPlayAllFocused() {
        playlists.seed("Engineering deep dives", builtOnTime, bridges, dams)

        openFromHub("3 videos")

        compose.onNodeWithText("Engineering deep dives").assertExists()
        // The fake store stamps playlists by a counter from the epoch.
        compose.onNodeWithText("3 videos · 30 min · Updated 56 years ago").assertExists()
        val tops = listOf("Built on time", "Bridges", "Dams").map(::topOf)
        assertEquals(tops.sorted(), tops)
        listOf("1", "2", "3").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("The B1M").assertExists()
        clickable("Play all").assertIsFocused()
    }

    @Test
    fun playAllPlaysThePlaylistInOrderFromItsFirstVideo() {
        val id = playlists.seed("Engineering deep dives", builtOnTime, bridges, dams)
        openFromHub("3 videos")

        clickable("Play all").dpadSelect()
        compose.waitForIdle()

        val route = navController.playerRoute()!!
        assertEquals(builtOnTime.id.value, route.videoId)
        assertEquals(id, route.savedPlaylistId)
        assertEquals(3L, route.playlistVideoCount)
        assertNull(route.shuffleSeed)
    }

    @Test
    fun shufflePlaysAShuffledDealOfThePlaylist() {
        val id = playlists.seed("Engineering deep dives", builtOnTime, bridges, dams)
        openFromHub("3 videos")

        compose.onNode(hasContentDescription("Shuffle") and hasClickAction()).dpadSelect()
        compose.waitForIdle()

        val route = navController.playerRoute()!!
        assertEquals(id, route.savedPlaylistId)
        assertNotNull(route.shuffleSeed)
    }

    @Test
    fun aRowPlaysThatVideoThenOnThroughThePlaylist() {
        val id = playlists.seed("Engineering deep dives", builtOnTime, bridges, dams)
        openFromHub("3 videos")

        clickable("Bridges").dpadSelect()
        compose.waitForIdle()

        val route = navController.playerRoute()!!
        assertEquals(bridges.id.value, route.videoId)
        assertEquals(id, route.savedPlaylistId)
        assertNull(route.shuffleSeed)
        assertFalse(route.startOver)
    }

    @Test
    fun anEmptyPlaylistSaysHowToFillItWithOnlyItsMoreButton() {
        playlists.seed("Weekend cooking")

        openFromHub("No videos")

        compose.onNodeWithText("Hold OK on any video to save it here").assertExists()
        compose.onNodeWithText("No videos · Updated 56 years ago").assertExists()
        clickable("Play all").assertDoesNotExist()
        compose.onNode(hasContentDescription("Shuffle")).assertDoesNotExist()
        more().assertIsFocused()
    }

    @Test
    fun moreOpensTheSheetOnNameAndBackClosesItOnMore() {
        playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")

        openSheet()

        compose.onNodeWithText("1 video · Updated 56 years ago").assertExists()
        clickable("Name").assertIsFocused()
        clickable("Delete playlist").assertExists()
        // Neither the page behind nor the dimmed rail takes focus while the sheet shows.
        clickable("Play all").performSemanticsAction(SemanticsActions.RequestFocus)
        clickable("Play all").assertIsNotFocused()
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsNotFocused()

        compose.pressBack()

        clickable("Name").assertDoesNotExist()
        more().assertIsFocused()
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsFocused()
    }

    @Test
    fun backWhileRenamingKeepsTheNameAndReturnsToTheNameRow() {
        val id = playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")
        openSheet()
        clickable("Name").dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("Code breakers")

        compose.pressBack()

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        clickable("Name").assertIsFocused()
        assertEquals("Engineering deep dives", stored().single { it.id == id }.name)

        compose.pressBack()
        more().assertIsFocused()
    }

    @Test
    fun theNameIsEditedInPlaceAndTheKeyboardsDoneSavesIt() {
        val id = playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")
        openSheet()

        clickable("Name").dpadSelect()
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()
        field.performTextReplacement("Code breakers")
        field.performImeAction()
        compose.waitForIdle()

        assertEquals("Code breakers", stored().single { it.id == id }.name)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        clickable("Name").assertIsFocused()

        compose.pressBack()
        compose.onNodeWithText("Code breakers").assertExists()
        compose.onNodeWithText("Engineering deep dives").assertDoesNotExist()
    }

    @Test
    fun aBlankNameIsNotSaved() {
        val id = playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")
        openSheet()
        clickable("Name").dpadSelect()
        compose.waitForIdle()

        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("   ")
        field.performImeAction()
        compose.waitForIdle()

        assertEquals("Engineering deep dives", stored().single { it.id == id }.name)
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun deleteAsksFirstWithKeepFocusedAndKeepReturnsToMore() {
        playlists.seed("Engineering deep dives", builtOnTime, bridges)
        openFromHub("2 videos")
        openSheet()

        clickable("Delete playlist").dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Delete “Engineering deep dives”?").assertExists()
        compose.onNodeWithText("This removes the playlist and its 2 saved videos from it.", substring = true).assertExists()
        clickable("Keep playlist").assertIsFocused()
        // The confirm covers the shell: the rail takes no focus.
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsNotFocused()

        clickable("Keep playlist").dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Delete “Engineering deep dives”?").assertDoesNotExist()
        more().assertIsFocused()
        assertEquals(1, stored().size)
    }

    @Test
    fun backOnTheConfirmReturnsToTheSheetOnDelete() {
        playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")
        openSheet()
        clickable("Delete playlist").dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Delete “Engineering deep dives”?").assertDoesNotExist()
        clickable("Delete playlist").assertIsFocused()
    }

    @Test
    fun deletingThePlaylistReturnsToTheHubWithoutIt() {
        playlists.seed("Engineering deep dives", builtOnTime)
        openFromHub("1 video")
        openSheet()
        clickable("Delete playlist").dpadSelect()
        compose.waitForIdle()

        compose.onAllNodes(button("Delete playlist")).onLast().dpadSelect()
        compose.waitForIdle()

        assertTrue(stored().isEmpty())
        assertTrue(navController.currentBackStackEntry!!.destination.route!!.contains("LibraryRoute"))
        compose.onNodeWithText("Your library fills as you watch").assertExists()
        clickable("Go to Home").assertIsFocused()
    }

    @Test
    fun aPlaylistThatIsntThereLeavesForTheHub() {
        watch.entries.value = listOf(Enigma)
        showShell()
        compose.runOnUiThread {
            navController.navigate(LibraryRoute)
            navController.navigate(SavedPlaylistRoute(999))
        }
        compose.waitForIdle()

        assertTrue(navController.currentBackStackEntry!!.destination.route!!.contains("LibraryRoute"))
        compose.onNodeWithText("History").assertExists()
    }

    @Test
    fun anUnreadablePlaylistSaysSoAndTryAgainReadsItAgain() {
        val id = playlists.seed("Engineering deep dives", builtOnTime)
        playlists.failReads = true
        showShell()
        compose.runOnUiThread {
            navController.navigate(LibraryRoute)
            navController.navigate(SavedPlaylistRoute(id))
        }
        compose.waitForIdle()

        compose.onNodeWithText("This playlist couldn’t load").assertExists()
        clickable("Try again").assertIsFocused()

        playlists.failReads = false
        compose.onAllNodes(button("Try again")).onLast().dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Built on time").assertExists()
        clickable("Play all").assertIsFocused()
    }
}
