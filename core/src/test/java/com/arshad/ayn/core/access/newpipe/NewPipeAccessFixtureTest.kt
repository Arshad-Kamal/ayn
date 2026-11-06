package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.NoPoTokenSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.isYouTubeStreamingUrl
import com.arshad.ayn.core.access.sabr.requirePlayable
import com.arshad.ayn.core.access.sabr.sabrStreamSetFrom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline parsing regression tests: the vendored extraction path runs against recorded
 * InnerTube fixtures (no network, deterministic), pinning the parsing → seam-model mapping the
 * live smoke can only observe opportunistically. Golden values are read from the recorded
 * payload itself into `RecordedCorpus.kt` (TEST-ORACLE-01). Fixtures are captured by `FixtureRecorderTest`.
 */
class NewPipeAccessFixtureTest {

    @Test
    fun parsesMetadataFromFixtures() {
        val metadata = extract()

        assertEquals(RecordedVideo.UPLOADER_NAME, metadata.uploaderName)
        assertEquals(RecordedVideo.TITLE, metadata.title)
        assertEquals(RecordedVideo.DURATION_SECONDS, metadata.durationSeconds)
        assertEquals(RECORDED_CHANNEL_URL, metadata.uploaderUrl)
        assertTrue("expected stream candidates", metadata.streams.isNotEmpty())
    }

    @Test
    fun parsesSabrPlaybackData() {
        val metadata = extract()

        assertTrue("SABR endpoint must be present and trusted", isYouTubeStreamingUrl(metadata.serverAbrStreamingUrl))
        assertTrue("ustreamerConfig should not be blank", !metadata.ustreamerConfig.isNullOrBlank())
    }

    @Test
    fun mapsToPinnedVp9StreamSetWithGoldenFields() {
        val set = sabrStreamSetFrom(extract())

        // TRK-1 restated from the rule (vp9 or the RFC 6381 vp09.00 profile-0 form).
        assertTrue(
            "the TRK-1 pin must hold on extraction output",
            set.videoStreams.all {
                it.codec?.let { codec -> codec.startsWith("vp9") || codec.startsWith("vp09.00") } == true
            },
        )
        assertEquals(RecordedVideo.VP9_VIDEO_ITAGS, set.videoStreams.map { it.itag })
        assertEquals(RecordedVideo.AUDIO_ITAGS, set.audioStreams.map { it.itag })
        requirePlayable(set, FIXED_VIDEO_ID) // must not throw

        val uhd = set.videoStreams.first { it.itag == RecordedVideo.UHD_ITAG }
        assertEquals("vp9", uhd.codec)
        assertEquals(3840, uhd.width)
        assertEquals(2160, uhd.height)
        assertEquals(RecordedVideo.UHD_FPS, uhd.fps)
        assertEquals(RecordedVideo.UHD_LAST_MODIFIED, uhd.lastModified)
        assertEquals(RecordedVideo.UHD_BITRATE, uhd.bitrate)

        val opus = set.audioStreams.first { it.itag == RecordedVideo.OPUS_ITAG }
        assertEquals("opus", opus.codec)
        assertEquals("audio/webm", opus.mimeType)
        assertEquals(RecordedVideo.OPUS_LAST_MODIFIED, opus.lastModified)
    }

    @Test
    fun capAt1080KeepsTheRecordedFormatsUpTo1080p() {
        val set = sabrStreamSetFrom(extract(), maxShortSide = 1080)

        // YouTube's VP9 itags by height: 313 2160p, 271 1440p, then 248 1080p down to 278 144p.
        assertEquals(RecordedVideo.VP9_VIDEO_ITAGS - listOf(313, 271), set.videoStreams.map { it.itag })
        assertTrue(set.videoStreams.all { minOf(it.width!!, it.height!!) <= 1080 })
    }

    private fun extract() = runBlocking {
        NewPipeAccess(poTokenSource = NoPoTokenSource, downloader = FixtureDownloader("video"))
            .video(VideoId(FIXED_VIDEO_ID))
    }
}
