package com.arshad.ayn.ui.tv.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.ui.tv.common.GridCardTitleSize
import com.arshad.ayn.ui.tv.common.GridCellWidth
import com.arshad.ayn.ui.tv.common.GridColumnGap
import com.arshad.ayn.ui.tv.common.GridColumns
import com.arshad.ayn.ui.tv.common.GridRowGap
import com.arshad.ayn.ui.tv.common.LineShape
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.ShortsShelf
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.TopAlign
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import kotlin.time.Instant

// b3-subscriptions grid (px ÷ 2): four 180 dp columns 15 apart, rows 22 apart; a day heading
// 20 dp tall, 11 above its cards and 28 below the previous group's last row. The grid's content
// starts 13 dp in, under a 14 dp top fade; a focused row settles where a later group's first row
// sits under its heading.
private val CellWidth = GridCellWidth
private val ColumnGap = GridColumnGap
private val RowGap = GridRowGap
private val GridTop = 13.dp
private val GridFade = 14.dp
private val HeadingHeight = 20.dp
private val HeadingBelow = 11.dp
private val HeadingAbove = 6.dp // after the previous row's RowGap: 28 in all
private val FocusedRowTop = GridTop + HeadingAbove + HeadingHeight + HeadingBelow
private val CardTitleSize = GridCardTitleSize
private val HeadingNoteSize = 12.sp
// b3-subscriptions shorts (px ÷ 2): Shorts 17 dp apart under a heading set like a day's.
private val ShelfSpacing = 17.dp
private const val ShelfKey = "shorts"
private const val SkeletonColumns = 4
private const val SkeletonRows = 2
// A new selection's rows join one per frame for the first few (past them, the rest at once): the
// screen shows about three.
private const val StaggeredRows = 4

/** A run of feed cards under one heading ("Today" with its date, "Latest from Veritasium"). */
internal data class FeedGroup(val title: String, val note: String?, val videos: List<VideoItem>)

/** The Shorts shelf ("Shorts" and [note]), after the first group's first [afterVideos] cards, or
 *  alone when there is no group; [channelUrl] is the selected channel's, `null` for every channel's. */
internal data class FeedShelf(val note: String, val shorts: List<VideoItem>, val afterVideos: Int, val channelUrl: String?)

/**
 * The feed as a grid of cards under day headings, with the [shelf] of Shorts when there is one. A
 * focused card's row settles near the grid's top ([TopAlign]), where a group's first row shows its
 * heading above it (the shelf's heading likewise); each card reports its light so the screen can
 * light the room. Holding OK on a card calls [onOpenMenu], on a Short [onOpenShortMenu]. [state]
 * is the caller's, so one grid can show one feed after another. Only the first [joinedRows] rows
 * are built ([rememberJoiningRows]); a row still to join holds bare places.
 */
@Composable
internal fun FeedGrid(
    groups: List<FeedGroup>,
    shelf: FeedShelf?,
    now: Instant,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    state: LazyGridState,
    joinedRows: IntState,
    modifier: Modifier = Modifier,
) {
    val steps = remember(groups, shelf) { rowSteps(groups, shelf) }
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { FocusedRowTop.toPx() }) }
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = CardTitleSize)
    PrefetchFaces(groups) { groups.flatMap { it.videos } }
    ProvideBringIntoView(align) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CellWidth),
            state = state,
            contentPadding = PaddingValues(
                start = KeLayout.ContentStart,
                end = KeLayout.SafeHorizontal,
                top = GridTop,
                bottom = KeLayout.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            modifier = modifier.topFade(GridFade),
        ) {
            fun shelfItem() {
                val shown = shelf ?: return
                // Keyed per feed, so another selection's shelf starts at its first Short.
                item(key = "$ShelfKey:${shown.channelUrl}", span = { GridItemSpan(maxLineSpan) }, contentType = ShelfKey) {
                    if (steps.shelf >= joinedRows.intValue) return@item ShelfPlace()
                    ShortsShelf(
                        shorts = shown.shorts,
                        itemSpacing = ShelfSpacing,
                        headingGap = HeadingBelow,
                        focusKey = { shortKey(it.id) },
                        note = shown.note,
                        meta = { short -> short.uploaderName },
                        secondMeta = { short -> viewsText(short.viewCount) },
                        onPlay = onPlayShort,
                        onOpenMenu = onOpenShortMenu,
                        onCardFocused = onCardFocused,
                        modifier = Modifier.padding(top = if (groups.isEmpty()) 0.dp else HeadingAbove, bottom = RowGap),
                    )
                }
            }
            val card: @Composable (VideoItem) -> Unit = { video ->
                if (steps.card.getValue(videoKey(video)) >= joinedRows.intValue) {
                    CardPlace()
                } else {
                    FeedCard(video, now, titleStyle, onPlay, onOpenMenu, onCardFocused)
                }
            }
            if (groups.isEmpty()) shelfItem()
            groups.forEachIndexed { index, group ->
                item(key = "heading:${group.title}", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                    GroupHeading(group, Modifier.padding(top = if (index == 0) 0.dp else HeadingAbove, bottom = HeadingBelow))
                }
                val split = shelfSplit(index, group, shelf)
                items(group.videos.take(split), key = { videoKey(it) }, contentType = { "video" }) { card(it) }
                if (index == 0) {
                    shelfItem()
                    items(group.videos.drop(split), key = { videoKey(it) }, contentType = { "video" }) { card(it) }
                }
            }
        }
    }
}

/** How many of [group]'s cards come before the shelf: the first group's split, else all. */
private fun shelfSplit(index: Int, group: FeedGroup, shelf: FeedShelf?): Int =
    if (index == 0 && shelf != null) shelf.afterVideos else group.videos.size

/** Which visual row (from 0) each card and the shelf sit on; mirrors [FeedGrid]'s item order. */
internal class RowSteps(val card: Map<String, Int>, val shelf: Int)

internal fun rowSteps(groups: List<FeedGroup>, shelf: FeedShelf?): RowSteps {
    val card = HashMap<String, Int>()
    var row = 0
    var shelfRow = 0
    fun cards(videos: List<VideoItem>) {
        videos.forEachIndexed { i, video -> card[videoKey(video)] = row + i / GridColumns }
        if (videos.isNotEmpty()) row += (videos.size + GridColumns - 1) / GridColumns
    }
    fun shelfRow() {
        if (shelf != null) shelfRow = row++
    }
    if (groups.isEmpty()) shelfRow()
    groups.forEachIndexed { index, group ->
        val split = shelfSplit(index, group, shelf)
        cards(group.videos.take(split))
        if (index == 0) {
            shelfRow()
            cards(group.videos.drop(split))
        }
    }
    return RowSteps(card, shelfRow)
}

/**
 * How many of a feed's rows are built: all for the first feed shown (focus may be coming back to a
 * card); once [feedKey] changes (another selection), 1, one more each frame up to [StaggeredRows],
 * then all, while the feed fades in, so no frame builds every card on screen. Remembered above the
 * grid, so a selection showing a statement in between still counts as a change.
 */
@Composable
internal fun rememberJoiningRows(feedKey: Any?): IntState {
    val firstFeed = remember { FirstFeed() }
    val rows = remember(feedKey) { mutableIntStateOf(if (firstFeed.shown) 1 else Int.MAX_VALUE) }
    SideEffect { firstFeed.shown = true }
    LaunchedEffect(rows) {
        while (rows.intValue < StaggeredRows) {
            withFrameNanos { }
            rows.intValue++
        }
        withFrameNanos { }
        rows.intValue = Int.MAX_VALUE
    }
    return rows
}

private class FirstFeed {
    var shown = false
}

/** The shelf's place while it waits to join: a card row's height (the feed is near-transparent
 *  then, so the row heights settling as they join don't show). */
@Composable
private fun ShelfPlace() {
    Spacer(Modifier.height(CellWidth / ThumbAspect))
}

/** A card's place while its row waits to join (nothing drawn, nothing focusable). */
@Composable
private fun CardPlace() {
    Spacer(Modifier.padding(bottom = RowGap).size(CellWidth, CellWidth / ThumbAspect))
}

@Composable
private fun FeedCard(
    video: VideoItem,
    now: Instant,
    titleStyle: TextStyle,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
) {
    VideoCard(
        video = video,
        meta = if (video.isLive) {
            metaLine(video.uploaderName, watchingText(video.viewCount))
        } else {
            metaLine(video.uploaderName, video.ageText(now))
        },
        focusKey = videoKey(video),
        onClick = { onPlay(video) },
        width = CellWidth,
        titleStyle = titleStyle,
        onFocused = onCardFocused,
        modifier = Modifier.padding(bottom = RowGap),
        onLongClick = { onOpenMenu(video) },
    )
}

@Composable
private fun GroupHeading(group: FeedGroup, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(HeadingHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(group.title, style = MaterialTheme.typography.titleLarge, color = KeColors.Ink, modifier = Modifier.alignByBaseline())
        group.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = HeadingNoteSize),
                color = KeColors.Ink3,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** State 7: a heading and two rows of cards in the grid's geometry (nothing focusable). */
@Composable
internal fun FeedSkeleton(modifier: Modifier = Modifier) {
    val sheen = rememberSheen()
    Column(modifier.padding(start = KeLayout.ContentStart, top = GridTop)) {
        Spacer(Modifier.width(150.dp).height(15.dp).skeleton(sheen, LineShape))
        Spacer(Modifier.height(HeadingBelow + HeadingHeight - 15.dp))
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            repeat(SkeletonRows) {
                Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                    repeat(SkeletonColumns) {
                        Column(Modifier.width(CellWidth)) {
                            Spacer(Modifier.fillMaxWidth().height(CellWidth / ThumbAspect).skeleton(sheen))
                            SkeletonLine(0.86f, top = 8.dp, sheen)
                            SkeletonLine(0.52f, top = 6.dp, sheen)
                        }
                    }
                }
            }
        }
    }
}
