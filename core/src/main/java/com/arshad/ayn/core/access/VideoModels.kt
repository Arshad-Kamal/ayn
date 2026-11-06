package com.arshad.ayn.core.access

import kotlin.time.Instant

/** Stable identity of a YouTube video (the 11-character watch id). Validated at the seam. */
@JvmInline
value class VideoId(val value: String) {
    init {
        require(isValid(value)) { "VideoId must be 11 chars of [A-Za-z0-9_-]" }
    }

    companion object {
        /** Whether [value] can be a [VideoId]; for input checked before anything is built from it. */
        fun isValid(value: String): Boolean =
            value.length == 11 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }
}

enum class StreamKind { AUDIO, VIDEO, VIDEO_ONLY }

/**
 * One playable stream candidate as seen at the extraction seam (spec D7). SABR candidates
 * carry `sabr://<itag>` content URLs plus the format metadata the SABR mapping needs;
 * the nullable fields mirror the extractor's `ItagItem` and stay `null` when it is absent.
 */
data class StreamCandidate(
    val url: String,
    val kind: StreamKind,
    val mimeType: String?,
    val itag: Int?,
    val resolution: String?,
    val bitrate: Int?,
    val codec: String? = null,
    val fps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val lastModified: Long? = null,
    val xtags: String? = null,
    val audioTrackId: String? = null,
    val audioTrackLocale: String? = null,
    val audioTrackType: String? = null,
    val durationMs: Long? = null,
    val isDrc: Boolean? = null,
)

/** A chapter marker as the uploader set it: its title and where it starts. */
data class Chapter(val title: String, val startSeconds: Long)

/**
 * One storyboard level for the scrub preview: sprite-sheet pages ([pageUrls], in playback order)
 * of up to [framesPerPageX]×[framesPerPageY] frames, read row by row. Each frame is
 * [frameWidth]×[frameHeight] px and covers [frameDurationMs] of the video; [frameCount] frames
 * in all. A page can be a few pixels narrower than the grid implies (NewPipe#11596): clamp a
 * frame to the decoded page.
 */
data class Storyboard(
    val pageUrls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameCount: Int,
    val frameDurationMs: Int,
    val framesPerPageX: Int,
    val framesPerPageY: Int,
)

data class VideoMetadata(
    val id: VideoId,
    val title: String,
    val uploaderName: String?,
    /** Channel URL: the key the seam's channel methods take. */
    val uploaderUrl: String? = null,
    val uploaderAvatarUrl: String? = null,
    /** The channel's subscriber count as the page rounds it ("21.3m" = 21,300,000); `null` when
     *  hidden or unreadable. */
    val uploaderSubscriberCount: Long? = null,
    val durationSeconds: Long,
    /** D11: an active live stream (its [durationSeconds] is 0); a post-live replay is not. */
    val isLive: Boolean = false,
    /** When the page's live broadcast started; `null` for a page that names none (never live, or
     *  unreadable). An ended stream or a premiere keeps its start, so read it with [isLive]. */
    val liveStartedAt: Instant? = null,
    /** The largest picture YouTube offers for the page (listing thumbnails are smaller). */
    val thumbnailUrl: String? = null,
    /** YouTube's like count; `null` when the video hides it or it can't be read (M7-D5). */
    val likeCount: Long? = null,
    /** Plain text, line breaks kept; `null` when the video has none. */
    val description: String? = null,
    /** In start order; empty when the video has no chapters. */
    val chapters: List<Chapter> = emptyList(),
    /** The storyboard levels YouTube offers, smallest frames first; empty when it offers none. */
    val storyboards: List<Storyboard> = emptyList(),
    /** YouTube's related videos for the page, in its order; empty when it offers none or they
     *  can't be read. Never Shorts (charter D5), playlists or mixes. A non-live entry always has
     *  a known length (upcoming premieres are dropped); a live entry's length is -1. */
    val relatedVideos: List<VideoItem> = emptyList(),
    /** The video's caption tracks in YouTube's order; empty when it has none or they can't be read. */
    val captionTracks: List<CaptionTrack> = emptyList(),
    /** The page's live chat or its replay (LIVE-D6); `null` when it has none, it is turned off, or
     *  it can't be read. */
    val liveChat: LiveChatSeed? = null,
    val hlsUrl: String?,
    val dashUrl: String?,
    val streams: List<StreamCandidate>,
    /** SABR endpoint + ustreamer config; both present when the server offers SABR playback. */
    val serverAbrStreamingUrl: String? = null,
    val ustreamerConfig: String? = null,
    /** The web client the streams came from when VISIONOS refused the video (made for kids, spec 12
     *  KP-D5); `null` for VISIONOS's own answer. */
    val webStreamingClient: WebStreamingClient? = null,
)

/**
 * The InnerTube client a player answer came from, as its SABR session must present itself: the
 * client's identity and the web PO token (base64url) its player request carried. The session sends
 * that same token (spec 12 KP-D31). [toString] leaves the token out.
 */
class WebStreamingClient(
    val clientId: Int,
    val clientVersion: String,
    val deviceMake: String,
    val deviceModel: String,
    val osName: String,
    val osVersion: String,
    val userAgent: String,
    val poToken: String,
) {
    override fun toString(): String = "WebStreamingClient(clientId=$clientId, clientVersion=$clientVersion)"
}
