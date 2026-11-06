package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.SearchRoute
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.focus.ContentSurface
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.focus.keScreen
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.theme.KeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A [CardRow] scrolled along comes back from the next screen as it was, with its card focused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class CardRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cards = (1..20).map { "Card $it" }

    @Test
    fun backReturnsToACardFarAlongTheRowWithTheRowWhereItWas() {
        compose.setContent {
            KeTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = HomeRoute) {
                    keScreen<HomeRoute>({}) {
                        CardRow(title = "Row", itemWidth = 200.dp, itemSpacing = 14.dp) {
                            items(cards, key = { it }) { title ->
                                ContentSurface(
                                    onClick = { nav.navigate(SearchRoute) },
                                    light = Color.Red,
                                    modifier = Modifier.focusMemory(title).size(200.dp, 112.dp),
                                ) { Text(title) }
                            }
                        }
                    }
                    keScreen<SearchRoute>({}) {
                        ControlSurface(onClick = {}) { Text("Next screen") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("Card 15")
        compose.waitForIdle()
        val opened = compose.onNodeWithText("Card 15").fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithText("Card 15").dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Card 15").assertIsFocused()
        assertEquals(opened, compose.onNodeWithText("Card 15").fetchSemanticsNode().boundsInRoot.center.x, 0.5f)
    }

    @Test
    fun theRowsFocusReachesItsSurfacesWithoutRecomposingItsCards() {
        val compositions = mutableMapOf<String, Int>()
        val dimRead = mutableMapOf<String, Boolean>()
        val first = FocusRequester()
        val outside = FocusRequester()
        compose.setContent {
            KeTheme {
                Column {
                    ControlSurface(onClick = {}, modifier = Modifier.focusRequester(outside)) { Text("Outside") }
                    CardRow(title = "Row", itemWidth = 200.dp, itemSpacing = 14.dp) { rowFocused ->
                        items(cards.take(3), key = { it }) { title ->
                            SideEffect { compositions[title] = (compositions[title] ?: 0) + 1 }
                            ContentSurface(
                                onClick = {},
                                light = Color.Red,
                                dimmed = { rowFocused().also { dimRead[title] = it } },
                                modifier = Modifier.size(200.dp, 112.dp).then(if (title == "Card 1") Modifier.focusRequester(first) else Modifier),
                            ) { Text(title) }
                        }
                    }
                }
            }
        }
        val once = mapOf("Card 1" to 1, "Card 2" to 1, "Card 3" to 1)
        compose.waitForIdle()
        assertEquals(once, compositions.toMap())
        assertEquals(false, dimRead["Card 2"])

        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("Card 1").assertIsFocused()
        assertEquals(true, dimRead["Card 2"])
        assertEquals(once, compositions.toMap())

        compose.runOnIdle { outside.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("Outside").assertIsFocused()
        assertEquals(false, dimRead["Card 2"])
        assertEquals(once, compositions.toMap())
    }
}
