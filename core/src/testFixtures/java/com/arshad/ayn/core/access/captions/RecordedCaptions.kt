package com.arshad.ayn.core.access.captions

import com.arshad.ayn.core.access.CaptionCue
import com.arshad.ayn.core.access.answer
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube's recorded TTML for the fixed video's two English tracks (`resources/captions/`:
 * `GET https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&…&fmt=ttml`, recorded 2026-10-08 from a
 * fresh resolve; the link itself expires, so the recorded video page's links are only matched by
 * their `lang` and `kind`). The goldens are read from the raw XML.
 */
object RecordedCaptions {
    /** The manual track: 61 `<p>`s, none overlapping, so one cue each. */
    const val MANUAL_CUE_COUNT = 61
    val MANUAL_FIRST_CUES = listOf(
        CaptionCue(1_360, 3_040, "[♪♪♪]"),
        CaptionCue(18_640, 21_880, "♪ We're no strangers to love ♪"),
        CaptionCue(22_640, 26_960, "♪ You know the rules\nand so do I ♪"),
    )
    val MANUAL_LAST_CUE = CaptionCue(207_920, 211_320, "♪ Never gonna tell a lie\nand hurt you ♪")

    /** The auto-generated track rolls up: each `<p>` overlaps the next, so a cue holds two lines. */
    val AUTO_FIRST_CUES = listOf(
        CaptionCue(320, 14_580, "[Music]"),
        CaptionCue(18_800, 21_800, "We're no strangers to"),
        CaptionCue(21_800, 25_960, "We're no strangers to\nlove. You know the rules and so do"),
        CaptionCue(25_960, 29_119, "love. You know the rules and so do\nI. I feel commitments from what I'm"),
    )

    fun manualTtml(): String = resource("dQw4w9WgXcQ-en.ttml")

    fun autoTtml(): String = resource("dQw4w9WgXcQ-a.en.ttml")

    /** The real [TimedTextCaptions], an English track answered with its recording (auto when the
     *  link says `kind=asr`), any other language with YouTube's 404; [onRequest] sees each one first. */
    fun source(onRequest: (Request) -> Unit = {}): TimedTextCaptions {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    onRequest(request)
                    val url = request.url
                    val respond = when {
                        url.queryParameter("lang") != "en" -> answer(404, "")
                        url.queryParameter("kind") == "asr" -> answer(200, autoTtml())
                        else -> answer(200, manualTtml())
                    }
                    respond(request)
                },
            )
            .build()
        return TimedTextCaptions(client, Dispatchers.Unconfined)
    }

    private fun resource(name: String): String =
        checkNotNull(RecordedCaptions::class.java.getResourceAsStream("/captions/$name")).use {
            it.readBytes().decodeToString()
        }
}
