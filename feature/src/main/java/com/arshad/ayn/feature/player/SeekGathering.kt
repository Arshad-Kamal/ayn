package com.arshad.ayn.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Steps (±10 s on a video, [PlayerChromePolicy.SHORT_STEP_MS] on a Short) gathered into one seek
 * [PlayerChromePolicy.SEEK_GATHER_MS] after the last (PERF-D6 B1): each step moves [targetMs] on from the last target, else from where playback is, and the
 * screen shows that target until the seek lands. The seek lands only on the video it was aimed at.
 * A direct seek ([seekTo]) replaces a pending one. On a live window the steps stop at its edge,
 * and a seek reaching it, or forward to short of showing as behind, rejoins the edge at the
 * default position (LIVE-D23).
 */
@Stable
class SeekGathering(private val player: Player, private val scope: CoroutineScope) {
    /** Where the pending seek goes; `null` when none waits. */
    var targetMs: Long? by mutableStateOf(null)
        private set

    private var pending: Job? = null

    /** One step; returns where the gathered seek now goes. With no known duration nothing seeks
     *  and it returns where playback is. */
    fun stepBy(deltaMs: Long): Long {
        if (player.duration <= 0) return player.currentPosition
        val target = PlayerChromePolicy.stepTargetMs(targetMs ?: player.currentPosition, deltaMs, player.duration, player.isCurrentMediaItemLive)
        val aimedAt = player.currentMediaItem?.mediaId
        targetMs = target
        pending?.cancel()
        pending = scope.launch {
            delay(PlayerChromePolicy.SEEK_GATHER_MS)
            pending = null
            if (player.currentMediaItem?.mediaId == aimedAt) land(target)
            targetMs = null
        }
        return target
    }

    fun seekTo(positionMs: Long) {
        cancel()
        land(positionMs)
    }

    /** Go live: the live window's edge, wherever a pending seek was going. */
    fun goLive() {
        cancel()
        player.seekToDefaultPosition()
    }

    /** Whether a seek to [targetMs] would rejoin a live window's edge rather than land there; what
     *  a screen's nudge says of a gathered step. */
    fun rejoinsLive(targetMs: Long): Boolean =
        player.isCurrentMediaItemLive && PlayerChromePolicy.rejoinsLiveEdge(player.currentPosition, targetMs, player.duration)

    private fun land(positionMs: Long) {
        if (rejoinsLive(positionMs)) {
            player.seekToDefaultPosition()
        } else {
            player.seekTo(positionMs)
        }
    }

    /** Drops a pending seek (the turn ended or the screen left). */
    fun cancel() {
        pending?.cancel()
        pending = null
        targetMs = null
    }
}
