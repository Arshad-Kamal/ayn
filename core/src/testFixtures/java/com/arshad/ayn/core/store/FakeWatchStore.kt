package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [WatchStore] for the layers above the store: resume points the test sets in
 * [resumePointsMs] (read by [resumePointMs], which falls back to [entries] as the store would);
 * history entries the test sets in [entries] (most
 * recently watched first; the list flows read them with the store's rules, and [remove]/[clear]
 * edit them); opened cards (and, in [openedAsShort], those opened as a Short) and position saves
 * recorded in call order. While [resumeGate] is set,
 * [resumePointMs] waits for it, so a test can hold the player's request; [failReads]/[failWrites]
 * make those calls throw (the list flows included). [readOn] records the dispatcher each list
 * flow is collected on.
 */
class FakeWatchStore(vararg resumePointsMs: Pair<VideoId, Long>) : WatchStore {
    val resumePointsMs: MutableMap<VideoId, Long> = mutableMapOf(*resumePointsMs)
    val opened: MutableList<VideoItem> = mutableListOf()
    val openedAsShort: MutableList<VideoId> = mutableListOf()
    val saves: MutableList<PositionSave> = mutableListOf()
    val entries: MutableStateFlow<List<WatchEntry>> = MutableStateFlow(emptyList())
    var resumeGate: CompletableDeferred<Unit>? = null
    var failReads: Boolean = false
    var failWrites: Boolean = false
    val readOn: MutableList<ContinuationInterceptor?> = mutableListOf()

    override fun history(): Flow<List<WatchEntry>> = read { it }

    override fun resumePoints(): Flow<List<WatchEntry>> = read { list -> list.filter { WatchThresholds.hasResumePoint(it.positionMs, it.durationMs) } }

    override fun watchedIds(): Flow<Set<VideoId>> = read { list -> list.filter { it.watched }.mapTo(HashSet()) { it.videoId } }

    override suspend fun resumePointMs(videoId: VideoId): Long? {
        resumeGate?.await()
        if (failReads) throw IllegalStateException("database closed")
        return resumePointsMs[videoId]
            ?: entries.value.firstOrNull { it.videoId == videoId && WatchThresholds.hasResumePoint(it.positionMs, it.durationMs) }?.positionMs
    }

    override suspend fun recordOpened(video: VideoItem, isShort: Boolean) {
        if (failWrites) throw IllegalStateException("disk full")
        opened += video
        if (isShort) openedAsShort += video.id
    }

    override suspend fun recordPosition(save: PositionSave) {
        if (failWrites) throw IllegalStateException("disk full")
        saves += save
    }

    override suspend fun remove(videoId: VideoId) {
        if (failWrites) throw IllegalStateException("disk full")
        resumePointsMs.remove(videoId)
        entries.value = entries.value.filterNot { it.videoId == videoId }
    }

    override suspend fun clear() {
        if (failWrites) throw IllegalStateException("disk full")
        resumePointsMs.clear()
        entries.value = emptyList()
    }

    private fun <T> read(transform: (List<WatchEntry>) -> T): Flow<T> = flow {
        readOn += currentCoroutineContext()[ContinuationInterceptor]
        if (failReads) throw IllegalStateException("database closed")
        emitAll(entries.map(transform))
    }
}
