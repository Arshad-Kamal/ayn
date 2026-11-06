package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [PlaylistStore] for the layers above the store, one profile's worth: the repository's
 * rules on plain lists (names trimmed and capped, a video once per playlist and appended, a save,
 * removal or rename stamps the playlist; most recently updated first; ids never reused; a saved
 * card comes back without views, upload text or live marker, as [SavedVideo] says). Time is a
 * counter, so the last write is the most recent. [failReads] makes every read flow throw;
 * [failWrites] every write, [failAdds] only saves into a playlist.
 */
class FakePlaylistStore : PlaylistStore {
    private data class Saved(val id: Long, val name: String, val videos: List<SavedVideo>, val updatedAtMillis: Long)

    private val playlists = MutableStateFlow<List<Saved>>(emptyList())
    private val later = MutableStateFlow<List<SavedVideo>>(emptyList())
    private var lastId = 0L
    private var clock = 0L
    var failReads: Boolean = false
    var failWrites: Boolean = false
    var failAdds: Boolean = false

    /** A playlist holding [videos] in order, as if saved one by one. */
    fun seed(name: String, vararg videos: VideoItem): Long {
        val id = ++lastId
        val now = ++clock
        playlists.value += Saved(id, name, videos.map { SavedVideo(it.asSaved(), now) }, now)
        return id
    }

    override fun playlists(): Flow<List<PlaylistSummary>> = read(playlists) { rows ->
        rows.sortedByDescending { it.updatedAtMillis }.map { it.summary() }
    }

    override fun videos(playlistId: Long): Flow<List<SavedVideo>> = read(playlists) { rows ->
        rows.firstOrNull { it.id == playlistId }?.videos.orEmpty()
    }

    override fun playlistVideos(): Flow<List<SavedVideo>> = read(playlists) { rows ->
        rows.sortedByDescending { it.updatedAtMillis }.flatMap { it.videos }
    }

    override fun watchLater(): Flow<List<SavedVideo>> = read(later) { it }

    override fun savedIn(videoId: VideoId): Flow<SavedIn> = flow {
        if (failReads) throw IllegalStateException("database closed")
        emitAll(
            combine(playlists, later) { rows, laterVideos ->
                SavedIn(
                    watchLater = laterVideos.any { it.video.id == videoId },
                    playlistIds = rows.filter { row -> row.videos.any { it.video.id == videoId } }.mapTo(HashSet()) { it.id },
                )
            },
        )
    }

    override suspend fun create(name: String): Long? {
        checkWrite()
        val trimmed = validPlaylistName(name) ?: return null
        val id = ++lastId
        playlists.value += Saved(id, trimmed, emptyList(), ++clock)
        return id
    }

    override suspend fun rename(playlistId: Long, name: String): Boolean {
        checkWrite()
        val trimmed = validPlaylistName(name) ?: return false
        return edit(playlistId) { saved, _ -> saved.copy(name = trimmed) }
    }

    override suspend fun delete(playlistId: Long): Boolean {
        checkWrite()
        val before = playlists.value
        playlists.value = before.filterNot { it.id == playlistId }
        return playlists.value.size < before.size
    }

    override suspend fun add(playlistId: Long, video: VideoItem) {
        checkWrite()
        if (failAdds) throw IllegalStateException("disk full")
        val holds = playlists.value.firstOrNull { it.id == playlistId }?.videos?.any { it.video.id == video.id } ?: return
        if (!holds) edit(playlistId) { saved, now -> saved.copy(videos = saved.videos + SavedVideo(video.asSaved(), now)) }
    }

    override suspend fun remove(playlistId: Long, videoId: VideoId) {
        checkWrite()
        val holds = playlists.value.firstOrNull { it.id == playlistId }?.videos?.any { it.video.id == videoId } ?: return
        if (holds) edit(playlistId) { saved, _ -> saved.copy(videos = saved.videos.filterNot { it.video.id == videoId }) }
    }

    override suspend fun addToWatchLater(video: VideoItem) {
        checkWrite()
        if (later.value.none { it.video.id == video.id }) later.value += SavedVideo(video.asSaved(), ++clock)
    }

    override suspend fun removeFromWatchLater(videoId: VideoId) {
        checkWrite()
        later.value = later.value.filterNot { it.video.id == videoId }
    }

    private fun <T, R> read(source: MutableStateFlow<T>, transform: (T) -> R): Flow<R> = flow {
        if (failReads) throw IllegalStateException("database closed")
        emitAll(source.map(transform))
    }

    /** Applies [change] at the next time, which also stamps the playlist. */
    private fun edit(playlistId: Long, change: (Saved, now: Long) -> Saved): Boolean {
        val found = playlists.value.any { it.id == playlistId }
        if (found) {
            val now = ++clock
            playlists.value = playlists.value.map { if (it.id == playlistId) change(it, now).copy(updatedAtMillis = now) else it }
        }
        return found
    }

    private fun checkWrite() {
        if (failWrites) throw IllegalStateException("disk full")
    }

    private fun VideoItem.asSaved(): VideoItem = copy(viewCount = -1, textualUploadDate = null, isLive = false)

    private fun Saved.summary(): PlaylistSummary = PlaylistSummary(
        id = id,
        name = name,
        videoCount = videos.size,
        totalDurationSeconds = videos.sumOf { it.video.durationSeconds.coerceAtLeast(0) },
        coverThumbnailUrl = videos.firstOrNull()?.video?.thumbnailUrl,
        updatedAtMillis = updatedAtMillis,
    )
}
