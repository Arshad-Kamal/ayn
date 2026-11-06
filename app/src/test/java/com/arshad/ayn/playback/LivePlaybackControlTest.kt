package com.arshad.ayn.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.sabr.SabrBridge
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.playbackRequest
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The control plane's live rules (LIVE-D3–D5, D20) on a synthetic YouTube-shaped HLS stream
 * ([SyntheticHls]) through the production source factory and an inert ExoPlayer, driven by a real
 * `MediaController`. The resolve answers the recorded live page with its manifest URL pointed at
 * the synthetic stream. Failures are injected as `PlaybackControlPlaneTest` injects them.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePlaybackControlTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hls = SyntheticHls()
    private val bridge = SabrBridge(DefaultBandwidthMeter.Builder(context).build(), OkHttpClient())
    private val player = inertExoPlayer(context, ItemSourceFactory(bridge, hls))
    private val profiles = ProfileSession().apply { select(OWNER) }
    private val saves = mutableListOf<PositionSave>()
    private var resolveCount = 0
    private var cap = 1080
    private val controlPlane = PlaybackControlPlane(player, scope, { _, _ -> resolveLive() }, profiles::currentId) { saves += it }
    private val session = MediaSession.Builder(context, player).setId("live").setCallback(controlPlane).build()
        .also { controlPlane.attach(it) }
    private var controller: MediaController? = null

    @After
    fun tearDown() {
        controller?.release()
        scope.cancel()
        session.release()
        player.release()
    }

    /** LIVE-D3/D4: a live item opens at the edge less the 10 s offset, even when the requester
     *  asked for the window's start (a resume point or a start-over). */
    @Test
    fun aLiveItemStartsAtTheEdgeWhateverTheRequestAsked() {
        playing(startMs = 0)

        assertTrue(player.isCurrentMediaItemLive)
        assertTrue(player.isCurrentMediaItemDynamic)
        assertNearTheEdge()
    }

    /** A stream whose playlist ends (`#EXT-X-ENDLIST`) is no longer a moving window. */
    @Test
    fun anEndedStreamIsNotDynamic() {
        hls.ended = true

        playing()

        assertFalse(player.isCurrentMediaItemDynamic)
    }

    /** LIVE-D5: the 720-line cap leaves only the 720p variant for the track selector. */
    @Test
    fun theQualityCapKeepsOnlyTheVariantsAtOrUnderIt() {
        cap = 720

        playing()

        assertEquals(listOf(720), videoHeights())
    }

    @Test
    fun withoutACapBelowTheStreamBothVariantsStay() {
        playing()

        assertEquals(listOf(720, 1080), videoHeights())
    }

    /** LIVE-D4: behind the live window rejoins the edge and shows no retry, spending nothing. */
    @Test
    fun fallingBehindTheWindowRejoinsTheEdgeWithoutARetry() {
        playing()
        player.seekTo(1_000)
        idleUntil("at the window's start") { player.currentPosition < 5_000 }

        failFromIdle(behindLiveWindow())
        idleUntil("prepared again") { prepared() }

        assertNearTheEdge()
        assertNull(retryKind())
    }

    /** A second behind-the-window before playback recovers counts as D8 recoverable (bounded). */
    @Test
    fun aSecondBehindTheWindowBeforeRecoveringIsARetry() {
        playing()

        player.stop()
        controlPlane.onPlaybackFailure(behindLiveWindow())
        player.stop()
        controlPlane.onPlaybackFailure(behindLiveWindow())
        idleUntil("retry shown") { retryKind() != null }

        assertEquals(PlaybackContract.RETRY_RECOVERABLE, retryKind())
        assertEquals(1, controller!!.sessionExtras.getInt(PlaybackContract.EXTRA_ATTEMPT))
    }

    /** LIVE-D4: a recoverable failure while live re-prepares at the edge, not where it stopped. */
    @Test
    fun aRetryWhileLiveGoesToTheEdge() {
        playing()
        player.seekTo(1_000)
        idleUntil("at the window's start") { player.currentPosition < 5_000 }

        failFromIdle(networkFailure())
        idleUntil("prepared again") { prepared() }

        assertNearTheEdge()
    }

    /** LIVE-D4: a 403 on a live playlist or segment asks for a new resolve (a fresh manifest URL),
     *  within the attestation budget, and the new item opens at the edge. */
    @Test
    fun aForbiddenFetchWhileLiveReResolves() {
        playing()
        player.seekTo(1_000)
        idleUntil("at the window's start") { player.currentPosition < 5_000 }

        failFromIdle(forbidden())
        idleUntil("re-resolved and prepared") { resolveCount == 2 && prepared() }

        assertNearTheEdge()
    }

    /** LIVE-D20: History gets the stream with no resume point, no length, never as watched. */
    @Test
    fun aLiveItemIsSavedWithoutAPlaceOrLength() {
        playing()

        controller!!.release() // the screen leaves: the last app controller's save
        idleUntil("saved") { saves.isNotEmpty() }

        assertEquals(PositionSave(OWNER, VideoId(RecordedLiveVideo.ID), positionMs = 0, durationMs = null, ended = false), saves.last())
    }

    private fun resolveLive(): MediaItem {
        resolveCount++
        val page = livePage.copy(hlsUrl = SyntheticHls.MULTIVARIANT_URL)
        return resolvedItem(page, runBlocking { playableFor(page, { false }) { cap } })
    }

    private fun playing(startMs: Long? = null) {
        val c = connectController(context, session).also { controller = it }
        val request = playbackRequest(RecordedLiveVideo.ID, short = false)
        if (startMs != null) c.setMediaItem(request, startMs) else c.setMediaItem(request)
        c.prepare()
        c.play()
        idleUntil("prepared") { prepared() }
    }

    /** The source has published its window and tracks. The empty segments buffer nothing, so the
     *  player may stay BUFFERING; READY is not the signal here. */
    private fun prepared(): Boolean = player.playbackState != Player.STATE_IDLE && !player.currentTracks.isEmpty

    private fun assertNearTheEdge() {
        val behindTheEdge = player.duration - player.currentPosition
        assertTrue("$behindTheEdge ms behind the window's end", behindTheEdge in 5_000L..15_000L)
    }

    private fun videoHeights(): List<Int> =
        player.currentTracks.groups.flatMap { group -> (0..<group.length).map { group.getTrackFormat(it).height } }
            .filter { it > 0 }
            .sorted()

    private fun retryKind(): String? = controller!!.sessionExtras.getString(PlaybackContract.EXTRA_RETRY_KIND)

    /** A failed ExoPlayer reports its error from IDLE, its timeline kept. */
    private fun failFromIdle(error: PlaybackException) {
        player.stop()
        controlPlane.onPlaybackFailure(error)
        idleMain()
    }

    private companion object {
        const val OWNER = 1L
        val livePage by lazy {
            runBlocking { NewPipeAccess(downloader = FixtureDownloader(LIVE_VIDEO_FLOW)).video(VideoId(RecordedLiveVideo.ID)) }
        }

        fun behindLiveWindow() =
            PlaybackException("behind", BehindLiveWindowException(), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)

        fun networkFailure() = PlaybackException(
            "source error",
            IOException("segment fetch failed", UnknownHostException()),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        fun forbidden() = PlaybackException(
            "source error",
            HttpDataSource.InvalidResponseCodeException(
                403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("${SyntheticHls.BASE}/720.m3u8")), ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }
}
