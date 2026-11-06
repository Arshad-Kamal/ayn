package com.arshad.ayn.core.access.ryd

import android.util.Log
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.executeCancellably
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.access.textWithin
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [VoteSource] over Return YouTube Dislike's `votes` endpoint. It has no hash-prefix API, so the
 * request names the video (M7-D2). An unknown video is answered with zeros, so zero views means no
 * data. One request per ask, nothing kept (M7-D5: asked when Details opens, far under RYD's
 * limits). Cancelling a lookup cancels its HTTP call ([executeCancellably]).
 */
class ReturnYouTubeDislikeVotes(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
) : VoteSource {

    override suspend fun votes(videoId: VideoId): VoteLookup {
        val call = client.newCall(Request.Builder().url(url(videoId)).build())
        return try {
            call.executeCancellably(io) { response -> read(response, videoId) }
        } catch (e: IOException) {
            failed(e.javaClass.simpleName)
        }
    }

    private fun url(videoId: VideoId): HttpUrl = apiBase.newBuilder().addQueryParameter("videoId", videoId.value).build()

    private fun read(response: Response, videoId: VideoId): VoteLookup {
        if (response.code == HTTP_NOT_FOUND) return VoteLookup.None
        if (!response.isSuccessful) return failed("HTTP ${response.code}")
        val text = response.textWithin(MAX_RESPONSE_BYTES) ?: return failed("answer too large")
        if (!nestsWithin(text, MAX_JSON_DEPTH)) return failed("answer nested too deep")
        val votes = decodeOrNull(text, WireVotes.serializer()) ?: return failed("answer unreadable")
        return when {
            votes.id != videoId.value -> failed("answer for another video")
            votes.dislikes < 0 || votes.viewCount < 0 -> failed("negative count")
            votes.viewCount == 0L -> VoteLookup.None
            else -> VoteLookup.Found(votes.dislikes)
        }
    }

    /** Only a bounded, fixed reason: the server's text and the decoder's message stay out (KT-JSON-04). */
    private fun failed(reason: String): VoteLookup {
        Log.w(LOG_TAG, "votes lookup failed: $reason")
        return VoteLookup.Failed
    }

    @Serializable
    private class WireVotes(val id: String, val dislikes: Long, val viewCount: Long)

    private companion object {
        const val API_BASE = "https://returnyoutubedislikeapi.com/votes"
        const val HTTP_NOT_FOUND = 404

        /** An answer is ~200 bytes (2026-10-06); far above that is not a real one. */
        const val MAX_RESPONSE_BYTES = 64L * 1024

        /** The answer nests 1 deep (one flat object); 4 leaves headroom. */
        const val MAX_JSON_DEPTH = 4
        const val LOG_TAG = "KeVotes"
    }
}
