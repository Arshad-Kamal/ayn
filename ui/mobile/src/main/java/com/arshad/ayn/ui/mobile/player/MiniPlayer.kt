package com.arshad.ayn.ui.mobile.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.LastShown
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

/** The mini player's place above the bar: it rises in while [open] is docked, sinks away after. */
@Composable
internal fun MiniDock(open: OpenPlayer?, onExpand: () -> Unit, onClose: () -> Unit) {
    val last = remember { LastShown<OpenPlayer>() }
    if (open != null) last.value = open
    AnimatedVisibility(
        visible = open != null,
        enter = fadeIn(AynMotion.base()) + slideInVertically(AynMotion.base()) { it / 2 },
        exit = fadeOut(AynMotion.base()) + slideOutVertically(AynMotion.base()) { it / 2 },
    ) {
        // Sinking away after a close, its controller is released: picture and title only.
        (open ?: last.value)?.let { MiniPlayer(it, onExpand, onClose, connected = open != null) }
    }
}

private val ExpandDrag = 24.dp
private const val EXPAND_FLING = 600f

/**
 * The docked player (`m6-player` 36, Q28): picture, title and channel, play/pause and ✕ (closes the
 * player); a line along its top shows how far the video is. A tap or a drag up expands it. Not
 * [connected] (sinking away after a close), it draws without its released controller.
 */
@Composable
internal fun MiniPlayer(open: OpenPlayer, onExpand: () -> Unit, onClose: () -> Unit, connected: Boolean, modifier: Modifier = Modifier) {
    val state by open.viewModel.uiState.collectAsStateWithLifecycle()
    val controller = open.connection.controller?.takeIf { connected }
    val face = rememberCardFace(state.video)
    var dragged by remember { mutableFloatStateOf(0f) }
    val expandAfter = with(LocalDensity.current) { ExpandDrag.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(AynColors.Surface2)
            .draggable(
                rememberDraggableState { dragged += it },
                Orientation.Vertical,
                onDragStarted = { dragged = 0f },
                onDragStopped = { velocity -> if (dragged < -expandAfter || velocity < -EXPAND_FLING) onExpand() },
            )
            .clickable(role = Role.Button, onClickLabel = "Open the watch page", onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(7.dp)).background(AynColors.Surface3)) {
                Picture(face?.thumbnailUrl ?: state.video.thumbnailUrl)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    face?.title ?: state.video.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.5.sp).atWeight(650),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.video.uploaderName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (controller != null) PlayPause(controller)
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Close the player", tint = AynColors.Ink, modifier = Modifier.size(20.dp))
            }
        }
        if (controller != null) ProgressLine(controller, Modifier.align(Alignment.TopStart))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayPause(player: Player) {
    val button = rememberPlayPauseButtonState(player)
    IconButton(onClick = button::onClick, enabled = button.isEnabled) {
        Icon(
            painterResource(if (button.showPlay) R.drawable.ic_play else R.drawable.ic_pause),
            contentDescription = if (button.showPlay) "Play" else "Pause",
            tint = AynColors.Ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ProgressLine(player: Player, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(player, PlayerChromePolicy.POSITION_TICK_MS, scope)
    Box(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            drawRect(AynColors.Ink.copy(alpha = 0.14f))
            val duration = progress.durationMs
            if (duration > 0) {
                val fraction = (progress.currentPositionMs.toFloat() / duration).coerceIn(0f, 1f)
                drawRect(AynColors.Ink, size = Size(size.width * fraction, size.height))
            }
        },
    )
}
