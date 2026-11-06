// ADDED (YouTube KE, 2026-09-27) — part of the vendoring delta (THIRD-PARTY.md entry 2).
// Minimal stand-ins for LibreTube's `api.obj.PipedStream` / `api.obj.Streams`, carrying exactly
// the fields this module consumes. Field names and nullability mirror upstream so the vendored
// manifest code stays diff-identical at rebase. Extend deliberately.
package com.github.libretube.player.manifest

import androidx.media3.common.util.UnstableApi

/** Stream format metadata consumed by [Representation] / [SabrManifest]. */
data class SabrFormat(
    val itag: Int?,
    val lastModified: Long?,
    val xtags: String?,
    val mimeType: String,
    val codec: String?,
    val bitrate: Int?,
    val fps: Int?,
    val width: Int?,
    val height: Int?,
    val audioTrackId: String?,
    val audioTrackLocale: String?,
    val audioTrackType: String?,
    val durationMs: Long?,
    val isDrc: Boolean?,
)

/** Extraction result bundle consumed by [SabrManifest]'s grouping constructor. */
data class SabrStreamSet(
    val serverAbrStreamingUrl: String?,
    val videoPlaybackUstreamerConfig: String?,
    /** Presentation duration in seconds (mirrors upstream `Streams.duration`). */
    val duration: Long,
    val videoStreams: List<SabrFormat>,
    val audioStreams: List<SabrFormat>,
)

/**
 * Build the [SabrManifest] for [videoId] from this set (YouTube KE, 2026-09-27; the grouping
 * constructor is module-internal, so the app-side seam surface enters here — THIRD-PARTY.md
 * entry 2). The endpoint fields are construction preconditions the vendored constructor asserts
 * on; they fail here with a named message instead.
 */
@UnstableApi
fun SabrStreamSet.toSabrManifest(videoId: String): SabrManifest {
    require(serverAbrStreamingUrl != null && videoPlaybackUstreamerConfig != null) {
        "no SABR playback data for $videoId"
    }
    return SabrManifest(videoId, this)
}
