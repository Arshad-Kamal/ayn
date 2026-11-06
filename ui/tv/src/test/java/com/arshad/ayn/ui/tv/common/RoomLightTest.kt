package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The room's light pools low under the focused card and follows it across the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomLightTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLightFollowsTheFocusedCardAcrossTheScreen() {
        val card = FocusedCardLight()
        val red = mutableStateOf(Color.Red)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CardRoomLight(card, lit = true)
            }
        }
        compose.waitForIdle()
        val width = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        fun redAt(fraction: Float): Float {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            return pixels[(pixels.width * fraction).toInt(), pixels.height - 2].red
        }

        compose.runOnIdle { card.onCardFocused(red, centerX = width * 0.9f) }
        compose.waitForIdle()
        assertTrue("under the card: lit", redAt(0.9f) > 0.08f)
        assertTrue("across the screen: dark", redAt(0.1f) < 0.02f)

        // A row scrolling the card back: its centre reported step after step (settled positions
        // only: Robolectric's frame clock doesn't show a tween mid-flight here).
        for (step in 1..8) {
            compose.runOnIdle { card.onCardFocused(red, centerX = width * (0.9f - 0.1f * step)) }
        }
        compose.waitForIdle()
        assertTrue("under the card's new place: lit", redAt(0.1f) > 0.08f)
        assertTrue("where it was: dark", redAt(0.9f) < 0.02f)
    }
}
