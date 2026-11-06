package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion

private val HeadingGap = 10.dp
private val AsideGap = 8.dp
private val AsideSize = 12.sp

/**
 * A scrolling row clips its cross axis 30 dp past its bounds; a focused card's halo reaches
 * ~90 dp (blur + spread). A row pads its content this much above and below and
 * reports its real height ([withoutLightRoom]), so lights aren't cut.
 */
internal val LightRoom = 90.dp

/** Lays out a row padded by [LightRoom] above and below (the room added to a bounded height, so
 *  the content keeps its own) and reports it at its content's height, in place. */
internal fun Modifier.withoutLightRoom(): Modifier = layout { measurable, constraints ->
    val room = LightRoom.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * room))
    layout(placeable.width, (placeable.height - 2 * room).coerceAtLeast(0)) {
        placeable.place(0, -room)
    }
}

/**
 * A titled row of cards (master: heading, then a track starting at the content column). The
 * track spans the screen (peeks bleed past the safe line) and scrolls whole card steps so the
 * focused card sits on a slot inside the safe line ([RowFit]), never TV's default pivot.
 * [aside] follows the title (a count, `.row h2 .aside`). The cards start at [start], the heading at
 * [headingStart], [headingGap] above them. [content] gets a read of whether the row holds focus
 * (resting neighbours dim), read where it is used so a row taking or losing focus doesn't
 * recompose its cards. A row coming back (its scroll restored) with a [reveal] item index brings that
 * item onto a slot ([RowFit.firstShowing]) before its first layout, so focus can return to it.
 * [onShowing] hears the keys of the items wholly on screen each time they change.
 */
@Composable
internal fun CardRow(
    title: String,
    itemWidth: Dp,
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    aside: String? = null,
    start: Dp = KeLayout.ContentStart,
    headingStart: Dp = start,
    headingGap: Dp = HeadingGap,
    reveal: Int? = null,
    onShowing: ((keys: List<Any>) -> Unit)? = null,
    content: LazyListScope.(rowFocused: () -> Boolean) -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }
    val readRowFocused: () -> Boolean = { rowFocused }
    val density = LocalDensity.current
    val rowWidth = screenWidth()
    val fit = remember(density, rowWidth, start, itemWidth, itemSpacing) {
        with(density) {
            RowFit(
                start = start.toPx(),
                end = KeLayout.SafeHorizontal.toPx(),
                itemWidth = itemWidth.toPx(),
                step = (itemWidth + itemSpacing).toPx(),
            )
        }
    }
    // Room past the last card for it to reach its slot, as the mockup's unclamped track shift does.
    val endRoom = with(density) { fit.endRoom(rowWidth.toPx()).toDp() }
    val state = rememberSaveable(saver = revealingSaver(fit, with(density) { rowWidth.toPx() }, reveal)) { LazyListState() }
    if (onShowing != null) {
        val currentOnShowing by rememberUpdatedState(onShowing)
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.wholeItemKeys() }.distinctUntilChanged().collect { currentOnShowing(it) }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = headingStart, bottom = headingGap),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(AsideGap),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = KeColors.Ink)
            aside?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = AsideSize, fontFeatureSettings = "tnum"),
                    color = KeColors.Ink3,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        ProvideBringIntoView(fit) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(
                    start = start,
                    end = endRoom,
                    top = LightRoom,
                    bottom = LightRoom,
                ),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .withoutLightRoom()
                    .onFocusChanged { rowFocused = it.hasFocus },
            ) {
                content(readRowFocused)
            }
        }
    }
}

/** The keys of the items wholly inside the viewport (the content padding's room included). */
private fun LazyListLayoutInfo.wholeItemKeys(): List<Any> =
    visibleItemsInfo.filter { it.offset >= viewportStartOffset && it.offset + it.size <= viewportEndOffset }.map { it.key }

/** The index of the item whose focus key is [key] (a row's or grid's reveal); null when none is. */
internal fun <T> List<T>.indexOfFocusKey(key: String?, focusKey: (T) -> String): Int? =
    key?.let { k -> indexOfFirst { focusKey(it) == k }.takeIf { it >= 0 } }

/** [LazyListState]'s own saved form, restored with [reveal] brought onto a slot when given. */
private fun revealingSaver(fit: RowFit, rowWidth: Float, reveal: Int?): Saver<LazyListState, *> = listSaver(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { (index, offset) ->
        val first = reveal?.let { fit.firstShowing(it, index, rowWidth) } ?: index
        LazyListState(first, if (first == index) offset else 0)
    },
)

/**
 * Keeps the focused card on a slot of the card grid ([start] + k·[step]) inside
 * [start]..(row width − [end]), the mockup's whole-step track shift: a card past the safe line
 * moves to the last slot, one before [start] to the first, one already in a slot stays. The
 * card is located by its centre and [itemWidth], so its focus scale and a focusable narrower
 * than its column (an avatar) don't move the grid. The answer is the remaining distance to that
 * slot, recomputed every animation frame, so it holds while the scroll runs.
 */
internal class RowFit(
    private val start: Float,
    private val end: Float,
    private val itemWidth: Float,
    private val step: Float,
) : BringIntoViewSpec {

    override val scrollAnimationSpec: AnimationSpec<Float> = KeMotion.move()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val left = offset + size / 2 - itemWidth / 2
        val slot = ((left - start) / step).roundToInt().coerceIn(0, lastSlot(containerSize))
        return left - (start + slot * step)
    }

    /** The end padding that lets the last card scroll to [lastSlot] in a row [rowWidth] wide. */
    fun endRoom(rowWidth: Float): Float = (rowWidth - start - itemWidth - lastSlot(rowWidth) * step).coerceAtLeast(end)

    /**
     * The item on the first slot that puts item [index] on a slot of a row [rowWidth] wide whose
     * first slot holds item [first] (a resting row sits on whole steps): [first] when it already
     * does, else [index] on the first slot (it was before them) or the last (after them), where
     * D-pad would have brought it.
     */
    fun firstShowing(index: Int, first: Int, rowWidth: Float): Int {
        val last = lastSlot(rowWidth)
        return when {
            index < first -> index
            index > first + last -> index - last
            else -> first
        }
    }

    private fun lastSlot(containerSize: Float): Int =
        floor((containerSize - end - itemWidth - start) / step).toInt().coerceAtLeast(0)
}
