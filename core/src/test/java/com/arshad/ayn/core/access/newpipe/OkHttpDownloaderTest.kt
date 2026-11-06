package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.YouTubeRestrict
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** The transport against canned HTTP answers (an interceptor plays the server, no network). */
class OkHttpDownloaderTest {

    @Test
    fun rateLimitingIsTheExtractorsTypedFailure() {
        val downloader = OkHttpDownloader(clientAnswering(429))

        val error = assertThrows(ReCaptchaException::class.java) { downloader.execute(get(URL)) }

        assertEquals(URL, error.url)
    }

    @Test
    fun otherStatusesReachTheExtractorWithTheirBody() {
        val downloader = OkHttpDownloader(clientAnswering(404, body = "gone"))

        val response = downloader.execute(get(URL))

        assertEquals(404, response.responseCode())
        assertEquals("gone", response.responseBody())
    }

    /** The suggest endpoint sends Latin-1 text as ISO-8859-1 bytes under that declared charset. */
    @Test
    fun bodiesDecodeInTheirDeclaredCharset() {
        val latin1Body = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
            .toResponseBody("application/json; charset=ISO-8859-1".toMediaType())
        val downloader = OkHttpDownloader(clientAnswering(200, latin1Body))

        val response = downloader.execute(get(URL))

        assertEquals("caf\u00e9", response.responseBody())
    }

    /** KP-D2: a call's Restricted Mode reaches each request to Google's listed hosts. */
    @Test
    fun aRestrictedCallsRequestsToYouTubeCarryTheHeader() {
        val sent = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpDownloader(clientAnswering(200, sent = sent))

        runBlocking(YouTubeRestrict.context(YouTubeRestrict.STRICT)) {
            downloader.execute(get(URL))
            downloader.execute(get("https://youtubei.googleapis.com/youtubei/v1/player"))
        }

        assertEquals(listOf("Strict", "Strict"), sent.map { it.header(YouTubeRestrict.HEADER) })
    }

    @Test
    fun otherHostsAndUnrestrictedCallsSendNoHeader() {
        val sent = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpDownloader(clientAnswering(200, sent = sent))

        runBlocking(YouTubeRestrict.context(YouTubeRestrict.STRICT)) {
            downloader.execute(get("https://suggestqueries-clients6.youtube.com/complete/search?q=a"))
        }
        runBlocking(YouTubeRestrict.context(null)) { downloader.execute(get(URL)) }
        downloader.execute(get(URL))

        assertEquals(listOf(null, null, null), sent.map { it.header(YouTubeRestrict.HEADER) })
        assertNull("the context ends with its call", YouTubeRestrict.current())
    }

    /** KP-D22: the clock takes InnerTube's `Date`, never the RSS feed's (cached, minutes stale). */
    @Test
    fun onlyInnerTubeAnswersSetTheServerClock() {
        val elapsed = TestTimeSource()
        val clock = ServerClock(elapsed)
        val feedDownloader = OkHttpDownloader(clientAnswering(200, date = "Sat, 10 Oct 2026 08:00:00 GMT"), clock)
        feedDownloader.execute(get(URL))
        assertNull(clock.now())

        val innerTubeDownloader = OkHttpDownloader(clientAnswering(200, date = "Sat, 10 Oct 2026 09:30:00 GMT"), clock)
        innerTubeDownloader.execute(get("https://youtubei.googleapis.com/youtubei/v1/player"))
        elapsed += 90.seconds

        assertEquals(Instant.parse("2026-10-10T09:31:30Z"), clock.now())
    }

    private fun get(url: String): Request = Request.newBuilder().get(url).build()

    private fun clientAnswering(
        code: Int,
        body: String = "",
        sent: MutableList<okhttp3.Request>? = null,
        date: String? = null,
    ): OkHttpClient = clientAnswering(code, body.toResponseBody(), sent, date)

    private fun clientAnswering(
        code: Int,
        body: ResponseBody,
        sent: MutableList<okhttp3.Request>? = null,
        date: String? = null,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    sent?.add(chain.request())
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .apply { if (date != null) header("Date", date) }
                        .body(body)
                        .build()
                },
            )
            .build()

    private companion object {
        const val URL = "https://www.youtube.com/feeds/videos.xml?channel_id=UCuAXFkgsw1L7xaCfnd5JJOw"
    }
}
