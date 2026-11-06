package com.arshad.ayn.core.access.sponsorblock

import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
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
 * [SponsorBlockSegments] on SponsorBlock's recorded answer ([RecordedSegments]), served through
 * an interceptor. The live service only under `KE_SMOKE=1` (re-record the fixture as
 * [RecordedSegments] says if its shape moves).
 */
class SponsorBlockSegmentsTest {
    private val requests = mutableListOf<Request>()

    @Test
    fun theVideosSegmentsComeBackInStartOrderInMilliseconds() = runTest {
        val lookup = source(answer(200, recorded())).segments(RECORDED_VIDEO, SegmentCategory.entries.toSet())

        assertEquals(
            SegmentLookup.Found(
                listOf(
                    Segment(SegmentCategory.Preview, 0, 51_970),
                    Segment(SegmentCategory.Intro, 51_970, 58_394),
                    Segment(SegmentCategory.Sponsor, 56_208, 134_034),
                    Segment(SegmentCategory.Interaction, 681_274, 687_665),
                    Segment(SegmentCategory.Outro, 687_665, 697_601),
                ),
            ),
            lookup,
        )
    }

    @Test
    fun theRequestNamesTheBucketNotTheVideoAndOnlyTheAskedCategories() = runTest {
        source(answer(200, recorded())).segments(RECORDED_VIDEO, setOf(SegmentCategory.Outro, SegmentCategory.Sponsor))

        val url = requests.single().url
        assertEquals("/api/skipSegments/5f6b", url.encodedPath)
        assertEquals("""["sponsor","outro"]""", url.queryParameter("categories"))
        assertEquals("""["skip"]""", url.queryParameter("actionTypes"))
        assertEquals(false, url.toString().contains(RECORDED_VIDEO.value))
    }

    @Test
    fun segmentsOfCategoriesNotAskedForAreDropped() = runTest {
        val lookup = source(answer(200, recorded())).segments(RECORDED_VIDEO, setOf(SegmentCategory.Sponsor))

        assertEquals(SegmentLookup.Found(listOf(Segment(SegmentCategory.Sponsor, 56_208, 134_034))), lookup)
    }

    /** dQw4w9WgXcQ hashes into the bucket but has no segments in it. */
    @Test
    fun aVideoMissingFromItsBucketHasNone() = runTest {
        assertEquals(SegmentLookup.None, source(answer(200, recorded())).segments(VideoId("dQw4w9WgXcQ"), ALL))
    }

    @Test
    fun anEmptyBucketIsNone() = runTest {
        assertEquals(SegmentLookup.None, source(answer(404, "Not Found")).segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun noCategoriesAskedMeansNoRequest() = runTest {
        assertEquals(SegmentLookup.None, source(answer(200, recorded())).segments(RECORDED_VIDEO, emptySet()))
        assertEquals(0, requests.size)
    }

    @Test
    fun aRateLimitOrServerErrorFails() = runTest {
        assertEquals(SegmentLookup.Failed, source(answer(429, "Too many requests")).segments(RECORDED_VIDEO, ALL))
        assertEquals(SegmentLookup.Failed, source(answer(500, "")).segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun noConnectionFails() = runTest {
        assertEquals(SegmentLookup.Failed, source { throw IOException("unreachable") }.segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun anAnswerCutShortFails() = runTest {
        val text = recorded()

        assertEquals(SegmentLookup.Failed, source(answer(200, text.take(text.length / 2))).segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun anAnswerOfTheWrongShapeFails() = runTest {
        assertEquals(SegmentLookup.Failed, source(answer(200, """{"videoID":"x"}""")).segments(RECORDED_VIDEO, ALL))
    }

    /** Well-shaped but for a key the decoder would skip: only the depth guard refuses it. */
    @Test
    fun anAnswerNestedDeeperThanAnyRealOneFails() = runTest {
        val deep = """[{"videoID":"x","segments":[],"extra":${"[".repeat(7)}${"]".repeat(7)}}]"""

        assertEquals(SegmentLookup.Failed, source(answer(200, deep)).segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun anAnswerOverTheSizeCapFails() = runTest {
        val huge = "[" + " ".repeat(2 * 1024 * 1024) + "]"

        assertEquals(SegmentLookup.Failed, source(answer(200, huge)).segments(RECORDED_VIDEO, ALL))
    }

    @Test
    fun segmentsWithTimesNoVideoHasOrAnotherActionAreDropped() = runTest {
        val id = RECORDED_VIDEO.value
        val text = """[{"videoID":"$id","segments":[
            {"category":"sponsor","actionType":"skip","segment":[1.005,20]},
            {"category":"sponsor","actionType":"mute","segment":[30,40]},
            {"category":"intro","actionType":"skip","segment":[50,50]},
            {"category":"intro","actionType":"skip","segment":[-1,5]},
            {"category":"outro","actionType":"skip","segment":[60]},
            {"category":"outro","actionType":"skip","segment":[1.0001,1.0004]},
            {"category":"preview","actionType":"skip","segment":[1e300,2e300]},
            {"category":"poi_highlight","actionType":"skip","segment":[70,80]}]}]"""

        assertEquals(
            SegmentLookup.Found(listOf(Segment(SegmentCategory.Sponsor, 1_005, 20_000))),
            source(answer(200, text)).segments(RECORDED_VIDEO, ALL),
        )
    }

    /** A loopback server that accepts and never answers: the blocked socket read ignores thread
     *  interrupts, so only cancelling the call ends the wait (real time, real IO threads). */
    @Test
    fun cancellingTheLookupUnblocksAStalledRead() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val base = "http://127.0.0.1:${server.localPort}/api/skipSegments".toHttpUrl()
            val started = TimeSource.Monotonic.markNow()

            val lookup = withTimeoutOrNull(200.milliseconds) {
                SponsorBlockSegments(OkHttpClient(), Dispatchers.IO, base).segments(RECORDED_VIDEO, ALL)
            }

            assertNull(lookup)
            assertTrue("waited ${started.elapsedNow()}", started.elapsedNow() < 2.seconds)
        }
    }

    @Test
    fun theLiveServiceAnswersThroughTheSeam() = runTest {
        assumeTrue("set KE_SMOKE=1 to ask SponsorBlock", System.getenv("KE_SMOKE") != null)

        val lookup = SponsorBlockSegments(defaultHttpClient()).segments(RECORDED_VIDEO, ALL)

        assertTrue("live answer: $lookup", lookup is SegmentLookup.Found)
    }

    private fun source(respond: (Request) -> Response): SponsorBlockSegments {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.request().also { requests += it }.let(respond) })
            .build()
        return SponsorBlockSegments(client, UnconfinedTestDispatcher())
    }

    private fun recorded(): String = RecordedSegments.body()
}

private val RECORDED_VIDEO = RecordedSegments.VIDEO
private val ALL = SegmentCategory.entries.toSet()
