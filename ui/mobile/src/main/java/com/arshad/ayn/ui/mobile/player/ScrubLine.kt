package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.StoryboardFrame
import com.arshad.ayn.feature.player.behindLiveText
import com.arshad.ayn.feature.player.chapterAt
import com.arshad.ayn.feature.player.pageWindow
import com.arshad.ayn.feature.player.storyboardCrop
import com.arshad.ayn.feature.player.storyboardFrame
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import kotlin.math.roundToInt

/** Where playback is, read where it is drawn (the position tick recomposes nothing else). */
internal class PlayTimes(val positionMs: () -> Long, val bufferedMs: () -> Long, val durationMs: () -> Long)

private val TouchHeight = 28.dp
private val LineHeight = 3.dp
private val DragLineHeight = 5.dp
private val HeadSize = 13.dp
private val DragHeadSize = 20.dp
private val CutWidth = 2.dp
private val RestTrack = Color.White.copy(alpha = 0.26f)
private val BufferedTrack = Color.White.copy(alpha = 0.42f)
private val CutInk = Shade.copy(alpha = 0.85f)
internal val HeadHalo = AynColors.Ink.copy(alpha = 0.18f)

/** The tag of the bar's drag strip. */
internal const val SCRUB_TAG = "watch-scrub"

/**
 * The bar along the picture's foot (`m6-player` 2–4): rest, buffered and played, the chapters' cuts
 * and SponsorBlock's [segments] in their colours. While [interactive] (the chrome shows) it has a
 * head, a tap seeks there ([onSeekTo]), and a drag scrubs: [scrubMs] follows the finger
 * ([onScrub]; `null` once lifted, when the seek is made), the line and head grow. On a [live]
 * window (LIVE-D23) a target stops at the edge, and it reads as Live or, while the chrome shows it
 * [behindLive], how far behind.
 */
@Composable
internal fun ScrubLine(
    times: PlayTimes,
    chapters: List<Chapter>,
    segments: List<Segment>,
    interactive: Boolean,
    scrubMs: Long?,
    onScrub: (Long?) -> Unit,
    onSeekTo: (Long) -> Unit,
    live: Boolean,
    behindLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val dragging = scrubMs != null
    val line by animateDpAsState(if (dragging) DragLineHeight else LineHeight, AynMotion.press(), label = "line")
    val head by animateDpAsState(
        when {
            !interactive -> 0.dp
            dragging -> DragHeadSize
            else -> HeadSize
        },
        AynMotion.press(),
        label = "head",
    )
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    Box(
        modifier
            .fillMaxWidth()
            .height(TouchHeight)
            .testTag(SCRUB_TAG)
            .semantics {
                val duration = times.durationMs().coerceAtLeast(1)
                val position = times.positionMs()
                progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat() / duration, 0f..1f)
                stateDescription = if (live) liveStateText(behindLive, position, duration) else "${timeText(position)} of ${timeText(duration)}"
                if (interactive) {
                    setProgress(label = "Seek") { fraction ->
                        currentOnSeekTo(msAt(fraction, times.durationMs(), live))
                        true
                    }
                }
            }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                // Where the finger is now: the seek on lifting goes where the preview showed.
                var lastMs = 0L
                detectHorizontalDragGestures(
                    onDragStart = {
                        lastMs = msAt(it.x / size.width, times.durationMs(), live)
                        currentOnScrub(lastMs)
                    },
                    onDragEnd = {
                        currentOnSeekTo(lastMs)
                        currentOnScrub(null)
                    },
                    onDragCancel = { currentOnScrub(null) },
                ) { change, _ ->
                    change.consume()
                    lastMs = msAt(change.position.x / size.width, times.durationMs(), live)
                    currentOnScrub(lastMs)
                }
            }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures { currentOnSeekTo(msAt(it.x / size.width, times.durationMs(), live)) }
            }
            .drawBehind {
                val total = times.durationMs()
                val height = line.toPx()
                val top = size.height - height
                drawRect(RestTrack, Offset(0f, top), Size(size.width, height))
                if (total <= 0) return@drawBehind
                val position = scrubMs ?: times.positionMs()
                drawSpan(0, times.bufferedMs(), total, top, height, BufferedTrack)
                drawSpan(0, position, total, top, height, AynColors.Ink)
                segments.forEach { drawSpan(it.startMs, it.endMs, total, top, height, it.category.color) }
                for (i in 1 until chapters.size) {
                    val x = size.width * chapters[i].startSeconds * 1000 / total
                    drawRect(CutInk, Offset(x - CutWidth.toPx() / 2, top), Size(CutWidth.toPx(), height))
                }
                if (head > 0.dp) {
                    val center = Offset(size.width * position.coerceIn(0, total) / total, top + height / 2)
                    if (dragging) drawCircle(HeadHalo, head.toPx() / 2 + 6.dp.toPx(), center)
                    drawCircle(AynColors.Ink, head.toPx() / 2, center)
                }
            },
    )
}

/** The time at [fraction] of the bar; on a [live] window no later than its edge. */
internal fun msAt(fraction: Float, durationMs: Long, live: Boolean = false): Long {
    val at = (fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0)).toLong()
    return if (live) at.coerceAtMost(PlayerChromePolicy.liveEdgeMs(durationMs)) else at
}

/** A live bar as a screen reader says it: how far [behind] live, else "Live". */
private fun liveStateText(behind: Boolean, positionMs: Long, durationMs: Long): String =
    if (behind) "${timeText(PlayerChromePolicy.liveBehindMs(positionMs, durationMs))} behind live" else "Live"

private fun DrawScope.drawSpan(fromMs: Long, toMs: Long, totalMs: Long, top: Float, height: Float, color: Color) {
    val start = size.width * fromMs.coerceIn(0, totalMs) / totalMs
    val end = size.width * toMs.coerceIn(0, totalMs) / totalMs
    if (end > start) drawRect(color, Offset(start, top), Size(end - start, height))
}

/** A SponsorBlock category's colour on the bar (the mockups' `--sponsor` …, as the TV's). */
internal val SegmentCategory.color: Color
    get() = when (this) {
        SegmentCategory.Sponsor -> Color(0xFF56D57B)
        SegmentCategory.SelfPromo -> Color(0xFFF0CF4C)
        SegmentCategory.Interaction -> Color(0xFFB28FEF)
        SegmentCategory.Intro -> Color(0xFF4CDBE3)
        SegmentCategory.Outro -> Color(0xFF60A7D6)
        SegmentCategory.Preview -> Color(0xFF2EA4DC)
        SegmentCategory.Hook -> Color(0xFF5A86CE)
        SegmentCategory.MusicOffTopic -> Color(0xFFF59740)
        SegmentCategory.Filler -> Color(0xFF7E4ED7)
    }

private val PreviewWidth = 150.dp
private val PreviewHeight = 84.dp
private val PreviewShape = RoundedCornerShape(10.dp)
private val PreviewEdge = 8.dp

/**
 * The scrub preview above the bar (`m6-player` 4): the storyboard frame at [atMs] (when YouTube
 * offers one), the time and its chapter, centred over [fraction] of the width and kept inside it.
 * On a live window ([behindLiveMs] given) it says how far behind live [atMs] is instead.
 */
@Composable
internal fun ScrubPreview(atMs: Long, fraction: Float, chapters: List<Chapter>, storyboard: Storyboard?, behindLiveMs: Long?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                val edge = PreviewEdge.roundToPx()
                val max = (constraints.maxWidth - placeable.width - edge).coerceAtLeast(edge)
                val x = (fraction * constraints.maxWidth - placeable.width / 2f).roundToInt().coerceIn(edge, max)
                layout(constraints.maxWidth, placeable.height) { placeable.place(x, 0) }
            }
            .width(PreviewWidth),
    ) {
        storyboard?.let { FrameImage(it, atMs) }
        Text(
            behindLiveMs?.let(::behindLiveText) ?: timeText(atMs),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        (if (behindLiveMs != null) "behind live" else chapterAt(chapters, atMs)?.title)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = AynColors.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One storyboard frame cut from its sprite page; blank until its own page lands (never another
 *  page's frame), the pages either side loaded too. A page a few pixels short is clamped. */
@Composable
private fun FrameImage(board: Storyboard, atMs: Long) {
    val frame = storyboardFrame(board, atMs) ?: return
    val pages = remember(board) { mutableStateMapOf<Int, ImageBitmap>() }
    PageLoads(board, pageWindow(frame.page, board.pageUrls.lastIndex, prefetch = true), pages)
    Box(
        Modifier
            .size(PreviewWidth, PreviewHeight)
            .clip(PreviewShape)
            .drawBehind {
                val image = pages[frame.page]
                if (image == null) drawRect(AynColors.Surface2) else drawFrame(image, board, frame)
                drawRoundRect(AynColors.Ink, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            },
    )
}

@Composable
private fun PageLoads(board: Storyboard, window: IntRange, pages: SnapshotStateMap<Int, ImageBitmap>) {
    val context = LocalContext.current
    for (index in window) {
        key(index) {
            LaunchedEffect(board, pages, context) {
                if (index in pages) return@LaunchedEffect
                val request = ImageRequest.Builder(context).data(board.pageUrls[index]).build()
                val result = SingletonImageLoader.get(context).execute(request)
                (result as? SuccessResult)?.let { pages[index] = it.image.toBitmap().asImageBitmap() }
            }
            DisposableEffect(pages) { onDispose { pages.remove(index) } }
        }
    }
}

private fun DrawScope.drawFrame(image: ImageBitmap, board: Storyboard, frame: StoryboardFrame) {
    drawRect(Color.Black)
    val crop = storyboardCrop(board, frame, image.width, image.height) ?: return
    // Fitted inside the 16:9 box at its own shape (a square or portrait video's frame on black).
    val scale = minOf(size.width / crop.width, size.height / crop.height)
    val dw = (crop.width * scale).roundToInt()
    val dh = (crop.height * scale).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
        dstSize = IntSize(dw, dh),
    )
}
