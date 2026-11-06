package com.arshad.ayn.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.robolectric.Shadows.shadowOf

// In-process MediaSession + MediaController harness for Robolectric (paused main looper).

private val WAIT_LIMIT = 5.seconds
private const val SILENCE_DURATION_US = 600_000_000L

internal fun idleMain() {
    shadowOf(Looper.getMainLooper()).idle()
}

/** Session and controller hop through the main looper; ExoPlayer's playback thread posts back
 *  to it from a real thread, hence the short sleeps between drains. */
internal fun idleUntil(what: String, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + WAIT_LIMIT
    while (true) {
        idleMain()
        if (condition()) return
        check(!deadline.hasPassedNow()) { "timed out waiting for: $what" }
        Thread.sleep(5)
    }
}

/** An ExoPlayer with one inert metadata renderer: nothing decodes on the JVM, and ExoPlayer
 *  refuses to build with no renderers. */
@OptIn(UnstableApi::class)
internal fun inertExoPlayer(context: Context, sourceFactory: MediaSource.Factory): ExoPlayer {
    val inertRenderers = RenderersFactory { handler, _, _, _, metadataOutput ->
        arrayOf(MetadataRenderer(metadataOutput, handler.looper))
    }
    return ExoPlayer.Builder(context, inertRenderers).setMediaSourceFactory(sourceFactory).build()
}

/** A silent stand-in source per item that keeps the item, as the SABR source does (the
 *  player's metadata comes from the source's item); [onCreate] sees each item first. */
@OptIn(UnstableApi::class)
internal class ItemKeepingSourceFactory(private val onCreate: (MediaItem) -> Unit = {}) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        onCreate(mediaItem)
        return SilenceMediaSource.Factory().setDurationUs(SILENCE_DURATION_US).createMediaSource()
            .apply { updateMediaItem(mediaItem) }
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
}

internal fun connectController(
    context: Context,
    session: MediaSession,
    listener: MediaController.Listener = object : MediaController.Listener {},
): MediaController {
    val future = MediaController.Builder(context, session.token).setListener(listener).buildAsync()
    idleUntil("controller connected") { future.isDone }
    return future.get()
}
