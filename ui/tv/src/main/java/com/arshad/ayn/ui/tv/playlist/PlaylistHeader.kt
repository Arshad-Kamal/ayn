package com.arshad.ayn.ui.tv.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.backdropScrims
import com.arshad.ayn.ui.tv.common.drawStack
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.weightSpan

// b8p-playlist header geometry (px ÷ 2): a 320 dp column at the content start, the page 48 dp
// down; the cover (300 wide at 16:9, an album's 200 square) sits 11 dp under two 15 dp bars.
internal val PageTop = 48.dp
internal val HeaderColumnWidth = 320.dp
internal val ColumnGap = 40.dp
private val CoverWidth = 300.dp
private val AlbumCoverSize = 200.dp
private val CoverTop = 11.dp
private val CoverShape = RoundedCornerShape(10.dp)
private val EmptyCoverIcon = 48.dp
private val BarHeight = 15.dp
private val FrontBarTop = 4.5.dp
private val KickerTop = 20.dp
private val TitleTop = 4.dp
private val MetaTop = 10.dp
private val ActionsTop = 17.dp
private val OwnerAvatarSize = 22.dp
private val OwnerAvatarGap = 7.dp

// b8p `.plbg img`: blur(40px), grown 30% about (88%, 72%) at half strength. Modifier.blur needs
// API 31 (the Shield is 30), so a decode this many px tall, upscaled, is the blur.
private const val BackdropDecodeHeight = 24
private const val BackdropScale = 1.3f
private const val BackdropAlpha = 0.5f

/**
 * A playlist's header: its cover over the bars of its other videos (an album's square art
 * stays square; none, the list icon in a well), "Playlist", the [title], [meta] (given its
 * place) and [action]. [onCoverLoaded] once the cover lands.
 */
@Composable
internal fun PlaylistHeader(
    title: String,
    coverUrl: String?,
    onCoverLoaded: () -> Unit,
    meta: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    isAlbum: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(HeaderColumnWidth)) {
        Cover(coverUrl, isAlbum, onCoverLoaded)
        Text(
            text = "Playlist",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            color = KeColors.Ink3,
            modifier = Modifier.padding(top = KickerTop),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 38.sp, letterSpacing = (-0.01).em),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = TitleTop),
        )
        meta(Modifier.padding(top = MetaTop))
        action?.let { Box(Modifier.padding(top = ActionsTop)) { it() } }
    }
}

/** A YouTube playlist's header: the owner line (none for an album) under the title. [countText]
 *  is the video count as the page states it. */
@Composable
internal fun YouTubePlaylistHeader(
    details: PlaylistDetails,
    isAlbum: Boolean,
    countText: String?,
    onCoverLoaded: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    PlaylistHeader(
        title = details.title,
        coverUrl = details.thumbnailUrl,
        onCoverLoaded = onCoverLoaded,
        isAlbum = isAlbum,
        modifier = modifier,
        action = action,
        meta = { place ->
            OwnerLine(
                owner = details.uploaderName.takeUnless { isAlbum },
                avatarUrl = details.uploaderAvatarUrl,
                countText = countText,
                modifier = place,
            )
        },
    )
}

@Composable
private fun Cover(url: String?, isAlbum: Boolean, onLoaded: () -> Unit) {
    val width = if (isAlbum) AlbumCoverSize else CoverWidth
    val height = if (isAlbum) AlbumCoverSize else CoverWidth / ThumbAspect
    Box(
        Modifier
            .width(width)
            .drawBehind { drawStack(frontTop = FrontBarTop, barHeight = BarHeight) }
            .padding(top = CoverTop),
    ) {
        Box(
            Modifier
                .size(width, height)
                .dropShadow(CoverShape) {
                    radius = 60.dp.toPx()
                    spread = (-15).dp.toPx()
                    offset = Offset(0f, 25.dp.toPx())
                    color = Color.Black
                    alpha = 0.9f
                }
                .drawBehind { drawInflated(CoverShape, 0.5.dp, Color.White, 0.1f) }
                .clip(CoverShape)
                .background(if (url == null) KeColors.Well else KeColors.Surface2),
        ) {
            if (url != null) {
                Picture(url, onLoaded = onLoaded)
            } else {
                Box(Modifier.fillMaxSize().drawBehind { drawKeyline(CoverShape, 1.dp, Color.White.copy(alpha = 0.1f), 1f) })
                Icon(
                    painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    tint = KeColors.Ink3,
                    modifier = Modifier.align(Alignment.Center).size(EmptyCoverIcon),
                )
            }
        }
    }
}

/** "Veritasium · 22 videos" after the owner's avatar (once YouTube's details bring it); the count alone without an owner. */
@Composable
private fun OwnerLine(owner: String?, avatarUrl: String?, countText: String?, modifier: Modifier = Modifier) {
    if (owner == null && countText == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (owner != null && avatarUrl != null) {
            Box(Modifier.padding(end = OwnerAvatarGap).size(OwnerAvatarSize).clip(CircleShape).background(KeColors.Surface2)) {
                Picture(avatarUrl)
            }
        }
        Text(
            text = buildAnnotatedString {
                owner?.let { withStyle(weightSpan(600, KeColors.Ink)) { append(it) } }
                countText?.let {
                    if (owner != null) withStyle(SpanStyle(color = KeColors.Ink3)) { append(MetaSeparator) }
                    append(it)
                }
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = KeColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The cover full-bleed behind the page, blurred and dim under the master's scrims. Callers pass
 * the cover once it has loaded, so the two requests share one download.
 */
@Composable
internal fun PlaylistBackdrop(coverUrl: String?) {
    if (coverUrl == null) return
    Box(Modifier.fillMaxSize().backdropScrims()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(coverUrl)
                .size(BackdropDecodeHeight)
                .precision(Precision.EXACT)
                .crossfade(KeMotion.BASE_MS)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = BackdropAlpha,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = BackdropScale
                    scaleY = BackdropScale
                    transformOrigin = TransformOrigin(0.88f, 0.72f)
                },
        )
    }
}
