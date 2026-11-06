package com.arshad.ayn.ui.tv.focus

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle

/**
 * The enclosing [ControlSurface]'s content colour, which animates with its focus. It is read when
 * drawn, so a focus fade redraws the control's text and icons without recomposing them.
 */
internal val LocalControlTint = compositionLocalOf<ColorProducer?> { null }

/** The content colour where this is composed: the enclosing control's, else [LocalContentColor]. */
@Composable
internal fun contentTint(): ColorProducer {
    LocalControlTint.current?.let { return it }
    val color = LocalContentColor.current
    return remember(color) { ColorProducer { color } }
}

/** [from] fading to [to] as [progress] goes 0..1, read when drawn. */
internal fun fadeTint(from: Color, to: Color, progress: State<Float>): ColorProducer =
    ColorProducer { lerp(from, to, progress.value) }

/** [fadeTint], remembered. */
@Composable
internal fun rememberFadeTint(from: Color, to: Color, progress: State<Float>): ColorProducer =
    remember(from, to, progress) { fadeTint(from, to, progress) }

/** This tint at [alpha] (a fixed alpha, as `Color.copy` sets it). */
internal fun ColorProducer.atAlpha(alpha: Float): ColorProducer = ColorProducer { this().copy(alpha = alpha) }

/**
 * tv-material `Text` drawn in [tint], read when drawn: the same layout, offscreen layer and pixels,
 * without recomposing as the colour animates. [fontFamily] merges into [style] as `Text`'s does.
 */
@Composable
internal fun TintedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    tint: ColorProducer = contentTint(),
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        style = style.merge(fontFamily = fontFamily),
        overflow = overflow,
        maxLines = maxLines,
        color = tint,
    )
}

/** [TintedText] for styled text (its spans keep their own colours). */
@Composable
internal fun TintedText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    tint: ColorProducer = contentTint(),
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        style = style,
        maxLines = maxLines,
        color = tint,
    )
}

/** tv-material `Icon` tinted with [tint], read when drawn (same size and pixels). */
@Composable
internal fun TintedIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: ColorProducer = contentTint(),
) {
    val tinted = remember(painter, tint) { TintedPainter(painter, tint) }
    Icon(tinted, contentDescription, modifier, tint = Color.Unspecified)
}

/** Draws [painter] tinted with [tint]'s colour at draw time; the filter is rebuilt only when it changes. */
private class TintedPainter(private val painter: Painter, private val tint: ColorProducer) : Painter() {
    private var color = Color.Unspecified
    private var filter: ColorFilter? = null

    override val intrinsicSize get() = painter.intrinsicSize

    override fun DrawScope.onDraw() {
        val now = tint()
        if (now != color) {
            color = now
            filter = ColorFilter.tint(now)
        }
        with(painter) { draw(size, colorFilter = filter) }
    }
}
