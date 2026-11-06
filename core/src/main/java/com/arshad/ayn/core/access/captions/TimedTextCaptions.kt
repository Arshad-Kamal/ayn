package com.arshad.ayn.core.access.captions

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ttml.TtmlParser
import com.arshad.ayn.core.access.CaptionCue
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.bytesWithin
import com.arshad.ayn.core.access.executeCancellably
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [CaptionSource] over YouTube's timedtext endpoint (M7-D14): the track's TTML, parsed by Media3's
 * TTML parser into text-only cues. Any answer but a readable 200 (YouTube's 429 rate-limit page,
 * an expired link's 403/404) is [CaptionLookup.Failed]. Nothing is kept: the caller holds a
 * track's cues for as long as it needs them. Cancelling a lookup cancels its HTTP call.
 */
class TimedTextCaptions(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CaptionSource {

    override suspend fun cues(track: CaptionTrack): CaptionLookup {
        val url = track.url.toHttpUrlOrNull() ?: return failed("not a link")
        val call = client.newCall(Request.Builder().url(url).build())
        return try {
            call.executeCancellably(io, ::read)
        } catch (e: IOException) {
            failed(e.javaClass.simpleName)
        }
    }

    private fun read(response: Response): CaptionLookup {
        if (!response.isSuccessful) return failed("HTTP ${response.code}")
        val bytes = response.bytesWithin(MAX_RESPONSE_BYTES) ?: return failed("answer too large")
        return parseTtml(bytes)?.let { CaptionLookup.Found(it) } ?: failed("answer unreadable")
    }

    /** Only a bounded, fixed reason: the link is signed and the body may quote it. */
    private fun failed(reason: String): CaptionLookup {
        Log.w(LOG_TAG, "captions lookup failed: $reason")
        return CaptionLookup.Failed
    }

    private companion object {
        /** A manual track was up to 78 KB and an auto TTML track 131 KB (2026-10-06); hours
         *  of auto captions stay well under this. */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        const val LOG_TAG = "KeCaptions"
    }
}

/**
 * [bytes] (a TTML document) as cues in time order, or `null` when it isn't one. The parser cuts
 * the document into consecutive spans between its event times, so cues never overlap; each span
 * becomes a cue of its lines' text joined by `\n` (positions, colours and other styling dropped).
 * Spans with no text, or shorter than a millisecond, are left out.
 */
@OptIn(UnstableApi::class)
private fun parseTtml(bytes: ByteArray): List<CaptionCue>? {
    val cues = mutableListOf<CaptionCue>()
    try {
        TtmlParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { span ->
            val text = span.cues.mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
                .joinToString("\n")
            val startMs = millisOf(span.startTimeUs)
            val endMs = millisOf(span.endTimeUs)
            if (text.isNotEmpty() && endMs > startMs) cues += CaptionCue(startMs, endMs, text)
        }
    } catch (_: RuntimeException) {
        // Untrusted input: the parser wraps an unreadable document in IllegalStateException, and
        // whatever else it throws on a malformed one must not reach the player. Its message may
        // quote the document, so nothing is logged here.
        return null
    }
    return cues.sortedBy { it.startMs }
}

/** The parser reads "00:00:29.119" through a double, which can land a microsecond short. An unset
 *  time (C.TIME_UNSET, far negative) stays negative, so its span is dropped. */
private fun millisOf(micros: Long): Long = Math.floorDiv(micros + 500, 1000L)
