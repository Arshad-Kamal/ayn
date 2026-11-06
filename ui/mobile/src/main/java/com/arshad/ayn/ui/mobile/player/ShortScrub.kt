package com.arshad.ayn.ui.mobile.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.SeekGathering
import com.arshad.ayn.feature.player.skippedLengthText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlin.math.roundToInt
import kotlin.math.sign

/** The progress line along the foot (`m6-shorts` scrub 1): the strip it is drawn in (what a screen
 *  reader finds), the line at rest and held, its head, and where the time sits above the line's foot. */
private val ProgressHeight = 16.dp
private val LineHeight = 2.dp
private val HeldLineHeight = 6.dp
private val HeadSize = 16.dp
private val HeadHaloWidth = 4.dp
private val TimeLift = 22.dp
private val TimeEdge = 8.dp

/** A double-tap's mark (`m6-shorts` scrub 2–3): its share of the width, how long it takes to light
 *  and how long it stays after the last tap. */
private const val STEP_MARK_SHARE = 0.42f
private const val STEP_MARK_IN_MS = 200
internal const val STEP_MARK_MS = 600L

/** One side's double-tap steps: which way and how far so far (a new object per tap restarts its time). */
internal class StepMark(val forward: Boolean, val ms: Long)

/** The third of the width [x] falls in: back (left), forward (right), or the middle's `null`. */
internal fun sideAt(x: Float, width: Int): Boolean? = when {
    x < width / 3f -> false
    x > width * 2f / 3f -> true
    else -> null
}

/**
 * A double-tap's mark on its side (`m6-shorts` scrub 2–3): the chevrons in their disc over a
 * half-oval glow and how far the taps went ("5 seconds"), shown while [lit]. A mark on the other
 * side lights up afresh.
 */
@Composable
internal fun BoxScope.StepMarkView(mark: StepMark?, lit: Boolean, modifier: Modifier = Modifier) {
    val back = mark?.forward == false
    key(back) {
        AnimatedVisibility(
            visible = lit && mark != null,
            modifier = modifier.align(if (back) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(STEP_MARK_SHARE),
            enter = fadeIn(tween(STEP_MARK_IN_MS, easing = AynMotion.EaseOut)) +
                scaleIn(tween(STEP_MARK_IN_MS, easing = AynMotion.EaseOut), initialScale = 0.94f),
            exit = fadeOut(AynMotion.base()),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .drawBehind {
                        // An oval twice the width, half of it showing: its flat side on the screen's edge.
                        val edge = if (back) 0f else size.width
                        drawOval(
                            Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.1f)), startX = edge, endX = size.width - edge),
                            topLeft = Offset(if (back) -size.width else 0f, 0f),
                            size = Size(size.width * 2, size.height),
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(56.dp).background(Shade.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_chevrons),
                        contentDescription = null,
                        tint = AynColors.Ink,
                        modifier = Modifier.size(30.dp).graphicsLayer { scaleX = if (back) -1f else 1f },
                    )
                }
                Text(
                    skippedLengthText(mark?.ms ?: 0),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontFeatureSettings = "tnum").atWeight(660),
                )
            }
        }
    }
}

/**
 * A sideways drag anywhere on the Short scrubs it (SSC-D3): it pauses, and [onScrub] follows the
 * finger from where the Short was, the screen's width spanning its whole length (`null` once
 * lifted); letting go seeks there and plays. A vertical swipe crosses its slop first and goes to the
 * page's swipe. Taken away mid-drag (a stop), it ends the hold and leaves the Short paused.
 */
internal fun Modifier.scrubDrag(player: Player, seeks: SeekGathering, onScrub: (Long?) -> Unit): Modifier =
    pointerInput(player, seeks) {
        var fromMs = 0L
        var movedPx = 0f
        var atMs = 0L
        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    fromMs = seeks.targetMs ?: player.currentPosition
                    // The first move reports only what passed the slop: the line follows from the touch.
                    movedPx = Float.NaN
                    atMs = fromMs
                    player.pause()
                    onScrub(atMs)
                },
                onDragEnd = {
                    if (player.duration > 0) seeks.seekTo(atMs)
                    player.play()
                    onScrub(null)
                },
                onDragCancel = {
                    player.play()
                    onScrub(null)
                },
            ) { change, dragAmount ->
                change.consume()
                movedPx = if (movedPx.isNaN()) dragAmount + sign(dragAmount) * viewConfiguration.touchSlop else movedPx + dragAmount
                val durationMs = player.duration.coerceAtLeast(0)
                atMs = (fromMs + movedPx / size.width * durationMs).toLong().coerceIn(0, durationMs)
                onScrub(atMs)
            }
        } finally {
            onScrub(null)
        }
    }

/**
 * The Short's progress along the foot, read at draw time: [scrubMs] while a drag holds it (the
 * line thicker, its head and "position / length" above it), else a gathered step's target, else
 * where playback is. It takes no touches; a screen reader reads it and can seek with it.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun ShortProgress(player: Player, seeks: SeekGathering, scrubMs: Long?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(player, PlayerChromePolicy.POSITION_TICK_MS, scope)
    val positionMs = { scrubMs ?: seeks.targetMs ?: progress.currentPositionMs }
    // 0 while the length is unknown.
    val fraction = { progress.durationMs.let { if (it > 0) (positionMs().toFloat() / it).coerceIn(0f, 1f) else 0f } }
    val held = scrubMs != null
    val line by animateDpAsState(if (held) HeldLineHeight else LineHeight, AynMotion.press(), label = "line")
    val head by animateDpAsState(if (held) HeadSize else 0.dp, AynMotion.press(), label = "head")
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .height(ProgressHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f)
                stateDescription = "${timeText(positionMs())} of ${timeText(progress.durationMs.coerceAtLeast(0))}"
                if (progress.durationMs > 0) {
                    setProgress(label = "Seek") { to ->
                        seeks.seekTo(msAt(to, progress.durationMs))
                        true
                    }
                }
            }
            .drawBehind {
                val height = line.toPx()
                val top = size.height - height
                drawRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, height))
                if (progress.durationMs <= 0) return@drawBehind
                val done = fraction()
                drawRect(AynColors.Ink, Offset(0f, top), Size(size.width * done, height))
                if (head > 0.dp) {
                    val center = Offset(size.width * done, top + height / 2)
                    drawCircle(HeadHalo, head.toPx() / 2 + HeadHaloWidth.toPx(), center)
                    drawCircle(AynColors.Ink, head.toPx() / 2, center)
                }
            },
    ) {
        AnimatedVisibility(
            visible = held,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .layout { measurable, constraints ->
                    // Centred over the head, kept inside the screen, its foot TimeLift above the line's.
                    val time = measurable.measure(Constraints())
                    val edge = TimeEdge.roundToPx()
                    val x = (constraints.maxWidth * fraction() - time.width / 2f).roundToInt()
                        .coerceIn(edge, (constraints.maxWidth - time.width - edge).coerceAtLeast(edge))
                    layout(constraints.maxWidth, 0) { time.place(x, -(TimeLift.roundToPx() + time.height)) }
                },
            enter = fadeIn(AynMotion.press()),
            exit = fadeOut(AynMotion.press()),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AynColors.Ink, fontWeight = FontWeight(700))) { append(timeText(positionMs())) }
                    append(" / ${timeText(progress.durationMs.coerceAtLeast(0))}")
                },
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontFeatureSettings = "tnum").atWeight(600),
                color = AynColors.Ink2,
                modifier = Modifier
                    .background(Shade.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
