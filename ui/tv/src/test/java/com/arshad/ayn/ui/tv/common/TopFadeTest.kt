package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * White content over a red page: the page shows through the fade, then the content is solid.
 * At the Shield's density (xhdpi), so a dp/px slip in the fade's length fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theContentFadesInOverItsTopLengthOnly() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Red)) {
                Box(Modifier.padding(top = Top).fillMaxSize().topFade(Fade).background(Color.White))
            }
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val px = compose.density.density
        fun at(dp: Float) = pixels[pixels.width / 2, (dp * px).toInt()]

        assertEquals("above the content: the page", Color.Red, at(Top.value / 2))
        val edge = at(Top.value)
        val middle = at(Top.value + Fade.value / 2)
        assertTrue("the content's top edge is the page: $edge", edge.green < 0.1f && edge.red > 0.9f)
        assertTrue("half-way down the fade is half content: $middle", middle.green in 0.35f..0.65f)
        assertEquals("below the fade: solid content", Color.White, at(Top.value + Fade.value + 2))
    }

    private companion object {
        val Top = 40.dp
        val Fade = 20.dp
    }
}
