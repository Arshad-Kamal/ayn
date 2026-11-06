package com.arshad.ayn.core.access.sponsorblock

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.answer
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * SponsorBlock's recorded answer for bucket `5f6b` (`resources/sponsorblock/skipSegments-5f6b.json`:
 * `GET /api/skipSegments/5f6b` with all nine categories and `actionTypes=["skip"]`, recorded
 * 2026-10-06, 132 videos), for the layers above the seam.
 */
object RecordedSegments {
    /** The recorded video the tests use. Its segments, as recorded: preview 0–51.970 s, intro
     *  51.970–58.394, sponsor 56.208–134.034, interaction 681.274–687.665, outro 687.665–697.601. */
    val VIDEO: VideoId = VideoId("q4FQa7N4kBg")

    /** The answer's text. */
    fun body(): String =
        checkNotNull(RecordedSegments::class.java.getResourceAsStream("/sponsorblock/skipSegments-5f6b.json")).use {
            it.readBytes().decodeToString()
        }

    /** [VIDEO]'s lookup in [categories] through the real [SponsorBlockSegments], served the recorded answer. */
    suspend fun lookup(categories: Set<SegmentCategory> = SegmentCategory.entries.toSet()): SegmentLookup {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> answer(200, body())(chain.request()) })
            .build()
        return SponsorBlockSegments(client, Dispatchers.Unconfined).segments(VIDEO, categories)
    }
}
