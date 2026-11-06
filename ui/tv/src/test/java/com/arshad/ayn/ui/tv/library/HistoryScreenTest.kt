package com.arshad.ayn.ui.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.nav.HistoryRoute
import com.arshad.ayn.feature.nav.LibraryRoute
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * All history through the real shell (`b3-library` history, empty, confirm), "now" being
 * Saturday, October 3 2026, 9 pm: days and where each video was left, Clear history's confirm
 * and what it leaves, the empty and unreadable statements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class HistoryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FakeWatchStore()
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    // Pages compose before the rail, so the rail's item is the last match.
    private fun rail(label: String): SemanticsNodeInteraction = compose.onAllNodes(button(label)).onLast()

    /** A row by a text inside it (its title). */
    private fun row(title: String): SemanticsNodeInteraction = compose.onNode(button(title))

    private fun openHistory() {
        compose.setContent {
            navController = rememberNavController()
            TestShell(watchStore = watch, navController = navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            navController.navigate(LibraryRoute)
            navController.navigate(HistoryRoute)
        }
        compose.waitForIdle()
    }

    private fun topOf(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun daysRunNewestFirstUnderTheirNamesAndDates() {
        watch.entries.value = RecordedHistory
        openHistory()

        val headings = listOf("Today", "Saturday, October 3", "Yesterday", "Friday, October 2", "Thursday", "October 1")
        headings.forEach { compose.onNodeWithText(it).assertExists() }
        val tops = listOf("Today", "Yesterday", "Thursday").map(::topOf)
        assertEquals(tops.sorted(), tops)
        row(Enigma.title!!).assertIsFocused()
    }

    @Test
    fun aDayPastAWeekIsNamedByItsDateThenItsWeekday() {
        watch.entries.value = listOf(Mustang)
        openHistory()

        compose.onNodeWithText("September 20").assertExists()
        compose.onNodeWithText("Sunday").assertExists()
    }

    @Test
    fun eachRowSaysWhereTheVideoWasLeftAndWhenItWasWatched() {
        watch.entries.value = RecordedHistory
        openHistory()

        compose.onNodeWithText("Veritasium · 34:51 of 47:42").assertExists()
        compose.onNodeWithText("FORMULA 1 · Watched").assertExists()
        compose.onNodeWithText("Kurzgesagt · 5:10 of 14:12").assertExists()
        compose.onNodeWithText("8:12 PM").assertExists()
        compose.onNodeWithText("10:05 PM").assertExists()
    }

    @Test
    fun clearHistoryAsksFirstWithKeepHistoryFocusedAndTheRailHidden() {
        watch.entries.value = RecordedHistory
        openHistory()

        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Clear all watch history?").assertExists()
        compose.onNodeWithText("This removes 5 videos and every saved position", substring = true).assertExists()
        compose.onNode(button("Keep history")).assertIsFocused()
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsNotFocused()
        compose.onNode(button("Keep history")).assertIsFocused()
    }

    @Test
    fun keepHistoryClosesTheConfirmBackOnClearHistory() {
        watch.entries.value = RecordedHistory
        openHistory()
        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(button("Keep history")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Clear all watch history?").assertDoesNotExist()
        compose.onNode(button("Clear history")).assertIsFocused()
        assertEquals(RecordedHistory, watch.entries.value)
        // The rail takes focus again.
        rail("Library").performSemanticsAction(SemanticsActions.RequestFocus)
        rail("Library").assertIsFocused()
    }

    @Test
    fun aClearThatFailsLeavesHistoryWithClearHistoryFocused() {
        watch.entries.value = RecordedHistory
        watch.failWrites = true
        openHistory()
        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()

        compose.onAllNodes(button("Clear history")).onLast().dpadSelect()
        compose.waitForIdle()

        assertEquals(RecordedHistory, watch.entries.value)
        compose.onNodeWithText("Clear all watch history?").assertDoesNotExist()
        compose.onNode(button("Clear history")).assertIsFocused()
    }

    @Test
    fun clearingThenBackLeavesTheEmptyLibraryWithGoToHomeFocused() {
        watch.entries.value = RecordedHistory
        openHistory()
        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()
        compose.onAllNodes(button("Clear history")).onLast().dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Your library fills as you watch").assertExists()
        compose.onNode(button("Go to Home")).assertIsFocused()
    }

    @Test
    fun aRowPlaysItsVideo() {
        watch.entries.value = RecordedHistory
        openHistory()

        row(Enigma.title!!).dpadSelect()
        compose.waitForIdle()

        compose.assertPlayerShows()
    }

    @Test
    fun backOnTheConfirmKeepsHistory() {
        watch.entries.value = RecordedHistory
        openHistory()
        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Clear all watch history?").assertDoesNotExist()
        compose.onNode(button("Clear history")).assertIsFocused()
        assertEquals(RecordedHistory, watch.entries.value)
    }

    @Test
    fun clearingLeavesTheEmptyStatementWithGoToHomeFocused() {
        watch.entries.value = RecordedHistory
        openHistory()
        compose.onNode(button("Clear history")).dpadSelect()
        compose.waitForIdle()

        // The confirm's button; the page's own waits under it.
        compose.onAllNodes(button("Clear history")).onLast().dpadSelect()
        compose.waitForIdle()

        assertEquals(emptyList<Any>(), watch.entries.value)
        compose.onNodeWithText("Your history starts with the next video").assertExists()
        compose.onNode(button("Go to Home")).assertIsFocused()
        compose.onNode(button("Clear history")).assertDoesNotExist()
    }

    @Test
    fun anEmptyHistorysGoToHomeReturnsHome() {
        openHistory()
        compose.onNode(button("Go to Home")).assertIsFocused()

        compose.onNode(button("Go to Home")).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Follow channels to fill Home").assertExists()
        compose.pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun anUnreadableHistorySaysSoAndTryAgainReadsItAgain() {
        watch.entries.value = RecordedHistory
        watch.failReads = true
        openHistory()

        compose.onNodeWithText("History couldn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        watch.failReads = false
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        row(Enigma.title!!).assertIsFocused()
    }
}
