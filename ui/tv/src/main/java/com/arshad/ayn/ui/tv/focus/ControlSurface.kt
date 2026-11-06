package com.arshad.ayn.ui.tv.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion

/** How a control rests and how far focus lifts it; focus fills it with [focusFill] (ink, or
 *  the danger red on a destructive control). */
internal data class ControlStyle(
    val restFill: Color,
    val restContent: Color,
    val restKeyline: Color? = null,
    val focusedScale: Float = 1.07f,
    /** Ring + warm light around the focused control (buttons); rail items only fill. */
    val ring: Boolean = true,
    val focusFill: Color = KeColors.Ink,
    val focusContent: Color = KeColors.OnInk,
    /** Where focus grows it from: rows at a safe line grow away from it. */
    val growFrom: TransformOrigin = TransformOrigin.Center,
) {
    companion object {
        /** Only the focused control is solid ink, so a resting primary is a keyline pill. */
        val Primary = ControlStyle(
            restFill = KeColors.Ink.copy(alpha = 0.16f),
            restContent = KeColors.Ink,
            restKeyline = KeColors.Ink.copy(alpha = 0.6f),
        )
        val Secondary = ControlStyle(restFill = KeColors.ControlRest, restContent = KeColors.Ink)

        /** Small pills on a page (search suggestions, Edit search): a faint well, no ring. */
        val Pill = ControlStyle(
            restFill = KeColors.Well,
            restContent = KeColors.Ink2,
            focusedScale = 1.06f,
            ring = false,
        )

        /** A destructive action (`.btn.danger`): it rests as a secondary and fills red when focused. */
        val Danger = Secondary.copy(focusFill = KeColors.Danger, focusContent = KeColors.OnDanger)

        /** A settings row (`.srow`): no fill at rest, a slight lift from its right end (at the
         *  safe line), no ring. */
        val Row = ControlStyle(
            // Transparent ink, not Color.Transparent (black): the fill lerps toward ink.
            restFill = KeColors.Ink.copy(alpha = 0f),
            restContent = KeColors.Ink,
            focusedScale = 1.015f,
            ring = false,
            growFrom = TransformOrigin(1f, 0.5f),
        )

        val DangerRow = Row.copy(restFill = KeColors.Danger.copy(alpha = 0f), focusFill = KeColors.Danger, focusContent = KeColors.OnDanger)

        /** A rail item; one whose resting ink changes passes it to [ControlSurface] as `restContent`. */
        val Rail = ControlStyle(
            // Transparent ink, not Color.Transparent (black): the fill lerps toward ink.
            restFill = KeColors.Ink.copy(alpha = 0f),
            restContent = KeColors.Ink,
            focusedScale = 1.04f,
            ring = false,
        )
    }
}

// Mockup: a ground-coloured gap, then an ink ring, then warm light below (px ÷ 2).
private val RingGap = 2.dp
private val RingOuter = 3.5.dp
private val KeylineWidth = 1.dp
private fun controlLight(color: Color) = FocusLight(color, alpha = 0.9f, blur = 25.dp, spread = (-7).dp, offsetY = 11.dp)

/**
 * Control focus family (buttons, rail items, chips, setting rows): focus fills the control
 * with ink, its content turns to the ground colour, and it grows a little. tv-material 1.1.0
 * animates only its own scale and snaps colours and borders, so [NeutralSurface] switches its
 * visuals off and this draws the family, animated per compose-tv D8. [content] runs in the
 * Surface's box, which does not stretch it: a fixed-size control fills it with `fillMaxSize()`.
 * [light] is the warm white under a focused control, or the picture's colour on a control that
 * sits on one (the Home hero's Play). [restContent], when given, is the resting ink in place of
 * the style's (a rail item's, which changes as the rail opens).
 *
 * The content's ink fades with focus at draw time: its text and icons draw through [TintedText]
 * and [TintedIcon] ([LocalControlTint]), and anything else in it that takes the ink must too:
 * [LocalContentColor] only snaps between the style's rest and focus inks (never [restContent]).
 */
@Composable
internal fun ControlSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.Secondary,
    shape: Shape = CircleShape,
    light: Color = KeColors.ControlLight,
    restContent: ColorProducer? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "fill")
    val tint = remember(style.restContent, style.focusContent, restContent, progress) {
        ColorProducer { lerp(restContent?.invoke() ?: style.restContent, style.focusContent, progress.value) }
    }

    NeutralSurface(
        onClick = onClick,
        shape = shape,
        modifier = modifier
            .controlLook(style, shape, light, progress)
            .onFocusChanged { focused = it.isFocused },
    ) {
        CompositionLocalProvider(
            LocalControlTint provides tint,
            LocalContentColor provides if (focused) style.focusContent else style.restContent,
        ) {
            content()
        }
    }
}

/**
 * A control drawn focused while focus stays elsewhere: the action a key runs from where focus is
 * (the player's skip prompt and skip note, whose OK the hidden chrome routes). It looks like a
 * focused [ControlSurface] and takes neither focus nor clicks.
 */
@Composable
internal fun DrawnFocusedControl(
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.Secondary,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val focused = remember { mutableFloatStateOf(1f) }
    Box(modifier.controlLook(style, shape, KeColors.ControlLight, focused)) {
        val tint = remember(style.focusContent) { ColorProducer { style.focusContent } }
        CompositionLocalProvider(LocalControlTint provides tint, LocalContentColor provides style.focusContent) {
            content()
        }
    }
}

/** The control family's look at [progress] (0 resting, 1 focused): scale, light, ring and fill. */
@Composable
private fun Modifier.controlLook(style: ControlStyle, shape: Shape, light: Color, progress: State<Float>): Modifier {
    val lights = remember(style.ring, light) { if (style.ring) listOf(controlLight(light)) else emptyList() }
    val focusLights = rememberFocusLights(shape, lights)
    return graphicsLayer {
        transformOrigin = style.growFrom
        val scale = 1f + (style.focusedScale - 1f) * progress.value
        scaleX = scale
        scaleY = scale
    }
        .focusLights(focusLights, progress)
        .drawBehind {
            val p = progress.value
            if (style.ring) {
                drawInflated(shape, RingOuter, style.focusFill, p)
                drawInflated(shape, RingGap, KeColors.Ground.copy(alpha = 0.9f), p)
            }
            drawInflated(shape, 0.dp, lerp(style.restFill, style.focusFill, p), 1f)
            style.restKeyline?.let { drawKeyline(shape, KeylineWidth, it, 1f - p) }
        }
}
