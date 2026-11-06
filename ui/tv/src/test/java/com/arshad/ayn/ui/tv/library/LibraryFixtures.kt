package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.ui.tv.inTestZone
import java.time.LocalDateTime

/** A history entry watched at [at] (local time in the test zone), [positionMs] into a [durationMs] video. */
internal fun watched(
    id: String,
    title: String,
    channel: String,
    at: LocalDateTime,
    positionMs: Long,
    durationMs: Long,
    watched: Boolean = false,
): WatchEntry {
    val millis = at.inTestZone().toEpochMilliseconds()
    return WatchEntry(
        videoId = VideoId(id),
        title = title,
        uploaderName = channel,
        uploaderUrl = null,
        thumbnailUrl = null,
        positionMs = positionMs,
        durationMs = durationMs,
        watched = watched,
        lastWatchedAtMillis = millis,
        updatedAtMillis = millis,
    )
}

/** The `b3-library` history: two today, one yesterday, one on Thursday, one two weeks back. */
internal val Enigma = watched("JsBZOcqZerk", "History and operating principles of the Enigma", "Veritasium", LocalDateTime.of(2026, 10, 3, 20, 12), 2_091_000, 2_862_000)
internal val PoleLap = watched("q7iVFMM0XQA", "Max Verstappen's Pole Lap", "FORMULA 1", LocalDateTime.of(2026, 10, 3, 19, 40), 125_000, 125_000, watched = true)
internal val Civilizations = watched("QW_jlUn4gA8", "We Imagine Godlike Civilizations the Wrong Way", "Kurzgesagt", LocalDateTime.of(2026, 10, 2, 22, 5), 310_000, 852_000)
internal val Heist = watched("_L1Rim7-IPo", "Most Genius Casino Heist Ever", "Mark Rober", LocalDateTime.of(2026, 10, 1, 21, 2), 5_000, 2_063_000)
internal val Mustang = watched("efdIAw-i380", "Mustang Stallions Duel Across The Nevada Desert", "BBC Earth", LocalDateTime.of(2026, 9, 20, 18, 48), 252_000, 252_000, watched = true)

/** Most recently watched first, as the store lists them. */
internal val RecordedHistory = listOf(Enigma, PoleLap, Civilizations, Heist, Mustang)

/** A saved card ([title] by [channel], [seconds] long). */
internal fun savedVideo(id: String, title: String, channel: String, seconds: Long): VideoItem = VideoItem(
    id = VideoId(id),
    title = title,
    uploaderName = channel,
    uploaderUrl = null,
    durationSeconds = seconds,
    viewCount = -1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = false,
)
