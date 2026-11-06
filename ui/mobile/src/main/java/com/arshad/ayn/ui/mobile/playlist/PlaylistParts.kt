package com.arshad.ayn.ui.mobile.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

private val CoverShape = RoundedCornerShape(16.dp)
private val AlbumSize = 232.dp

// The cover decoded this small and stretched over the head reads as a blur (no blur modifier on minSdk 30).
private const val BackdropDecode = 24
private const val BackdropScale = 1.35f
private const val BackdropAlpha = 0.5f

/** The item key of a playlist page's header; the rows follow it. */
internal const val HeadKey = "head"

/**
 * A playlist's cover over the bars of the videos behind it (`.pstack`): [content] in a [shape]d
 * box at [aspect], one bar peeking above it ([inset] in from each side).
 */
@Composable
internal fun CoverStack(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    aspect: Float = 16f / 9f,
    inset: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.padding(top = 7.dp)) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-7).dp)
                .padding(horizontal = inset)
                .fillMaxWidth()
                .height(14.dp)
                .background(AynColors.Surface3, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)),
        )
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(shape).background(AynColors.Surface2), content = content)
    }
}

/** An empty playlist's cover: the list mark in a well. */
@Composable
internal fun BoxScope.EmptyCover(iconSize: Dp = 34.dp) {
    Box(Modifier.matchParentSize().background(AynColors.Surface1))
    Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.align(Alignment.Center).size(iconSize))
}

/**
 * A playlist page's head (`.phead`): the cover (an album's square, [square]; none: the list mark)
 * over its colour blurred behind the top, the [kicker], the [title], the [meta] line, then [actions].
 * [top] clears the bar.
 */
@Composable
internal fun PlaylistHead(
    coverUrl: String?,
    kicker: String,
    title: String,
    top: Dp,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth()) {
        Backdrop(coverUrl, Modifier.matchParentSize())
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = top)) {
            CoverStack(
                modifier = if (square) Modifier.width(AlbumSize).align(Alignment.CenterHorizontally) else Modifier.padding(horizontal = 6.dp),
                shape = CoverShape,
                aspect = if (square) 1f else 16f / 9f,
                inset = 14.dp,
            ) {
                if (coverUrl != null) Picture(coverUrl) else EmptyCover()
            }
            Text(kicker, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 18.dp))
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
            Box(Modifier.padding(top = 10.dp)) { meta() }
            if (actions != null) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** A head's line ("8 videos · 3 h 39 min · Updated today"). */
@Composable
internal fun HeadMeta(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = AynColors.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** The cover full-bleed behind the head, soft and dim, fading into the ground; nothing without one. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    if (url == null) return
    val context = LocalContext.current
    Box(modifier) {
        AsyncImage(
            model = remember(url, context) {
                ImageRequest.Builder(context).data(url).size(BackdropDecode).precision(Precision.EXACT).crossfade(AynMotion.BASE_MS).build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = BackdropAlpha,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = BackdropScale
                scaleY = BackdropScale
            },
        )
        Box(Modifier.fillMaxSize().background(BackdropFade))
    }
}

private val BackdropFade = Brush.verticalGradient(
    0f to AynColors.Ground.copy(alpha = 0.25f),
    0.55f to AynColors.Ground.copy(alpha = 0.7f),
    1f to AynColors.Ground,
)

/** Whether [list]'s head has scrolled up under the bar (ending at [barBottom]): the bar then turns solid with the title. */
@Composable
internal fun rememberHeadPassed(list: LazyListState, barBottom: Dp): State<Boolean> {
    val barPx = with(LocalDensity.current) { barBottom.roundToPx() }
    return remember(list, barPx) {
        derivedStateOf {
            val head = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HeadKey }
            if (head != null) head.offset + head.size < barPx else list.firstVisibleItemIndex > 0
        }
    }
}
