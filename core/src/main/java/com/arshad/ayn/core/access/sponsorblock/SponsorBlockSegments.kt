package com.arshad.ayn.core.access.sponsorblock

import android.util.Log
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.SegmentSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.executeCancellably
import com.arshad.ayn.core.access.hashPrefix
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.access.textWithin
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [SegmentSource] over SponsorBlock's hash-prefix endpoint (M7-D2): the request names the
 * video's bucket ([hashPrefix]), never the video; the answer lists every video in the bucket and
 * this keeps [videoId]'s. Skip-type segments only (M7-D3). Cancelling the lookup cancels its HTTP
 * call ([executeCancellably]).
 */
class SponsorBlockSegments(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
) : SegmentSource {

    override suspend fun segments(videoId: VideoId, categories: Set<SegmentCategory>): SegmentLookup {
        if (categories.isEmpty()) return SegmentLookup.None
        val call = client.newCall(Request.Builder().url(url(videoId, categories)).build())
        return try {
            call.executeCancellably(io) { response -> read(response, videoId, categories) }
        } catch (e: IOException) {
            failed(e.javaClass.simpleName)
        }
    }

    /** `…/skipSegments/<prefix>?categories=[…]&actionTypes=["skip"]`, categories in a fixed order. */
    private fun url(videoId: VideoId, categories: Set<SegmentCategory>): HttpUrl =
        apiBase.newBuilder()
            .addPathSegment(hashPrefix(videoId))
            .addQueryParameter("categories", SegmentCategory.entries.filter { it in categories }.joinToString(",", "[", "]") { "\"${it.key}\"" })
            .addQueryParameter("actionTypes", "[\"$SKIP\"]")
            .build()

    private fun read(response: Response, videoId: VideoId, categories: Set<SegmentCategory>): SegmentLookup {
        if (response.code == HTTP_NOT_FOUND) return SegmentLookup.None // nothing in the bucket
        if (!response.isSuccessful) return failed("HTTP ${response.code}")
        val text = response.textWithin(MAX_RESPONSE_BYTES) ?: return failed("answer too large")
        return parse(text, videoId, categories)
    }

    private fun parse(text: String, videoId: VideoId, categories: Set<SegmentCategory>): SegmentLookup {
        if (!nestsWithin(text, MAX_JSON_DEPTH)) return failed("answer nested too deep")
        val videos = decodeOrNull(text, ListSerializer(WireVideo.serializer())) ?: return failed("answer unreadable")
        val segments = videos.filter { it.videoID == videoId.value }
            .flatMap { it.segments }
            .mapNotNull { it.toSegment(categories) }
            .sortedBy { it.startMs }
        return if (segments.isEmpty()) SegmentLookup.None else SegmentLookup.Found(segments)
    }

    /** Only a bounded, fixed reason: the server's text and the decoder's message stay out (KT-JSON-04). */
    private fun failed(reason: String): SegmentLookup {
        Log.w(LOG_TAG, "segments lookup failed: $reason")
        return SegmentLookup.Failed
    }

    @Serializable
    private class WireVideo(val videoID: String, val segments: List<WireSegment>)

    @Serializable
    private class WireSegment(val category: String, val actionType: String, val segment: List<Double>) {
        /** `null` for a segment of another kind or category, or with times no video has. */
        fun toSegment(asked: Set<SegmentCategory>): Segment? {
            val category = SegmentCategory.of(this.category)?.takeIf { it in asked } ?: return null
            if (actionType != SKIP || segment.size != 2 || segment.any { !it.isFinite() }) return null
            val (startMs, endMs) = segment.map { (it * 1000).roundToLong() }
            return if (startMs >= 0 && endMs > startMs) Segment(category, startMs, endMs) else null
        }
    }

    private companion object {
        const val API_BASE = "https://sponsor.ajay.app/api/skipSegments"
        const val SKIP = "skip"
        const val HTTP_NOT_FOUND = 404

        /** A bucket's answer is ~52 KB (2026-10-06); far above that is not a real one. */
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        /** The answer nests 5 deep (list, video, segments, segment, its two times). */
        const val MAX_JSON_DEPTH = 8
        const val LOG_TAG = "KeSegments"
    }
}
