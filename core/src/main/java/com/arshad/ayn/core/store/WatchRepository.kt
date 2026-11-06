package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * [WatchStore] over Room for the [session]'s profile; a profile switch re-emits every flow.
 * Each write is a skeleton insert-or-ignore plus one column-scoped update in one transaction,
 * so the two writers never undo each other (M5-D8). A removed entry leaves a removal record for
 * sync (M8-D6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val clock: Clock = Clock.System,
) : WatchStore {
    private val dao: WatchDao = database.watchDao()

    override fun history(): Flow<List<WatchEntry>> =
        session.selectedId.flatMapLatest { profileId -> dao.history(profileId).map { rows -> rows.map(WatchEntryEntity::toEntry) } }

    override fun resumePoints(): Flow<List<WatchEntry>> =
        session.selectedId.flatMapLatest { profileId -> dao.resumePoints(profileId).map { rows -> rows.map(WatchEntryEntity::toEntry) } }

    override fun watchedIds(): Flow<Set<VideoId>> =
        session.selectedId.flatMapLatest { profileId -> dao.watchedIds(profileId).map { ids -> ids.mapTo(HashSet(), ::VideoId) } }

    override suspend fun resumePointMs(videoId: VideoId): Long? = dao.resumePointMs(session.currentId(), videoId.value)

    override suspend fun recordOpened(video: VideoItem, isShort: Boolean) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            val now = clock.now().toEpochMilliseconds()
            dao.insertIfAbsent(skeleton(profileId, video.id, now))
            dao.updateDisplay(profileId, video.id.value, video.title, video.uploaderName, video.uploaderUrl, video.thumbnailUrl, isShort, now)
        }
    }

    override suspend fun recordPosition(save: PositionSave) {
        database.withWriteTransaction {
            val now = clock.now().toEpochMilliseconds()
            dao.insertIfAbsent(skeleton(save.profileId, save.videoId, now))
            dao.updatePosition(
                profileId = save.profileId,
                videoId = save.videoId.value,
                positionMs = save.positionMs,
                durationMs = save.durationMs,
                watched = WatchThresholds.isWatched(save.positionMs, save.durationMs, save.ended),
                nowMillis = now,
            )
        }
    }

    override suspend fun remove(videoId: VideoId) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (!database.profileDao().isKids(profileId)) {
                database.removalDao().watchEntry(profileId, videoId.value, clock.now().toEpochMilliseconds())
                dao.delete(profileId, videoId.value)
            }
        }
    }

    override suspend fun clear() {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (!database.profileDao().isKids(profileId)) {
                database.removalDao().watchHistory(profileId, clock.now().toEpochMilliseconds())
                dao.clear(profileId)
                database.relatedListDao().clear(profileId)
            }
        }
    }
}

private fun skeleton(profileId: Long, videoId: VideoId, nowMillis: Long): WatchEntryEntity =
    WatchEntryEntity(profileId = profileId, videoId = videoId.value, lastWatchedAtMillis = nowMillis, updatedAtMillis = nowMillis)

private fun WatchEntryEntity.toEntry(): WatchEntry = WatchEntry(
    videoId = VideoId(videoId),
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    lastWatchedAtMillis = lastWatchedAtMillis,
    updatedAtMillis = updatedAtMillis,
    isShort = isShort,
)
