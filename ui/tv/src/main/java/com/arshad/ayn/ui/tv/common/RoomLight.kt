package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlinx.coroutines.launch

/** How strongly a focused card's picture lights the room. */
private const val CardLightAlpha = 0.34f

/** Where the light sits across the screen (0..1) before any card has been focused. */
private const val RestingLightX = 0.3f

/**
 * The card lighting a screen's room: its light (read live: it may be sampled after focus) and
 * its centre across the screen in root px. Cards report to [onCardFocused].
 */
@Stable
internal class FocusedCardLight {
    var light: State<Color> by mutableStateOf(mutableStateOf(NeutralLight))
        private set
    var centerX: Float by mutableFloatStateOf(Float.NaN)
        private set

    fun onCardFocused(light: State<Color>, centerX: Float) {
        this.light = light
        this.centerX = centerX
    }
}

/**
 * The room lit by [card]'s picture while [lit] (a card of the screen holds focus), easing
 * between cards (D8). Childless, so the light's per-frame redraw doesn't re-record the page
 * drawn over it. The card's centre is followed outside composition: a scrolling row reports it
 * every frame, and reading it here would recompose the light each frame.
 */
@Composable
internal fun CardRoomLight(card: FocusedCardLight, lit: Boolean, modifier: Modifier = Modifier) {
    // Read by the effect, so a width change moves the light without restarting it mid-tween.
    val rootWidth by rememberUpdatedState(LocalWindowInfo.current.containerSize.width.toFloat())
    val on = animateFloatAsState(if (lit) 1f else 0f, KeMotion.move(), label = "lit")
    val color = animateColorAsState(card.light.value, KeMotion.move(), label = "litColor")
    fun lightX(centerX: Float) = if (centerX.isNaN()) RestingLightX else (centerX / rootWidth).coerceIn(0f, 1f)
    val x = remember(card) { Animatable(Snapshot.withoutReadObservation { lightX(card.centerX) }) }
    // As animateFloatAsState does: a launch per target lets the next animateTo take over from the
    // running one's frame time; cancelling first (collectLatest) restarts each tween at time 0, so
    // a centre reported every frame would hold the light still until the row stops.
    LaunchedEffect(card) {
        snapshotFlow { lightX(card.centerX) }.collect { target ->
            if (target != x.targetValue) launch { x.animateTo(target, KeMotion.move()) }
        }
    }
    Box(modifier.fillMaxSize().roomLight({ color.value }, alpha = { CardLightAlpha * on.value }, x = { x.value }))
}

/**
 * The room's light: a wide, low pool of [color] rising from below the screen at [x] (0..1),
 * the mockups' radial `::before` (an ellipse 55% × 216 dp, fading out at 72%). Read at draw time.
 */
internal fun Modifier.roomLight(color: () -> Color, alpha: () -> Float, x: () -> Float): Modifier = drawBehind {
    val a = alpha()
    if (a > 0f) drawRoomLight(color().copy(alpha = a), x())
}

/** The room with no picture in focus: a faint warm white low at the left (statements, controls). */
internal fun Modifier.restingRoomLight(): Modifier = drawBehind {
    drawRoomLight(KeColors.ControlLight.copy(alpha = 0.14f), x = 0.22f)
}

private fun DrawScope.drawRoomLight(color: Color, x: Float) {
    val radiusX = size.width * 0.55f
    val radiusY = 216.dp.toPx()
    val center = Offset(size.width * x, size.height + 82.dp.toPx())
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(0f to color, 0.72f to color.copy(alpha = 0f), center = center, radius = radiusX),
            radius = radiusX,
            center = center,
        )
    }
}
