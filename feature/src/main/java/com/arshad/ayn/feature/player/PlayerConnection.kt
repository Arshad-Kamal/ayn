package com.arshad.ayn.feature.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** A player screen's [MediaController] on the playback service, and the session extras it carries (D8). */
@Stable
class PlayerConnection {
    var controller: MediaController? by mutableStateOf(null)
        private set

    /** The service didn't answer (it couldn't be bound, or it went away). */
    var isDown: Boolean by mutableStateOf(false)
        private set

    var sessionExtras: Bundle by mutableStateOf(Bundle.EMPTY)
        private set

    private fun connected(controller: MediaController) {
        sessionExtras = controller.sessionExtras
        this.controller = controller
    }

    private fun extrasChanged(extras: Bundle) {
        sessionExtras = extras
    }

    private fun lost() {
        controller = null
        isDown = true
    }

    /**
     * Binds a controller to the session [sessionToken] names; the returned release pauses first, so
     * nothing plays once the screen that bound it is gone, then lets the controller go. Each UI calls
     * this from its player screen's scope (M3-PLAYER-04 deviation, stack-notes: not `onStop`). A token
     * that can't be resolved reads as a service that didn't answer, never a crash.
     */
    fun bind(context: Context, sessionToken: (Context) -> SessionToken): () -> Unit {
        var future: ListenableFuture<MediaController>? = null
        try {
            val building = MediaController.Builder(context, sessionToken(context))
                .setListener(
                    object : MediaController.Listener {
                        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = extrasChanged(extras)

                        override fun onDisconnected(controller: MediaController) = lost()
                    },
                )
                .buildAsync()
            future = building
            building.addListener(
                {
                    // Cancelled when the screen left first; nothing reads this connection then.
                    if (building.isCancelled) return@addListener
                    val result = runCatching { building.get() }
                    result.exceptionOrNull()?.let { Log.w(PlaybackContract.LOG_TAG, "player session didn't connect", it) }
                    result.getOrNull()?.let(::connected) ?: lost()
                },
                context.mainExecutor,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(PlaybackContract.LOG_TAG, "player session unresolvable", e)
            lost()
        }
        return {
            controller?.pause()
            future?.let(MediaController::releaseFuture)
        }
    }
}

/** Why playback stopped, from the session extras: a `PlayerPolicy.TERMINAL_*` reason, where a
 *  Retry resumes (0 when nothing is kept) and the stop's [detail] (`resolveStopDetail`); `null`
 *  while it runs. */
data class PlaybackStop(val reason: String, val positionMs: Long, val detail: String? = null)

fun stopFrom(extras: Bundle): PlaybackStop? =
    extras.getString(PlaybackContract.EXTRA_STOP_REASON)?.let {
        PlaybackStop(it, extras.getLong(PlaybackContract.EXTRA_POSITION_MS), extras.getString(PlaybackContract.EXTRA_STOP_DETAIL))
    }

/** The D8 retry in progress, from the session extras ([PlayerErrorUiState.None] when none runs). */
fun retryFrom(extras: Bundle): PlayerErrorUiState {
    val attempt = extras.getInt(PlaybackContract.EXTRA_ATTEMPT)
    val max = extras.getInt(PlaybackContract.EXTRA_MAX)
    return when (extras.getString(PlaybackContract.EXTRA_RETRY_KIND)) {
        PlaybackContract.RETRY_RECOVERABLE -> PlayerErrorUiState.Retrying(attempt, max)
        PlaybackContract.RETRY_ATTESTATION -> PlayerErrorUiState.Refreshing(attempt, max)
        else -> PlayerErrorUiState.None
    }
}
