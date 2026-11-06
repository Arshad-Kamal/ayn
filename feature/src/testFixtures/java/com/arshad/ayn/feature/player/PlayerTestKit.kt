@file:OptIn(UnstableApi::class)

package com.arshad.ayn.feature.player

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionToken
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking

// The player test kit both UIs' tests share: the recorded Enigma video, a scripted player, and an
// in-process playback session standing in for the service.

/** No playback service in this test: a player opened here says it didn't answer. */
val NoPlayerSession: (Context) -> SessionToken = { throw IllegalArgumentException("no player service in this test") }

/** [RecordedEnigmaPage]'s card, as a row lists it. */
val EnigmaCard: VideoItem by lazy {
    VideoItem(
        id = VideoId(CHAPTERS_VIDEO_ID),
        title = RecordedEnigmaPage.title,
        uploaderName = RecordedEnigmaPage.uploaderName,
        uploaderUrl = RecordedEnigmaPage.uploaderUrl,
        durationSeconds = 2861,
        viewCount = 15_000_000,
        textualUploadDate = "12 days ago",
        uploadDate = null,
        thumbnailUrl = null,
        isLive = false,
    )
}

/** The recorded Enigma video page (chapters, storyboards, avatar) through the real seam. */
val RecordedEnigmaPage: VideoMetadata by lazy {
    runBlocking { NewPipeAccess(downloader = FixtureDownloader(CHAPTERS_VIDEO_FLOW)).video(VideoId(CHAPTERS_VIDEO_ID)) }
}

/** The recorded live page (`video-live`, an active stream) through the real seam. */
val RecordedLivePage: VideoMetadata by lazy {
    runBlocking { NewPipeAccess(downloader = FixtureDownloader(LIVE_VIDEO_FLOW)).video(VideoId(RecordedLiveVideo.ID)) }
}

/** [RecordedLivePage]'s card, as Live now lists it: its count is the audience. */
val LiveCard: VideoItem by lazy {
    VideoItem(
        id = VideoId(RecordedLiveVideo.ID),
        title = RecordedLivePage.title,
        uploaderName = RecordedLivePage.uploaderName,
        uploaderUrl = RecordedLivePage.uploaderUrl,
        durationSeconds = -1,
        viewCount = 1_204,
        textualUploadDate = null,
        uploadDate = null,
        thumbnailUrl = null,
        isLive = true,
    )
}

/**
 * The playback service's stand-in, all on the main looper (TEST-DETERM-01): a session whose
 * resolve dresses any requested video with [page] and [segments] and keeps the requested start (as
 * the service's resolve does), over a player the test moves through its states.
 */
class FakePlaybackService(context: Context) {
    val player = ScriptedPlayer()

    /** The page every resolve reads; [RecordedEnigmaPage] unless a test sets another. */
    var page: VideoMetadata = RecordedEnigmaPage

    /** The SponsorBlock lookup every resolve carries; none unless a test sets one. */
    var segments: SegmentLookup? = null
    val session: MediaSession = MediaSession.Builder(context, player)
        .setId("test-${System.nanoTime()}")
        .setCallback(
            object : MediaSession.Callback {
                override fun onSetMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                    startIndex: Int,
                    startPositionMs: Long,
                ): ListenableFuture<MediaItemsWithStartPosition> {
                    val id = mediaItems.single().mediaId
                    return Futures.immediateFuture(MediaItemsWithStartPosition(listOf(dressedItem(id, page, segments)), 0, startPositionMs))
                }
            },
        )
        .build()

    val token: (Context) -> SessionToken = { session.token }

    /** Playback stopped for [reason] (a `PlayerPolicy.TERMINAL_*`), Retry resuming at [positionMs],
     *  with the stop's [detail]: the session extras the service sets (D8). On the main thread. */
    fun stop(reason: String, positionMs: Long, detail: String? = null) {
        session.setSessionExtras(
            Bundle().apply {
                putString(PlaybackContract.EXTRA_STOP_REASON, reason)
                putLong(PlaybackContract.EXTRA_POSITION_MS, positionMs)
                detail?.let { putString(PlaybackContract.EXTRA_STOP_DETAIL, it) }
            },
        )
    }

    /** The service ended its video (`ProfilePlayback.end`): no stop note, nothing held. */
    fun end() {
        session.setSessionExtras(Bundle.EMPTY)
        player.clear()
    }

    fun release() {
        session.release()
        player.release()
    }
}

/** [id] as the service's resolve hands it over: dressed with [page] and [segments]. */
fun dressedItem(id: String, page: VideoMetadata = RecordedEnigmaPage, segments: SegmentLookup? = null): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(page.title)
            .setExtras(PlaybackDressing.from(page, segments).toBundle())
            .build(),
    )
    .build()

/** One selected VP9 video track at [frameRate]. */
fun videoTracks(frameRate: Float): Tracks = Tracks(
    listOf(
        Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setFrameRate(frameRate).build()),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        ),
    ),
)

/** A player whose state the test sets; it records what it is asked for (videos, their start
 *  positions, seeks; a seek to the default position as `C.TIME_UNSET`). */
class ScriptedPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    val requested = mutableListOf<String>()

    /** Each request's start position; `C.TIME_UNSET` for the video's default start. */
    val starts = mutableListOf<Long>()
    val seeks = mutableListOf<Long>()
    private var items: List<MediaItem> = emptyList()
    private var playbackState = Player.STATE_IDLE
    private var playWhenReady = false
    private var positionMs = 0L

    /** The selected video track's rate, once the stream lands; none when `null`. */
    var frameRate: Float? = null

    /** The stream's tracks once it lands, when a test lays them out; else [frameRate]'s one track. */
    var tracks: Tracks? = null

    /** What the screen last asked the track selector for. */
    var trackSelection: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
        private set

    /** The picture's size once the stream lands. */
    var pictureSize: VideoSize = VideoSize(3840, 2160)

    /** The playback speed, 1 at Normal. */
    var speed: Float = 1f
        private set

    /** Set, the item is a live window this long (LIVE-D23): dynamic, its default position
     *  [PlayerPolicy.LIVE_OFFSET_MS] short of its end and snapped back to a segment's start
     *  ([LIVE_SEGMENT_SNAP_MS]), as Media3's HLS source places it; else a video of [DURATION_MS]. */
    var liveWindowMs: Long? = null

    /** A video's length, unless [liveWindowMs] makes it a live window. */
    var videoDurationMs: Long = DURATION_MS

    val isPlayWhenReady: Boolean get() = playWhenReady

    private val durationMs: Long get() = liveWindowMs ?: videoDurationMs

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(items.map { itemData(it) })
        .setPlaybackState(if (items.isEmpty() && playbackState != Player.STATE_IDLE) Player.STATE_ENDED else playbackState)
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setContentPositionMs(positionMs)
        .setVideoSize(if (playbackState == Player.STATE_READY) pictureSize else VideoSize.UNKNOWN)
        .setPlaybackParameters(PlaybackParameters(speed))
        .setTrackSelectionParameters(trackSelection)
        .build()

    private fun itemData(item: MediaItem): MediaItemData {
        val data = MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durationMs * 1000)
            .setTracks(streamTracks().takeIf { playbackState == Player.STATE_READY } ?: Tracks.EMPTY)
        liveWindowMs?.let { window ->
            data.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(PlayerPolicy.LIVE_OFFSET_MS).build())
                .setIsDynamic(true)
                .setIsSeekable(true)
                .setDefaultPositionUs(liveDefaultMs(window) * 1000)
        }
        return data.build()
    }

    private fun liveDefaultMs(window: Long): Long = window - PlayerPolicy.LIVE_OFFSET_MS - LIVE_SEGMENT_SNAP_MS

    private fun streamTracks(): Tracks? = tracks ?: frameRate?.let(::videoTracks)

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackSelection = trackSelectionParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        requested += mediaItems.map { it.mediaId }
        starts += startPositionMs
        playbackState = Player.STATE_IDLE
        positionMs = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = if (items.isEmpty()) Player.STATE_ENDED else Player.STATE_BUFFERING
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        seeks += positionMs
        this.positionMs = if (positionMs != C.TIME_UNSET) positionMs else liveWindowMs?.let(::liveDefaultMs) ?: 0
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** Stopped with nothing held, as the service's end leaves it. */
    fun clear() {
        items = emptyList()
        playbackState = Player.STATE_IDLE
        playWhenReady = false
        invalidateState()
    }

    /** The stream landed, [atMs] into the video. */
    fun ready(atMs: Long = positionMs) {
        playbackState = Player.STATE_READY
        positionMs = atMs
        invalidateState()
    }

    /** Waiting for media where it is. */
    fun buffering() {
        playbackState = Player.STATE_BUFFERING
        invalidateState()
    }

    fun end() {
        playbackState = Player.STATE_ENDED
        positionMs = durationMs
        invalidateState()
    }

    companion object {
        const val DURATION_MS = 2_861_000L

        /** How far before the target offset a live window's default position falls: the segment
         *  it lies in starts there. */
        const val LIVE_SEGMENT_SNAP_MS = 4_000L
    }
}

/** A VP9 picture of [width] × [height]. */
fun vp9(width: Int, height: Int, frameRate: Float = 30f, bitrate: Int = Format.NO_VALUE): Format =
    Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(width).setHeight(height).setFrameRate(frameRate).setAverageBitrate(bitrate).build()

/** YouTube's VP9 ladder for a 4K video, best first: 2160p at index 0 … 144p at 7. */
val vp9Ladder: TrackGroup = TrackGroup(
    *listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360, 426 to 240, 256 to 144)
        .map { (width, height) -> vp9(width, height) }
        .toTypedArray(),
)

/** [group] adaptively selected, each track playable unless [supported] says otherwise. */
fun selected(group: TrackGroup, supported: BooleanArray = BooleanArray(group.length) { true }): Tracks = Tracks(
    listOf(
        Tracks.Group(
            group,
            true,
            IntArray(group.length) { if (supported[it]) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE },
            BooleanArray(group.length) { supported[it] },
        ),
    ),
)
