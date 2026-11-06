package com.arshad.ayn.core.access.ryd

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.answer
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Return YouTube Dislike's recorded answers (`resources/ryd/votes-<id>.json`:
 * `GET https://returnyoutubedislikeapi.com/votes?videoId=<id>`, recorded 2026-10-06), for the
 * seam's own tests and the layers above it.
 */
object RecordedVotes {
    /** A video RYD knows: 521,035 dislikes as recorded. */
    val VIDEO: VideoId = VideoId("dQw4w9WgXcQ")
    const val DISLIKES = 521_035L

    /** A well-formed id no video has: answered with zeros. */
    val UNKNOWN: VideoId = VideoId("notInBuaxxu")

    /** The answer recorded for [videoId]. */
    fun body(videoId: VideoId): String =
        checkNotNull(RecordedVotes::class.java.getResourceAsStream("/ryd/votes-${videoId.value}.json")).use {
            it.readBytes().decodeToString()
        }

    /** The real [ReturnYouTubeDislikeVotes], each request answered with its video's recording;
     *  [onRequest] sees each one first. */
    fun source(onRequest: (VideoId) -> Unit = {}): ReturnYouTubeDislikeVotes {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val videoId = VideoId(checkNotNull(chain.request().url.queryParameter("videoId")))
                    onRequest(videoId)
                    answer(200, body(videoId))(chain.request())
                },
            )
            .build()
        return ReturnYouTubeDislikeVotes(client, Dispatchers.Unconfined)
    }
}
