package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.WatchThresholds
import kotlin.time.Instant

/** How far a profile got into a video: the progress bar under its card. */
sealed interface WatchMark {
    /** Left part-way, at a resume point ("34:51 of 47:42", "12:51 left"). */
    data class PartWay(val positionMs: Long, val durationMs: Long) : WatchMark {
        val fraction: Float get() = positionMs.toFloat() / durationMs
    }

    /** Played to the end, or near enough to count (M5-D7). */
    data object Watched : WatchMark
}

/** A resume point wins over an earlier watch to the end: the video is part-way through again. */
fun WatchEntry.mark(): WatchMark? {
    val length = durationMs
    return when {
        length != null && WatchThresholds.hasResumePoint(positionMs, length) -> WatchMark.PartWay(positionMs, length)
        watched -> WatchMark.Watched
        else -> null
    }
}

/** A video in history: its card as recorded when it started playing. */
data class HistoryVideo(val video: VideoItem, val mark: WatchMark?, val lastWatchedAt: Instant)

/**
 * The entries the Library can show, most recently watched first. An entry whose card the player
 * never recorded (a failed write) has no title to show and is left out.
 */
fun List<WatchEntry>.historyVideos(): List<HistoryVideo> = mapNotNull { entry ->
    val title = entry.title ?: return@mapNotNull null
    HistoryVideo(
        video = VideoItem(
            id = entry.videoId,
            title = title,
            uploaderName = entry.uploaderName,
            uploaderUrl = entry.uploaderUrl,
            durationSeconds = entry.durationMs?.let { it / 1000 } ?: UNKNOWN,
            viewCount = UNKNOWN,
            textualUploadDate = null,
            uploadDate = null,
            thumbnailUrl = entry.thumbnailUrl,
            isLive = false,
        ),
        mark = entry.mark(),
        lastWatchedAt = Instant.fromEpochMilliseconds(entry.lastWatchedAtMillis),
    )
}

/** Every video's mark, for cards outside History (Watch later, saved playlists). */
fun List<WatchEntry>.marks(): Map<VideoId, WatchMark> = buildMap {
    for (entry in this@marks) entry.mark()?.let { put(entry.videoId, it) }
}

/** The vendored extractor's unknown-value convention, which [VideoItem] follows. */
private const val UNKNOWN = -1L
