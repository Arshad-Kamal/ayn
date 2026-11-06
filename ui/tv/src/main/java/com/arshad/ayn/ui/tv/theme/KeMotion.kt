package com.arshad.ayn.ui.tv.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * Motion tokens (compose-tv D8: every state change animates, 100–200 ms, transform and alpha
 * only). Fast = colour and fill changes, Base = focus lift, Move = position and reveal;
 * [Ease] for state changes, [EaseOut] for entrances that rise in (sheets, toasts, statements).
 */
internal object KeMotion {
    const val FAST_MS: Int = 140
    const val BASE_MS: Int = 190
    const val MOVE_MS: Int = 200

    val Ease: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun <T> fast(): TweenSpec<T> = tween(FAST_MS, easing = Ease)
    fun <T> base(): TweenSpec<T> = tween(BASE_MS, easing = Ease)
    fun <T> move(): TweenSpec<T> = tween(MOVE_MS, easing = Ease)
}
