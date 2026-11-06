package com.arshad.ayn.ui.tv.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.theme.KeColors

/**
 * A watch bar's place on its thumbnail (master `.prog`, px ÷ 2): a card's, or a list row's smaller
 * one; [badgeBottom] lifts the length badge clear of it.
 */
internal enum class WatchBarSize(val inset: Dp, val bottom: Dp, val height: Dp, val badgeBottom: Dp) {
    Card(inset = 7.dp, bottom = 5.dp, height = 2.5.dp, badgeBottom = 13.dp),
    Row(inset = 5.dp, bottom = 4.dp, height = 2.dp, badgeBottom = 11.dp),
}

private val TrackColor = Color.White.copy(alpha = 0.28f)

/** How far into a video the profile got: a track across the thumbnail's foot, ink to [fraction] (0..1). */
@Composable
internal fun WatchBar(fraction: Float, size: WatchBarSize, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .padding(start = size.inset, end = size.inset, bottom = size.bottom)
            .fillMaxWidth()
            .height(size.height)
            .drawBehind {
                val radius = CornerRadius(this.size.height / 2)
                drawRoundRect(TrackColor, cornerRadius = radius)
                val filled = this.size.width * fraction.coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(KeColors.Ink, size = Size(filled, this.size.height), cornerRadius = radius)
            },
    )
}
