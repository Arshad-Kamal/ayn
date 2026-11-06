package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.theme.KeMotion

/**
 * Its content fades in when it appears, and again each time [key] changes (D8, alpha only). The
 * content stays composed across keys, so content that changes with the key (a grid's cards) is
 * updated in place rather than built again.
 */
@Composable
internal fun Appearing(modifier: Modifier = Modifier, key: Any? = Unit, content: @Composable () -> Unit) {
    // A new Animatable per key starts at 0 in the same frame the new content first draws.
    val alpha = remember(key) { Animatable(0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, KeMotion.base()) }
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) { content() }
}

// The master's `@keyframes rise`: up from 9 dp below its place.
internal val RiseFrom = 9.dp

/** 0 → 1 once, [delayMs] after it is first composed: the progress [rising] draws. */
@Composable
internal fun rememberRise(delayMs: Int = 0): State<Float> {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(KeMotion.MOVE_MS, delayMs, KeMotion.EaseOut)) }
    return rise.asState()
}

/** Rises into place and fades in with [progress] (0 below and clear, 1 in place; D8, transform + alpha). */
internal fun Modifier.rising(progress: State<Float>): Modifier = graphicsLayer {
    alpha = progress.value
    translationY = RiseFrom.toPx() * (1f - progress.value)
}
