package com.arshad.ayn.ui.tv.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion

internal val CardShape: Shape = RoundedCornerShape(7.dp)

/** A resting picture beside a focused one in the same row (master: "resting neighbours 62%"). */
private const val DimmedAlpha = 0.62f

/** How one content kind lifts; values are the master mockup's (px ÷ 2). */
private class ContentLook(
    val focusedScale: Float,
    val lift: Dp,
    /** Ground-coloured gap between the picture and its ink ring (0 = the rim touches it). */
    val ringGap: Dp,
    val ringOuter: Dp,
    val hairlineAlpha: Float,
)

// Thumbnail: a 3 px ink rim, a lift of 6 px, the picture's own colour as a halo (no light below it).
private val ThumbnailLook = ContentLook(
    focusedScale = 1.08f, lift = 3.dp, ringGap = 0.dp, ringOuter = 1.5.dp, hairlineAlpha = 0.06f,
)

// Avatar: a 3 px ground gap, then the ink ring out to 6 px, and a dark drop instead of colour.
private val AvatarLook = ContentLook(
    focusedScale = 1.1f, lift = 0.dp, ringGap = 1.5.dp, ringOuter = 3.dp, hairlineAlpha = 0.08f,
)
private val AvatarLights = listOf(FocusLight(Color.Black, alpha = 0.8f, blur = 25.dp, spread = (-7).dp, offsetY = 10.dp))

// Profile tile (`b3-profiles` picker): a 4 px ground gap, then the ink ring out to 8 px, the tint as two lights.
private val TileLook = ContentLook(
    focusedScale = 1.1f, lift = 0.dp, ringGap = 2.dp, ringOuter = 4.dp, hairlineAlpha = 0.06f,
)
private val Hairline = 0.5.dp

/** A thumbnail's rim at [progress] (0 resting, 1 focused): a faint hairline at rest, the ink rim when focused (a list row's thumbnail). */
internal fun DrawScope.drawThumbnailRim(shape: Shape, progress: Float) {
    drawContentRim(ThumbnailLook, shape, progress)
}

private fun DrawScope.drawContentRim(look: ContentLook, shape: Shape, progress: Float) {
    drawInflated(shape, Hairline, Color.White, look.hairlineAlpha * (1f - progress))
    drawInflated(shape, look.ringOuter, KeColors.Ink, progress)
    if (look.ringGap > 0.dp) drawInflated(shape, look.ringGap, KeColors.Ground, progress)
}

/**
 * Content focus family, thumbnails: focus lifts the picture, rims it in ink and spills [light]
 * (its own dominant colour) around it as a halo, so the focused item lights the room. [dimmed]
 * rests it at 62% while a neighbour in its row holds focus; it is read here, so only the surface
 * recomposes when it changes. Drawn here rather than through tv-material's
 * Border/Glow for the reason in [ControlSurface]; [content] is clipped to [shape]. Holding OK
 * calls [onLongClick].
 */
@Composable
internal fun ContentSurface(
    onClick: () -> Unit,
    light: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    dimmed: () -> Boolean = { false },
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val lights = remember(light) { listOf(FocusLight(light, alpha = 0.42f, blur = 80.dp, spread = 9.dp)) }
    LiftingSurface(onClick, ThumbnailLook, lights, shape, dimmed, modifier, onLongClick = onLongClick, content = content)
}

/** Content focus family, avatars: focus grows the picture inside a ground gap and an ink ring
 *  over a dark drop (an avatar spills no colour). */
@Composable
internal fun AvatarSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
) {
    LiftingSurface(onClick, AvatarLook, AvatarLights, CircleShape, dimmed, modifier, content = content)
}

/** Content focus family, profile tiles: focus grows the tile inside a ground gap and an ink ring
 *  and spills [light] (the profile's tint) around it, so the focused profile lights the room. */
@Composable
internal fun ProfileTileSurface(
    onClick: () -> Unit,
    light: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    dimmed: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
) {
    val lights = remember(light) {
        listOf(
            FocusLight(light, alpha = 0.4f, blur = 85.dp, spread = 10.dp),
            FocusLight(light, alpha = 1f, blur = 35.dp, spread = (-8).dp, offsetY = 15.dp),
        )
    }
    LiftingSurface(onClick, TileLook, lights, shape, dimmed, modifier, content = content)
}

@Composable
private fun LiftingSurface(
    onClick: () -> Unit,
    look: ContentLook,
    lights: List<FocusLight>,
    shape: Shape,
    dimmed: () -> Boolean,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "lift")
    val pictureAlpha = animateFloatAsState(if (dimmed() && !focused) DimmedAlpha else 1f, KeMotion.base(), label = "dim")
    val focusLights = rememberFocusLights(shape, lights)

    NeutralSurface(
        onClick = onClick,
        shape = shape,
        onLongClick = onLongClick,
        modifier = modifier
            .graphicsLayer {
                val scale = 1f + (look.focusedScale - 1f) * progress.value
                scaleX = scale
                scaleY = scale
                translationY = -look.lift.toPx() * progress.value
            }
            .focusLights(focusLights, progress)
            .drawBehind { drawContentRim(look, shape, progress.value) }
            .onFocusChanged { focused = it.isFocused },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = pictureAlpha.value }
                .clip(shape)
                .background(KeColors.Surface2),
            content = content,
        )
    }
}
