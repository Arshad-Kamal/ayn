package com.arshad.ayn.feature.player

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize

/**
 * The player state a screen draws, kept current by the player's own events (M3-UI-01: snapshot
 * state, no Flows); each UI adds it as the player's listener for its screen's scope. [dressing]
 * belongs to the current item: `null` while it is masked (set by a controller, not yet resolved).
 * [frameRate] is read only from the resolved item the screen [request]ed (a masked item still shows
 * the previous item's tracks, and the service keeps the last screen's video) and keeps the last rate
 * known, so the display mode holds between Play all's videos instead of restoring and switching
 * back. [codec] and [quality] are read from that item too. Every event batch syncs (SABR's loading
 * toggles too), so the dressing is parsed again only for a new extras object and the track fields
 * only for new tracks or parameters: Media3 hands the same objects on while they are unchanged.
 * (`MediaMetadata.equals` ignores extras, so the metadata event alone wouldn't catch a new dressing.)
 */
@Stable
class PlaybackView(private val player: Player) : Player.Listener {
    private var requestedId: String? = null
    private var dressingExtras: Bundle? = null
    private var readTracks: Tracks? = null
    private var readParameters: TrackSelectionParameters? = null
    var mediaId: String? by mutableStateOf(null)
        private set
    var dressing: PlaybackDressing? by mutableStateOf(null)
        private set
    var playbackState: Int by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var playWhenReady: Boolean by mutableStateOf(false)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var videoSize: VideoSize by mutableStateOf(VideoSize.UNKNOWN)
        private set
    var frameRate: Float? by mutableStateOf(null)
        private set
    var codec: String? by mutableStateOf(null)
        private set
    var quality: VideoQuality by mutableStateOf(VideoQuality.None)
        private set

    /** The playback speed, 1 at Normal. */
    var speed: Float by mutableFloatStateOf(1f)
        private set

    override fun onEvents(player: Player, events: Player.Events) = sync()

    /** The current item is [videoId], resolved. */
    fun carries(videoId: String): Boolean = mediaId == videoId && dressing != null

    fun request(videoId: String) {
        requestedId = videoId
        sync()
    }

    fun sync() {
        val item = player.currentMediaItem
        mediaId = item?.mediaId
        // The item's own metadata, not the player's: a controller masking a new item keeps the
        // previous item's `mediaMetadata` until the session answers, which would dress the new id.
        val extras = item?.mediaMetadata?.extras
        // Unread, null extras and no dressing agree: fromBundle(null) is null.
        if (extras !== dressingExtras) {
            dressing = PlaybackDressing.fromBundle(extras)
            dressingExtras = extras
        }
        playbackState = player.playbackState
        playWhenReady = player.playWhenReady
        isPlaying = player.isPlaying
        videoSize = player.videoSize
        speed = player.playbackParameters.speed
        if (requestedId?.let(::carries) == true) {
            val tracks = player.currentTracks
            val parameters = player.trackSelectionParameters
            // The three are pure in these two: the same objects give the same values.
            if (tracks !== readTracks || parameters !== readParameters) {
                readTracks = tracks
                readParameters = parameters
                contentFrameRate(tracks)?.let { frameRate = it }
                codec = videoCodec(tracks)
                quality = videoQuality(tracks, parameters)
            }
        }
    }
}
