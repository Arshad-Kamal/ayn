package com.arshad.ayn.playback

import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.arshad.ayn.core.access.LivePlayable
import com.arshad.ayn.core.access.PlaybackFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.sabr.causeChain
import com.arshad.ayn.core.access.sabr.causeChainClasses
import com.arshad.ayn.core.access.sabr.classifyPlaybackFailure
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.PlaybackRetrySession
import com.arshad.ayn.feature.player.PlayerErrorUiState
import com.arshad.ayn.feature.player.playbackErrorUiState
import com.arshad.ayn.feature.player.resolveStopDetail
import com.arshad.ayn.feature.player.resolveStopReason
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The session's control plane on Media3's own pattern. A controller sets one media item whose
 * media id is a video id; [onSetMediaItems] resolves it on [scope] through [resolve] into a
 * playable item (title in `MediaMetadata`, its SABR or live playable in the tag, built into a
 * source by the player's media source factory), and Media3 holds that controller's later
 * commands until the resolve completes. Failures run the D8 policy; retry progress and why playback stopped go
 * out in the session extras ([PlaybackContract]). Not as the session's playback exception: while
 * one is set, Media3 withdraws the controllers' player commands, so the screen could not retry.
 *
 * Only the app's own controllers and trusted system controllers connect (the service is
 * exported for media-button routing). When the app's last UI controller leaves, the resolve is
 * cancelled and playback pauses: no video outside the player screen (D1/TVU-9).
 *
 * It also keeps the playing video's place (M5-D7/D8): every [POSITION_SAVE_INTERVAL_MS] while
 * playing, whenever playback stops moving, at the end, when the screen leaves, before a new
 * request replaces the item, and before a sync when it moved since the last save. Each save goes to [savePosition] under the profile captured when the
 * video was asked for ([currentProfileId]), so a profile switch never moves its writes. A request
 * flagged a Short ([PlaybackContract.isShortRequest], M7-D13) repeats (Q35) and saves its place
 * as its start: History orders by the latest save, and a Short keeps no resume point (Q39);
 * [resolve] gets the flag.
 *
 * A live item (LIVE-D4, D20, D23) always starts at its default position (the live edge less the
 * target offset) and at 1×, whatever the requester asked, and is saved as a Short is, with no
 * length and never as ended. Falling behind the live window rejoins the edge without spending a
 * retry; a retry while the item is dynamic goes to the edge too; a 403 on a live item re-resolves
 * (a fresh manifest URL, as for the ~6 h expiry) within the attestation budget; each recovery
 * (READY) re-arms the budgets.
 */
@OptIn(UnstableApi::class)
class PlaybackControlPlane(
    private val player: Player,
    private val scope: CoroutineScope,
    private val resolve: suspend (videoId: VideoId, short: Boolean) -> MediaItem,
    private val currentProfileId: suspend () -> Long,
    private val savePosition: (PositionSave) -> Unit,
) : MediaSession.Callback {

    private val appUid = Process.myUid()

    private lateinit var session: MediaSession
    private var resolveJob: Job? = null
    private var pendingResolve: SettableFuture<MediaItemsWithStartPosition>? = null
    private var refreshJob: Job? = null
    private val retrySession = PlaybackRetrySession()

    /** A behind-live-window rejoin is under way; a second one before READY counts as D8 recoverable. */
    private var rejoiningLive = false

    /** The video the last landed resolve set, and whose profile its place belongs to. */
    private var playing: PlayingVideo? = null
    private var saveTicks: Job? = null
    private var lastSave: PositionSave? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onPlaybackFailure(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    // A successful prepare ends a retry or a stop (a media key can resume a
                    // stopped item) and moots any kept position.
                    retrySession.clearKeptPosition()
                    rejoiningLive = false
                    // A live stream runs for hours: each recovered incident (a blip, a ~6 h URL
                    // expiry) re-arms the budgets, so only one that never recovers stops it.
                    if (playing?.live == true) retrySession.rearm()
                    clearExtras()
                }
                Player.STATE_ENDED -> saveCurrent(ended = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveTicks?.cancel()
            if (isPlaying) {
                saveTicks = scope.launch {
                    while (true) {
                        delay(POSITION_SAVE_INTERVAL_MS)
                        saveCurrent()
                    }
                }
            } else {
                saveCurrent() // a pause, a stall, a failure: wherever it stopped moving
            }
        }
    }

    /** Wires the built [session] (it needs this callback first) and starts observing the player. */
    fun attach(session: MediaSession) {
        this.session = session
        player.addListener(playerListener)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult =
        if (controller.uid == appUid || controller.isTrusted) {
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        } else {
            MediaSession.ConnectionResult.reject()
        }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (session.connectedControllers.none { isAppUi(it) }) {
            Log.i(PLAYBACK_LOG_TAG, "last app controller left: resolveActive=${resolveJob?.isActive} state=${player.playbackState}")
            cancelResolve()
            clearExtras() // the next screen starts clean; a kept Retry position lives on
            saveCurrent() // a seek while paused moved the place without a pause to save it
            player.pause()
        }
    }

    /** Items only enter through a resolve; an item added as-is would reach the source factory
     *  without its playable. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("items enter through a resolve"))

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val request = mediaItems.singleOrNull()
        val videoId = request?.mediaId?.let { runCatching { VideoId(it) }.getOrNull() }
            ?: return Futures.immediateFailedFuture(UnsupportedOperationException("one video id per request"))
        val short = PlaybackContract.isShortRequest(request.requestMetadata.extras)
        // A request ends the current item, so a resolve that never lands (cancelled, failed)
        // leaves nothing for the requester's queued prepare/play to start.
        endCurrentItem()
        val result = SettableFuture.create<MediaItemsWithStartPosition>()
        // Undispatched: the profile is captured now, in this call, whatever the scope's dispatcher.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val profileId = currentProfileId()
            resolveCatching(videoId, short).fold(
                onSuccess = { item ->
                    // Consumed only once the resolve succeeds: one that dies keeps the position
                    // for the next same-video Retry (PlaybackRetrySession.begin scopes it to the
                    // video). Otherwise the requester's start (a resume point, 0 to start over,
                    // or C.TIME_UNSET). A live item ignores both and opens at its default
                    // position (LIVE-D4); its kept position is consumed unused.
                    val kept = retrySession.begin(videoId.value)
                    val live = item.localConfiguration?.tag is LivePlayable
                    val startMs = if (live) C.TIME_UNSET else kept ?: startPositionMs
                    // At 1× whatever speed the requester set (LIVE-D23): faster runs into the
                    // edge, slower drifts off it.
                    if (live) player.setPlaybackSpeed(1f)
                    playing = PlayingVideo(videoId, profileId, short, live)
                    player.repeatMode = if (short) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    result.set(MediaItemsWithStartPosition(listOf(item), 0, startMs))
                },
                onFailure = { e -> failResolve(videoId, e, result) },
            )
        }
        job.invokeOnCompletion { cause -> if (cause != null) result.cancel(false) }
        resolveJob = job
        pendingResolve = result
        return result
    }

    /**
     * A re-lock or a profile switch (M5-D3): saves the current video's place under its own
     * profile, then stops the player and drops the item, so no system control can show or
     * resume it, and no kept Retry position carries over to the next profile.
     */
    fun endSession() {
        endCurrentItem()
        retrySession.clearKeptPosition()
    }

    /** Whether the player holds a video (playing, paused, stopped or ended). */
    fun holdsItem(): Boolean = player.currentMediaItem != null

    /** Saves the current video's place now, playback going on, when it differs from the last save:
     *  a sync reads it next (M8-D8), and an unchanged place rewritten would stamp the video watched
     *  now and set off another sync. */
    fun saveMovedPlace() {
        saveCurrent(onlyIfMoved = true)
    }

    /** Saves the current video's place while it is still the current item (M5-D8), then cancels
     *  any resolve and stops and clears the player. */
    private fun endCurrentItem() {
        saveCurrent()
        playing = null
        rejoiningLive = false
        cancelResolve()
        clearExtras()
        player.stop()
        player.clearMediaItems()
    }

    /** D8: classify, map to the UI state, and act within the budgets. */
    internal fun onPlaybackFailure(error: PlaybackException) {
        if (refreshJob?.isActive == true) {
            // The refused session, prepared again meanwhile (a controller's play prepares from
            // IDLE): the refresh replaces it, so this spends nothing.
            Log.i(PLAYBACK_LOG_TAG, "failure during a refresh: left to the refresh")
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && !rejoiningLive) {
            // The window moved past the picture (a long pause): not a failure of the stream.
            Log.i(PLAYBACK_LOG_TAG, "behind the live window: rejoining the edge")
            rejoiningLive = true
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val failure = classify(error)
        Log.e(PLAYBACK_LOG_TAG, "playback failed: $failure")
        retrySession.recordFailure(player.currentMediaItem?.mediaId, player.currentPosition)
        val attemptsSpent = when (failure) {
            is PlaybackFailure.Recoverable -> retrySession.recoverableAttempts
            is PlaybackFailure.Attestation -> retrySession.attestationAttempts
            is PlaybackFailure.ProtocolFatal -> 0
        }
        when (val ui = playbackErrorUiState(failure, attemptsSpent)) {
            is PlayerErrorUiState.Retrying -> {
                retrySession.spendRecoverable(ui.attempt)
                showRetry(PlaybackContract.RETRY_RECOVERABLE, ui.attempt, ui.max)
                reprepare()
            }
            is PlayerErrorUiState.Refreshing -> {
                retrySession.spendAttestation(ui.attempt)
                showRetry(PlaybackContract.RETRY_ATTESTATION, ui.attempt, ui.max)
                refreshSession()
            }
            is PlayerErrorUiState.Terminal -> {
                Log.i(PLAYBACK_LOG_TAG, "terminal pause: ${ui.reason}") // breadcrumb: the pause below is ours
                showStop(ui.reason, player.currentPosition)
                rejoiningLive = false // a media key's resume may land behind the window again
                player.pause() // the position stays kept for Retry
            }
            PlayerErrorUiState.None -> Unit
        }
    }

    /** D8's classification, except that a 403 on a live item's playlist or segment asks for a new
     *  resolve: its manifest URL expired or was refused (LIVE-D4). */
    private fun classify(error: PlaybackException): PlaybackFailure =
        if (playing?.live == true && error.isHttpForbidden()) {
            PlaybackFailure.Attestation("live 403")
        } else {
            classifyPlaybackFailure(error)
        }

    /** Cancels the in-flight resolve and its future at once, so the requester's queued commands
     *  run now, against the cleared player, not after a later item lands; and any refresh. */
    private fun cancelResolve() {
        pendingResolve?.cancel(false)
        pendingResolve = null
        resolveJob?.cancel()
        refreshJob?.cancel()
    }

    /**
     * An attestation or reload control from the server (M5-D22): a new resolve opens a new SABR
     * session (a new player request, so new visitorData); re-preparing would only repeat the
     * session the server refused. A new request, a leaving screen or [endSession] cancels it.
     * Not a new request: the budgets stay spent, the profile stays.
     */
    private fun refreshSession() {
        val video = playing ?: return // a playback failure only follows a landed resolve
        refreshJob = scope.launch {
            resolveCatching(video.videoId, video.short).fold(
                onSuccess = { item ->
                    // Where the player is now (the failure position, or wherever a seek moved it);
                    // a live item's edge has moved on, so it rejoins there (LIVE-D4). The answer
                    // decides: a stream that ended meanwhile comes back as a replay (LIVE-D18).
                    val live = item.localConfiguration?.tag is LivePlayable
                    playing = video.copy(live = live)
                    player.setMediaItem(item, /* resetPosition = */ live)
                    player.prepare() // MED-5; playWhenReady holds, so a pause meanwhile stays
                },
                onFailure = { e ->
                    // Kept where the player is now: a seek made meanwhile is where Retry resumes.
                    retrySession.recordFailure(video.videoId.value, player.currentPosition)
                    rejoiningLive = false
                    stopForResolveFailure(video.videoId, e)
                    player.pause() // as a terminal stop
                },
            )
        }
    }

    /**
     * [resolve] with extraction's failures as a result; cancellation flows through (KT-CONC-03).
     * Extraction blocks uninterruptibly, so a resolve cancelled meanwhile returns late: it throws
     * then, so it neither lands nor publishes a stop.
     */
    private suspend fun resolveCatching(videoId: VideoId, short: Boolean): Result<MediaItem> {
        val outcome = try {
            Result.success(resolve(videoId, short))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // The vendored trees run outside the seam's guarded wall; D8's fatal row (API-band)
            // lands here rather than crashing the process.
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    private fun failResolve(videoId: VideoId, failure: Throwable, result: SettableFuture<MediaItemsWithStartPosition>) {
        // The player was cleared for this request; a failed Retry still keeps the earlier place.
        stopForResolveFailure(videoId, failure)
        result.setException(IllegalStateException("resolve failed: ${causeChainClasses(failure)}"))
    }

    private fun stopForResolveFailure(videoId: VideoId, failure: Throwable) {
        Log.e(PLAYBACK_LOG_TAG, "resolve failed for ${videoId.value}: ${causeChainClasses(failure)}")
        showStop(resolveStopReason(failure), retrySession.keptPositionFor(videoId.value), resolveStopDetail(failure))
    }

    /** MED-5: `prepare()` is the only exit from IDLE; the seek holds the failure position, or for
     *  a dynamic item (a live window, not an ended stream) goes to its edge (LIVE-D4).
     *  `playWhenReady` survives untouched, so a user pause during the retry window holds. */
    private fun reprepare() {
        if (player.isCurrentMediaItemDynamic) player.seekToDefaultPosition() else player.seekTo(player.currentPosition)
        player.prepare()
    }

    private fun showRetry(kind: String, attempt: Int, max: Int) {
        publish(
            Bundle().apply {
                putString(PlaybackContract.EXTRA_RETRY_KIND, kind)
                putInt(PlaybackContract.EXTRA_ATTEMPT, attempt)
                putInt(PlaybackContract.EXTRA_MAX, max)
                putLong(PlaybackContract.EXTRA_POSITION_MS, player.currentPosition)
            },
        )
    }

    /** [reason] is a `PlayerPolicy.TERMINAL_*` constant (bounded fixed strings, KT-SEC-02);
     *  [positionMs] is where a Retry resumes (0 when nothing is kept); [detail] is YouTube's own
     *  sentence for a not-started video, one line capped at the seam. */
    private fun showStop(reason: String, positionMs: Long, detail: String? = null) {
        publish(
            Bundle().apply {
                putString(PlaybackContract.EXTRA_STOP_REASON, reason)
                putLong(PlaybackContract.EXTRA_POSITION_MS, positionMs)
                detail?.let { putString(PlaybackContract.EXTRA_STOP_DETAIL, it) }
            },
        )
    }

    private fun publish(extras: Bundle) {
        session.setSessionExtras(extras)
    }

    private fun clearExtras() {
        if (!session.sessionExtras.isEmpty) session.setSessionExtras(Bundle.EMPTY)
    }

    /** The app's own UI controllers (the service's media-notification controller is the app's
     *  too, but it is no screen). */
    private fun isAppUi(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == appUid && !session.isMediaNotificationController(controller)

    /** Saves the current item's place if it is the video the last resolve set (a Short's and a
     *  live stream's as its start; a live stream's without its window's length, never ended). */
    private fun saveCurrent(ended: Boolean = false, onlyIfMoved: Boolean = false) {
        val video = playing ?: return
        if (player.currentMediaItem?.mediaId != video.videoId.value) return
        val save = PositionSave(
            profileId = video.profileId,
            videoId = video.videoId,
            positionMs = if (video.short || video.live) 0 else player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && !video.live },
            ended = ended && !video.live,
        )
        if (onlyIfMoved && save == lastSave) return
        lastSave = save
        savePosition(save)
    }

    private data class PlayingVideo(val videoId: VideoId, val profileId: Long, val short: Boolean, val live: Boolean)
}

/** An HTTP 403 anywhere in [this]'s cause chain (Media3 wraps a refused playlist or segment load). */
private fun PlaybackException.isHttpForbidden(): Boolean =
    causeChain(this).any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == HTTP_FORBIDDEN }

private const val HTTP_FORBIDDEN = 403

/** M5-D7: how often a playing video's place is saved. */
private const val POSITION_SAVE_INTERVAL_MS = 10_000L

/** The playback breadcrumb tag, shared with the player screen (one logcat filter). */
internal const val PLAYBACK_LOG_TAG = PlaybackContract.LOG_TAG
