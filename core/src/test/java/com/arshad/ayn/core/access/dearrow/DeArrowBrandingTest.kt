package com.arshad.ayn.core.access.dearrow

import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
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
 * [DeArrowBranding] on DeArrow's recorded answers ([RecordedBranding]), served through an
 * interceptor that answers by the requested bucket. The default answer hides negative-score
 * submissions, so the untrusted cases are written by hand. The live service only under
 * `KE_SMOKE=1` (re-record the fixtures the same way if the shape moves).
 */
class DeArrowBrandingTest {
    private val requests = mutableListOf<Request>()

    @Test
    fun aLockedTitleAndThumbnailReplaceYouTubes() = runTest {
        val lookup = source(recordedBuckets()).branding(RICK, RICK_ORIGINAL)

        assertEquals(
            BrandingLookup.Found(
                Branding(
                    title = RecordedBranding.VIDEO_TITLE,
                    thumbnailUrl = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=dQw4w9WgXcQ&time=${RecordedBranding.VIDEO_FRAME_SECONDS}" +
                        "&redirectUrl=https%3A%2F%2Fi.ytimg.com%2Fvi%2FdQw4w9WgXcQ%2Fmqdefault.jpg",
                ),
            ),
            lookup,
        )
    }

    @Test
    fun anUnlockedSubmissionAtZeroVotesIsTrusted() = runTest {
        val lookup = source(recordedBuckets()).branding(VideoId("q4FQa7N4kBg"), null)

        assertEquals(
            BrandingLookup.Found(
                Branding(
                    title = "Sarah Colwill, a woman with Foreign Accent Syndrome | Tales from the Internet",
                    thumbnailUrl = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=q4FQa7N4kBg&time=54.760145",
                ),
            ),
            lookup,
        )
    }

    /** M7-D8: no random frame; the first of the video's two titles. */
    @Test
    fun aTitleWithoutAThumbnailKeepsYouTubesPicture() = runTest {
        val lookup = source(recordedBuckets()).branding(VideoId("9wX4NFCUeGQ"), "https://i.ytimg.com/vi/9wX4NFCUeGQ/mqdefault.jpg")

        assertEquals(BrandingLookup.Found(Branding(title = "GameStop is offering to buy eBay", thumbnailUrl = null)), lookup)
    }

    @Test
    fun theNoFormatMarkersAreStripped() = runTest {
        val lookup = source(recordedBuckets()).branding(VideoId("TvTjKybsnI0"), null)

        assertEquals(
            BrandingLookup.Found(Branding("Loch Ness Monster - Busch Gardens Williamsburg | Problematic Roller Coasters", null)),
            lookup,
        )
    }

    /** Only a `>` that starts the title or follows a space and precedes a word is a marker. */
    @Test
    fun aGreaterThanSignThatIsNoMarkerStays() = runTest {
        val text = handWritten(titles = """{"title":">Leading marker: 5 > 3, a->b ","original":false,"votes":0,"locked":false}""", thumbnails = "")

        assertEquals(
            BrandingLookup.Found(Branding("Leading marker: 5 > 3, a->b", null)),
            source(answer(200, text)).branding(RICK, null),
        )
    }

    @Test
    fun aTrustedThumbnailReplacesYouTubesUnderAnUntrustedTitle() = runTest {
        val text = handWritten(
            titles = """{"title":"Clickbait fixed","original":false,"votes":-1,"locked":false}""",
            thumbnails = """{"timestamp":12.5,"original":false,"votes":0,"locked":false}""",
        )

        assertEquals(
            BrandingLookup.Found(Branding(null, "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=dQw4w9WgXcQ&time=12.5")),
            source(answer(200, text)).branding(RICK, null),
        )
    }

    /** YouTube allows 100 characters; the seam keeps up to 300. */
    @Test
    fun aTitleLongerThanAnyRealOneKeepsYouTubes() = runTest {
        val text = handWritten(titles = """{"title":"${"x".repeat(301)}","original":false,"votes":0,"locked":true}""", thumbnails = "")

        assertEquals(BrandingLookup.None, source(answer(200, text)).branding(RICK, null))
    }

    /** "Me at the zoo": the locked first title and the first thumbnail are YouTube's own. */
    @Test
    fun aFirstSubmissionMarkedOriginalKeepsYouTubes() = runTest {
        assertEquals(BrandingLookup.None, source(recordedBuckets()).branding(VideoId("jNQXAC9IVRw"), null))
    }

    @Test
    fun aVideoListedWithNoSubmissionsHasNone() = runTest {
        assertEquals(BrandingLookup.None, source(recordedBuckets()).branding(NO_SUBMISSIONS, null))
    }

    /** notInBuaxxu hashes into bucket 5f6b but isn't listed in it. */
    @Test
    fun aVideoMissingFromItsBucketHasNone() = runTest {
        assertEquals(BrandingLookup.None, source(recordedBuckets()).branding(VideoId("notInBuaxxu"), null))
    }

    @Test
    fun anEmptyBucketIsNone() = runTest {
        assertEquals(BrandingLookup.None, source(answer(404, "Not Found")).branding(RICK, null))
    }

    @Test
    fun anUntrustedFirstSubmissionKeepsYouTubesEvenWithATrustedOneBehind() = runTest {
        val text = handWritten(
            titles = """{"title":"Clickbait fixed","original":false,"votes":-1,"locked":false},
                {"title":"Second","original":false,"votes":5,"locked":false}""",
            thumbnails = """{"timestamp":12.5,"original":false,"votes":-2,"locked":false},
                {"timestamp":20,"original":false,"votes":5,"locked":false}""",
        )

        assertEquals(BrandingLookup.None, source(answer(200, text)).branding(RICK, null))
    }

    @Test
    fun aLockedSubmissionIsTrustedWhateverItsVotes() = runTest {
        val text = handWritten(
            titles = """{"title":"Locked title","original":false,"votes":-3,"locked":true}""",
            thumbnails = """{"timestamp":12.5,"original":false,"votes":-3,"locked":true}""",
        )

        assertEquals(
            BrandingLookup.Found(
                Branding("Locked title", "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=dQw4w9WgXcQ&time=12.5"),
            ),
            source(answer(200, text)).branding(RICK, null),
        )
    }

    @Test
    fun aBlankTitleAndAFrameNoVideoHasAreDropped() = runTest {
        val text = handWritten(
            titles = """{"title":"  ","original":false,"votes":0,"locked":false}""",
            thumbnails = """{"timestamp":-1,"original":false,"votes":0,"locked":false}""",
        )

        assertEquals(BrandingLookup.None, source(answer(200, text)).branding(RICK, null))
    }

    @Test
    fun theRequestNamesTheBucketNotTheVideo() = runTest {
        source(recordedBuckets()).branding(RICK, null)

        val url = requests.single().url
        assertEquals("/api/branding/5f6b", url.encodedPath)
        assertEquals(false, url.toString().contains(RICK.value))
    }

    @Test
    fun aSecondAskIsAnsweredFromTheCacheWithItsOwnOriginal() = runTest {
        val source = source(recordedBuckets())
        source.branding(RICK, null)

        val lookup = source.branding(RICK, "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg")

        assertEquals(1, requests.size)
        assertEquals(
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=dQw4w9WgXcQ&time=${RecordedBranding.VIDEO_FRAME_SECONDS}" +
                "&redirectUrl=https%3A%2F%2Fi.ytimg.com%2Fvi%2FdQw4w9WgXcQ%2Fmaxresdefault.jpg",
            (lookup as BrandingLookup.Found).branding.thumbnailUrl,
        )
    }

    @Test
    fun nothingIsAtHandBeforeTheFirstAsk() {
        assertNull(source(recordedBuckets()).cached(RICK, null))
        assertEquals(0, requests.size)
    }

    @Test
    fun anAnswerIsAtHandAfterItsAskWithTheCallersOriginal() = runTest {
        val source = source(recordedBuckets())
        source.branding(RICK, null)
        source.branding(NO_SUBMISSIONS, null)

        assertEquals(source.branding(RICK, RICK_ORIGINAL), source.cached(RICK, RICK_ORIGINAL))
        assertEquals(BrandingLookup.None, source.cached(NO_SUBMISSIONS, null))
        assertEquals(2, requests.size)
    }

    @Test
    fun aFailureLeavesNothingAtHand() = runTest {
        val source = source(answer(500, ""))
        source.branding(RICK, null)

        assertNull(source.cached(RICK, null))
    }

    @Test
    fun noneIsCachedToo() = runTest {
        val source = source(recordedBuckets())
        source.branding(NO_SUBMISSIONS, null)

        assertEquals(BrandingLookup.None, source.branding(NO_SUBMISSIONS, null))
        assertEquals(1, requests.size)
    }

    @Test
    fun aFailureIsNotCachedSoTheNextAskTriesAgain() = runTest {
        var failing = true
        val source = source({ request -> if (failing) answer(500, "")(request) else recordedBuckets()(request) })
        assertEquals(BrandingLookup.Failed, source.branding(RICK, null))
        failing = false

        val lookup = source.branding(RICK, null)

        assertTrue("after recovery: $lookup", lookup is BrandingLookup.Found)
        assertEquals(2, requests.size)
    }

    @Test
    fun theLeastRecentlyUsedVideoLeavesAFullCache() = runTest {
        val source = source(recordedBuckets(), cacheSize = 2)
        source.branding(RICK, null)
        source.branding(VideoId("q4FQa7N4kBg"), null)
        source.branding(RICK, null) // used again: q4FQa7N4kBg is now the oldest
        source.branding(VideoId("9wX4NFCUeGQ"), null)
        requests.clear()

        source.branding(RICK, null)
        source.branding(VideoId("q4FQa7N4kBg"), null)

        assertEquals(1, requests.size)
    }

    @Test
    fun aRateLimitOrServerErrorFails() = runTest {
        assertEquals(BrandingLookup.Failed, source(answer(429, "Too many requests")).branding(RICK, null))
        assertEquals(BrandingLookup.Failed, source(answer(500, "")).branding(RICK, null))
    }

    @Test
    fun noConnectionFails() = runTest {
        assertEquals(BrandingLookup.Failed, source({ throw IOException("unreachable") }).branding(RICK, null))
    }

    @Test
    fun anAnswerCutShortFails() = runTest {
        val text = RecordedBranding.bucket("5f6b")

        assertEquals(BrandingLookup.Failed, source(answer(200, text.take(text.length / 2))).branding(RICK, null))
    }

    @Test
    fun anAnswerOfTheWrongShapeFails() = runTest {
        assertEquals(BrandingLookup.Failed, source(answer(200, """[{"videoID":"x"}]""")).branding(RICK, null))
    }

    /** Well-shaped but for a key the decoder would skip: only the depth guard refuses it. */
    @Test
    fun anAnswerNestedDeeperThanAnyRealOneFails() = runTest {
        val deep = """{"${RICK.value}":{"titles":[],"thumbnails":[],"extra":${"[".repeat(7)}${"]".repeat(7)}}}"""

        assertEquals(BrandingLookup.Failed, source(answer(200, deep)).branding(RICK, null))
    }

    @Test
    fun anAnswerOverTheSizeCapFails() = runTest {
        val huge = "{" + " ".repeat(1024 * 1024) + "}"

        assertEquals(BrandingLookup.Failed, source(answer(200, huge)).branding(RICK, null))
    }

    /** A loopback server that accepts and never answers: the blocked socket read ignores thread
     *  interrupts, so only cancelling the call ends the wait (real time, real IO threads). */
    @Test
    fun cancellingTheLookupUnblocksAStalledRead() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val base = "http://127.0.0.1:${server.localPort}/api/branding".toHttpUrl()
            val started = TimeSource.Monotonic.markNow()

            val lookup = withTimeoutOrNull(200.milliseconds) {
                DeArrowBranding(OkHttpClient(), Dispatchers.IO, base).branding(RICK, null)
            }

            assertNull(lookup)
            assertTrue("waited ${started.elapsedNow()}", started.elapsedNow() < 2.seconds)
        }
    }

    @Test
    fun theLiveServiceAnswersThroughTheSeam() = runTest {
        assumeTrue("set KE_SMOKE=1 to ask DeArrow", System.getenv("KE_SMOKE") != null)

        val lookup = DeArrowBranding(defaultHttpClient()).branding(RICK, RICK_ORIGINAL)

        assertTrue("live answer: $lookup", lookup is BrandingLookup.Found)
    }

    private fun source(respond: (Request) -> Response, cacheSize: Int = 1_000): DeArrowBranding {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.request().also { requests += it }.let(respond) })
            .build()
        return DeArrowBranding(client, UnconfinedTestDispatcher(), cacheSize = cacheSize)
    }

    /** Answers each request with the recorded bucket it names. */
    private fun recordedBuckets(): (Request) -> Response = { request ->
        answer(200, RecordedBranding.bucket(request.url.pathSegments.last()))(request)
    }

    private fun handWritten(titles: String, thumbnails: String): String =
        """{"${RICK.value}":{"titles":[$titles],"thumbnails":[$thumbnails],"randomTime":0.5,"videoDuration":null}}"""

}

private val RICK = RecordedBranding.VIDEO
private val NO_SUBMISSIONS = RecordedBranding.NO_SUBMISSIONS
private const val RICK_ORIGINAL = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
