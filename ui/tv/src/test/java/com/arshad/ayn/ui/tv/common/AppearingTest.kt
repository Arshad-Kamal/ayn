package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Content over a black page, white under one key and green under the next: a new key fades the
 *  content in again from clear without composing it again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aNewKeyFadesTheSameContentInAgain() {
        var key by mutableStateOf(0)
        var compositions = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Appearing(key = key) {
                    remember { compositions++ }
                    Box(Modifier.fillMaxSize().background(if (key == 0) Color.White else Color.Green))
                }
            }
        }
        compose.waitForIdle()
        assertTrue("faded in", centre() > 0.99f)

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { key = 1 }
        // The change lands within three frames: the new content's first frame is clear (no flash
        // of it at full strength before the fade), and it then fades in.
        val frames = (1..4).map {
            compose.mainClock.advanceTimeByFrame()
            centrePixel()
        }
        val landed = frames.indexOfFirst { it.red < 0.5f }
        assertTrue("the new content comes in clear, frames $frames", landed >= 0 && frames[landed].green < 0.05f)
        assertTrue("never at full strength straight away: $frames", frames.none { it.red < 0.5f && it.green > 0.9f })
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("faded in again", centre() > 0.99f)
        assertEquals("the content composed once", 1, compositions)
    }

    /** The centre pixel's brightness (white content over black: its alpha). */
    private fun centre(): Float = centrePixel().green

    private fun centrePixel(): Color {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
}
