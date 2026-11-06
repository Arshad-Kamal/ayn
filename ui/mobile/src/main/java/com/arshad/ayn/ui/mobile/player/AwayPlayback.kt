package com.arshad.ayn.ui.mobile.player

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerUiState
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.player.awaitStarted
import com.arshad.ayn.feature.player.followSkips
import com.arshad.ayn.feature.player.requestTurn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The turn last asked for on a controller, by the watch page or by [playAway]: each asks only for
 * a turn the other hasn't, so a return doesn't ask again for what was asked away (an ended video
 * would replay) and going away doesn't ask again for what the page asked (a failed one would retry).
 */
internal class AskedTurn {
    private var controller: Player? = null
    private var turn = -1

    fun isAsked(controller: Player, turn: Int): Boolean = this.controller === controller && this.turn == turn

    fun mark(controller: Player, turn: Int) {
        this.controller = controller
        this.turn = turn
    }
}

/**
 * Background audio (M6-D5): while Ayn is away ([lifecycle] below started) the watch page doesn't
 * compose, so the playing turn's skips, its start (its History card) and its end run here, and
 * Play all's next video is asked for from here. Back on screen this stops and the page takes over, its own skips ticking again.
 */
internal suspend fun playAway(lifecycle: Lifecycle, controller: Player, view: PlaybackView, viewModel: PlayerViewModel, asked: AskedTurn) {
    lifecycle.currentStateFlow.collectLatest { now ->
        if (!now.isAtLeast(Lifecycle.State.STARTED)) awayTurns(lifecycle, controller, view, viewModel, asked)
    }
}

private suspend fun awayTurns(lifecycle: Lifecycle, controller: Player, view: PlaybackView, viewModel: PlayerViewModel, asked: AskedTurn) {
    var state = viewModel.uiState.value
    // Left between a turn's start and the page's request for it: ask here.
    if (!asked.isAsked(controller, state.turn)) withContext(NonCancellable) { request(lifecycle, controller, view, viewModel, state, asked) }
    while (true) {
        val turn = state.turn
        val videoId = state.video.id.value
        coroutineScope {
            val skips = launch {
                controller.followSkips { positionMs ->
                    viewModel.segmentTick(turn, view.dressing?.takeIf { view.carries(videoId) }?.segments, positionMs)
                }
            }
            val start = launch {
                view.awaitStarted(videoId)
                viewModel.playbackStarted(turn)
            }
            snapshotFlow { view.carries(videoId) && view.playbackState == Player.STATE_ENDED }.first { it }
            skips.cancel()
            start.cancel()
        }
        // An end is a start too (one that ended before its start was seen still played).
        viewModel.playbackStarted(turn)
        viewModel.ended(turn)
        // A single video ends on Up next (Q27): nothing more until Ayn is back.
        state = viewModel.uiState.first { it.turn != turn }
        // Marked as asked before it suspends, so a return mid-request must not drop it.
        withContext(NonCancellable) { request(lifecycle, controller, view, viewModel, state, asked) }
    }
}

private suspend fun request(lifecycle: Lifecycle, controller: Player, view: PlaybackView, viewModel: PlayerViewModel, state: PlayerUiState, asked: AskedTurn) {
    asked.mark(controller, state.turn)
    controller.requestTurn(
        lifecycle,
        state.video.id,
        short = state.shorts != null,
        startingOver = state.startOver,
        afresh = false,
        resumePointMs = viewModel::resumePointMs,
        startSpeedPercent = viewModel::startSpeedPercent,
        away = true,
    ) {}
    view.request(state.video.id.value)
    controller.play()
}
