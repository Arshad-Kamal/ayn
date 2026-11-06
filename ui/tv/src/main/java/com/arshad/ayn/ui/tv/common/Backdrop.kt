package com.arshad.ayn.ui.tv.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import com.arshad.ayn.ui.tv.theme.KeColors

/**
 * The master's `.backdrop::after`: the ground drawn over a full-bleed picture from the left
 * (where the copy sits), from below (where rows rise) and a little from the top.
 */
internal fun Modifier.backdropScrims(): Modifier = drawWithCache {
    val across = Brush.horizontalGradient(
        0f to KeColors.Ground.copy(alpha = 0.96f),
        0.22f to KeColors.Ground.copy(alpha = 0.82f),
        0.48f to KeColors.Ground.copy(alpha = 0.35f),
        0.7f to KeColors.Ground.copy(alpha = 0f),
    )
    val up = Brush.verticalGradient(
        0.34f to KeColors.Ground.copy(alpha = 0f),
        0.48f to KeColors.Ground.copy(alpha = 0.2f),
        0.74f to KeColors.Ground.copy(alpha = 0.86f),
        0.96f to KeColors.Ground,
    )
    val down = Brush.verticalGradient(0f to KeColors.Ground.copy(alpha = 0.55f), 0.18f to KeColors.Ground.copy(alpha = 0f))
    onDrawWithContent {
        drawContent()
        drawRect(across)
        drawRect(up)
        drawRect(down)
    }
}
