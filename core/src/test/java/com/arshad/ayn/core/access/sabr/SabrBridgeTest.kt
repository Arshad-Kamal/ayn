package com.arshad.ayn.core.access.sabr

import com.arshad.ayn.core.access.StreamCandidate
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.WebStreamingClient
import com.arshad.ayn.core.store.DEFAULT_MAX_VIDEO_HEIGHT
import com.github.libretube.player.parser.SabrClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline checks for the extraction → SABR mapping (M3.5): the VP9 format-list pin (TRK-1), the
 * field mapping the vendored `Representation`/`SabrManifest` code asserts on, and the
 * manifest-construction preconditions at the trust boundary (M3-SEC-01).
 */
class SabrBridgeTest {

    @Test
    fun videoPinEmitsOnlyVp9Profile0() {
        val set = sabrStreamSetFrom(
            metadata(
                video(
                    codec = "vp9",
                    itag = 248,
                ),
                video(
                    codec = "av01.0.08M.08",
                    itag = 399,
                ),
                video(
                    codec = "vp09.02.10",
                    itag = 325,
                ),
                video(
                    codec = "vp09.00.10",
                    itag = 244,
                ),
            )
        )

        assertEquals(listOf(248, 244), set.videoStreams.map { it.itag })
    }

    @Test
    fun mapsFormatIdentityAndAudioTrackFields() {
        val set = sabrStreamSetFrom(
            metadata(
                video(codec = "vp9", itag = 248).copy(
                    lastModified = 1_700_000_000,
                    xtags = "clen=123",
                    fps = 60,
                    width = 1920,
                    height = 1080,
                    durationMs = 212_000,
                ),
                audio().copy(
                    audioTrackId = "en.3",
                    audioTrackLocale = "en-US",
                    audioTrackType = "original",
                    isDrc = true,
                ),
            )
        )

        val video = set.videoStreams.single()
        assertEquals(248, video.itag)
        assertEquals(1_700_000_000L, video.lastModified)
        assertEquals("clen=123", video.xtags)
        assertEquals(60, video.fps)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(212_000L, video.durationMs)

        val audio = set.audioStreams.single()
        assertEquals("en.3", audio.audioTrackId)
        assertEquals("en-US", audio.audioTrackLocale)
        assertEquals("original", audio.audioTrackType)
        assertEquals(true, audio.isDrc)
    }

    @Test
    fun capKeepsFormatsWhoseShortSideFits() {
        val set = sabrStreamSetFrom(
            metadata(sized(313, 3840, 2160), sized(271, 2560, 1440), sized(248, 1920, 1080), sized(247, 1280, 720)),
            maxShortSide = 1080,
        )

        assertEquals(listOf(248, 247), set.videoStreams.map { it.itag })
    }

    @Test
    fun capCountsAPortraitPictureByItsShortSide() {
        val set = sabrStreamSetFrom(
            metadata(sized(271, 1440, 2560), sized(248, 1080, 1920), sized(247, 720, 1280)),
            maxShortSide = 1080,
        )

        assertEquals(listOf(248, 247), set.videoStreams.map { it.itag })
    }

    @Test
    fun capWithNothingSmallEnoughKeepsTheSmallest() {
        val set = sabrStreamSetFrom(
            metadata(sized(313, 3840, 2160), sized(247, 1280, 720), sized(248, 1920, 1080)),
            maxShortSide = 480,
        )

        assertEquals(listOf(247), set.videoStreams.map { it.itag })
    }

    @Test
    fun capKeepsAFormatOfUnknownSize() {
        val set = sabrStreamSetFrom(
            metadata(sized(313, 3840, 2160), video(codec = "vp9", itag = 248)),
            maxShortSide = 1080,
        )

        assertEquals(listOf(248), set.videoStreams.map { it.itag })
    }

    @Test
    fun dropsStreamsMissingFormatIdentity() {
        val set = sabrStreamSetFrom(
            metadata(
                video(codec = "vp9", itag = 248).copy(lastModified = null),
                video(codec = "vp9", itag = 248).copy(lastModified = -1), // extractor sentinel
                video(codec = "vp9", itag = null),
                video(codec = "vp9", itag = 248).copy(mimeType = null),
            )
        )

        assertTrue("no stream may reach Representation.formatId() incomplete", set.videoStreams.isEmpty())
    }

    @Test
    fun passesThroughSabrEndpointFields() {
        val set = sabrStreamSetFrom(metadata(video(codec = "vp9", itag = 248), audio()))

        assertEquals("https://sabr.googlevideo.com/videoplayback", set.serverAbrStreamingUrl)
        assertEquals("dXN0cmVhbWVy", set.videoPlaybackUstreamerConfig)
    }

    @Test
    fun rejectsMissingSabrPlaybackData() {
        val metadata = metadata(video(codec = "vp9", itag = 248), audio())
            .copy(serverAbrStreamingUrl = null)

        assertThrows(IllegalArgumentException::class.java) {
            sabrPlayable(metadata, DEFAULT_MAX_VIDEO_HEIGHT)
        }
    }

    @Test
    fun rejectsUntrustedSabrEndpoint() {
        for (endpoint in listOf(
            "https://evil.example/videoplayback",
            "http://googlevideo.com/videoplayback",
            "https://googlevideo.com.evil.example/videoplayback",
        )) {
            val metadata = metadata(video(codec = "vp9", itag = 248), audio())
                .copy(serverAbrStreamingUrl = endpoint)

            assertThrows(IllegalArgumentException::class.java) {
                sabrPlayable(metadata, DEFAULT_MAX_VIDEO_HEIGHT)
            }
        }
    }

    @Test
    fun visionOsAnswerStreamsAsVisionOsWithoutAToken() {
        val session = sabrSessionFor(metadata(video(codec = "vp9", itag = 248), audio()))

        assertEquals(SabrClientIdentity.VISIONOS, session.identity)
        assertNull(session.poToken)
    }

    @Test
    fun rejectsAWebClientTokenThatIsNotBase64Url() {
        val client = WebStreamingClient(2, "2.20261023.01.00", "Apple", "iPad", "iPadOS", "16.7.10", "UA", "not a token!")
        val metadata = metadata(video(codec = "vp9", itag = 248), audio()).copy(webStreamingClient = client)

        assertThrows(IllegalArgumentException::class.java) {
            sabrSessionFor(metadata)
        }
    }

    @Test
    fun rejectsEmptyFormatLists() {
        val metadata = metadata(video(codec = "av01.0.08M.08", itag = 399), audio())

        assertThrows(IllegalArgumentException::class.java) {
            sabrPlayable(metadata, DEFAULT_MAX_VIDEO_HEIGHT)
        }
    }


    private fun metadata(vararg streams: StreamCandidate): VideoMetadata =
        VideoMetadata(
            id = VideoId("dQw4w9WgXcQ"),
            title = "test",
            uploaderName = "test",
            durationSeconds = 212,
            hlsUrl = null,
            dashUrl = null,
            streams = streams.toList(),
            serverAbrStreamingUrl = "https://sabr.googlevideo.com/videoplayback",
            ustreamerConfig = "dXN0cmVhbWVy",
        )

    private fun video(codec: String, itag: Int?): StreamCandidate =
        StreamCandidate(
            url = "sabr://$itag",
            kind = StreamKind.VIDEO_ONLY,
            mimeType = "video/webm",
            itag = itag,
            resolution = "1080p",
            bitrate = 2_000_000,
            codec = codec,
            lastModified = 1_700_000_000,
        )

    private fun sized(itag: Int, width: Int, height: Int): StreamCandidate =
        video(codec = "vp9", itag = itag).copy(width = width, height = height)

    private fun audio(): StreamCandidate =
        StreamCandidate(
            url = "sabr://251",
            kind = StreamKind.AUDIO,
            mimeType = "audio/webm",
            itag = 251,
            resolution = null,
            bitrate = 128_000,
            codec = "opus",
            lastModified = 1_700_000_000,
        )
}
