package com.arshad.ayn.feature.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.WatchEntry

/** A history entry as the store keeps it, watched [lastWatchedAtMillis]; titled unless [title] is null. */
internal fun watchEntry(
    id: String,
    lastWatchedAtMillis: Long = 0,
    positionMs: Long = 0,
    durationMs: Long? = 600_000,
    watched: Boolean = false,
    title: String? = "title $id",
): WatchEntry = WatchEntry(
    videoId = VideoId(id),
    title = title,
    uploaderName = "uploader",
    uploaderUrl = "https://www.youtube.com/channel/UC$id",
    thumbnailUrl = null,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    lastWatchedAtMillis = lastWatchedAtMillis,
    updatedAtMillis = lastWatchedAtMillis,
)
