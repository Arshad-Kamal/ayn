package com.arshad.ayn.ui.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.ui.tv.common.ChannelTileCard
import com.arshad.ayn.ui.tv.common.GridCardTitleSize
import com.arshad.ayn.ui.tv.common.GridCellWidth
import com.arshad.ayn.ui.tv.common.GridColumns
import com.arshad.ayn.ui.tv.common.GridColumnGap
import com.arshad.ayn.ui.tv.common.GridRowGap
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.ShortsShelf
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.bringWholeIntoView
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.TopAlign
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.feature.format.dateText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.tv.theme.KeLayout

// b3-search grid (px ÷ 2): four 180 dp columns 15 apart, rows 22 apart; the first row's top sits
// 15 dp into the grid, under a top fade as tall, where every focused row settles.
private val CellWidth = GridCellWidth
private val ColumnGap = GridColumnGap
private val RowGap = GridRowGap
internal val GridTop = 15.dp
private const val SkeletonCards = 8

// b3-search shorts (px ÷ 2): the shelf sits 3 dp lower and 2 dp higher than a row would, its
// heading 20 dp above its cards (room for the focused card's grow), its cards 15 dp apart.
private val ShelfTop = 3.dp
private val ShelfBottom = 2.dp
private val ShelfHeadingGap = 20.dp
private val ShelfSpacing = 15.dp
private const val ShelfKey = "shorts"

/**
 * The answer as a grid of cards, the [shorts] shelf (when any) after its first row. A focused
 * card's row moves to the grid's top ([TopAlign], the mockup's anchored scroll), the shelf with its
 * heading; each card reports its light so the screen can light the room. Holding OK on a video
 * calls [onOpenMenu], on a Short [onOpenShortMenu].
 */
@Composable
internal fun ResultGrid(
    items: List<ListingItem>,
    shorts: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { GridTop.toPx() }) }
    val titleStyle = cardTitleStyle()
    PrefetchFaces(items) { items.filterIsInstance<VideoItem>() }
    ProvideBringIntoView(align) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CellWidth),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(
                start = KeLayout.ContentStart,
                end = KeLayout.SafeHorizontal,
                top = GridTop,
                bottom = KeLayout.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            modifier = modifier.topFade(GridTop),
        ) {
            items(items.take(GridColumns), key = { it.listingKey }, contentType = { it.kind }) { item ->
                ResultCard(item, titleStyle, onPlay, onOpenMenu, onOpenChannel, onCardFocused)
            }
            if (shorts.isNotEmpty()) {
                item(key = ShelfKey, span = { GridItemSpan(maxLineSpan) }, contentType = ShelfKey) {
                    ShortsShelf(
                        shorts = shorts,
                        itemSpacing = ShelfSpacing,
                        headingGap = ShelfHeadingGap,
                        focusKey = { shortKey(it.id) },
                        // YouTube sends no channel with a search Short (Q43).
                        meta = { viewsText(it.viewCount) },
                        onPlay = onPlayShort,
                        onOpenMenu = onOpenShortMenu,
                        onCardFocused = onCardFocused,
                        modifier = Modifier.bringWholeIntoView().padding(top = ShelfTop, bottom = ShelfBottom),
                    )
                }
            }
            items(items.drop(GridColumns), key = { it.listingKey }, contentType = { it.kind }) { item ->
                ResultCard(item, titleStyle, onPlay, onOpenMenu, onOpenChannel, onCardFocused)
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: ListingItem,
    titleStyle: TextStyle,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
) {
    when (item) {
        is VideoItem -> VideoCard(
            video = item,
            meta = if (item.isLive) metaLine(item.uploaderName, watchingText(item.viewCount)) else item.uploaderName,
            secondMeta = if (item.isLive) null else metaLine(viewsText(item.viewCount), item.dateText()).ifEmpty { null },
            focusKey = resultKey(item),
            onClick = { onPlay(item) },
            width = CellWidth,
            titleStyle = titleStyle,
            onFocused = onCardFocused,
            onLongClick = { onOpenMenu(item) },
        )
        is ChannelItem -> ChannelTileCard(
            name = item.name,
            avatarUrl = item.avatarUrl,
            meta = subscribersText(item.subscriberCount),
            focusKey = resultKey(item),
            onClick = { onOpenChannel(item.url) },
            width = CellWidth,
            titleStyle = titleStyle,
            onFocused = onCardFocused,
        )
        // searchLayout drops playlists: Search has no playlist card.
        is PlaylistItem -> Unit
    }
}

private val ListingItem.kind: String
    get() = when (this) {
        is VideoItem -> "video"
        is ChannelItem -> "channel"
        is PlaylistItem -> "playlist"
    }

@Composable
private fun cardTitleStyle(): TextStyle = MaterialTheme.typography.titleSmall.copy(fontSize = GridCardTitleSize)

/** State 8: two rows of cards in the grid's geometry while the search runs (nothing focusable). */
@Composable
internal fun SkeletonGrid(modifier: Modifier = Modifier) {
    val sheen = rememberSheen()
    Column(
        modifier.padding(start = KeLayout.ContentStart, top = GridTop),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        repeat(SkeletonCards / GridColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                repeat(GridColumns) {
                    Column(Modifier.width(CellWidth)) {
                        Spacer(Modifier.fillMaxWidth().height(CellWidth / ThumbAspect).skeleton(sheen))
                        SkeletonLine(0.86f, top = 8.dp, sheen)
                        SkeletonLine(0.52f, top = 6.dp, sheen)
                        SkeletonLine(0.64f, top = 4.dp, sheen)
                    }
                }
            }
        }
    }
}
