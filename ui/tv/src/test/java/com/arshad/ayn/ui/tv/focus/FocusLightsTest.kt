package com.arshad.ayn.ui.tv.focus

import androidx.activity.ComponentActivity
import androidx.collection.ScatterMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.theme.KeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The platform caches a blurred mask per distinct `Shadow` and keeps it while the window is shown
 * (B14-1: animating the shadow's alpha blurred and kept a bitmap per frame). A focus fade in and
 * out, across pictures of different colours, must leave one cached mask per light geometry, while
 * the light still shows the picture's colour and follows the fade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusLightsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var shadows: ShadowContext? = null

    /** Captures the window's shadow context, whose cache the lights draw through. */
    @Composable
    private fun WatchedShadows(content: @Composable () -> Unit) {
        shadows = LocalGraphicsContext.current.shadowContext
        content()
    }

    /** The platform's drop-shadow cache size (Compose UI 1.12.1 `AndroidShadowContext.dropShadowCache`). */
    private fun cachedShadows(): Int {
        val context = checkNotNull(shadows)
        val cache = context.javaClass.getDeclaredField("dropShadowCache").apply { isAccessible = true }.get(context)
        return (cache as ScatterMap<*, *>?)?.size ?: 0
    }

    /** Focuses each requester in turn and draws every frame of each fade until it rests. */
    private fun focusThrough(vararg requesters: FocusRequester) {
        compose.mainClock.autoAdvance = false
        requesters.forEach { requester ->
            compose.runOnIdle { requester.requestFocus() }
            repeat(FadeFrames) {
                compose.mainClock.advanceTimeByFrame()
                compose.onRoot().captureToImage()
            }
        }
    }

    @Test
    fun thumbnailHalosOfDifferentColoursCacheOneMaskThroughEveryFade() {
        val red = FocusRequester()
        val blue = FocusRequester()
        val away = FocusRequester()
        compose.setContent {
            KeTheme {
                WatchedShadows {
                    Row {
                        ContentSurface({}, light = Color.Red, Modifier.focusRequester(red).size(200.dp, 112.dp)) { Text("Red") }
                        ContentSurface({}, light = Color.Blue, Modifier.focusRequester(blue).size(200.dp, 112.dp)) { Text("Blue") }
                        ControlSurface({}, Modifier.focusRequester(away), style = ControlStyle.Rail) { Text("Away") }
                    }
                }
            }
        }

        compose.onRoot().captureToImage()
        assertEquals("resting surfaces draw no light", 0, cachedShadows())

        focusThrough(red, blue, away)

        assertEquals("one halo mask, whatever the picture's colour", 1, cachedShadows())
    }

    @Test
    fun aControlLightCachesOneMaskThroughItsFade() {
        val play = FocusRequester()
        val away = FocusRequester()
        compose.setContent {
            KeTheme {
                WatchedShadows {
                    Row {
                        ControlSurface({}, Modifier.focusRequester(play)) { Text("Play") }
                        ControlSurface({}, Modifier.focusRequester(away), style = ControlStyle.Rail) { Text("Away") }
                    }
                }
            }
        }

        focusThrough(play, away)

        assertEquals(1, cachedShadows())
    }

    @Test
    fun aFocusedThumbnailSpillsItsColourAndItsLightFollowsTheFocusFade() {
        val red = FocusRequester()
        val away = FocusRequester()
        compose.setContent {
            KeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Row(Modifier.padding(Room)) {
                        ContentSurface({}, light = Color.Red, Modifier.focusRequester(red).size(CardWidth, CardHeight)) {}
                        ControlSurface({}, Modifier.focusRequester(away), style = ControlStyle.Rail) { Text("Away") }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        // Under the card's middle, below its lifted, scaled bottom edge: inside the halo.
        val (x, y) = with(compose.density) { (Room + CardWidth / 2).roundToPx() to (Room + CardHeight + 12.dp).roundToPx() }
        fun light() = compose.onRoot().captureToImage().toPixelMap()[x, y]
        // As far above the card's lifted, scaled top edge as [y] is below its bottom one.
        val above = with(compose.density) { (Room - 12.dp).roundToPx() }

        val resting = light()
        compose.runOnIdle { red.requestFocus() }
        // The focus change lands and the fade starts within three frames; two more are mid-fade.
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val fading = light()
        repeat(FadeFrames) { compose.mainClock.advanceTimeByFrame() }
        val focused = light()
        val top = compose.onRoot().captureToImage().toPixelMap()[x, above]
        compose.runOnIdle { away.requestFocus() }
        repeat(FadeFrames) { compose.mainClock.advanceTimeByFrame() }
        val left = light()

        assertEquals(Color.Black, resting)
        assertTrue("the light is the picture's colour: $focused", focused.red > 0.1f && focused.green < 0.05f && focused.blue < 0.05f)
        // A halo only: no light pushed below the card, so above and below it are lit alike.
        assertEquals("lit alike above and below: $top vs $focused", focused.red, top.red, 0.04f)
        // Mid-fade the lift has nearly arrived, so a light that skipped the fade would be near full.
        assertTrue("the light fades in: $fading vs $focused", fading.red > 0f && fading.red < focused.red * 0.8f)
        assertEquals(Color.Black, left)
    }
}

private val Room = 100.dp
private val CardWidth = 200.dp
private val CardHeight = 112.dp

/** Frames that outlast the slowest focus fade (KeMotion.base) at 16 ms each. */
private const val FadeFrames = 30
