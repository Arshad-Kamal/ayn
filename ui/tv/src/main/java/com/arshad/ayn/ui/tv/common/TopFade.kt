package com.arshad.ayn.ui.tv.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * The content fades in over its top [length] (the mockups' `mask-image` top edge), so a row
 * scrolled under the page's head dissolves instead of being cut. The page behind shows through,
 * room light included, which a ground-coloured scrim would hide.
 */
internal fun Modifier.topFade(length: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val band = Size(size.width, length.toPx())
        val fade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = band.height)
        onDrawWithContent {
            drawContent()
            // Below the band the mask is opaque: DstIn there would change nothing.
            drawRect(fade, size = band, blendMode = BlendMode.DstIn)
        }
    }

/** The content fades out over its bottom [length], as [topFade] does at the top. */
internal fun Modifier.bottomFade(length: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val band = Size(size.width, length.toPx())
        val top = size.height - band.height
        val fade = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = top, endY = size.height)
        onDrawWithContent {
            drawContent()
            drawRect(fade, topLeft = Offset(0f, top), size = band, blendMode = BlendMode.DstIn)
        }
    }
