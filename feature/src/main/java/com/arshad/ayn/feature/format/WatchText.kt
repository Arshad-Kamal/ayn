package com.arshad.ayn.feature.format

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.WatchMark

/** How much of a video's watch bar is filled: part-way to its place, full once watched, none if never started. */
fun WatchMark?.barFraction(): Float? = when (this) {
    is WatchMark.PartWay -> fraction
    WatchMark.Watched -> 1f
    null -> null
}

/** "34:51" (a place in a video; the start reads "0:00"). */
fun timecode(ms: Long): String = lengthText(ms / 1000) ?: "0:00"

/** What's left of a part-way video ("12:51 left"), else `null`. */
fun leftText(mark: WatchMark?): String? =
    (mark as? WatchMark.PartWay)?.let { "${timecode(it.durationMs - it.positionMs)} left" }

/** A history card on the hub: the channel, then what's left or "Watched" (history keeps no upload date). */
fun historyCardMeta(video: VideoItem, mark: WatchMark?): String? = metaLine(
    video.uploaderName,
    leftText(mark) ?: if (mark == WatchMark.Watched) "Watched" else null,
).ifEmpty { null }
