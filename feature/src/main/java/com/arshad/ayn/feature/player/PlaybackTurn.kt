package com.arshad.ayn.feature.player

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import com.arshad.ayn.core.access.VideoId
import kotlinx.coroutines.flow.first

/**
 * A player screen's request for this turn's video (M5-D8), shared by both UIs. It is asked for once
 * [lifecycle] is started (a request landing behind another screen would play unseen), from its
 * resume point as the store holds it then (a start at 0 would save over it); the same live item,
 * playing as asked ([PlaybackContract.shouldRestartResolve]), isn't asked again. [startingOver] asks
 * from 0; [afresh] (Try again) always asks. Just before a request is sent, [onAsked] gets the point
 * it resumes from (`null`: the store has none). The caller plays once this returns. [away] asks
 * while Ayn is away (a phone's background audio moving on, M6-D5): it doesn't wait for the screen.
 */
suspend fun Player.requestTurn(
    lifecycle: Lifecycle,
    videoId: VideoId,
    short: Boolean,
    startingOver: Boolean,
    afresh: Boolean,
    resumePointMs: suspend (VideoId) -> Long?,
    startSpeedPercent: suspend () -> Int,
    away: Boolean = false,
    onAsked: (resumeMs: Long?) -> Unit,
) {
    if (!away) lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
    val id = videoId.value
    val live = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
    // The control plane repeats exactly the items it resolved as Shorts.
    val playingShort = repeatMode == Player.REPEAT_MODE_ONE
    if (!afresh && !startingOver && !PlaybackContract.shouldRestartResolve(id, currentMediaItem?.mediaId, live, short, playingShort)) return
    val request = playbackRequest(id, short)
    val resumeMs = if (startingOver) 0L else resumePointMs(videoId)
    val speed = startSpeedPercent() / 100f
    if (!away) lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) } // left during the read
    onAsked(resumeMs)
    // The speed and a held quality are this video's: another video starts at the profile's speed
    // and at Auto.
    if (currentMediaItem?.mediaId != id) {
        if (playbackParameters.speed != speed) setPlaybackSpeed(speed)
        holdQuality(null)
    }
    if (resumeMs != null) setMediaItem(request, resumeMs) else setMediaItem(request)
    prepare()
}

/** Suspends until [videoId] has played (or ended) in this view since it was asked for. An item
 *  still ended is the last turn's, until this turn's request lands. */
suspend fun PlaybackView.awaitStarted(videoId: String) {
    snapshotFlow { playbackState }.first { it != Player.STATE_ENDED }
    snapshotFlow { carries(videoId) && (isPlaying || playbackState == Player.STATE_ENDED) }.first { it }
}

/** Leaving the app pauses (D1/TVU-9) unless it [playsAway] then (a phone's background audio,
 *  M6-D5); coming back to a video that hasn't started yet ([startPending]) starts it. */
fun pauseWhileAway(
    pause: () -> Unit,
    play: () -> Unit,
    startPending: () -> Boolean,
    playsAway: () -> Boolean = { false },
): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> if (!playsAway()) pause()
            Lifecycle.Event.ON_START -> if (startPending()) play()
            else -> Unit
        }
    }
