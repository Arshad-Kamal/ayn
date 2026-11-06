package com.arshad.ayn.ui.tv.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.ui.tv.common.LineShape
import com.arshad.ayn.ui.tv.common.ListIndexWidth
import com.arshad.ayn.ui.tv.common.ListRowFrame
import com.arshad.ayn.ui.tv.common.ListRowInset
import com.arshad.ayn.ui.tv.common.ListThumbShape
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.TopAlign
import com.arshad.ayn.ui.tv.common.VideoListRow
import com.arshad.ayn.ui.tv.common.listRowHeight
import com.arshad.ayn.feature.format.dateText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.tv.theme.KeLayout

// b8p-playlist list geometry (px ÷ 2): the list column starts 40 dp right of the header column.
// Rows are 86 dp (a 128×72 thumbnail with 7 dp above and below); a row's band reaches 9 dp past
// the column and the list's box 20 dp further (room for the focused row's light), fading in over
// its top 20 dp and out over its last 10%. A focused row settles one row down (the mockup's
// 172 px anchor); the list ends 100 dp past its last row.
internal val ListStart = KeLayout.ContentStart + HeaderColumnWidth + ColumnGap
private val ListBleed = 20.dp
private val ListHeight = 470.dp
private val ThumbWidth = 128.dp
private val RowGap = 13.dp
private val RowHeight = listRowHeight(ThumbWidth)
private val ListEnd = 100.dp
private const val FadeOutFrom = 0.9f
private const val SkeletonRowCount = 6

/** Focus this close to the end of the listed rows asks for the next page. */
private const val LoadMoreAhead = 10

/**
 * The playlist's videos in order, numbered from 1 (rows key by position: a playlist can hold one
 * video twice), each with its [line] and how far the profile got ([progress]). A focused row
 * reports its light to [onRowFocused] and, near the end of what is listed while [hasMore], asks
 * [onLoadMore] for the next page. Holding OK on a row calls [onOpenMenu] with its video.
 */
@Composable
internal fun PlaylistRows(
    videos: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onRowFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    line: (VideoItem) -> String? = ::rowLine,
    progress: (VideoItem) -> Float? = { null },
) {
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { (ListBleed + RowHeight).toPx() }) }
    PrefetchFaces(videos) { videos }
    ProvideBringIntoView(align) {
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = ListBleed, top = ListBleed, end = ListBleed, bottom = ListEnd),
            modifier = modifier.listBox(),
        ) {
            itemsIndexed(videos, key = { position, _ -> position }) { position, video ->
                VideoListRow(
                    video = video,
                    line = remember(video, line) { line(video) },
                    focusKey = rowKey(position),
                    onClick = { onPlay(video) },
                    onFocused = { light, centerX ->
                        onRowFocused(light, centerX)
                        if (hasMore && position >= videos.size - LoadMoreAhead) onLoadMore()
                    },
                    thumbWidth = ThumbWidth,
                    gap = RowGap,
                    number = position + 1,
                    progress = progress(video),
                    onLongClick = { onOpenMenu(video) },
                )
            }
        }
    }
}

/** State 3: rows in their final shape under one sheen while the videos load (nothing focusable). */
@Composable
internal fun SkeletonRows(modifier: Modifier = Modifier) {
    val sheen = rememberSheen()
    Column(modifier.listBox().padding(start = ListBleed, top = ListBleed, end = ListBleed)) {
        repeat(SkeletonRowCount) {
            ListRowFrame(RowHeight, RowGap) {
                Box(Modifier.width(ListIndexWidth), contentAlignment = Alignment.CenterEnd) {
                    Spacer(Modifier.size(14.dp, 13.dp).skeleton(sheen, LineShape))
                }
                Spacer(Modifier.size(ThumbWidth, ThumbWidth / ThumbAspect).skeleton(sheen, ListThumbShape))
                Column(Modifier.weight(1f)) {
                    SkeletonLine(0.78f, top = 0.dp, sheen, height = 13.dp)
                    SkeletonLine(0.46f, top = 7.dp, sheen, height = 13.dp)
                }
            }
        }
    }
}

/**
 * The list's place beside the header. The rows fade into the cover's backdrop at its top and
 * bottom, which a ground-coloured scrim can't match, so the box masks its own content (one
 * offscreen layer).
 */
private fun Modifier.listBox(): Modifier = this
    .padding(start = ListStart - ListRowInset - ListBleed, top = PageTop - ListBleed, end = KeLayout.SafeHorizontal - ListRowInset - ListBleed)
    .fillMaxWidth()
    .height(ListHeight)
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val fade = Brush.verticalGradient(
            0f to Color.Transparent,
            ListBleed.toPx() / size.height to Color.Black,
            FadeOutFrom to Color.Black,
            1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            drawRect(fade, blendMode = BlendMode.DstIn)
        }
    }

/** "Veritasium · 4.9M views · 2 years ago"; a live stream's audience in place of views and age (D11). */
internal fun rowLine(video: VideoItem): String? {
    val line = if (video.isLive) {
        metaLine(video.uploaderName, watchingText(video.viewCount))
    } else {
        metaLine(video.uploaderName, viewsText(video.viewCount), video.dateText())
    }
    return line.ifEmpty { null }
}
