package com.arshad.ayn.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.arshad.ayn.core.access.LivePlayable
import com.arshad.ayn.core.access.sabr.SabrBridge
import com.arshad.ayn.core.access.sabr.SabrPlayable

/**
 * The player-wide source factory (LIVE-D2): builds each resolved item's source from the playable
 * its resolve put in the tag, a [SabrPlayable] through [sabr], a [LivePlayable] as an HLS source
 * reading the item's URI over [liveData] (the app's OkHttp client in production), its variants
 * capped at the playable's quality cap (LIVE-D5).
 */
@OptIn(UnstableApi::class)
internal class ItemSourceFactory(
    private val sabr: SabrBridge,
    private val liveData: DataSource.Factory,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        when (val playable = mediaItem.localConfiguration?.tag) {
            is SabrPlayable -> sabr.createMediaSource(playable, mediaItem)
            is LivePlayable -> hlsFactory(playable.maxShortSide).createMediaSource(mediaItem)
            else -> error("item ${mediaItem.mediaId} did not come from a resolve")
        }

    private fun hlsFactory(maxShortSide: Int): HlsMediaSource.Factory = HlsMediaSource.Factory(liveData)
        .setPlaylistParserFactory(CappedHlsPlaylistParserFactory(maxShortSide))
        .setPlaylistTrackerFactory(livePlaylistTracker())

    // Both sources keep their own defaults; the player never configures its factory.
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)
}

/**
 * LIVE-D3: a live media playlist that stops changing for this many target durations counts as
 * stuck (Media3's default is 3.5). Raised to NewPipe's 15 so a live playlist that stalls for a
 * moment doesn't fail playback early.
 */
private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15.0

/**
 * The live source's playlist tracker: Media3's, with the stuck coefficient raised. A function, not
 * a val: lint doesn't count an @OptIn on a top-level val as covering its initializer.
 */
@OptIn(UnstableApi::class)
internal fun livePlaylistTracker(): HlsPlaylistTracker.Factory = HlsPlaylistTracker.Factory { dataSourceFactory, loadErrorHandlingPolicy, playlistParserFactory, cmcdConfiguration, downloadExecutor ->
    DefaultHlsPlaylistTracker(
        dataSourceFactory,
        loadErrorHandlingPolicy,
        playlistParserFactory,
        cmcdConfiguration,
        PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
        downloadExecutor,
    )
}
