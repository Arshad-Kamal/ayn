package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * [PlaylistStore] over Room for the [session]'s profile; a profile switch re-emits every flow.
 * Ownership checks and writes share one write transaction. A deleted playlist or removed video
 * leaves a removal record for sync (M8-D6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val clock: Clock = Clock.System,
) : PlaylistStore {
    private val dao: PlaylistDao = database.playlistDao()

    override fun playlists(): Flow<List<PlaylistSummary>> =
        session.selectedId.flatMapLatest { profileId -> dao.summaries(profileId).map { rows -> rows.map(PlaylistSummaryRow::toSummary) } }

    override fun videos(playlistId: Long): Flow<List<SavedVideo>> =
        session.selectedId.flatMapLatest { profileId -> dao.items(profileId, playlistId).map { rows -> rows.map(PlaylistItemEntity::toSaved) } }

    override fun playlistVideos(): Flow<List<SavedVideo>> =
        session.selectedId.flatMapLatest { profileId -> dao.playlistItems(profileId).map { rows -> rows.map(PlaylistItemEntity::toSaved) } }

    override fun watchLater(): Flow<List<SavedVideo>> =
        session.selectedId.flatMapLatest { profileId -> dao.watchLaterItems(profileId).map { rows -> rows.map(PlaylistItemEntity::toSaved) } }

    override fun savedIn(videoId: VideoId): Flow<SavedIn> =
        session.selectedId.flatMapLatest { profileId ->
            dao.holding(profileId, videoId.value).map { refs ->
                SavedIn(
                    watchLater = refs.any { it.watchLaterMark != null },
                    playlistIds = refs.filter { it.watchLaterMark == null }.mapTo(HashSet()) { it.id },
                )
            }
        }

    override suspend fun create(name: String): Long? {
        val trimmed = validPlaylistName(name) ?: return null
        val profileId = session.currentId()
        val now = nowMillis()
        return dao.insertPlaylist(
            PlaylistEntity(profileId = profileId, name = trimmed, watchLaterMark = null, createdAtMillis = now, updatedAtMillis = now),
        )
    }

    override suspend fun rename(playlistId: Long, name: String): Boolean {
        val trimmed = validPlaylistName(name) ?: return false
        return dao.rename(session.currentId(), playlistId, trimmed, nowMillis()) > 0
    }

    override suspend fun delete(playlistId: Long): Boolean {
        val profileId = session.currentId()
        return database.withWriteTransaction {
            database.removalDao().playlist(profileId, playlistId, nowMillis())
            dao.delete(profileId, playlistId) > 0
        }
    }

    override suspend fun add(playlistId: Long, video: VideoItem) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (dao.ownsPlaylist(profileId, playlistId)) append(playlistId, video)
        }
    }

    override suspend fun remove(playlistId: Long, videoId: VideoId) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (dao.ownsPlaylist(profileId, playlistId)) removeItem(playlistId, videoId)
        }
    }

    override suspend fun addToWatchLater(video: VideoItem) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            val watchLaterId = dao.watchLaterId(profileId) ?: dao.insertPlaylist(watchLaterEntity(profileId, nowMillis()))
            append(watchLaterId, video)
        }
    }

    override suspend fun removeFromWatchLater(videoId: VideoId) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            dao.watchLaterId(profileId)?.let { removeItem(it, videoId) }
        }
    }

    /** Inside a write transaction. */
    private suspend fun append(playlistId: Long, video: VideoItem) {
        val now = nowMillis()
        val inserted = dao.insertItem(video.toItem(playlistId, dao.nextPosition(playlistId), now))
        if (inserted != NOT_INSERTED) dao.touch(playlistId, now)
    }

    /** Inside a write transaction. */
    private suspend fun removeItem(playlistId: Long, videoId: VideoId) {
        val now = nowMillis()
        database.removalDao().playlistItem(playlistId, videoId.value, now)
        if (dao.deleteItem(playlistId, videoId.value) > 0) dao.touch(playlistId, now)
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()
}

/** Watch later's stored name; screens show their own label. */
private const val WATCH_LATER_NAME = "Watch later"

/** [raw] trimmed, or `null` when that is blank or longer than [MAX_PLAYLIST_NAME]. */
internal fun validPlaylistName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_PLAYLIST_NAME }

private fun watchLaterEntity(profileId: Long, nowMillis: Long): PlaylistEntity = PlaylistEntity(
    profileId = profileId,
    name = WATCH_LATER_NAME,
    watchLaterMark = WATCH_LATER_MARK,
    createdAtMillis = nowMillis,
    updatedAtMillis = nowMillis,
)

private fun VideoItem.toItem(playlistId: Long, position: Long, nowMillis: Long): PlaylistItemEntity = PlaylistItemEntity(
    playlistId = playlistId,
    videoId = id.value,
    position = position,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    durationSeconds = durationSeconds,
    uploadDateMillis = uploadDate?.toEpochMilliseconds(),
    thumbnailUrl = thumbnailUrl,
    addedAtMillis = nowMillis,
    updatedAtMillis = nowMillis,
)

private fun PlaylistItemEntity.toSaved(): SavedVideo = SavedVideo(
    video = VideoItem(
        id = VideoId(videoId),
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        durationSeconds = durationSeconds,
        viewCount = UNKNOWN_VIEW_COUNT,
        textualUploadDate = null,
        uploadDate = uploadDateMillis?.let(Instant::fromEpochMilliseconds),
        thumbnailUrl = thumbnailUrl,
        isLive = false,
    ),
    addedAtMillis = addedAtMillis,
)

/** The seam's unknown-count value (the vendored extractor's -1). */
private const val UNKNOWN_VIEW_COUNT = -1L

private fun PlaylistSummaryRow.toSummary(): PlaylistSummary = PlaylistSummary(
    id = id,
    name = name,
    videoCount = videoCount,
    totalDurationSeconds = totalDurationSeconds,
    coverThumbnailUrl = coverThumbnailUrl,
    updatedAtMillis = updatedAtMillis,
)
