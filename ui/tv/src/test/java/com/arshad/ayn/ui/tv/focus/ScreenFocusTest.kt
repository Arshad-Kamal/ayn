package com.arshad.ayn.ui.tv.focus

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.SearchRoute
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.theme.KeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The destination focus contract every screen enters through ([keScreen]): a first visit
 * focuses the screen's initial item, BACK returns focus to the card that opened the next
 * screen (not the rail), also when that card sat far along a scrolled row, a card that is gone
 * on return falls back to the initial item (or, gone while an overlay held focus, to the card
 * nearest its place; gone from a list that gave its slot, to the card now in that slot), a screen
 * with nothing focusable hands focus to the
 * shell, content landing after arrival takes focus unless the shell holds it, and a one-shot
 * return key outranks the remembered card on the next entry only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ScreenFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cards = mutableStateListOf<String>().apply { addAll((1..20).map { "Card $it" }) }
    private var emptyScreenCalls = 0

    /** [slotted]: each card gives its [FocusSlot] in the row. */
    private fun setUpTwoScreens(initialFocusKey: String, slotted: Boolean = false) {
        compose.setContent {
            KeTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = HomeRoute) {
                    keScreen<HomeRoute>({ emptyScreenCalls++ }, initialFocusKey) {
                        LazyRow(
                            modifier = Modifier.testTag("row"),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(cards, key = { _, title -> title }) { index, title ->
                                ContentSurface(
                                    onClick = { nav.navigate(SearchRoute) },
                                    light = Color.Red,
                                    modifier = Modifier.focusMemory(title, FocusSlot("row", index).takeIf { slotted }).size(200.dp, 112.dp),
                                ) { Text(title) }
                            }
                        }
                    }
                    keScreen<SearchRoute>({ emptyScreenCalls++ }) {
                        ControlSurface(onClick = {}) { Text("Next screen") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun leftMovesAlongTheRowAndAtItsStartHandsFocusToTheShell() {
        setUpTwoScreens(initialFocusKey = "Card 2")
        compose.onNodeWithText("Card 2").assertIsFocused()

        compose.onNodeWithText("Card 2").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithText("Card 1").assertIsFocused()
        assertEquals(0, emptyScreenCalls)

        compose.onNodeWithText("Card 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(1, emptyScreenCalls)
    }

    @Test
    fun firstVisitFocusesTheScreensInitialItem() {
        setUpTwoScreens(initialFocusKey = "Card 2")

        compose.onNodeWithText("Card 2").assertIsFocused()
    }

    @Test
    fun backReturnsFocusToTheCardThatOpenedTheNextScreen() {
        setUpTwoScreens(initialFocusKey = "Card 1")
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("Next screen").assertIsFocused()

        compose.pressBack()

        compose.onNodeWithText("Card 3").assertIsFocused()
        assertEquals(0, emptyScreenCalls)
    }

    @Test
    fun backReturnsFocusToACardFarAlongAScrolledRow() {
        setUpTwoScreens(initialFocusKey = "Card 1")
        compose.onNodeWithTag("row").performScrollToKey("Card 15")
        compose.onNodeWithText("Card 15").dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Card 15").assertIsFocused()
    }

    @Test
    fun aCardGoneOnReturnFallsBackToTheInitialItem() {
        setUpTwoScreens(initialFocusKey = "Card 2")
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { cards.remove("Card 3") }

        compose.pressBack()

        compose.onNodeWithText("Card 2").assertIsFocused()
    }

    @Test
    fun aCardGoneOnReturnFromARowThatGaveItsSlotHandsFocusToTheCardNowInItsPlace() {
        setUpTwoScreens(initialFocusKey = "Card 2", slotted = true)
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { cards.remove("Card 3") }

        compose.pressBack()

        compose.onNodeWithText("Card 4").assertIsFocused()
    }

    @Test
    fun theLastCardGoneOnReturnFromARowThatGaveItsSlotHandsFocusToTheCardBefore() {
        cards.retainAll(listOf("Card 1", "Card 2", "Card 3"))
        setUpTwoScreens(initialFocusKey = "Card 1", slotted = true)
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { cards.remove("Card 3") }

        compose.pressBack()

        compose.onNodeWithText("Card 2").assertIsFocused()
    }

    @Test
    fun aSlottedCardStillThereOnReturnTakesFocusThoughItsIndexMoved() {
        setUpTwoScreens(initialFocusKey = "Card 1", slotted = true)
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { cards.add(0, "Card 0") }

        compose.pressBack()

        compose.onNodeWithText("Card 3").assertIsFocused()
    }

    @Test
    fun aFocusedCardWhoseIndexMovesKeepsItsNewSlotForTheReturn() {
        setUpTwoScreens(initialFocusKey = "Card 1", slotted = true)
        compose.onNodeWithText("Card 3").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnUiThread { cards.add(0, "Card 0") }
        compose.waitForIdle()
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { cards.remove("Card 3") }

        compose.pressBack()

        compose.onNodeWithText("Card 4").assertIsFocused()
    }

    @Test
    fun aSlotHandsFocusOnlyWithinItsOwnRow() {
        val rowA = mutableStateListOf("A1", "A2", "A3")
        compose.setContent {
            KeTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = HomeRoute) {
                    keScreen<HomeRoute>({ emptyScreenCalls++ }, initialFocusKey = "A1") {
                        Column {
                            listOf("A" to rowA, "B" to listOf("B1", "B2", "B3")).forEach { (group, titles) ->
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    itemsIndexed(titles, key = { _, title -> title }) { index, title ->
                                        ContentSurface(
                                            onClick = { nav.navigate(SearchRoute) },
                                            light = Color.Red,
                                            modifier = Modifier.focusMemory(title, FocusSlot(group, index)).size(200.dp, 112.dp),
                                        ) { Text(title) }
                                    }
                                }
                            }
                        }
                    }
                    keScreen<SearchRoute>({ emptyScreenCalls++ }) {
                        ControlSurface(onClick = {}) { Text("Next screen") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("A3").dpadSelect()
        compose.waitForIdle()
        compose.runOnUiThread { rowA.remove("A3") }

        compose.pressBack()

        compose.onNodeWithText("A2").assertIsFocused()
    }

    private val returnKey = mutableStateOf<String?>(null)
    private var returned = 0

    /** One screen of cards with a one-shot [returnKey], consumed through its callback. */
    private fun setUpReturningScreen() {
        compose.setContent {
            KeTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        ScreenFocus(
                            onNothingToFocus = { emptyScreenCalls++ },
                            initialFocusKey = "Card 1",
                            returnKey = returnKey.value,
                            onReturned = {
                                returned++
                                returnKey.value = null
                            },
                        ) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(cards, key = { it }) { title ->
                                    ContentSurface(
                                        onClick = { nav.navigate(SearchRoute) },
                                        light = Color.Red,
                                        modifier = Modifier.focusMemory(title).size(200.dp, 112.dp),
                                    ) { Text(title) }
                                }
                            }
                        }
                    }
                    keScreen<SearchRoute>({ emptyScreenCalls++ }) {
                        ControlSurface(onClick = {}) { Text("Next screen") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aReturnKeyOutranksTheRememberedCardOnceThenTheRememberedOneIsBack() {
        setUpReturningScreen()
        compose.onNodeWithText("Card 2").dpadSelect()
        compose.waitForIdle()
        returnKey.value = "Card 4"

        compose.pressBack()

        compose.onNodeWithText("Card 4").assertIsFocused()
        assertEquals(1, returned)
        compose.onNodeWithText("Card 3").dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.onNodeWithText("Card 3").assertIsFocused()
        assertEquals(1, returned)
    }

    @Test
    fun aReturnKeyWithNoCardIsConsumedAndTheRememberedCardTakesFocus() {
        setUpReturningScreen()
        compose.onNodeWithText("Card 2").dpadSelect()
        compose.waitForIdle()
        returnKey.value = "Card 99"

        compose.pressBack()

        compose.onNodeWithText("Card 2").assertIsFocused()
        assertEquals(1, returned)
        assertEquals(null, returnKey.value)
    }

    @Test
    fun contentLandingAfterArrivalTakesFocusAndLoadingHandsNothingToTheShell() {
        val loaded = mutableStateOf(false)
        setUpLoadingScreen(loaded, heldElsewhere = { false })
        assertEquals("loading never hands focus away", 0, emptyScreenCalls)

        compose.runOnUiThread { loaded.value = true }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
    }

    @Test
    fun contentLandingAfterFocusMovedToTheShellLeavesItThere() {
        val loaded = mutableStateOf(false)
        val held = mutableStateOf(false)
        setUpLoadingScreen(loaded, heldElsewhere = { held.value })

        held.value = true // the user moved to the rail while the screen loaded
        compose.runOnUiThread { loaded.value = true }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsNotFocused()
    }

    private fun setUpLoadingScreen(loaded: MutableState<Boolean>, heldElsewhere: () -> Boolean) {
        compose.setContent {
            KeTheme {
                ScreenFocus(
                    onNothingToFocus = { emptyScreenCalls++ },
                    initialFocusKey = "content",
                    entryKey = if (loaded.value) "content" else null,
                    focusHeldElsewhere = heldElsewhere,
                ) {
                    if (loaded.value) {
                        ControlSurface(onClick = {}, modifier = Modifier.focusMemory("content")) { Text("Content") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun arrivingWhileTheShellHoldsFocusTakesItWhenContentLands() {
        // Arrived from the rail (it keeps focus after a select): the first content is the arrival.
        val loaded = mutableStateOf(false)
        setUpLoadingScreen(loaded, heldElsewhere = { true })

        compose.runOnUiThread { loaded.value = true }
        compose.waitForIdle()

        compose.onNodeWithText("Content").assertIsFocused()
    }

    @Test
    fun aScreenWithNothingFocusableHandsFocusToTheShell() {
        compose.setContent {
            KeTheme {
                ScreenFocus(onNothingToFocus = { emptyScreenCalls++ }) { Text("Nothing to focus") }
            }
        }
        compose.waitForIdle()

        assertEquals(1, emptyScreenCalls)
    }

    private val overlayOpen = mutableStateOf(false)
    private val page = FocusRequester()
    private val overlay = FocusRequester()

    /** A screen of three cards beside an overlay's control, the screen under [LocalOverlayHoldsFocus]. */
    private fun setUpScreenBesideAnOverlay() {
        cards.retainAll(listOf("Card 1", "Card 2", "Card 3"))
        compose.setContent {
            KeTheme {
                Row {
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalOverlayHoldsFocus provides overlayOpen.value) {
                            ScreenFocus(onNothingToFocus = { emptyScreenCalls++ }, modifier = Modifier.focusRequester(page), initialFocusKey = "Card 1") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(cards, key = { it }) { title ->
                                        ContentSurface(onClick = {}, light = Color.Red, modifier = Modifier.focusMemory(title).size(200.dp, 112.dp)) {
                                            Text(title)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ControlSurface(onClick = {}, modifier = Modifier.focusRequester(overlay)) { Text("Overlay") }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Focus moves from [card] to the overlay's control ([asOverlay]: while the overlay flag is
     *  set), [card] leaves the row, and focus comes back to the screen once the flag is down. */
    private fun leaveRemoveAndReturn(card: String, asOverlay: Boolean) {
        compose.onNodeWithText(card).performSemanticsAction(SemanticsActions.RequestFocus)
        overlayOpen.value = asOverlay
        compose.waitForIdle()
        compose.runOnUiThread { overlay.requestFocus() }
        compose.waitForIdle()
        compose.runOnUiThread { cards.remove(card) }
        overlayOpen.value = false
        compose.waitForIdle()
        compose.runOnUiThread { page.requestFocus() }
        compose.waitForIdle()
    }

    @Test
    fun theLastCardGoneWhileAnOverlayHeldFocusHandsItToTheCardBefore() {
        setUpScreenBesideAnOverlay()

        leaveRemoveAndReturn("Card 3", asOverlay = true)

        compose.onNodeWithText("Card 2").assertIsFocused()
    }

    @Test
    fun aCardGoneWhileFocusWasElsewhereFallsBackToTheInitialItem() {
        setUpScreenBesideAnOverlay()

        leaveRemoveAndReturn("Card 3", asOverlay = false)

        compose.onNodeWithText("Card 1").assertIsFocused()
    }
}
