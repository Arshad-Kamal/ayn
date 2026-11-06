package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.YouTubeRestrict
import com.arshad.ayn.core.access.defaultHttpClient
import java.io.IOException
import kotlin.time.toKotlinInstant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * OkHttp implementation of the vendored extractor's injectable `Downloader` seam (spec D7,
 * house media3.md PL-7 pattern: transport behind an app-owned boundary, no extraction logic
 * here). Blocking by contract: called from the extraction dispatcher only. HTTP 429 is the
 * extractor's typed rate-limit failure, as in upstream NewPipe's downloader. A request to one of
 * [YouTubeRestrict.HOSTS] made under a Restricted Mode context carries its header. Every
 * InnerTube answer's `Date` goes to [serverClock] (KP-D22).
 */
class OkHttpDownloader(
    private val client: OkHttpClient = defaultHttpClient(),
    private val serverClock: ServerClock? = null,
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val url = request.url().toHttpUrl()
        val builder = OkHttpRequest.Builder()
            .url(url)
            .method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val restrict = YouTubeRestrict.current()
        if (restrict != null && url.host in YouTubeRestrict.HOSTS) builder.header(YouTubeRestrict.HEADER, restrict)
        client.newCall(builder.build()).execute().use { response ->
            if (serverClock != null && isInnerTube(url)) {
                response.headers.getInstant(DATE)?.let { serverClock.record(it.toKotlinInstant()) }
            }
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val responseBody: String = response.body?.string() ?: ""
            val latestUrl: String = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                latestUrl,
            )
        }
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DATE = "Date"

        /** Both InnerTube hosts answer with a `Date` true to the second; the RSS feed's (also on
         *  www.youtube.com, outside this path) was 12 minutes stale (2026-10-10). */
        val INNERTUBE_HOSTS = setOf("www.youtube.com", "youtubei.googleapis.com")

        fun isInnerTube(url: HttpUrl): Boolean = url.host in INNERTUBE_HOSTS && url.encodedPath.startsWith("/youtubei/v1/")
    }
}
