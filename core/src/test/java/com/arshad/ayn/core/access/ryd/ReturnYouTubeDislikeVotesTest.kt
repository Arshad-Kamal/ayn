package com.arshad.ayn.core.access.ryd

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.answer
import com.arshad.ayn.core.access.defaultHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [ReturnYouTubeDislikeVotes] on Return YouTube Dislike's recorded answers ([RecordedVotes]),
 * served through an interceptor. The live service only under `KE_SMOKE=1` (re-record the fixtures
 * the same way if the shape moves).
 */
class ReturnYouTubeDislikeVotesTest {
    private val requests = mutableListOf<Request>()

    @Test
    fun aKnownVideosDislikesComeBack() = runTest {
        assertEquals(VoteLookup.Found(RecordedVotes.DISLIKES), source(answer(200, recorded(RICK))).votes(RICK))
    }

    @Test
    fun theRequestNamesTheVideo() = runTest {
        source(answer(200, recorded(RICK))).votes(RICK)

        assertEquals("https://returnyoutubedislikeapi.com/votes?videoId=dQw4w9WgXcQ", requests.single().url.toString())
    }

    @Test
    fun anUnknownVideoAnsweredWithZerosHasNone() = runTest {
        assertEquals(VoteLookup.None, source(answer(200, recorded(UNKNOWN))).votes(UNKNOWN))
    }

    /** No views is what marks no data; no dislikes on a watched video is data. */
    @Test
    fun aWatchedVideoWithNoDislikesHasZero() = runTest {
        val text = """{"id":"${RICK.value}","likes":5,"dislikes":0,"rating":5,"viewCount":10,"deleted":false}"""

        assertEquals(VoteLookup.Found(0), source(answer(200, text)).votes(RICK))
    }

    @Test
    fun notFoundIsNone() = runTest {
        assertEquals(VoteLookup.None, source(answer(404, "Not Found")).votes(RICK))
    }

    @Test
    fun aRateLimitFails() = runTest {
        assertEquals(VoteLookup.Failed, source(answer(429, "Too many requests")).votes(RICK))
    }

    @Test
    fun aBadRequestOrServerErrorFails() = runTest {
        assertEquals(VoteLookup.Failed, source(answer(400, "Bad Request")).votes(RICK))
        assertEquals(VoteLookup.Failed, source(answer(500, "")).votes(RICK))
    }

    @Test
    fun noConnectionFails() = runTest {
        assertEquals(VoteLookup.Failed, source({ throw IOException("unreachable") }).votes(RICK))
    }

    @Test
    fun anAnswerForAnotherVideoFails() = runTest {
        assertEquals(VoteLookup.Failed, source(answer(200, recorded(RICK))).votes(UNKNOWN))
    }

    @Test
    fun aNegativeDislikeCountFails() = runTest {
        val text = """{"id":"${RICK.value}","likes":5,"dislikes":-1,"rating":5,"viewCount":10,"deleted":false}"""

        assertEquals(VoteLookup.Failed, source(answer(200, text)).votes(RICK))
    }

    @Test
    fun aNegativeViewCountFails() = runTest {
        val text = """{"id":"${RICK.value}","likes":5,"dislikes":5,"rating":5,"viewCount":-1,"deleted":false}"""

        assertEquals(VoteLookup.Failed, source(answer(200, text)).votes(RICK))
    }

    @Test
    fun anAnswerCutShortFails() = runTest {
        val text = recorded(RICK)

        assertEquals(VoteLookup.Failed, source(answer(200, text.take(text.length / 2))).votes(RICK))
    }

    @Test
    fun anAnswerOfTheWrongShapeFails() = runTest {
        assertEquals(VoteLookup.Failed, source(answer(200, """[{"id":"x"}]""")).votes(RICK))
    }

    /** Well-shaped but for a key the decoder would skip: only the depth guard refuses it. */
    @Test
    fun anAnswerNestedDeeperThanAnyRealOneFails() = runTest {
        val deep = """{"id":"${RICK.value}","dislikes":1,"viewCount":10,"extra":${"[".repeat(4)}${"]".repeat(4)}}"""

        assertEquals(VoteLookup.Failed, source(answer(200, deep)).votes(RICK))
    }

    /** A real answer padded past the cap: only the cap refuses it. */
    @Test
    fun anAnswerOverTheSizeCapFails() = runTest {
        val huge = recorded(RICK).trimEnd().dropLast(1) + " ".repeat(64 * 1024) + "}"

        assertEquals(VoteLookup.Failed, source(answer(200, huge)).votes(RICK))
    }

    /** A loopback server that accepts and never answers: the blocked socket read ignores thread
     *  interrupts, so only cancelling the call ends the wait (real time, real IO threads). */
    @Test
    fun cancellingTheLookupUnblocksAStalledRead() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val base = "http://127.0.0.1:${server.localPort}/votes".toHttpUrl()
            val started = TimeSource.Monotonic.markNow()

            val lookup = withTimeoutOrNull(200.milliseconds) {
                ReturnYouTubeDislikeVotes(OkHttpClient(), Dispatchers.IO, base).votes(RICK)
            }

            assertNull(lookup)
            assertTrue("waited ${started.elapsedNow()}", started.elapsedNow() < 2.seconds)
        }
    }

    @Test
    fun theLiveServiceAnswersThroughTheSeam() = runTest {
        assumeTrue("set KE_SMOKE=1 to ask Return YouTube Dislike", System.getenv("KE_SMOKE") != null)

        val lookup = ReturnYouTubeDislikeVotes(defaultHttpClient()).votes(RICK)

        assertTrue("live answer: $lookup", lookup is VoteLookup.Found)
    }

    private fun source(respond: (Request) -> Response): ReturnYouTubeDislikeVotes {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.request().also { requests += it }.let(respond) })
            .build()
        return ReturnYouTubeDislikeVotes(client, UnconfinedTestDispatcher())
    }

    private fun recorded(videoId: VideoId): String = RecordedVotes.body(videoId)
}

private val RICK = RecordedVotes.VIDEO
private val UNKNOWN = RecordedVotes.UNKNOWN
