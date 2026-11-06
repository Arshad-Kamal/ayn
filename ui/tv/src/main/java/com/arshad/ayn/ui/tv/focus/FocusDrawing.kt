package com.arshad.ayn.ui.tv.focus

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface

/**
 * A tv-material clickable Surface with its own focus visuals switched off: it keeps focus,
 * D-pad select and semantics (TVF-1), while the focus families draw the look. [onLongClick]
 * fires when OK is held (on the key's first repeat), and that press's release then doesn't click.
 * The hold is handled here, not by tv-material: its long-click flag clears only on a release it
 * receives, so a release that went to what the hold opened left it set and swallowed the next OK.
 */
@Composable
internal fun NeutralSurface(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val hold = remember { Hold() }
    val holding = if (onLongClick == null) {
        Modifier
    } else {
        Modifier
            .onFocusChanged { if (!it.isFocused) hold.longClicked = false }
            .onPreviewKeyEvent { event -> hold.onKey(event) { currentOnLongClick?.invoke() } }
    }
    Surface(
        onClick = onClick,
        modifier = modifier.then(holding),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.Unspecified,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Color.Unspecified,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = Color.Unspecified,
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        content = content,
    )
}

/** The remote's OK, and a keyboard's Enter. */
internal val EnterKeys: Set<Key> = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

// Written and read by key and focus callbacks only: no composition reads it.
private class Hold {
    var longClicked: Boolean = false

    /** OK's repeats stay here (the first is the long click); after one, its release doesn't click. */
    fun onKey(event: KeyEvent, onLongClick: () -> Unit): Boolean {
        if (event.key !in EnterKeys) return false
        val repeat = event.nativeKeyEvent.repeatCount
        return when (event.type) {
            KeyEventType.KeyDown if repeat == 0 -> {
                longClicked = false
                false
            }
            KeyEventType.KeyDown -> {
                if (repeat == 1) {
                    longClicked = true
                    onLongClick()
                }
                true
            }
            KeyEventType.KeyUp -> longClicked.also { longClicked = false }
            else -> false
        }
    }
}

/** A soft light under a focused element, in CSS box-shadow terms (offset, blur, spread). */
internal data class FocusLight(
    val color: Color,
    val alpha: Float,
    val blur: Dp,
    val spread: Dp = 0.dp,
    val offsetY: Dp = 0.dp,
)

/** One light's blurred mask with the colour and strength it is drawn in. */
private class LightLayer(val mask: Painter, val tint: ColorFilter, val alpha: Float)

/**
 * [lights] around [shape], ready to draw at any focus progress. The platform blurs each shadow's
 * mask on the CPU and caches it, keyed by the whole `Shadow` (alpha and colour included), for as
 * long as the window is shown (Compose UI 1.12.1); so a light's `Shadow`
 * carries only its geometry, and its colour (a tint) and strength (the painter's alpha) are
 * applied when drawing: one mask per shape, size and geometry, whatever the picture's colour or
 * the animation frame.
 */
internal class FocusLights(shadows: ShadowContext, shape: Shape, lights: List<FocusLight>) {
    private val layers = lights.map { light ->
        val geometry = Shadow(radius = light.blur, spread = light.spread, offset = DpOffset(0.dp, light.offsetY))
        LightLayer(shadows.createDropShadowPainter(shape, geometry), ColorFilter.tint(light.color), light.alpha)
    }

    fun draw(scope: DrawScope, progress: Float) {
        if (progress <= 0f) return
        for (i in layers.indices) {
            val layer = layers[i]
            with(layer.mask) { scope.draw(scope.size, alpha = layer.alpha * progress, colorFilter = layer.tint) }
        }
    }
}

/** [FocusLights] built once per shape and light list. */
@Composable
internal fun rememberFocusLights(shape: Shape, lights: List<FocusLight>): FocusLights {
    val shadows = LocalGraphicsContext.current.shadowContext
    return remember(shadows, shape, lights) { FocusLights(shadows, shape, lights) }
}

/** Draws [lights] at [progress] (0 = resting, 1 = focused); read at draw time, no recomposition. */
internal fun Modifier.focusLights(lights: FocusLights, progress: State<Float>): Modifier =
    drawBehind { lights.draw(this, progress.value) }

/** Fills [shape] grown by [by] on every side (a ring when something is drawn inside it). */
internal fun DrawScope.drawInflated(shape: Shape, by: Dp, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val d = by.toPx()
    val outline = shape.createOutline(Size(size.width + 2 * d, size.height + 2 * d), layoutDirection, this)
    translate(-d, -d) { drawOutline(outline, color, alpha) }
}

/** Strokes [shape]'s edge from the inside. */
internal fun DrawScope.drawKeyline(shape: Shape, width: Dp, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val w = width.toPx()
    val outline = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
    translate(w / 2, w / 2) { drawOutline(outline, color, alpha, style = Stroke(w)) }
}
