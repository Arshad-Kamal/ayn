package com.arshad.ayn.core.access.dearrow

import android.util.Log
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.executeCancellably
import com.arshad.ayn.core.access.hashPrefix
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.access.textWithin
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [BrandingSource] over DeArrow's hash-prefix endpoint (M7-D2): the request names the video's
 * bucket ([hashPrefix]), never the video. Per video only the first title and the first thumbnail
 * count, and only when trusted (locked, or votes >= 0: DeArrow's API docs); a first one marked
 * `original` is YouTube's own, so YouTube's stays. A title is shown as written, DeArrow's `>`
 * markers stripped (a blank one, or one over 300 characters where YouTube allows 100, keeps
 * YouTube's); a title without a thumbnail keeps YouTube's picture (M7-D8). A thumbnail is the frame
 * DeArrow's thumbnail server renders, redirecting to the original when it has none (M7-D4).
 *
 * Each video's answer, "none" included, is kept in memory for this instance's life (least recently
 * used out first, M7-D4); a failed lookup isn't kept, so the next ask tries again. Cancelling a
 * lookup cancels its HTTP call ([executeCancellably]).
 */
class DeArrowBranding(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
    private val thumbnailBase: HttpUrl = THUMBNAIL_BASE.toHttpUrl(),
    private val cacheSize: Int = CACHE_SIZE,
) : BrandingSource {

    private val cache = object : LinkedHashMap<VideoId, Choice>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VideoId, Choice>): Boolean = size > cacheSize
    }

    override suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup {
        val choice = synchronized(cache) { cache[videoId] }
            ?: fetch(videoId)?.also { synchronized(cache) { cache[videoId] = it } }
            ?: return BrandingLookup.Failed
        return lookupOf(videoId, choice, originalThumbnailUrl)
    }

    override fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? =
        synchronized(cache) { cache[videoId] }?.let { lookupOf(videoId, it, originalThumbnailUrl) }

    private fun lookupOf(videoId: VideoId, choice: Choice, originalThumbnailUrl: String?): BrandingLookup {
        val thumbnailUrl = choice.thumbnailAtSeconds?.let { thumbnailUrl(videoId, it, originalThumbnailUrl) }
        if (choice.title == null && thumbnailUrl == null) return BrandingLookup.None
        return BrandingLookup.Found(Branding(choice.title, thumbnailUrl))
    }

    /** DeArrow's choice for [videoId], or `null` when the lookup failed. */
    private suspend fun fetch(videoId: VideoId): Choice? {
        // Lets a device session count fetches per video (CardBranding shares concurrent asks; this
        // source doesn't); off unless the tag is enabled (`adb shell setprop log.tag.KeBranding DEBUG`).
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, "branding fetch ${videoId.value}")
        val call = client.newCall(Request.Builder().url(url(videoId)).build())
        return try {
            call.executeCancellably(io) { response -> read(response, videoId) }
        } catch (e: IOException) {
            failed(e.javaClass.simpleName)
        }
    }

    /** `…/branding/<prefix>`: the bucket, not the video. */
    private fun url(videoId: VideoId): HttpUrl = apiBase.newBuilder().addPathSegment(hashPrefix(videoId)).build()

    private fun read(response: Response, videoId: VideoId): Choice? {
        if (response.code == HTTP_NOT_FOUND) return NO_CHOICE // nothing in the bucket
        if (!response.isSuccessful) return failed("HTTP ${response.code}")
        val text = response.textWithin(MAX_RESPONSE_BYTES) ?: return failed("answer too large")
        if (!nestsWithin(text, MAX_JSON_DEPTH)) return failed("answer nested too deep")
        val videos = decodeOrNull(text, MapSerializer(String.serializer(), WireBranding.serializer()))
            ?: return failed("answer unreadable")
        return videos[videoId.value]?.choice() ?: NO_CHOICE
    }

    /** `…/getThumbnail?videoID=…&time=…&redirectUrl=<original>`; no redirect without an original. */
    private fun thumbnailUrl(videoId: VideoId, atSeconds: Double, originalThumbnailUrl: String?): String =
        thumbnailBase.newBuilder()
            .addQueryParameter("videoID", videoId.value)
            .addQueryParameter("time", atSeconds.toString())
            .apply { if (originalThumbnailUrl != null) addQueryParameter("redirectUrl", originalThumbnailUrl) }
            .build()
            .toString()

    /** Only a bounded, fixed reason: the server's text and the decoder's message stay out (KT-JSON-04). */
    private fun failed(reason: String): Choice? {
        Log.w(LOG_TAG, "branding lookup failed: $reason")
        return null
    }

    /** DeArrow's trusted title and thumbnail frame for a video; `null` keeps YouTube's. */
    private data class Choice(val title: String?, val thumbnailAtSeconds: Double?)

    @Serializable
    private class WireBranding(val titles: List<WireTitle>, val thumbnails: List<WireThumbnail>) {
        fun choice(): Choice = Choice(
            title = titles.firstOrNull()?.takeIf { it.trusted && !it.original }
                ?.title?.replace(NO_FORMAT_MARKER, "$1$2")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS },
            thumbnailAtSeconds = thumbnails.firstOrNull()?.takeIf { it.trusted && !it.original }
                ?.timestamp?.takeIf { it.isFinite() && it >= 0 },
        )
    }

    @Serializable
    private class WireTitle(val title: String, val original: Boolean, val votes: Int, val locked: Boolean) {
        val trusted: Boolean get() = locked || votes >= 0
    }

    /** [timestamp] is `null` for an `original` thumbnail. */
    @Serializable
    private class WireThumbnail(val timestamp: Double? = null, val original: Boolean, val votes: Int, val locked: Boolean) {
        val trusted: Boolean get() = locked || votes >= 0
    }

    private companion object {
        const val API_BASE = "https://sponsor.ajay.app/api/branding"
        const val THUMBNAIL_BASE = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
        const val HTTP_NOT_FOUND = 404

        /** About 1,000 cards' worth: several pages of Home, Search and Subscriptions. */
        const val CACHE_SIZE = 1_000

        /** A bucket's answer is ~18–21 KB (2026-10-06); far above that is not a real one. */
        const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        /** The answer nests 4 deep (videos, a video, its titles, a title). */
        const val MAX_JSON_DEPTH = 8

        /** YouTube allows 100 characters; far longer is no real title, and it is cached for the process's life. */
        const val MAX_TITLE_CHARS = 300
        const val LOG_TAG = "KeBranding"
        val NO_CHOICE = Choice(title = null, thumbnailAtSeconds = null)

        /** DeArrow's "don't auto-format this word" marker: `>` before a word (extension `titleFormatter.ts`). */
        val NO_FORMAT_MARKER = Regex("""(^|\s)>(\S)""")
    }
}
