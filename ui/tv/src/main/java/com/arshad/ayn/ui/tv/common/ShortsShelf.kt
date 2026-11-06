package com.arshad.ayn.ui.tv.common

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.theme.KeLayout

/**
 * A Shorts shelf (`b3-search` / `b3-subscriptions` shorts): "Shorts" and [note], [headingGap]
 * over a row of [ShortCard]s. Laid out in a grid item that starts at the content column, it spans
 * the screen (the width [CardRow]'s slots are reckoned for), so the row starts at the content
 * column and its peeks bleed past the safe line, as on Home. OK on a Short calls [onPlay], holding
 * OK [onOpenMenu]. Coming back with focus returning to one of its Shorts (Q37), the row brings it
 * onto a slot.
 */
@Composable
internal fun ShortsShelf(
    shorts: List<VideoItem>,
    itemSpacing: Dp,
    headingGap: Dp,
    focusKey: (VideoItem) -> String,
    meta: (VideoItem) -> String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    secondMeta: (VideoItem) -> String? = { null },
) {
    val returnKey = LocalFocusMemory.current?.returnKey
    CardRow(
        title = "Shorts",
        itemWidth = ShortCardWidth,
        itemSpacing = itemSpacing,
        headingGap = headingGap,
        aside = note,
        reveal = shorts.indexOfFocusKey(returnKey, focusKey),
        modifier = modifier.screenWide(screenWidth(), start = KeLayout.ContentStart),
    ) { rowFocused ->
        items(shorts, key = { it.id.value }) { short ->
            ShortCard(
                video = short,
                meta = meta(short),
                secondMeta = secondMeta(short),
                focusKey = focusKey(short),
                onClick = { onPlay(short) },
                rowFocused = rowFocused,
                onFocused = onCardFocused,
                onLongClick = { onOpenMenu(short) },
            )
        }
    }
}

/** Lays out [screenWidth] wide from the screen's left edge, its slot starting [start] in, and
 *  reports the slot's width (a full-span grid item is its cells and gaps, short of the padding). */
private fun Modifier.screenWide(screenWidth: Dp, start: Dp): Modifier = layout { measurable, constraints ->
    val width = screenWidth.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-start.roundToPx(), 0)
    }
}

/**
 * A request to bring anything inside into view brings this whole element instead, so a scroller
 * above it aligns the element's top, not the child's (a shelf's heading stays above its focused
 * card when the grid moves the focused row to its top). The scrollers inside it still move the
 * child into view along their own axis first.
 */
internal fun Modifier.bringWholeIntoView(): Modifier = this then WholeIntoViewElement

private data object WholeIntoViewElement : ModifierNodeElement<WholeIntoViewNode>() {
    override fun create(): WholeIntoViewNode = WholeIntoViewNode()

    override fun update(node: WholeIntoViewNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "bringWholeIntoView"
    }
}

private class WholeIntoViewNode : Modifier.Node(), BringIntoViewModifierNode, LayoutAwareModifierNode {
    private var size = IntSize.Zero

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        bringIntoView { Rect(Offset.Zero, size.toSize()) }
    }
}
