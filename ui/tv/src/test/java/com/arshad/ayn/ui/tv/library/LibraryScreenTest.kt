package com.arshad.ayn.ui.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library hub through the real shell, opened from the rail (`b3-library` hub, hubpartial,
 * hubempty, newpl): its rows and their counts, the rows that go when empty, the empty and
 * unreadable statements, where its cards and tiles lead, and naming a new playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class LibraryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    // Pages compose before the rail, so the rail's item is the last match.
    private fun rail(label: String): SemanticsNodeInteraction = compose.onAllNodes(button(label)).onLast()

    /** A card by the text inside its focusable (a length badge, a playlist's count). */
    private fun card(badge: String): SemanticsNodeInteraction = compose.onNode(button(badge))

    /** A tile (All history, New playlist) by its name. */
    private fun tile(name: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(name) and hasClickAction())

    private fun openLibrary() {
        compose.setContent { TestShell(watchStore = watch, playlistStore = playlists) }
        compose.waitForIdle()
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Library").dpadSelect()
        compose.waitForIdle()
    }

    private fun stored(): List<PlaylistSummary> = runBlocking { playlists.playlists().first() }

    private fun topOf(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theHubListsHistoryPlaylistsAndWatchLaterWithTheirCounts() {
        watch.entries.value = listOf(Enigma, PoleLap)
        playlists.seed(
            "Engineering deep dives",
            savedVideo("CBXkSJT1a7w", "Built on time", "The B1M", 600),
            savedVideo("Ve8wdT2Lb5k", "Bridges", "Practical Engineering", 900),
            savedVideo("4GyvpXa1Q2c", "Dams", "Practical Engineering", 300),
        )
        runBlocking { playlists.addToWatchLater(savedVideo("5x9mBqDPJtQ", "Leopard hunt", "BBC Earth", 300)) }

        openLibrary()

        compose.onNodeWithText("Saved on this Shield").assertExists()
        compose.onNodeWithText("2 videos").assertExists()
        compose.onNodeWithText("Veritasium · 12:51 left").assertExists()
        compose.onNodeWithText("FORMULA 1 · Watched").assertExists()
        compose.onNodeWithText("1 playlist").assertExists()
        compose.onNodeWithText("Engineering deep dives").assertExists()
        compose.onNode(button("3 videos")).assertExists()
        // The fake store stamps playlists by a counter from the epoch.
        compose.onNodeWithText("30 min · Updated 56 years ago").assertExists()
        compose.onNodeWithText("Watch later").assertExists()
        compose.onNodeWithText("1 video").assertExists()
        // The latest watched card holds focus first.
        card("47:42").assertIsFocused()
        val tops = listOf("History", "Your playlists", "Watch later").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun aFocusedRowRisesToTheTopAndStaysThereAfterAVideo() {
        watch.entries.value = listOf(Enigma)
        val enigma = savedVideo(Enigma.videoId.value, Enigma.title!!, "Veritasium", 2862)
        runBlocking { playlists.addToWatchLater(enigma) }
        openLibrary()
        // The Watch later card knows where history left the video, as the history card does.
        assertEquals(2, compose.onAllNodesWithText("Veritasium · 12:51 left").fetchSemanticsNodes().size)

        val resting = topOf("Watch later")
        val laterCard = compose.onAllNodes(button("47:42")).fetchSemanticsNodes().maxBy { it.boundsInRoot.top }
        compose.onNode(SemanticsMatcher("the Watch later card") { it.id == laterCard.id }).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // Toward the top, as far as the rows below it allow (the mockup's clamp).
        val risen = topOf("Watch later")
        assertTrue("the row rose: $resting -> $risen", risen < resting)

        compose.onNode(SemanticsMatcher("the Watch later card") { it.id == laterCard.id }).dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        compose.pressBack()

        assertEquals(risen, topOf("Watch later"), 1f)
        compose.onAllNodes(button("47:42") and isFocused()).assertCountEquals(1)
    }

    @Test
    fun withNoHistoryTheFirstPlaylistTakesFocus() {
        playlists.seed("Weekend cooking", savedVideo("CBXkSJT1a7w", "Built on time", "The B1M", 600))

        openLibrary()

        card("1 video").assertIsFocused()
    }

    @Test
    fun emptyRowsGoExceptYourPlaylistsAndItsNewPlaylistTile() {
        watch.entries.value = listOf(Heist)

        openLibrary()

        compose.onNodeWithText("History").assertExists()
        compose.onNodeWithText("Your playlists").assertExists()
        compose.onNodeWithText("None yet").assertExists()
        tile("New playlist").assertExists()
        compose.onNodeWithText("Watch later").assertDoesNotExist()
        // History keeps no upload date, and an unstarted video has no place: the channel alone.
        compose.onNodeWithText("Mark Rober").assertExists()
    }

    @Test
    fun aLibraryWithNothingInItIsAStatementAndGoToHomeReturnsHome() {
        openLibrary()

        compose.onNodeWithText("Your library fills as you watch").assertExists()
        compose.onNode(button("Go to Home")).assertIsFocused()
        compose.onNode(button("New playlist")).assertExists()

        compose.onNode(button("Go to Home")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Follow channels to fill Home").assertExists()
    }

    @Test
    fun allHistoryOpensHistoryAndBackReturnsToItsTile() {
        watch.entries.value = listOf(Enigma)
        openLibrary()

        tile("All history").dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Clear history").assertExists()

        compose.pressBack()
        tile("All history").assertIsFocused()
    }

    @Test
    fun aHistoryCardPlaysItsVideo() {
        watch.entries.value = listOf(Enigma)
        openLibrary()

        card("47:42").dpadSelect()
        compose.waitForIdle()

        compose.assertPlayerShows()
    }

    @Test
    fun newPlaylistNamesOneOnAScreenOfItsOwnThenOpensIt() {
        watch.entries.value = listOf(Heist)
        openLibrary()

        tile("New playlist").dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Name the playlist").assertExists()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()
        // The screen covers the shell: the rail takes no focus.
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsNotFocused()

        field.performTextReplacement("Weekend projects")
        field.performImeAction()
        compose.waitForIdle()

        assertEquals(listOf("Weekend projects"), stored().map { it.name })
        compose.onNodeWithText("Hold OK on any video to save it here").assertExists()
        compose.onNodeWithText("Weekend projects").assertExists()
        compose.onNode(hasContentDescription("More") and hasClickAction()).assertIsFocused()

        compose.pressBack()
        compose.onNodeWithText("1 playlist").assertExists()
        card("No videos").assertExists()
        tile("New playlist").assertIsFocused()
    }

    @Test
    fun aBlankNameCreatesNothing() {
        watch.entries.value = listOf(Heist)
        openLibrary()
        tile("New playlist").dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Create playlist")).dpadSelect()
        compose.waitForIdle()

        assertTrue(stored().isEmpty())
        compose.onNodeWithText("Name the playlist").assertExists()
    }

    @Test
    fun cancelCreatesNothingAndReturnsToTheNewPlaylistTile() {
        watch.entries.value = listOf(Heist)
        openLibrary()
        tile("New playlist").dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("Weekend")

        compose.onNode(button("Cancel")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Name the playlist").assertDoesNotExist()
        assertTrue(stored().isEmpty())
        tile("New playlist").assertIsFocused()
    }

    @Test
    fun backFromNamingReturnsToTheEmptyLibrarysNewPlaylistButton() {
        openLibrary()
        compose.onNode(button("New playlist")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Name the playlist").assertExists()

        compose.pressBack()

        compose.onNodeWithText("Name the playlist").assertDoesNotExist()
        compose.onNode(button("New playlist")).assertIsFocused()
    }

    @Test
    fun anUnreadableLibrarySaysSoAndTryAgainReadsItAgain() {
        watch.entries.value = listOf(Enigma)
        watch.failReads = true
        openLibrary()

        compose.onNodeWithText("Library couldn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        watch.failReads = false
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Veritasium · 12:51 left").assertExists()
        card("47:42").assertIsFocused()
    }
}
