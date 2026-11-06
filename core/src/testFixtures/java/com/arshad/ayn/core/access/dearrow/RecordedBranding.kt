package com.arshad.ayn.core.access.dearrow

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.answer
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * DeArrow's recorded answers for buckets `5f6b` and `6745` (`resources/dearrow/branding-<prefix>.json`:
 * `GET https://sponsor.ajay.app/api/branding/<prefix>`, no parameters, recorded 2026-10-06; 138 and
 * 163 videos), for the seam's own tests and the layers above it.
 */
object RecordedBranding {
    /** Locked title and thumbnail as recorded: [VIDEO_TITLE], the frame at [VIDEO_FRAME_SECONDS]. */
    val VIDEO: VideoId = VideoId("dQw4w9WgXcQ")
    const val VIDEO_TITLE = "Rick Astley - Never gonna give you up (official music video)"
    const val VIDEO_FRAME_SECONDS = "3.92349"

    /** Listed with no submissions: DeArrow has nothing for it. */
    val NO_SUBMISSIONS: VideoId = VideoId("thODYqd9G9U")

    /** The answer recorded for bucket [prefix]. */
    fun bucket(prefix: String): String = checkNotNull(recorded(prefix)) { "no recorded bucket $prefix" }

    /** The real [DeArrowBranding], each request answered with the recorded bucket it names, a
     *  bucket never recorded as empty (DeArrow's 404), so a page of other videos shows YouTube's;
     *  [onRequest] sees each bucket asked for first. */
    fun source(onRequest: (String) -> Unit = {}): DeArrowBranding {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val prefix = chain.request().url.pathSegments.last()
                    onRequest(prefix)
                    val body = recorded(prefix)
                    (if (body != null) answer(200, body) else answer(404, "Not Found"))(chain.request())
                },
            )
            .build()
        return DeArrowBranding(client, Dispatchers.Unconfined)
    }

    private fun recorded(prefix: String): String? =
        RecordedBranding::class.java.getResourceAsStream("/dearrow/branding-$prefix.json")?.use { it.readBytes().decodeToString() }
}
