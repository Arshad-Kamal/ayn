package com.arshad.ayn.core.access

/**
 * A live page's playable (LIVE-D1/D2): the HLS manifest VISIONOS's player answer names, which the
 * player's HLS source opens, and the TV's quality cap its variants stop at (LIVE-D5). Built only by
 * [livePlayable], so its URL has passed the host check.
 */
class LivePlayable internal constructor(val hlsUrl: String, val maxShortSide: Int)

/** [metadata]'s live playable capped at [maxShortSide] lines, or null when the page names no HLS
 *  manifest on a YouTube serving host. */
fun livePlayable(metadata: VideoMetadata, maxShortSide: Int): LivePlayable? =
    metadata.hlsUrl?.takeIf(::isYouTubeStreamingUrl)?.let { LivePlayable(it, maxShortSide) }
