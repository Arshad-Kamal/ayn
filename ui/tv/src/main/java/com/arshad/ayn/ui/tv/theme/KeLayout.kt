package com.arshad.ayn.ui.tv.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen geometry. The mockups draw a 1920×1080 canvas, which Android TV lays out as
 * 960×540 dp, so mockup px ÷ 2 = dp.
 */
internal object KeLayout {
    /** TVU-1 safe area: no text or control past these lines; backgrounds may bleed. */
    val SafeHorizontal: Dp = 58.dp
    val SafeVertical: Dp = 28.dp

    /** Where page content starts, clear of the collapsed rail's icons. */
    val ContentStart: Dp = 126.dp
}
