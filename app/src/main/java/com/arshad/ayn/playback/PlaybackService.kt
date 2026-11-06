package com.arshad.ayn.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.arshad.ayn.AppContainer
import com.arshad.ayn.KeApplication
import com.arshad.ayn.MainActivity
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.sabr.SabrBridge
import com.arshad.ayn.core.store.DEFAULT_MAX_VIDEO_HEIGHT
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/**
 * Production player (Register D3 / MED-1): owns the `ExoPlayer` + `MediaSession`; the UI drives
 * it through a `MediaController` and [PlaybackControlPlane] resolves what it asks for. The
 * player core: VP9 pin (TRK-1), display-mode viewport (TRK-3), 4K/60 ceilings (TRK-2), audio
 * focus via Media3 (MED-11), `setSessionActivity` (MED-10), and a player-wide source factory
 * that builds each resolved item's source ([ItemSourceFactory]: SABR, or HLS for a live stream).
 * The service also tears down the app-scoped PO-token source and `n` solver in [onDestroy].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var controlPlane: PlaybackControlPlane

    /** Pause breadcrumbs: every pause is observable with its cause. Our own pause paths log at
     *  their source (last controller left, D8 terminal), so an unmatched entry here points at
     *  Media3 (audio-focus loss / becoming-noisy) or a controller command. Transient focus loss
     *  does not flip `playWhenReady`: it pauses via playback suppression, logged separately. */
    private val breadcrumbs = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                Log.i(PLAYBACK_LOG_TAG, "paused: reason=${playWhenReadyChangeReasonName(reason)} state=${player.playbackState} controllers=${mediaSession.connectedControllers.size}")
            }
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                Log.i(PLAYBACK_LOG_TAG, "suppressed: reason=${playbackSuppressionReasonName(reason)} state=${player.playbackState}")
            }
        }
    }

    /** Playback keeps a locked profile open while KE is in the foreground (§ B3p), and counts
     *  as a kids profile's watch time (KP-D8). */
    private val playingSignal = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            container.relockMonitor.onPlayingChanged(isPlaying)
            container.kidsTimer.onPlayingChanged(isPlaying)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as KeApplication).container
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this).build()
        val sabrBridge = SabrBridge(
            bandwidthEstimator = bandwidthMeter,
            httpClient = container.httpClient,
        )
        val (viewportWidth, viewportHeight) = displayModeViewport()
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // TRK-1 defense-in-depth; the hard pin is the format list in SabrBridge.
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_VP9)
                // TRK-3: the display mode's size, not the app-visible size.
                .setViewportSize(viewportWidth, viewportHeight, false)
                // TRK-2 ceilings for the reference-device class (4K/60 SDR).
                .setMaxVideoFrameRate(60)
                .setMaxVideoSize(3840, 2160)
                .build()
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(ItemSourceFactory(sabrBridge, OkHttpDataSource.Factory(container.httpClient)))
            // TRK-6/M3-DISPLAY-02: the app owns the display plane (D9 AFR), so Media3's
            // change-frame-rate signaling is off — never two owners of one plane.
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            // The notification and lock screen's back 10 and forward 10 (M6 Q30).
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true, // MED-11: Media3 owns focus, no hand-rolled code
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(breadcrumbs)
                addListener(playingSignal)
            }
        controlPlane = PlaybackControlPlane(
            player = player,
            scope = serviceScope,
            resolve = ItemResolver(
                extract = ::extract,
                segments = container.segmentSource,
                settings = { container.profileSettingsStore.settings().first().settings },
                keepRelated = { metadata -> serviceScope.keepRelatedList(container.relatedListStore, metadata) },
                shortsHidden = { !container.contentFilters.shortsShownNow() },
                playTime = container.kidsTimer::current,
                branding = container.cardBranding,
                contentFilter = container.contentFilters::now,
            )::resolve,
            currentProfileId = container.profileSession::currentId,
            savePosition = container.positionWriter::save,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    // MED-10: Now Playing returns to the app, on a phone to the watch page.
                    Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setCallback(controlPlane)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .setDisplayName(getString(androidx.media3.session.R.string.media3_controls_seek_back_description))
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .setDisplayName(getString(androidx.media3.session.R.string.media3_controls_seek_forward_description))
                        .build(),
                ),
            )
            .build()
        controlPlane.attach(mediaSession)
        container.playbackHandle.attach(controlPlane)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    /** M6-D9: a kids profile's video shows no media notification and so no lock-screen controls;
     *  the update that takes a notification away (nothing held) still goes through. */
    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        if (container.kidsProfileOpen() && session.player.currentMediaItem != null) return Futures.immediateVoidFuture()
        return super.onUpdateNotificationAsync(session, startInForegroundRequired)
    }

    override fun onDestroy() {
        container.playbackHandle.detach(controlPlane)
        serviceScope.cancel()
        mediaSession.release() // MED-2: session first, then player; never inside a listener
        player.release()
        container.relockMonitor.onPlayingChanged(false) // release tells no listener
        container.kidsTimer.onPlayingChanged(false)
        container.poTokenSource.close() // a no-op unless something minted
        container.nChallengeSolver.close() // a no-op unless something was solved
        super.onDestroy()
    }

    /** Extraction, then the live and manifest checks ([playableFor]), so a bad stream fails the
     *  resolve, not the player. */
    private suspend fun extract(videoId: VideoId): ExtractedVideo {
        val metadata = container.extractor.video(videoId)
        return ExtractedVideo(metadata, playableFor(metadata, container::kidsProfileOpenNow, ::maxVideoHeight))
    }

    /** The TV's quality cap; a failed read caps nothing below the default. */
    private suspend fun maxVideoHeight(): Int =
        try {
            container.deviceSettingsStore.settings().first().maxVideoHeight
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "device settings read failed: ${e.javaClass.simpleName}")
            DEFAULT_MAX_VIDEO_HEIGHT
        }

    /** TRK-3 / M3-TRACK-03: viewport = the display mode's size, stated landscape. */
    private fun displayModeViewport(): Pair<Int, Int> {
        val display = getSystemService(Context.DISPLAY_SERVICE).let {
            (it as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        }
        val mode = display.mode
        return maxOf(mode.physicalWidth, mode.physicalHeight) to
            minOf(mode.physicalWidth, mode.physicalHeight)
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L

        /** Field-readable breadcrumb names (Player.PLAY_WHEN_READY_CHANGE_REASON_*; the set
         *  verified against the installed media3-common 1.11.1 — includes
         *  SUPPRESSED_TOO_LONG, which predates 1.11 but is easy to miss). */
        fun playWhenReadyChangeReasonName(reason: Int): String = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user_request"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_media_item"
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed_too_long"
            else -> "unknown=$reason"
        }

        /** Field-readable breadcrumb names (Player.PLAYBACK_SUPPRESSION_REASON_*; the
         *  non-NONE set verified against the installed media3-common 1.11.1). */
        fun playbackSuppressionReasonName(reason: Int): String = when (reason) {
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient_audio_focus_loss"
            Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE -> "unsuitable_audio_route"
            Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable_audio_output"
            Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING -> "scrubbing"
            else -> "unknown=$reason"
        }
    }
}
