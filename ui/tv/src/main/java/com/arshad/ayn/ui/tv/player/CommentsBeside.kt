package com.arshad.ayn.ui.tv.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.BehindLiveLatch
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout

// `.frame.side` (px ÷ 2): the 1920 × 1080 picture at 49.375 %, its 44 px corners and 4 px ring with it.
private val FrameSize = DpSize(474.dp, 266.625.dp)
private val FrameCorner = 10.9.dp
private val FrameRing = 1.dp
private val FrameRingColor = Color.White.copy(alpha = 0.12f)

// `.cglow`: oklch(30% 0.025 200 / .45), an ellipse 48% × 52% of the screen at 30% / 34%, clear at 72%.
private val GlowColor = Color(0xFF1E3233).copy(alpha = 0.45f)

// `.cside` (px ÷ 2): under the frame, its width.
private val InfoTop = 309.dp
private val BarHeight = 3.dp
private val BarTrack = Color.White.copy(alpha = 0.22f)
private val BarBuffered = Color.White.copy(alpha = 0.36f)
private const val INFO_DELAY_MS = 60

/**
 * Where the picture plays while the comments panel shows over a video (Q7): the whole picture in
 * a frame at the safe lines' top left, aligned top start by the caller. Its SurfaceView can't be
 * scaled, so it moves there at once and [CommentsBeside]'s ground rounds its corners.
 */
internal fun Modifier.commentsFrame(): Modifier =
    padding(start = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical).size(FrameSize)

/**
 * The free side beside the comments panel (`b3-player-panels` cmt-loaded) or a [live] stream's chat
 * panel (`live-tv-player` chat-*): the ground around the picture's frame ([commentsFrame]), and
 * under it the playing position, the times (with the chapter; live, the LIVE tag, how far behind
 * and the window), the channel line and the title, rising in as the panel opens.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun CommentsBeside(video: VideoItem, avatarUrl: String?, chapters: List<Chapter>, player: Player, live: Boolean = false) {
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(player, PlayerChromePolicy.POSITION_TICK_MS, scope)
    val times = remember(progress) { ScrubTimes({ progress.currentPositionMs }, { progress.bufferedPositionMs }, { progress.durationMs }) }
    val latch = remember(live) { BehindLiveLatch() }
    val behind by remember(times, latch, live) { derivedStateOf { live && latch.behind(times.positionMs(), times.durationMs()) } }
    Box(Modifier.fillMaxSize().commentsGround()) {
        Column(
            Modifier
                .padding(start = KeLayout.SafeHorizontal, top = InfoTop)
                .width(FrameSize.width)
                .rising(rememberRise(INFO_DELAY_MS)),
        ) {
            PositionBar(times)
            if (live) {
                LiveTimesRow(times, behind, Modifier.padding(top = 6.dp))
            } else {
                TimesRow(times, chapters, hideElapsed = false, modifier = Modifier.padding(top = 6.dp))
            }
            Box(Modifier.padding(top = 20.dp)) { ChannelLine(video, avatarUrl, live) }
            val face = rememberCardFace(video)
            val arrival = rememberFaceArrival(face != null)
            Text(
                text = face?.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineBreak = LineBreak.Heading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp).graphicsLayer { alpha = arrival.value },
            )
        }
    }
}

/** The played and buffered share of the video (`.cbar`), read at draw time. */
@Composable
private fun PositionBar(times: ScrubTimes) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .drawBehind {
                val corner = CornerRadius(size.height / 2)
                drawRoundRect(BarTrack, cornerRadius = corner)
                val duration = times.durationMs()
                if (duration > 0) {
                    val buffered = (times.bufferedMs().toFloat() / duration).coerceIn(0f, 1f)
                    val played = (times.positionMs().toFloat() / duration).coerceIn(0f, 1f)
                    drawRoundRect(BarBuffered, size = Size(size.width * buffered, size.height), cornerRadius = corner)
                    drawRoundRect(KeColors.Ink, size = Size(size.width * played, size.height), cornerRadius = corner)
                }
            },
    )
}

/**
 * The ground beside the panel (the ground with `.cglow`'s cool light) everywhere but the picture's
 * frame ([commentsFrame]), whose corners it rounds, and the frame's ring.
 */
private fun Modifier.commentsGround(): Modifier = drawWithCache {
    val frame = Rect(
        offset = Offset(KeLayout.SafeHorizontal.toPx(), KeLayout.SafeVertical.toPx()),
        size = Size(FrameSize.width.toPx(), FrameSize.height.toPx()),
    )
    val corner = FrameCorner.toPx()
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(frame, CornerRadius(corner)))
    }
    val glowCenter = Offset(size.width * 0.3f, size.height * 0.34f)
    val glowRadius = size.height * 0.52f
    val glow = Brush.radialGradient(0f to GlowColor, 0.72f to GlowColor.copy(alpha = 0f), center = glowCenter, radius = glowRadius)
    val ring = FrameRing.toPx()
    onDrawBehind {
        clipPath(outside) {
            drawRect(KeColors.Ground)
            // The gradient is a circle; stretched across, it is the mockup's ellipse.
            scale(scaleX = size.width * 0.48f / glowRadius, scaleY = 1f, pivot = glowCenter) { drawRect(glow) }
        }
        drawRoundRect(
            color = FrameRingColor,
            topLeft = frame.topLeft - Offset(ring / 2, ring / 2),
            size = Size(frame.width + ring, frame.height + ring),
            cornerRadius = CornerRadius(corner + ring / 2),
            style = Stroke(ring),
        )
    }
}
