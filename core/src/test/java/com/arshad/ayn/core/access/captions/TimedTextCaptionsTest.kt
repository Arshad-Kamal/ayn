package com.arshad.ayn.core.access.captions

import com.arshad.ayn.core.access.CaptionCue
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.answer
import com.arshad.ayn.core.access.defaultHttpClient
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TimedTextCaptions] on YouTube's recorded TTML ([RecordedCaptions]), served through an
 * interceptor; Robolectric supplies the platform XML parser Media3's TTML parser runs on. YouTube
 * itself only under `KE_SMOKE=1`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimedTextCaptionsTest {
    private val requests = mutableListOf<Request>()

    @Test
    fun aManualTracksCuesComeBackOnePerLine() = runTest {
        val cues = found(source(answer(200, RecordedCaptions.manualTtml())).cues(MANUAL))

        assertEquals(RecordedCaptions.MANUAL_CUE_COUNT, cues.size)
        assertEquals(RecordedCaptions.MANUAL_FIRST_CUES, cues.take(3))
        assertEquals(RecordedCaptions.MANUAL_LAST_CUE, cues.last())
    }

    @Test
    fun anAutoTracksRollUpComesBackAsTwoLineCuesWithNoGapsShown() = runTest {
        val cues = found(source(answer(200, RecordedCaptions.autoTtml())).cues(AUTO))

        assertEquals(RecordedCaptions.AUTO_FIRST_CUES, cues.take(4))
    }

    @Test
    fun cuesAreInTimeOrderAndNeverOverlap() = runTest {
        val cues = found(source(answer(200, RecordedCaptions.autoTtml())).cues(AUTO))

        assertTrue(cues.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test
    fun theRequestIsTheTracksOwnLink() = runTest {
        source(answer(200, RecordedCaptions.manualTtml())).cues(MANUAL)

        assertEquals(MANUAL.url, requests.single().url.toString())
    }

    @Test
    fun aTrackWithNoTextHasNoCues() = runTest {
        val empty = """<?xml version="1.0" encoding="utf-8" ?><tt xmlns="http://www.w3.org/ns/ttml"><body><div></div></body></tt>"""

        assertEquals(CaptionLookup.Found(emptyList()), source(answer(200, empty)).cues(MANUAL))
    }

    /** YouTube's rate limit answers with an HTML page. */
    @Test
    fun aRateLimitFails() = runTest {
        assertEquals(CaptionLookup.Failed, source(answer(429, "<html>Sorry…</html>")).cues(MANUAL))
    }

    @Test
    fun anExpiredOrRefusedLinkFails() = runTest {
        assertEquals(CaptionLookup.Failed, source(answer(403, "")).cues(MANUAL))
        assertEquals(CaptionLookup.Failed, source(answer(404, "")).cues(MANUAL))
    }

    @Test
    fun noConnectionFails() = runTest {
        assertEquals(CaptionLookup.Failed, source({ throw IOException("unreachable") }).cues(MANUAL))
    }

    @Test
    fun anAnswerThatIsntTtmlFails() = runTest {
        assertEquals(CaptionLookup.Failed, source(answer(200, "<html><body>not captions</body></html>")).cues(MANUAL))
    }

    @Test
    fun spansWithNoTextOrUnderAMillisecondAreLeftOut() = runTest {
        val ttml = """<?xml version="1.0" encoding="utf-8" ?><tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:02.000">one</p>
            <p begin="00:00:02.000" end="00:00:03.000"> </p>
            <p begin="00:00:03.0000" end="00:00:03.0004">tiny</p>
            <p begin="00:00:04.000" end="00:00:05.000">two</p>
            </div></body></tt>"""

        val cues = found(source(answer(200, ttml)).cues(MANUAL))

        assertEquals(listOf(CaptionCue(1_000, 2_000, "one"), CaptionCue(4_000, 5_000, "two")), cues)
    }

    @Test
    fun anAnswerCutShortFails() = runTest {
        val text = RecordedCaptions.manualTtml()

        assertEquals(CaptionLookup.Failed, source(answer(200, text.take(text.length / 2))).cues(MANUAL))
    }

    /** A real track padded past the cap with XML whitespace: only the cap refuses it. */
    @Test
    fun anAnswerOverTheSizeCapFails() = runTest {
        val huge = RecordedCaptions.manualTtml() + " ".repeat(8 * 1024 * 1024)

        assertEquals(CaptionLookup.Failed, source(answer(200, huge)).cues(MANUAL))
    }

    @Test
    fun aTrackWhoseLinkIsntALinkFailsWithoutARequest() = runTest {
        assertEquals(CaptionLookup.Failed, source(answer(200, RecordedCaptions.manualTtml())).cues(MANUAL.copy(url = "not a link")))
        assertEquals(emptyList<Request>(), requests)
    }

    /** A loopback server that accepts and never answers: only cancelling the call ends the wait. */
    @Test
    fun cancellingTheLookupUnblocksAStalledRead() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val stalled = MANUAL.copy(url = "http://127.0.0.1:${server.localPort}/api/timedtext?lang=en")
            val started = TimeSource.Monotonic.markNow()

            val lookup = withTimeoutOrNull(200.milliseconds) { TimedTextCaptions(OkHttpClient(), Dispatchers.IO).cues(stalled) }

            assertNull(lookup)
            assertTrue("waited ${started.elapsedNow()}", started.elapsedNow() < 2.seconds)
        }
    }

    @Test
    fun youTubesTracksAnswerThroughTheSeam() = runTest {
        assumeTrue("set KE_SMOKE=1 to ask YouTube", System.getenv("KE_SMOKE") != null)
        val track = NewPipeAccess().video(VideoId("dQw4w9WgXcQ")).captionTracks.first { !it.autoGenerated }

        val lookup = TimedTextCaptions(defaultHttpClient()).cues(track)

        assertTrue("live answer: $lookup", lookup is CaptionLookup.Found && lookup.cues.isNotEmpty())
    }

    private fun found(lookup: CaptionLookup): List<CaptionCue> =
        (lookup as? CaptionLookup.Found)?.cues ?: throw AssertionError("expected cues, got $lookup")

    private fun source(respond: (Request) -> Response): TimedTextCaptions {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.request().also { requests += it }.let(respond) })
            .build()
        return TimedTextCaptions(client, UnconfinedTestDispatcher())
    }
}

private const val TRACK_URL = "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=en&fmt=ttml"
private val MANUAL = CaptionTrack(languageTag = "en", name = "English", autoGenerated = false, url = TRACK_URL)
private val AUTO = CaptionTrack(languageTag = "en", name = "English (auto-generated)", autoGenerated = true, url = "$TRACK_URL&kind=asr")
