package com.arshad.ayn.core.sabr

import android.os.Handler
import androidx.media3.common.C
import org.junit.Assert.assertTrue
import com.github.libretube.player.SabrChunkSource
import androidx.media3.exoplayer.source.chunk.InitializationChunk
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream
import androidx.media3.exoplayer.source.chunk.ChunkHolder
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.github.libretube.player.DefaultSabrChunkSource
import com.github.libretube.player.SabrDataSource
import com.github.libretube.player.SabrMediaPeriod
import com.github.libretube.player.manifest.SabrFormat
import com.github.libretube.player.manifest.SabrStreamSet
import com.github.libretube.player.manifest.toSabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest

/**
 * Which video format a start names to the server, with no network: an interceptor records the
 * first SABR request. A start used to name the top format (303), so the first answer carried it
 * and the estimate's pick (302) was fetched at position 0 again.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SabrMediaPeriodTest {

    /** 0.7 × 5 Mb/s = 3.5 Mb/s: 302 (2.7 Mb/s) fits, 303 (4.4 Mb/s) doesn't. */
    @Test
    fun anAudioFirstStartNamesTheEstimatesPickNotTheTopFormat() {
        val start = Start(estimate = 5_000_000L)

        assertEquals(listOf(302), start.audioRequestVideoItags())
    }

    @Test
    fun aVideoFirstStartAsksTheEstimatesPicksInitChunk() {
        val start = Start(estimate = 5_000_000L)

        assertEquals(302, start.videoInitChunkItag())
    }

    @Test
    fun anEstimateThatFitsTheTopFormatStartsOnIt() {
        val start = Start(estimate = 10_000_000L)

        assertEquals(303, start.videoInitChunkItag())
        assertEquals(listOf(303), start.audioRequestVideoItags())
    }

    /** A new selection for a kept stream (a quality setting changed mid-play) names its own pick. */
    @Test
    fun aKeptStreamsNewSelectionNamesItsPick() {
        val start = Start(estimate = 10_000_000L)

        start.reselect(estimate = 5_000_000L)

        assertEquals(listOf(302), start.audioRequestVideoItags())
    }

    /** A period started on 303/302 + 251; requests are recorded, never answered with media. */
    private class Start(estimate: Long) {
        private val manifest = SabrStreamSet(
            serverAbrStreamingUrl = "https://rr1---sn-test.googlevideo.com/videoplayback?id=1",
            videoPlaybackUstreamerConfig = "AQID",
            duration = 600,
            videoStreams = listOf(video(itag = 303, height = 1080, bitrate = 4_400_000), video(itag = 302, height = 720, bitrate = 2_700_000)),
            audioStreams = listOf(AUDIO),
        ).toSabrManifest(VIDEO_ID)
        private val requests = mutableListOf<VideoPlaybackAbrRequest>()
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }
            requests += VideoPlaybackAbrRequest.parseFrom(body.readByteArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(0).toResponseBody()).build()
        }.build()
        private val client = SabrClient(manifest, { null }, { null }, FixedEstimate(estimate), http)
        private val period = SabrMediaPeriod(
            manifest = manifest,
            sabrClient = client,
            periodIndex = 0,
            chunkSourceFactory = DefaultSabrChunkSource.Factory(SabrDataSource.Factory(client)),
            transferListener = null,
            cmcdConfiguration = null,
            drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED,
            drmEventDispatcher = DrmSessionEventListener.EventDispatcher(),
            loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
            mediaSourceEventDispatcher = MediaSourceEventListener.EventDispatcher(),
            elapsedRealtimeOffsetMs = 0,
            allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            compositeSequenceableLoaderFactory = DefaultCompositeSequenceableLoaderFactory(),
            playerId = PlayerId.UNSET,
        )
        private val streams = arrayOfNulls<SampleStream>(2)

        init {
            select(estimate, retain = false)
        }

        fun reselect(estimate: Long) = select(estimate, retain = true)

        /** The video formats the first SABR request (audio's init chunk) names. */
        fun audioRequestVideoItags(): List<Int> {
            requests.clear()
            client.getNextSegment(PlaybackRequest.initRequest(manifest.adaptationSets[1].representations[0]!!.formatId(), 0, 1f))
            return requests.single().preferredVideoFormatIdsList.map { it.itag }
        }

        /** The format of the video stream's first chunk (its init chunk). */
        fun videoInitChunkItag(): Int {
            @Suppress("UNCHECKED_CAST")
            val video = streams[0] as ChunkSampleStream<SabrChunkSource>
            val out = ChunkHolder()
            video.chunkSource.getNextChunk(LoadingInfo.Builder().setPlaybackPositionUs(0).build(), 0, emptyList(), out)
            val chunk = out.chunk
            assertTrue("a new format starts with its init chunk, got $chunk", chunk is InitializationChunk)
            return (chunk!!.dataSpec.customData as PlaybackRequest).format.itag
        }

        private fun select(estimate: Long, retain: Boolean) {
            val groups = period.trackGroups
            val selections = arrayOf<ExoTrackSelection?>(
                AdaptiveTrackSelection(groups[0], intArrayOf(0, 1), FixedEstimate(estimate)),
                FixedTrackSelection(groups[1], 0),
            )
            period.selectTracks(selections, BooleanArray(2) { retain }, streams, BooleanArray(2), 0)
        }
    }

    private class FixedEstimate(private val estimate: Long) : BandwidthMeter {
        override fun getBitrateEstimate(): Long = estimate
        override fun getTransferListener(): TransferListener? = null
        override fun getTimeToFirstByteEstimateUs(): Long = C.TIME_UNSET
        override fun addEventListener(handler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        val AUDIO = SabrFormat(
            itag = 251, lastModified = 1_700_000_000L, xtags = null, mimeType = "audio/webm",
            codec = "opus", bitrate = 128_000, fps = null, width = null, height = null,
            audioTrackId = null, audioTrackLocale = null, audioTrackType = null,
            durationMs = 600_000L, isDrc = null,
        )

        fun video(itag: Int, height: Int, bitrate: Int) = SabrFormat(
            itag = itag, lastModified = 1_700_000_000L, xtags = null, mimeType = "video/webm",
            codec = "vp9", bitrate = bitrate, fps = 60, width = height * 16 / 9, height = height,
            audioTrackId = null, audioTrackLocale = null, audioTrackType = null,
            durationMs = 600_000L, isDrc = null,
        )
    }
}
