package com.arshad.ayn.feature.player

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * The player control-plane contract between the player screens (`:ui:tv`, `:ui:mobile`) and the
 * production `MediaSessionService` (`:app`): one shared home so both sides agree without either
 * module depending on the other.
 *
 * Commands and most state are Media3's own: the screen sets a media item whose media id is the
 * video id (the service resolves it in `onSetMediaItems`), the title rides `MediaMetadata`, the
 * content frame rate rides the controller's `currentTracks`, the chrome's extras ride the item's
 * `MediaMetadata.extras` ([PlaybackDressing]). The D8 state Media3 has no field for rides the
 * session extras under these keys; empty extras mean playback is fine.
 */
object PlaybackContract {
    /** One logcat filter for every playback breadcrumb (service, control plane, screen). */
    const val LOG_TAG = "KePlayback"

    /** A request item's `requestMetadata.extras` flag: the video is a Short (M7-D13), so the
     *  service asks no SponsorBlock, loops it and saves its place as its start. */
    private const val REQUEST_SHORT = "ke.short"

    /** The request extras the screen sends for a video: the Short flag, or none. */
    fun requestExtras(short: Boolean): Bundle? = if (short) Bundle().apply { putBoolean(REQUEST_SHORT, true) } else null

    /** Whether a request's extras ([requestExtras]) ask for a Short. */
    fun isShortRequest(extras: Bundle?): Boolean = extras?.getBoolean(REQUEST_SHORT) == true

    /** Why playback stopped, one of the `PlayerPolicy.TERMINAL_*`; absent while it runs. */
    const val EXTRA_STOP_REASON = "stopReason"

    /** A stop's extra words (`resolveStopDetail`: YouTube's sentence for a not-started video); absent when none. */
    const val EXTRA_STOP_DETAIL = "stopDetail"

    /** The D8 retry in progress, [RETRY_RECOVERABLE] or [RETRY_ATTESTATION]; absent when none
     *  is. */
    const val EXTRA_RETRY_KIND = "retryKind"
    const val RETRY_RECOVERABLE = "retrying"
    const val RETRY_ATTESTATION = "refreshing"
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_MAX = "max"

    /** Where a retry or a stop keeps the viewer's place (0 when nothing is kept). */
    const val EXTRA_POSITION_MS = "positionMs"

    /**
     * The re-entry rule: the same video already loaded and LIVE (READY or BUFFERING —
     * not IDLE, not ENDED), playing as asked (a Short or a video), resumes; anything else
     * resolves fresh. An ended item must re-resolve: `playWhenReady` alone never restarts ENDED
     * playback, and a fresh resolve also refreshes a stale stream URL. A Short and the same
     * video as a video play differently (loop, SponsorBlock, saves), so one never resumes as the
     * other; [playingShort] is whether the loaded item repeats as a Short does.
     */
    fun shouldRestartResolve(
        requestedVideoId: String,
        currentMediaId: String?,
        playerIsLive: Boolean,
        requestedShort: Boolean,
        playingShort: Boolean,
    ): Boolean = requestedVideoId != currentMediaId || !playerIsLive || requestedShort != playingShort
}

/** A player screen's request for [videoId]: the service resolves it, a Short flagged (M7-D13).
 *  The service's tests send it too. */
fun playbackRequest(videoId: String, short: Boolean): MediaItem =
    MediaItem.Builder()
        .setMediaId(videoId)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(PlaybackContract.requestExtras(short)).build())
        .build()
