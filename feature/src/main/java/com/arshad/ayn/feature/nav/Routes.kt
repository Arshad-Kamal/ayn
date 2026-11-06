package com.arshad.ayn.feature.nav

import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.PlayAll
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Top-level navigation routes (spec D7): Nav 2 typed routes — every route is a
 * @Serializable object (parameterless destinations) or data class (argumented routes) so
 * destinations are compile-time checked, never stringly-typed.
 */
@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object SubscriptionsRoute

@Serializable
data object LibraryRoute

/** The Library's All history page (`b3-library` history). */
@Serializable
data object HistoryRoute

/** One of the profile's own playlists (`b3-library` playlist), by id. */
@Serializable
data class SavedPlaylistRoute(val playlistId: Long)

@Serializable
data object SettingsRoute

/** A kids profile's Parent page (`kp-kids` parent), behind the owner's lock (KP-D11). */
@Serializable
data object ParentRoute

/**
 * A video card → the player. Carries the card, which dresses the screen while the stream starts
 * (a live card lands on the "Live streams aren't supported yet" slate, D11), for Play all the
 * list it plays through (a YouTube playlist by [playlistUrl], a saved one by [savedPlaylistId]),
 * whether the video plays from the start past its resume point, and whether it is a Short (the
 * Shorts player; its list is handed over apart, M7-D13).
 */
@Serializable
data class PlayerRoute(
    val videoId: String,
    val isLive: Boolean,
    val title: String,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    val textualUploadDate: String?,
    val uploadDateEpochMs: Long?,
    val thumbnailUrl: String?,
    val playlistUrl: String? = null,
    val savedPlaylistId: Long? = null,
    val playlistTitle: String? = null,
    val playlistVideoCount: Long = -1,
    val shuffleSeed: Long? = null,
    val startOver: Boolean = false,
    val short: Boolean = false,
) {
    constructor(video: VideoItem, playAll: PlayAll? = null, startOver: Boolean = false, short: Boolean = false) : this(
        videoId = video.id.value,
        isLive = video.isLive,
        title = video.title,
        uploaderName = video.uploaderName,
        uploaderUrl = video.uploaderUrl,
        durationSeconds = video.durationSeconds,
        viewCount = video.viewCount,
        textualUploadDate = video.textualUploadDate,
        uploadDateEpochMs = video.uploadDate?.toEpochMilliseconds(),
        thumbnailUrl = video.thumbnailUrl,
        playlistUrl = (playAll as? PlayAll.YouTube)?.playlist?.url,
        savedPlaylistId = (playAll as? PlayAll.Saved)?.playlistId,
        playlistTitle = playAll?.title,
        playlistVideoCount = playAll?.videoCount ?: -1,
        shuffleSeed = (playAll as? PlayAll.Saved)?.shuffleSeed,
        startOver = startOver,
        short = short,
    )

    fun video(): VideoItem = VideoItem(
        id = VideoId(videoId),
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        durationSeconds = durationSeconds,
        viewCount = viewCount,
        textualUploadDate = textualUploadDate,
        uploadDate = uploadDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
        thumbnailUrl = thumbnailUrl,
        isLive = isLive,
    )

    /** The list Play all goes through (only what the player needs of a YouTube playlist's card). */
    fun playAll(): PlayAll? = when {
        playlistUrl != null -> PlayAll.YouTube(
            PlaylistItem(title = playlistTitle.orEmpty(), url = playlistUrl, thumbnailUrl = null, videoCount = playlistVideoCount, uploaderName = null),
        )
        savedPlaylistId != null -> PlayAll.Saved(savedPlaylistId, playlistTitle.orEmpty(), playlistVideoCount, shuffleSeed)
        else -> null
    }
}

/** A video card's uploader → the channel page. */
@Serializable
data class ChannelRoute(val channelUrl: String)

/** A channel tab's playlist card → its playlist page (ui-rebuild B-D10). Carries the card, which
 *  shows the header while the playlist loads. */
@Serializable
data class PlaylistRoute(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val videoCount: Long,
    val uploaderName: String?,
) {
    constructor(card: PlaylistItem) : this(
        url = card.url,
        title = card.title,
        thumbnailUrl = card.thumbnailUrl,
        videoCount = card.videoCount,
        uploaderName = card.uploaderName,
    )

    fun card(): PlaylistItem = PlaylistItem(
        title = title,
        url = url,
        thumbnailUrl = thumbnailUrl,
        videoCount = videoCount,
        uploaderName = uploaderName,
    )
}
