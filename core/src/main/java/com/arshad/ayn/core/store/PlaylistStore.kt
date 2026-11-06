package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.flow.Flow

/**
 * Local playlists and Watch later (spec D4, M5-D13) for the current profile ([ProfileSession]).
 * A playlist id that isn't the current profile's reads empty and writes nothing.
 */
interface PlaylistStore {
    /** The profile's own playlists, the most recently updated first. */
    fun playlists(): Flow<List<PlaylistSummary>>

    /** A playlist's videos in order. */
    fun videos(playlistId: Long): Flow<List<SavedVideo>>

    /** Every video of the profile's own playlists (Watch later aside) in one read: playlist by
     *  playlist as [playlists] lists them, each in order; a video in two playlists comes twice. */
    fun playlistVideos(): Flow<List<SavedVideo>>

    /** Watch later's videos in order. */
    fun watchLater(): Flow<List<SavedVideo>>

    /** Where [videoId] is saved (the "Save to playlist" switches). */
    fun savedIn(videoId: VideoId): Flow<SavedIn>

    /** A new, empty playlist; `null` when [name] is blank after trimming or longer than
     *  [MAX_PLAYLIST_NAME] characters. */
    suspend fun create(name: String): Long?

    /** `false` when [name] is invalid (as for [create]) or the playlist isn't one of the
     *  profile's own. */
    suspend fun rename(playlistId: Long, name: String): Boolean

    /** Deletes one of the profile's own playlists with its videos; `false` when it isn't one. */
    suspend fun delete(playlistId: Long): Boolean

    /** Saves [video] at the end of the playlist; a video already there keeps its place. */
    suspend fun add(playlistId: Long, video: VideoItem)

    suspend fun remove(playlistId: Long, videoId: VideoId)

    /** Saves [video] at the end of Watch later; a video already there keeps its place. */
    suspend fun addToWatchLater(video: VideoItem)

    suspend fun removeFromWatchLater(videoId: VideoId)
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val videoCount: Int,
    /** Lengths YouTube didn't give count as 0. */
    val totalDurationSeconds: Long,
    /** The first video's thumbnail; `null` for an empty playlist. */
    val coverThumbnailUrl: String?,
    /** The last save, removal or rename. */
    val updatedAtMillis: Long,
)

/**
 * A saved video as its card was when saved: views, the upload date's display text and the live
 * marker aren't kept ([VideoItem.viewCount] -1, [VideoItem.textualUploadDate] null,
 * [VideoItem.isLive] false: a stream live when saved is usually a replay by the time it's
 * watched, and only its page can tell; M5.9 has the player read that, as for RSS feed cards).
 */
data class SavedVideo(val video: VideoItem, val addedAtMillis: Long)

data class SavedIn(val watchLater: Boolean, val playlistIds: Set<Long>)

const val MAX_PLAYLIST_NAME = 150
