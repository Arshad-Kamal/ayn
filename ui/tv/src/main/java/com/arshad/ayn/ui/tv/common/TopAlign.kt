package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.arshad.ayn.ui.tv.theme.KeMotion

/**
 * Brings a focused card's row to [top] px into a grid, wherever it is (the mockups' scroll
 * anchored at the top); the grid's own scroll bounds stop the first and last rows short. TV's
 * default spec would pivot instead. Known gap: a
 * lower row settles ~7 dp below [top] (the request carries the focused, scaled and lifted
 * thumbnail's bounds); checked on the device pass.
 */
internal class TopAlign(private val top: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = KeMotion.move()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - top
}

// The one opt-in for the experimental BringIntoViewSpec local (TVF-5 practice: one wrapper), for rows,
// grids and Home's list.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideBringIntoView(spec: BringIntoViewSpec, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
