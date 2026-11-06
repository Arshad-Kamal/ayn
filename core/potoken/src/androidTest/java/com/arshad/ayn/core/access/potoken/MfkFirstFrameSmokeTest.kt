package com.arshad.ayn.core.access.potoken

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.sabr.SabrBridge
import com.arshad.ayn.core.access.sabr.sabrPlayable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KP.1b measurement (spec 12 Q27/Q29): time to first frame of a made-for-kids video through the
 * real chain (NewPipeAccess with the WebView minter and the EJS solver → sabrPlayable →
 * SabrBridge → ExoPlayer on a Surface) against a video VISIONOS plays. Logged under [TAG]; the
 * minter and solver log under KePoToken / KeEjs. One behaviour test rides along:
 * [laterMintStreamsPastAttestation].
 *
 * A cold arm must be the first resolve of its process, so it runs only when it is the one method
 * the instrumentation asked for (`am instrument -e KE_SMOKE 1 -e class
 * …MfkFirstFrameSmokeTest#coldMadeForKids`); in a wider run it is skipped. Opt-in: `KE_SMOKE=1`
 * (runner argument).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MfkFirstFrameSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun coldMadeForKids() {
        assumeSmoke()
        assumeAlone("coldMadeForKids")
        firstFrame("cold mfk", MFK_VIDEO_ID, expectWeb = true)
    }

    @Test
    fun coldVisionos() {
        assumeSmoke()
        assumeAlone("coldVisionos")
        firstFrame("cold visionos", VISIONOS_VIDEO_ID, expectWeb = false)
    }

    /**
     * A first made-for-kids play warms the minter's WebView, the player code, the solver and the
     * bridge's bandwidth meter (one per player service in the app, too); the timed one is another
     * video, as a next play would be (a token is bound to its video). Dora stays on its first
     * format, so it pays no format-switch wait (spec 12 ledger "MFK first frame").
     */
    @Test
    fun warm() {
        assumeSmoke()
        firstFrame("warm-up mfk", MFK_VIDEO_ID, expectWeb = true)
        firstFrame("warm visionos", VISIONOS_VIDEO_ID, expectWeb = false)
        firstFrame("warm mfk", OTHER_MFK_VIDEO_ID, expectWeb = true)
    }

    /**
     * A made-for-kids video streams past the ~65 s of media an unattested session gets when its
     * token is not the minter's first, as in Ayn, where any earlier made-for-kids resolve mints
     * first. Dora plays until ExoPlayer has buffered past [PAST_ATTESTATION_MS].
     */
    @Test
    fun laterMintStreamsPastAttestation() {
        assumeSmoke()
        val earlier = runBlocking { access.video(VideoId(MFK_VIDEO_ID)) }
        assertEquals("the earlier resolve mints", true, earlier.webStreamingClient != null)
        val metadata = runBlocking { access.video(VideoId(OTHER_MFK_VIDEO_ID)) }
        assertEquals("answered through MWEB", true, metadata.webStreamingClient != null)
        val playable = sabrPlayable(metadata, MAX_SHORT_SIDE)
        var failure: PlaybackException? = null
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        lateinit var player: ExoPlayer
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failure = error
                    }
                })
                setVideoSurface(surface)
                setMediaSource(bridge.createMediaSource(playable, MediaItem.fromUri("ke://watch/$OTHER_MFK_VIDEO_ID")))
                prepare()
                play()
            }
        }
        try {
            var buffered = 0L
            var waited = 0
            while (failure == null && buffered < PAST_ATTESTATION_MS && waited < STREAM_TIMEOUT_S) {
                Thread.sleep(1000)
                waited++
                instrumentation.runOnMainSync { buffered = player.bufferedPosition }
            }
            Log.i(TAG, "later mint $OTHER_MFK_VIDEO_ID: buffered to $buffered ms after $waited s")
            failure?.let { throw AssertionError("later mint: ${it.errorCodeName}", it) }
            assertTrue("later mint: buffered only to $buffered ms", buffered >= PAST_ATTESTATION_MS)
        } finally {
            instrumentation.runOnMainSync { player.release() }
            surface.release()
            texture.release()
        }
    }

    private fun assumeSmoke() {
        assumeTrue(
            "set KE_SMOKE=1 to run network smoke tests",
            InstrumentationRegistry.getArguments().containsKey("KE_SMOKE"),
        )
    }

    private fun assumeAlone(method: String) {
        assumeTrue(
            "a cold arm runs alone in its process",
            InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#$method",
        )
    }

    /** Resolves [videoId] and plays it to its first rendered frame; logs the timings. */
    private fun firstFrame(arm: String, videoId: String, expectWeb: Boolean) {
        val started = SystemClock.elapsedRealtime()
        val metadata = runBlocking { access.video(VideoId(videoId)) }
        val resolved = SystemClock.elapsedRealtime()
        assertEquals("$arm: answered through MWEB", expectWeb, metadata.webStreamingClient != null)
        val playable = sabrPlayable(metadata, MAX_SHORT_SIDE)

        val rendered = CountDownLatch(1)
        var frameAt = 0L
        var size = VideoSize.UNKNOWN
        var failure: PlaybackException? = null
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        lateinit var player: ExoPlayer
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        frameAt = SystemClock.elapsedRealtime()
                        rendered.countDown()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        size = videoSize
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure = error
                        rendered.countDown()
                    }
                })
                setVideoSurface(surface)
                setMediaSource(bridge.createMediaSource(playable, MediaItem.fromUri("ke://watch/$videoId")))
                prepare()
                play()
            }
        }
        try {
            check(rendered.await(FRAME_TIMEOUT_S, TimeUnit.SECONDS)) { "$arm: no first frame in $FRAME_TIMEOUT_S s" }
            failure?.let { throw AssertionError("$arm: ${it.errorCodeName}", it) }
            Log.i(
                TAG,
                "$arm $videoId: video() ${resolved - started} ms, resolve to first frame ${frameAt - resolved} ms, " +
                    "first frame ${frameAt - started} ms, ${size.width}x${size.height}",
            )
        } finally {
            instrumentation.runOnMainSync { player.release() }
            surface.release()
            texture.release()
        }
    }

    private companion object {
        const val TAG = "KeMfkFrame"
        const val MFK_VIDEO_ID = "XqZsoesa55w" // made for kids, VISIONOS UNPLAYABLE
        const val OTHER_MFK_VIDEO_ID = "L8A4XbM5sXA" // Dora, made for kids
        const val VISIONOS_VIDEO_ID = "JsBZOcqZerk" // plays through VISIONOS
        const val MAX_SHORT_SIDE = 1080
        const val FRAME_TIMEOUT_S = 60L
        const val PAST_ATTESTATION_MS = 80_000L

        /** ExoPlayer buffers ~50 s ahead, so reaching [PAST_ATTESTATION_MS] takes ~30 s of play. */
        const val STREAM_TIMEOUT_S = 90

        // One per process: the extractor's seams are process-global (NewPipeAccess's init).
        val access: NewPipeAccess by lazy {
            val context = ApplicationProvider.getApplicationContext<Context>()
            NewPipeAccess(WebViewPoTokenSource(context), EjsNChallengeSolver(context))
        }
        val bridge: SabrBridge by lazy {
            SabrBridge(DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build(), OkHttpClient())
        }
    }
}
