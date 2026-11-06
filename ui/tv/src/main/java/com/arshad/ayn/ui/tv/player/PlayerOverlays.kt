package com.arshad.ayn.ui.tv.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerErrorUiState
import com.arshad.ayn.feature.player.PlaylistPosition
import com.arshad.ayn.feature.player.behindLiveText
import com.arshad.ayn.feature.player.retryLine
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.leadingRule
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.theme.KeColors

private val PillShape = RoundedCornerShape(50)
private val PillFill = Color(0xFF0B0907).copy(alpha = 0.86f)
private val NudgeFill = Color(0xFF080605).copy(alpha = 0.78f)
private val SpinnerTrack = Color.White.copy(alpha = 0.16f)
private const val SpinnerTurnMs = 900

/** A turning ring (the master's `.spin`): [size] across, [width] thick; rotation only (D8). */
@Composable
internal fun Spinner(size: Dp, width: Dp, modifier: Modifier = Modifier) {
    val turn = rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SpinnerTurnMs, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = turn.value }
            .drawBehind {
                val stroke = width.toPx()
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(SpinnerTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(KeColors.Ink, -135f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            },
    )
}

/** Buffering: the spinner and what is loading ("Loading 2160p"), over the dimmed picture. */
@Composable
internal fun BufferingNote(label: String, modifier: Modifier = Modifier, color: Color = KeColors.Ink2) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Spinner(48.dp, 3.dp)
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = color)
    }
}

/** D8 in progress: "Connection dropped · retrying | 2 of 6" (or the session refresh), rising in. */
@Composable
internal fun RetryPill(retry: PlayerErrorUiState, modifier: Modifier = Modifier) {
    val line = retryLine(retry) ?: return
    Row(
        modifier = modifier
            .rising(rememberRise())
            .background(PillFill, PillShape)
            .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Spinner(20.dp, 2.dp)
        Text(line.text, style = MaterialTheme.typography.labelMedium.copy(fontSize = 15.sp))
        Text(
            text = line.count,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier.leadingRule(9.dp),
        )
    }
}

/** A seek while the chrome is hidden: which way, where it goes (the gathered target), of how long
 *  (`b3-player` state 2); on a [live] window, how far behind live it goes, or Live where the seek
 *  [rejoinsLive] the edge (LIVE-D23). */
@Composable
internal fun SeekNudge(
    forward: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    rejoinsLive: Boolean = false,
) {
    Row(
        modifier = modifier
            .rising(rememberRise())
            .background(NudgeFill, PillShape)
            .padding(start = 8.dp, end = 15.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        NudgeDirection(if (forward) R.drawable.ic_fwd10 else R.drawable.ic_back10)
        val behind = live && !rejoinsLive
        val text = when {
            behind -> behindLiveText(PlayerChromePolicy.liveBehindMs(positionMs, durationMs))
            live -> "Live"
            else -> timeText(positionMs)
        }
        Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp, fontFeatureSettings = "tnum"))
        val after = when {
            behind -> "behind live"
            live || durationMs <= 0 -> null
            else -> "of ${timeText(durationMs)}"
        }
        after?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = KeColors.Ink2) }
    }
}

@Composable
private fun NudgeDirection(@DrawableRes icon: Int) {
    Box(Modifier.size(28.dp).background(KeColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = KeColors.Ground, modifier = Modifier.size(17.dp))
        Text(
            "10",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 5.sp),
            color = KeColors.Ground,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * v8 opening: what the screen has before the stream lands, the card's picture far back, its
 * channel line and title where the chrome puts them, and "Starting" (or a D8 retry already under
 * way). Nothing takes focus until it plays.
 */
@Composable
internal fun Opening(
    card: VideoItem,
    playlist: PlaylistPosition?,
    avatarUrl: String?,
    retry: PlayerErrorUiState,
) {
    Box(Modifier.fillMaxSize().background(KeColors.Ground)) {
        BlurredPicture(card.thumbnailUrl, brightness = 0.4f, saturation = 0.7f, decodeHeight = OpeningDecodeHeight)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val top = Brush.verticalGradient(
                    0f to PlayerShade.copy(alpha = 0.86f),
                    0.26f to PlayerShade.copy(alpha = 0.5f),
                    0.44f to PlayerShade.copy(alpha = 0f),
                )
                onDrawBehind { drawRect(top) }
            },
        )
        ChromeTop(card, playlist, avatarUrl, badge = null, status = false)
        if (retry == PlayerErrorUiState.None) {
            BufferingNote("Starting", Modifier.align(Alignment.Center))
        } else {
            RetryPill(retry, Modifier.align(Alignment.Center))
        }
    }
}

// The opening's picture is only softened (the mockup's 6 px blur at 1080p): a 180 px decode.
private const val OpeningDecodeHeight = 180
