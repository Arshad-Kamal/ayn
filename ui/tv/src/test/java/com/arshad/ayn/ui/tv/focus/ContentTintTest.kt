package com.arshad.ayn.ui.tv.focus

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.theme.KeColors
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
 * A control's text and icons take its ink when drawn: the same pixels tv-material's `Text` and
 * `Icon` draw, and a focus fade that redraws them without composing them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentTintTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tintedTextAndIconDrawWhatTvMaterialDraws() {
        val inks = listOf(KeColors.Ink, KeColors.OnInk.copy(alpha = 0.72f))
        val styled = buildAnnotatedString {
            append("12:04 ")
            withStyle(SpanStyle(color = KeColors.Danger)) { append("live") }
        }
        val long = "A playlist name far too long for its pill"
        compose.setContent {
            KeTheme {
                Column(Modifier.background(KeColors.Ground)) {
                    inks.forEachIndexed { i, ink ->
                        val tint = ColorProducer { ink }
                        val label = MaterialTheme.typography.labelLarge
                        Row {
                            Sample("text-tv-$i") { Text("Subscribe · 12", style = label, color = ink) }
                            Sample("text-tinted-$i") { TintedText("Subscribe · 12", style = label, tint = tint) }
                            Sample("font-tv-$i") { Text("[a-z]+", style = label, color = ink, fontFamily = FontFamily.Monospace) }
                            Sample("font-tinted-$i") { TintedText("[a-z]+", style = label, tint = tint, fontFamily = FontFamily.Monospace) }
                            Sample("styled-tv-$i") { Text(styled, style = label, color = ink) }
                            Sample("styled-tinted-$i") { TintedText(styled, style = label, tint = tint) }
                            Sample("cut-tv-$i") { Text(long, Modifier.width(60.dp), ink, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Sample("cut-tinted-$i") { TintedText(long, Modifier.width(60.dp), label, tint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Sample("icon-tv-$i") { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = ink) }
                            Sample("icon-tinted-$i") { TintedIcon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = tint) }
                        }
                    }
                }
            }
        }

        inks.indices.forEach { i ->
            listOf("text", "font", "styled", "cut", "icon").forEach { kind ->
                val expected = capture("$kind-tv-$i")
                val actual = capture("$kind-tinted-$i")
                assertEquals(expected.width, actual.width)
                assertEquals(expected.height, actual.height)
                var inked = 0
                for (x in 0 until expected.width) {
                    for (y in 0 until expected.height) {
                        assertEquals("$kind $i at $x,$y", expected[x, y], actual[x, y])
                        if (expected[x, y] != KeColors.Ground) inked++
                    }
                }
                assertTrue("$kind $i draws something", inked > 0)
            }
        }
    }

    @Test
    fun aControlsInkFadesAsItIsDrawnAndItsContentComposesOnce() {
        val control = FocusRequester()
        val away = FocusRequester()
        var content = 0
        var reader = 0
        compose.setContent {
            KeTheme {
                Row(Modifier.background(KeColors.Ground).padding(40.dp)) {
                    ControlSurface({}, Modifier.focusRequester(control).size(120.dp, 40.dp)) {
                        SideEffect { content++ }
                        InkProbe { reader++ }
                    }
                    ControlSurface({}, Modifier.focusRequester(away).padding(start = 40.dp).size(40.dp)) {}
                }
            }
        }
        compose.mainClock.autoAdvance = false

        val resting = Ink(probeInk(), textLight())
        compose.runOnIdle { control.requestFocus() }
        // The focus change lands and the 140 ms fade starts within three frames; two more are mid-fade.
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val fading = Ink(probeInk(), textLight())
        repeat(FadeFrames) { compose.mainClock.advanceTimeByFrame() }
        val focused = Ink(probeInk(), textLight())
        compose.runOnIdle { away.requestFocus() }
        repeat(FadeFrames) { compose.mainClock.advanceTimeByFrame() }
        val left = Ink(probeInk(), textLight())

        assertClose(KeColors.Ink, resting.icon)
        assertClose(KeColors.OnInk, focused.icon)
        assertTrue("icon mid-fade: $fading", fading.icon.red in (focused.icon.red + 0.05f)..(resting.icon.red - 0.05f))
        assertTrue("text mid-fade: $resting $fading $focused", fading.text in (focused.text + 0.05f)..(resting.text - 0.05f))
        assertClose(KeColors.Ink, left.icon)
        assertEquals("the content block composed once through both fades", 1, content)
        assertEquals("the ink's reader composed once through both fades", 1, reader)
    }

    @Test
    fun aRailItemsRestingInkFollowsItsProducerWhenDrawn() {
        val rest = mutableStateOf(Color.Red)
        var reader = 0
        compose.setContent {
            KeTheme {
                Box(Modifier.background(KeColors.Ground).padding(40.dp)) {
                    ControlSurface({}, Modifier.size(120.dp, 40.dp), style = ControlStyle.Rail, restContent = { rest.value }) {
                        InkProbe { reader++ }
                    }
                }
            }
        }
        assertClose(Color.Red, probeInk())

        rest.value = Color.Blue
        compose.waitForIdle()

        assertClose(Color.Blue, probeInk())
        assertEquals(1, reader)
    }

    /** A real settings row: its avatar's initials fade with the row's focus, not snap (P6 review). */
    @Test
    fun aSettingRowsMonogramFadesWithItsFocus() {
        val row = FocusRequester()
        compose.setContent {
            KeTheme {
                Box(Modifier.background(KeColors.Ground).padding(40.dp).width(400.dp)) {
                    SettingRow(
                        label = "Ayn Kids",
                        onClick = {},
                        modifier = Modifier.focusRequester(row),
                        lead = { Box(Modifier.size(26.dp).testTag(Avatar)) { AvatarPicture("Ayn Kids", url = null, monogramSize = 11.sp) } },
                    )
                }
            }
        }
        compose.mainClock.autoAdvance = false

        // The plate's reds lie between 0.27 and 0.46: a pixel above 0.8 is ink initials, one below
        // 0.12 dark initials. Mid-fade the initials are neither.
        val resting = monogramReds()
        compose.runOnIdle { row.requestFocus() }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val fading = monogramReds()
        repeat(FadeFrames) { compose.mainClock.advanceTimeByFrame() }
        val focused = monogramReds()

        assertTrue("ink initials at rest", resting.any { it > 0.8f })
        assertTrue("dark initials when focused", focused.any { it < 0.12f })
        assertTrue("mid-fade: ${fading.min()}..${fading.max()}", fading.all { it in 0.12f..0.8f })
    }

    private fun capture(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun probeInk(): Color {
        val centre = compose.onNodeWithTag(Probe, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        return compose.onRoot().captureToImage().toPixelMap()[centre.x.toInt(), centre.y.toInt()]
    }

    /** The brightest red in the probe's text over its ground (inside its edge, clear of the fill). */
    private fun textLight(): Float {
        val pixels = compose.onNodeWithTag(ProbeText, useUnmergedTree = true).captureToImage().toPixelMap()
        return (EdgeInset until pixels.width - EdgeInset).maxOf { x ->
            (EdgeInset until pixels.height - EdgeInset).maxOf { y -> pixels[x, y].red }
        }
    }

    /** The reds in the avatar's middle: its initials over the plate, clear of the row's fill. */
    private fun monogramReds(): List<Float> {
        val pixels = compose.onNodeWithTag(Avatar, useUnmergedTree = true).captureToImage().toPixelMap()
        return (pixels.width / 4 until pixels.width * 3 / 4).flatMap { x ->
            (pixels.height / 4 until pixels.height * 3 / 4).map { y -> pixels[x, y].red }
        }
    }

    private fun assertClose(expected: Color, actual: Color) {
        val close = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue)
            .all { kotlin.math.abs(it) < 2f / 255 }
        assertTrue("expected $expected, drew $actual", close)
    }

    private data class Ink(val icon: Color, val text: Float)
}

@Composable
private fun Sample(tag: String, content: @Composable () -> Unit) {
    Box(Modifier.testTag(tag).padding(4.dp)) { content() }
}

/** A solid square and a word in the control's ink, counting the compositions of the scope that reads it. */
@Composable
private fun InkProbe(onComposed: () -> Unit) {
    SideEffect(onComposed)
    val ink = contentTint()
    Row {
        TintedIcon(ColorPainter(Color.White), null, Modifier.size(20.dp).testTag(Probe), tint = ink)
        Box(Modifier.background(KeColors.Ground).testTag(ProbeText)) {
            TintedText("Ink", style = MaterialTheme.typography.labelLarge, tint = ink)
        }
    }
}

private const val Probe = "probe"
private const val ProbeText = "probe-text"
private const val Avatar = "avatar"
private const val EdgeInset = 3

/** Frames that outlast the slowest focus fade (KeMotion.base) at 16 ms each. */
private const val FadeFrames = 30
