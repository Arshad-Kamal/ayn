package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.arshad.ayn.ui.tv.focus.releasesStayWith
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion

/** `.dim` under a sheet: oklch 8% at 62%. */
internal val DimColor = Color(0xFF060403).copy(alpha = 0.62f)

private val SheetSlide = 40.dp

/**
 * A page with what can show over it: [sheet] slides in at the right edge over the dimmed page
 * (the rail dims, [onRailShade]), [cover] covers the shell on the ground colour (the rail hides).
 * While either shows nothing on the page takes focus, and a key's release whose press came
 * before the overlay changed is dropped, so the OK that opened one (a held OK opening a menu)
 * doesn't also click what it focuses first. [sheetKey] and [coverKey] tell overlays apart from
 * what changes inside one (a name being typed keeps its sheet). Focus returning to the page as
 * one closes is the caller's (`FocusOnClose`).
 */
@Composable
internal fun <S : Any, C : Any> Overlays(
    sheet: S?,
    cover: C?,
    onRailShade: (RailShade) -> Unit,
    sheetContent: @Composable (S) -> Unit,
    coverContent: @Composable (C) -> Unit,
    modifier: Modifier = Modifier,
    sheetKey: (S) -> Any = { it },
    coverKey: (C) -> Any = { it },
    page: @Composable () -> Unit,
) {
    val shade = when {
        cover != null -> RailShade.Hidden
        sheet != null -> RailShade.Dimmed
        else -> RailShade.Lit
    }
    val currentOnRailShade by rememberUpdatedState(onRailShade)
    LaunchedEffect(shade) { currentOnRailShade(shade) }
    DisposableEffect(Unit) { onDispose { currentOnRailShade(RailShade.Lit) } }
    val overlaid = shade != RailShade.Lit
    val shownSheet = sheet?.takeIf { cover == null }

    Box(modifier.fillMaxSize().releasesStayWith(sheet?.let(sheetKey) to cover?.let(coverKey))) {
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { onEnter = { if (overlaid) cancelFocusChange() } }
                .focusGroup(),
        ) {
            page()
        }
        AnimatedVisibility(shownSheet != null, enter = fadeIn(KeMotion.move()), exit = fadeOut(KeMotion.move())) {
            Box(Modifier.fillMaxSize().background(DimColor))
        }
        val slide = with(LocalDensity.current) { SheetSlide.roundToPx() }
        AnimatedContent(
            targetState = rememberShowing(shownSheet, sheetKey),
            modifier = Modifier.fillMaxSize(),
            contentKey = { it?.key },
            transitionSpec = {
                (fadeIn(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) + slideInHorizontally(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) { slide }) togetherWith
                    (fadeOut(KeMotion.fast()) + slideOutHorizontally(KeMotion.fast()) { slide })
            },
            label = "sheet",
        ) { showing ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                if (showing != null) sheetContent(showing.shown)
            }
        }
        AnimatedContent(
            targetState = rememberShowing(cover, coverKey),
            modifier = Modifier.fillMaxSize(),
            contentKey = { it?.key },
            transitionSpec = { fadeIn(KeMotion.base()) togetherWith fadeOut(KeMotion.base()) },
            label = "cover",
        ) { showing ->
            if (showing != null) {
                Box(Modifier.fillMaxSize().background(KeColors.Ground)) { coverContent(showing.shown) }
            }
        }
    }
}

/**
 * One showing of an overlay, for its `AnimatedContent`'s `contentKey`: [key] is new each time
 * what shows changes identity. Keying on the screen itself instead would recompose the overlay
 * from scratch on every change inside it (each typed character).
 */
internal data class Showing<T>(val shown: T, val key: Any)

/**
 * [shown] keyed by showing: an overlay shown again while its last showing still fades out (a
 * refused file picked again) is new content, composed fresh so it takes its initial focus again.
 * Keyed by [identity] alone, AnimatedContent would bring back the fading content, whose one-time
 * initial focus already ran, and focus would stay on the page beneath. Changes inside one
 * showing (a name being typed) keep its key.
 */
@Composable
internal fun <T : Any> rememberShowing(shown: T?, identity: (T) -> Any): Showing<T>? {
    val key = remember(shown?.let(identity)) { Any() }
    return shown?.let { Showing(it, key) }
}

/**
 * What showed before [shown] last changed [identity]: a newly shown sheet reads it to open on the
 * row that leads back where the person came from (BACK from a confirm lands on Delete).
 */
@Composable
internal fun <T> rememberCameFrom(shown: T, identity: (T) -> Any?): T? {
    val trail = remember { Trail<T>() }
    val from = remember(identity(shown)) { trail.shown }
    SideEffect { trail.shown = shown }
    return from
}

// Written after each composition, read only when the identity changes.
private class Trail<T> {
    var shown: T? = null
}
