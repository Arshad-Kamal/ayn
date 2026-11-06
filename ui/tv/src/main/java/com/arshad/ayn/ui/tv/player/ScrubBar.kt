package com.arshad.ayn.ui.tv.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.StoryboardFrame
import com.arshad.ayn.feature.player.behindLiveText
import com.arshad.ayn.feature.player.chapterAt
import com.arshad.ayn.feature.player.pageWindow
import com.arshad.ayn.feature.player.storyboardCrop
import com.arshad.ayn.feature.player.storyboardFrame
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.focus.NeutralSurface
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.color
import kotlin.math.roundToInt

/**
 * The scrub's pending target: Left/Right move it from where playback is, OK seeks there, and
 * leaving the bar (or hiding the chrome) drops it. `null` while nothing is pending.
 */
@Stable
internal class ScrubState {
    var focused: Boolean by mutableStateOf(false)
    var targetMs: Long? by mutableStateOf(null)

    fun drop() {
        targetMs = null
    }
}

/** Where the bar's position reads come from: the progress holder's tick (M3-PLAYER-07), or a
 *  pending gathered seek's target. */
internal class ScrubTimes(val positionMs: () -> Long, val bufferedMs: () -> Long, val durationMs: () -> Long) {
    /** These times as they are now, fixed: reading them observes no state, so no tick recomposes
     *  (or redraws) their reader. */
    fun held(): ScrubTimes = Snapshot.withoutReadObservation {
        val position = positionMs()
        val buffered = bufferedMs()
        val duration = durationMs()
        ScrubTimes({ position }, { buffered }, { duration })
    }
}

// `.scrub` / `.bar` / `.head` / `.preview` (px ÷ 2): the bar is 6 dp drawn at 2/3 height until
// focused; chapters are cut with 3 dp gaps; the preview is 200 dp wide, 6 dp above the scrub.
private val ScrubHeight = 20.dp
private val BarHeight = 6.dp
private const val RestingBarScale = 0.667f
private val ChapterGap = 3.dp
private val BarRadius = 2.dp
private val HeadWidth = 3.dp
private val HeadHeight = 14.dp
private val HeadRing = 1.5.dp
private const val RestingHeadScale = 0.6f
private val PreviewWidth = 200.dp
private val PreviewFrameHeight = PreviewWidth * 9 / 16
private val PreviewLift = ScrubHeight + 6.dp
private val PreviewRise = 6.dp
private const val RestingPreviewScale = 0.97f
private val PreviewShape = RoundedCornerShape(6.dp)
private const val HeadMoveMs = 120

private val RestTrack = Color.White.copy(alpha = 0.24f)
private val BufferedTrack = Color.White.copy(alpha = 0.42f)
private val HeadRingColor = PlayerShade.copy(alpha = 0.6f)

// `.sb`: a segment over the track, slightly see-through.
private val SegmentRadius = 1.dp
private const val SegmentAlpha = 0.95f

/**
 * The scrub bar: what's played, what's buffered and the rest, with SponsorBlock's [segments] in
 * their categories' colours over them, cut at each chapter. Focused, it grows and shows its head,
 * and Left/Right move [scrub]'s pending target by [PlayerChromePolicy.SEEK_STEP_MS] with that
 * moment's storyboard frame and chapter above it; OK seeks there ([onSeekTo]). On a [live] window
 * the target stops at the edge and the bubble says how far behind live it is (`live-tv-player`
 * live-scrub; a live stream has no storyboard).
 */
@Composable
internal fun ScrubBar(
    times: ScrubTimes,
    chapters: List<Chapter>,
    segments: List<Segment>,
    storyboard: Storyboard?,
    scrub: ScrubState,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    val focus by animateFloatAsState(if (scrub.focused) 1f else 0f, KeMotion.base(), label = "scrubFocus")
    val shownMs = scrub.targetMs ?: times.positionMs()
    val duration = times.durationMs()
    // The head and preview show only while the bar is focused or fading: unseen, they follow the
    // tick without a tween. Read at the fade's two edges only.
    val headSeen by remember(scrub) { derivedStateOf { scrub.focused || focus > 0f } }
    val headFraction by animateFloatAsState(
        targetValue = if (duration > 0) (shownMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        animationSpec = if (headSeen) tween(HeadMoveMs, easing = LinearEasing) else snap(),
        label = "head",
    )
    var width by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxWidth().height(ScrubHeight).onSizeChanged { width = it.width }) {
        val previewModifier = Modifier
            .align(Alignment.BottomStart)
            // The bar is ScrubHeight tall; the preview stands above it at its own height.
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            .offset {
                val previewWidth = PreviewWidth.roundToPx()
                val x = (headFraction * width - previewWidth / 2f).roundToInt().coerceIn(0, (width - previewWidth).coerceAtLeast(0))
                IntOffset(x, -PreviewLift.roundToPx())
            }
            .graphicsLayer {
                alpha = focus
                val scale = RestingPreviewScale + (1f - RestingPreviewScale) * focus
                scaleX = scale
                scaleY = scale
                translationY = PreviewRise.toPx() * (1f - focus)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
        if (live) {
            LiveBubble(PlayerChromePolicy.liveBehindMs(shownMs, duration), previewModifier)
        } else {
            Preview(atMs = shownMs, chapters = chapters, storyboard = storyboard, prefetch = scrub.focused, modifier = previewModifier)
        }
        NeutralSurface(
            onClick = {
                scrub.targetMs?.let(onSeekTo)
                scrub.drop()
            },
            shape = RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(ScrubHeight)
                .focusRequester(focusRequester)
                .focusProperties { this.canFocus = canFocus }
                .onFocusChanged {
                    scrub.focused = it.isFocused
                    if (!it.isFocused) scrub.drop()
                }
                .onPreviewKeyEvent { event ->
                    val step = when (event.key) {
                        Key.DirectionLeft -> -PlayerChromePolicy.SEEK_STEP_MS
                        Key.DirectionRight -> PlayerChromePolicy.SEEK_STEP_MS
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        scrub.targetMs = PlayerChromePolicy.stepTargetMs(scrub.targetMs ?: times.positionMs(), step, times.durationMs(), live)
                    }
                    true
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(BarHeight)
                    .graphicsLayer { scaleY = RestingBarScale + (1f - RestingBarScale) * focus }
                    .chapterCutTrack(times, chapters, segments),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((headFraction * width - HeadWidth.toPx() / 2).roundToInt(), 0) }
                    .size(HeadWidth, HeadHeight)
                    .graphicsLayer {
                        alpha = focus
                        scaleY = RestingHeadScale + (1f - RestingHeadScale) * focus
                    }
                    .drawBehind {
                        val radius = CornerRadius(size.width / 2)
                        val ring = HeadRing.toPx()
                        drawRoundRect(HeadRingColor, Offset(-ring, -ring), Size(size.width + 2 * ring, size.height + 2 * ring), CornerRadius(radius.x + ring))
                        drawRoundRect(KeColors.Ink, cornerRadius = radius)
                    },
            )
        }
    }
}

/** The track: rest, buffered, played, then the segments, clipped to the chapters (positions read
 *  at draw time). */
private fun Modifier.chapterCutTrack(times: ScrubTimes, chapters: List<Chapter>, segments: List<Segment>): Modifier = drawWithCache {
    val duration = times.durationMs()
    val gap = ChapterGap.toPx()
    val pieces = Path().apply {
        val cuts = if (duration > 0) chapters.drop(1).map { it.startSeconds * 1000f / duration * size.width } else emptyList()
        var start = 0f
        for (cut in cuts + size.width) {
            val end = if (cut >= size.width) size.width else cut - gap / 2
            if (end > start) addRect(Rect(start, 0f, end, size.height))
            start = cut + gap / 2
        }
    }
    val radius = CornerRadius(BarRadius.toPx())
    val segmentRadius = CornerRadius(SegmentRadius.toPx())
    onDrawBehind {
        val total = times.durationMs()
        clipPath(pieces) {
            drawRoundRect(RestTrack, cornerRadius = radius)
            if (total > 0) {
                drawFraction(times.bufferedMs().toFloat() / total, BufferedTrack, radius)
                drawFraction(times.positionMs().toFloat() / total, KeColors.Ink, radius)
                for (segment in segments) {
                    val start = size.width * segment.startMs / total
                    val end = size.width * segment.endMs.coerceAtMost(total) / total
                    if (end > start) {
                        drawRoundRect(
                            segment.category.color,
                            topLeft = Offset(start, 0f),
                            size = Size(end - start, size.height),
                            cornerRadius = segmentRadius,
                            alpha = SegmentAlpha,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawFraction(fraction: Float, color: Color, radius: CornerRadius) {
    val w = size.width * fraction.coerceIn(0f, 1f)
    if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = radius)
}

/** How far behind live a live scrub's target is (`.lbubble`): "−24:00" boxed, "behind live" under it. */
@Composable
private fun LiveBubble(behindMs: Long, modifier: Modifier = Modifier) {
    Column(modifier.width(PreviewWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = behindLiveText(behindMs),
            style = MaterialTheme.typography.headlineSmall.atWeight(680).copy(fontSize = 16.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier
                .background(BubbleFill, BubbleShape)
                .hairlineBorder(Color.White.copy(alpha = 0.85f), 7.dp)
                .padding(start = 11.dp, end = 11.dp, top = 5.dp, bottom = 6.dp),
        )
        Text(
            text = "behind live",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = KeColors.Ink2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val BubbleShape = RoundedCornerShape(7.dp)
private val BubbleFill = PlayerShade.copy(alpha = 0.9f)

/** The frame at [atMs] (when YouTube offers a storyboard), the time and its chapter. */
@Composable
private fun Preview(atMs: Long, chapters: List<Chapter>, storyboard: Storyboard?, prefetch: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(PreviewWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        storyboard?.let { board -> StoryboardFrameImage(board, atMs, prefetch) }
        Text(
            text = timeText(atMs),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(top = 7.dp),
        )
        chapterAt(chapters, atMs)?.let {
            Text(
                text = it.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = KeColors.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One storyboard frame, cut from its sprite page (decoded at its own size, TVU-3). The preview is
 * always 16:9; a square or portrait video's frame sits inside it at its own
 * shape on black, as the player shows the video. Until the frame's own page lands the cell is
 * blank (PERF-D6), never another page's frame; with [prefetch] (the bar focused) the pages either
 * side are loaded too, so a scrub across a page edge usually finds its page waiting.
 * A page can be a few pixels narrower than the grid implies (NewPipe#11596): the cut is clamped.
 */
@Composable
private fun StoryboardFrameImage(board: Storyboard, atMs: Long, prefetch: Boolean) {
    val frame = storyboardFrame(board, atMs) ?: return
    val pages = remember(board) { mutableStateMapOf<Int, ImageBitmap>() }
    StoryboardPageLoads(board, pageWindow(frame.page, board.pageUrls.lastIndex, prefetch), pages)
    Box(
        Modifier
            .size(PreviewWidth, PreviewFrameHeight)
            .dropShadow(PreviewShape) {
                radius = 30.dp.toPx()
                spread = (-5).dp.toPx()
                offset = Offset(0f, 12.dp.toPx())
                color = Color.Black.copy(alpha = 0.8f)
            }
            .clip(PreviewShape)
            .drawBehind {
                val image = pages[frame.page]
                if (image == null) drawRect(KeColors.Surface2) else drawFrame(image, board, frame)
            }
            .hairlineBorder(Color.White.copy(alpha = 0.85f), 6.dp),
    )
}

/** Loads each page of [window] into [pages] (Coil decodes off the main thread and keeps them in
 *  its memory cache); a page leaving the window is dropped from [pages]. */
@Composable
private fun StoryboardPageLoads(board: Storyboard, window: IntRange, pages: SnapshotStateMap<Int, ImageBitmap>) {
    val context = LocalContext.current
    for (index in window) {
        key(index) {
            // A page stays loaded while it is in the window; only a context change restarts
            // this with the page already held. Leaving the window drops it (the DisposableEffect).
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
    val fitted = fittedSize(Size(crop.width.toFloat(), crop.height.toFloat()), size)
    drawImage(
        image,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset(((size.width - fitted.width) / 2).roundToInt(), ((size.height - fitted.height) / 2).roundToInt()),
        dstSize = IntSize(fitted.width.roundToInt(), fitted.height.roundToInt()),
    )
}
