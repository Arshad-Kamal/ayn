package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/** The shell's DeArrow faces (M7-D11); `null` (previews, tests without one) shows YouTube's everywhere. */
internal val LocalCardBranding = staticCompositionLocalOf<CardBranding?> { null }

/**
 * The face [video] shows: at once when it needs no wait, else `null` while DeArrow is waited for
 * (at most 1.5 s, M7-D8), then the face. A face arrived is kept for this showing; a change of
 * video or of the profile's DeArrow switches asks again. A DeArrow title the open profile's
 * filter hides shows as YouTube's ([LocalContentFilter], KP-D20).
 */
@Composable
internal fun rememberCardFace(video: VideoItem): CardFace? {
    val branding = LocalCardBranding.current ?: return CardFace.original(video)
    var face by remember(branding, video) { mutableStateOf(branding.known(video)) }
    LaunchedEffect(branding, video) {
        // The switches' first read is part of face()'s one wait (M7-D11): only a later change asks again.
        val changes = branding.toggles.filterNotNull().distinctUntilChanged().drop(1)
        merge(flowOf(Unit), changes).collectLatest {
            face = branding.known(video) ?: run {
                face = null
                branding.face(video)
            }
        }
    }
    return face?.checkedBy(LocalContentFilter.current, video)
}

/**
 * Starts DeArrow's lookups for a list's [videos] while it shows, so cards scrolled to later draw
 * their faces at once ([CardBranding.prefetch]: in order, about a page). [key] is what the videos
 * are read from; a new one starts again with the new list.
 */
@Composable
internal fun PrefetchFaces(key: Any, videos: () -> List<VideoItem>) {
    val branding = LocalCardBranding.current ?: return
    // Read once as the effect starts; a new key starts it again with the lambda of that moment.
    LaunchedEffect(branding, key) { branding.prefetch(videos()) }
}

/**
 * The alpha of what a face fills in: 1 when [ready] at first composition (nothing waited, nothing
 * fades); after a wait it fades in from 0 once [ready] (`b3-home` dearrow-wait: alpha only,
 * nothing moves).
 */
@Composable
internal fun rememberFaceArrival(ready: Boolean): State<Float> {
    val alpha = remember { Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(ready) {
        when {
            !ready -> alpha.snapTo(0f)
            alpha.value < 1f -> alpha.animateTo(1f, KeMotion.base())
        }
    }
    return alpha.asState()
}

/**
 * A thumbnail's fill: the face's picture, fading in with [arrival], or a skeleton under [sheen]
 * while the face is waited for (callers hold a sheen exactly while [face] is `null`).
 */
@Composable
internal fun BoxScope.FacePicture(face: CardFace?, arrival: State<Float>, sheen: State<Float>?) {
    if (face != null) {
        Picture(face.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
    } else if (sheen != null) {
        Box(Modifier.fillMaxSize().skeleton(sheen, RectangleShape))
    }
}

/** A title's two lines as skeleton bars (`b3-home` `.skh`: 2.44 em tall, bars at 94% and 58%). */
@Composable
internal fun TitleSkeleton(style: TextStyle, sheen: State<Float>, modifier: Modifier = Modifier) {
    val height = with(LocalDensity.current) { (style.fontSize * TitleSkeletonEms).toDp() }
    Column(modifier.fillMaxWidth().height(height), verticalArrangement = Arrangement.SpaceEvenly) {
        SkeletonLine(0.94f, top = 0.dp, sheen)
        SkeletonLine(0.58f, top = 0.dp, sheen)
    }
}

private const val TitleSkeletonEms = 2.44f
