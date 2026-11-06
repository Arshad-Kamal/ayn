package com.arshad.ayn.ui.tv.theme

import androidx.compose.ui.graphics.Color

/**
 * Cinema colour tokens, converted from the master mockup's oklch values
 * (the `cinema-design-system` mockup) to sRGB. One light source: the picture, so the
 * ground is a warm near-black and every text tone is the one ink at a lower alpha.
 */
internal object KeColors {
    val Ground = Color(0xFF0D0907)
    val Ink = Color(0xFFF6F3EF)
    val Ink2 = Ink.copy(alpha = 0.72f)
    val Ink3 = Ink.copy(alpha = 0.52f)
    val Line = Ink.copy(alpha = 0.14f)
    val OnInk = Color(0xFF100C09)
    val Surface1 = Color(0xFF16120F)
    val Surface2 = Color(0xFF201C18)
    val Well = Color.White.copy(alpha = 0.07f)
    val RailScrim = Color(0xFF080504)
    val Live = Color(0xFFEE3533)
    val Warn = Color(0xFFEEB154)
    val Danger = Color(0xFFF45249)

    /** Text on a danger fill (`oklch(99% 0 0)`). */
    val OnDanger = Color(0xFFFCFCFC)

    /** Resting secondary button fill and the warm-white light under a focused control. */
    val ControlRest = Color(0xFFF5F1EA).copy(alpha = 0.12f)
    val ControlLight = Color(red = 236, green = 228, blue = 214)

    /** The length badge's plate over a thumbnail. */
    val BadgeScrim = Color(0xFF040302).copy(alpha = 0.78f)

    /** Skeleton blocks and their passing sheen; the warm pool where a loading picture lands. */
    val Skeleton = Color.White.copy(alpha = 0.06f)
    val Sheen = Color.White.copy(alpha = 0.1f)
    val Warm = Color(0xFF2D2118)
}
