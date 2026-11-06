package com.arshad.ayn.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.arshad.ayn.core.access.LivePlayable
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.sabr.SabrBridge
import com.arshad.ayn.core.access.sabr.SabrPlayable
import com.arshad.ayn.feature.player.LiveStreamRefused
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LIVE-D1–D3 on the recorded pages: the live page resolves to an HLS item (not the D11 stop), a
 * video page still to SABR, and the player's source factory builds each from its tag.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePlaybackTest {

    @Test
    fun theRecordedLivePageResolvesToALiveItem() = runTest {
        val playable = playableFor(livePage, { false }, CAP_1080)

        val item = resolvedItem(livePage, playable)

        assertTrue(playable is LivePlayable)
        assertSame(playable, item.localConfiguration?.tag)
        assertEquals(RecordedLiveVideo.ID, item.mediaId)
        assertEquals(livePage.hlsUrl, item.localConfiguration?.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertEquals(10_000L, item.liveConfiguration.targetOffsetMs)
        assertEquals(livePage.title, item.mediaMetadata.title)
    }

    /** LIVE-D13 through the resolve's dispatch: the kids profile's stop, no item. */
    @Test
    fun aKidsProfileIsRefusedTheLivePage() = runTest {
        val refusal = runCatching { playableFor(livePage, { true }, CAP_1080) }.exceptionOrNull()

        assertTrue(refusal is LiveStreamRefused)
    }

    /** A video page keeps the SABR path, capped at the TV's quality cap, though its VISIONOS answer
     *  names an HLS manifest too: the page's live flag decides (LIVE-D18). */
    @Test
    fun aVideoPageStillResolvesToSabr() = runTest {
        var capAsked = false
        assertNotNull("precondition: the video page names an HLS manifest", videoPage.hlsUrl)

        val playable = playableFor(videoPage, { error("a video page asks no kids check") }) { capAsked = true; 1080 }

        assertTrue(playable is SabrPlayable)
        assertTrue(capAsked)
    }


    @Test
    fun theFactoryBuildsAnHlsSourceForALiveItem() {
        val item = resolvedItem(livePage, runBlocking { playableFor(livePage, { false }, CAP_1080) })

        val source = factory().createMediaSource(item)

        assertTrue(source is HlsMediaSource)
        assertEquals(item, source.mediaItem)
    }

    @Test
    fun theFactoryBuildsASabrSourceForAVideoItem() {
        val playable = runBlocking { playableFor(videoPage, { false }) { 1080 } } as SabrPlayable
        val item = resolvedItem(videoPage, playable)

        val source = factory().createMediaSource(item)

        assertEquals(bridge.createMediaSource(playable, item)::class, source::class)
        assertEquals(item, source.mediaItem)
    }

    /** LIVE-D5: a cap no variant fits keeps the smallest alone, as the SABR cap does. */
    @Test
    fun aCapBelowEveryVariantKeepsTheSmallest() {
        val parsed = CappedHlsPlaylistParserFactory(maxShortSide = 480).createPlaylistParser()
            .parse(Uri.parse(SyntheticHls.MULTIVARIANT_URL), SyntheticHls.MULTIVARIANT.byteInputStream())

        assertEquals(listOf(720), (parsed as HlsMultivariantPlaylist).variants.map { it.format.height })
    }

    /** LIVE-D5 by short side: a portrait 1080×1920 variant fits a 1080 cap, a variant of unknown
     *  size stays, and the audio rendition survives the filtering; the 1440p variant goes. */
    @Test
    fun theCapMeasuresTheShortSideAndKeepsAudioAndUnknownSizes() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",DEFAULT=YES,URI="https://manifest.googlevideo.com/a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.640028",RESOLUTION=1080x1920,AUDIO="a"
            https://manifest.googlevideo.com/portrait.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="avc1.640032",RESOLUTION=2560x1440,AUDIO="a"
            https://manifest.googlevideo.com/1440.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401e",AUDIO="a"
            https://manifest.googlevideo.com/unsized.m3u8
        """.trimIndent() + "\n"

        val parsed = CappedHlsPlaylistParserFactory(maxShortSide = 1080).createPlaylistParser()
            .parse(Uri.parse(SyntheticHls.MULTIVARIANT_URL), playlist.byteInputStream()) as HlsMultivariantPlaylist

        assertEquals(listOf("portrait.m3u8", "unsized.m3u8"), parsed.variants.map { it.url.lastPathSegment })
        assertEquals(1, parsed.audios.size)
    }

    @Test
    fun theFactoryRefusesAnItemThatDidNotComeFromAResolve() {
        val item = MediaItem.Builder().setMediaId(FIXED_VIDEO_ID).setUri("ke://watch/$FIXED_VIDEO_ID").build()

        assertThrows(IllegalStateException::class.java) { factory().createMediaSource(item) }
    }

    private val bridge = SabrBridge(DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build(), OkHttpClient())

    private fun factory() = ItemSourceFactory(
        sabr = bridge,
        liveData = DataSource.Factory { error("nothing loads before prepare") },
    )

    private companion object {
        val CAP_1080: suspend () -> Int = { 1080 }
        val livePage by lazy { page(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID) }
        val videoPage by lazy { page("video", FIXED_VIDEO_ID) }

        fun page(flow: String, id: String) = runBlocking { NewPipeAccess(downloader = FixtureDownloader(flow)).video(VideoId(id)) }
    }
}
