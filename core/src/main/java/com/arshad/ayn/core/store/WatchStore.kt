package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.flow.Flow

/**
 * Watch state seam (spec D4, M5-D7/D8): history, resume points and watched videos, for the
 * current profile ([ProfileSession]) except [recordPosition], which names its own. Two writers
 * share a row: the player records the card once the video starts playing ([recordOpened]) and the
 * playback service records where playback is ([recordPosition]); neither overwrites the other's
 * columns.
 */
interface WatchStore {
    /** Every video that started playing, the most recently watched first. */
    fun history(): Flow<List<WatchEntry>>

    /** Videos left part-way ([WatchThresholds]), the most recently watched first. */
    fun resumePoints(): Flow<List<WatchEntry>>

    /** Videos played to the end or to [WatchThresholds.WATCHED_PERCENT] of their length. */
    fun watchedIds(): Flow<Set<VideoId>>

    /** Where [videoId] would resume, or `null` when it has no resume point. */
    suspend fun resumePointMs(videoId: VideoId): Long?

    /** [video] started playing: its card's title, channel and thumbnail; a card without a
     *  channel keeps the one already stored. [isShort]: it played as a Short, which marks the entry
     *  a Short for good (PAF-D6). */
    suspend fun recordOpened(video: VideoItem, isShort: Boolean)

    /** Where playback of a video is, under the profile it was set for. */
    suspend fun recordPosition(save: PositionSave)

    /** Removes [videoId] from the current profile's history; a kids profile's history is the
     *  owner's to review, so it changes nothing there (KP-D11). */
    suspend fun remove(videoId: VideoId)

    /** Clears the current profile's history, resume points and watched marks, and its kept
     *  related lists ([RelatedListStore], FY-D4); changes nothing on a kids profile (KP-D11). */
    suspend fun clear()
}

/**
 * A video in a profile's watch state. [title], channel and [thumbnailUrl] are null until the
 * player screen records the card; [durationMs] until the player knows the stream's length.
 */
data class WatchEntry(
    val videoId: VideoId,
    val title: String?,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    /** The last place playback reached, resume point or not. */
    val positionMs: Long,
    val durationMs: Long?,
    val watched: Boolean,
    val lastWatchedAtMillis: Long,
    /** Written on every change; the D4 LWW key (ARCH-7). */
    val updatedAtMillis: Long,
    /** Played as a Short at least once since PAF-D6 (a Short played in the 16:9 player isn't known
     *  as one); older entries are not. */
    val isShort: Boolean = false,
)

/**
 * Where a playing video is. [profileId] is the profile the video was set for (M5-D8), never the
 * session's current one: a profile switch while it plays must not move its writes. [durationMs]
 * is null while the stream's length is unknown (the stored one stays).
 */
data class PositionSave(
    val profileId: Long,
    val videoId: VideoId,
    val positionMs: Long,
    val durationMs: Long?,
    val ended: Boolean,
)

/**
 * M5-D7: a resume point is a place at least [MIN_RESUME_MS] in and short of [WATCHED_PERCENT]
 * of the length; from there, or at the end, a video counts as watched (and stays so).
 * YouTube videos run short, so KE starts resuming earlier than Kodi's 180 s or Jellyfin's 300 s.
 */
object WatchThresholds {
    const val MIN_RESUME_MS = 30_000L
    const val WATCHED_PERCENT = 90

    fun isWatched(positionMs: Long, durationMs: Long?, ended: Boolean): Boolean =
        ended || (durationMs != null && durationMs > 0 && positionMs * 100 >= durationMs * WATCHED_PERCENT)

    /** The resume point rule in Kotlin; `WatchDao`'s SQL states the same (a change to one
     *  changes the other; `WatchRepositoryTest` holds them together). */
    fun hasResumePoint(positionMs: Long, durationMs: Long?): Boolean =
        positionMs >= MIN_RESUME_MS && durationMs != null && durationMs > 0 && !isWatched(positionMs, durationMs, ended = false)
}
