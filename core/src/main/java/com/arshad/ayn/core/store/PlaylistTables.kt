package com.arshad.ayn.core.store

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * A profile's playlist (M5-D13); goes with its profile. [watchLaterMark] is [WATCH_LATER_MARK]
 * for the profile's Watch later and NULL for the playlists it named; the unique index makes a
 * second Watch later fail the write (SQLite UNIQUE admits many NULLs). [syncId] names the playlist
 * across devices within its profile (M8-D12).
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["profileId", "watchLaterMark"], unique = true),
        Index(value = ["profileId", "syncId"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val watchLaterMark: Int?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Added in v12, which gave every row its own; new rows get one here. The column's `''`
     *  default only serves the migration's `ADD COLUMN`. */
    @ColumnInfo(defaultValue = "''") val syncId: String = if (watchLaterMark != null) WATCH_LATER_SYNC_ID else newSyncId(),
)

/**
 * A video saved to a playlist, at most once per playlist; goes with its playlist. Keeps the
 * card's fields so the video opens and shows without a request.
 */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    /** Order in the playlist: a new item goes after every other. */
    val position: Long,
    val title: String,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val uploadDateMillis: Long?,
    val thumbnailUrl: String?,
    val addedAtMillis: Long,
    val updatedAtMillis: Long,
)

/** One row of [PlaylistDao.summaries]. */
data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    val updatedAtMillis: Long,
    val videoCount: Int,
    val totalDurationSeconds: Long,
    val coverThumbnailUrl: String?,
)

/** One playlist holding a video ([PlaylistDao.holding]). */
data class PlaylistRefRow(val id: Long, val watchLaterMark: Int?)

/**
 * Reads and playlist-level writes name the profile, so a playlist id from another profile reads
 * and changes nothing. Item writes and [touch] take a playlist id the caller has checked with
 * [ownsPlaylist] (or [watchLaterId]) in the same transaction, as [PlaylistRepository] does.
 */
@Dao
interface PlaylistDao {
    /** The profile's own playlists (Watch later aside), the most recently updated first. A
     *  length YouTube didn't give (-1) counts as 0. The total sums with `TOTAL`, which can't
     *  overflow the way `SUM` does (a restored file's lengths are whatever it says). */
    @Query(
        "SELECT p.id, p.name, p.updatedAtMillis, " +
            "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS videoCount, " +
            "(SELECT CAST(TOTAL(MAX(i.durationSeconds, 0)) AS INTEGER) FROM playlist_items i WHERE i.playlistId = p.id) " +
            "AS totalDurationSeconds, " +
            "(SELECT i.thumbnailUrl FROM playlist_items i WHERE i.playlistId = p.id ORDER BY i.position LIMIT 1) " +
            "AS coverThumbnailUrl " +
            "FROM playlists p WHERE p.profileId = :profileId AND p.watchLaterMark IS NULL " +
            "ORDER BY p.updatedAtMillis DESC, p.id DESC",
    )
    fun summaries(profileId: Long): Flow<List<PlaylistSummaryRow>>

    @Query(
        "SELECT i.* FROM playlist_items i JOIN playlists p ON p.id = i.playlistId " +
            "WHERE p.profileId = :profileId AND p.id = :playlistId ORDER BY i.position",
    )
    fun items(profileId: Long, playlistId: Long): Flow<List<PlaylistItemEntity>>

    /** [items] of every playlist [summaries] lists, in its order. */
    @Query(
        "SELECT i.* FROM playlist_items i JOIN playlists p ON p.id = i.playlistId " +
            "WHERE p.profileId = :profileId AND p.watchLaterMark IS NULL " +
            "ORDER BY p.updatedAtMillis DESC, p.id DESC, i.position",
    )
    fun playlistItems(profileId: Long): Flow<List<PlaylistItemEntity>>

    @Query(
        "SELECT i.* FROM playlist_items i JOIN playlists p ON p.id = i.playlistId " +
            "WHERE p.profileId = :profileId AND p.watchLaterMark IS NOT NULL ORDER BY i.position",
    )
    fun watchLaterItems(profileId: Long): Flow<List<PlaylistItemEntity>>

    @Query(
        "SELECT p.id, p.watchLaterMark FROM playlists p JOIN playlist_items i ON i.playlistId = p.id " +
            "WHERE p.profileId = :profileId AND i.videoId = :videoId",
    )
    fun holding(profileId: Long, videoId: String): Flow<List<PlaylistRefRow>>

    /** Every playlist, Watch later included, for a backup: by profile, then oldest first. */
    @Query("SELECT * FROM playlists ORDER BY profileId, id")
    suspend fun allPlaylists(): List<PlaylistEntity>

    /** Every saved video, for a backup: by playlist, then in order. */
    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    suspend fun allItems(): List<PlaylistItemEntity>

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :playlistId AND profileId = :profileId)")
    suspend fun ownsPlaylist(profileId: Long, playlistId: Long): Boolean

    @Query("SELECT id FROM playlists WHERE profileId = :profileId AND watchLaterMark IS NOT NULL")
    suspend fun watchLaterId(profileId: Long): Long?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Long

    /** A video already in the playlist keeps its place and fields: `-1`, nothing written. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    /** Record the removal first ([RemovalDao.playlistItem], M8-D6). */
    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun deleteItem(playlistId: Long, videoId: String): Int

    @Query("UPDATE playlists SET updatedAtMillis = :nowMillis WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, nowMillis: Long)

    /** Renames one of the profile's own playlists (never Watch later); returns rows changed. */
    @Query(
        "UPDATE playlists SET name = :name, updatedAtMillis = :nowMillis " +
            "WHERE id = :playlistId AND profileId = :profileId AND watchLaterMark IS NULL",
    )
    suspend fun rename(profileId: Long, playlistId: Long, name: String, nowMillis: Long): Int

    /** Deletes one of the profile's own playlists (never Watch later) and, by cascade, its items.
     *  Record the removal first ([RemovalDao.playlist], M8-D6). */
    @Query("DELETE FROM playlists WHERE id = :playlistId AND profileId = :profileId AND watchLaterMark IS NULL")
    suspend fun delete(profileId: Long, playlistId: Long): Int

    /** A sync's apply (M8-D16): the merge's copy of the row, as it is. */
    @Query("UPDATE playlists SET name = :name, createdAtMillis = :createdAtMillis, updatedAtMillis = :updatedAtMillis WHERE id = :playlistId")
    suspend fun setRow(playlistId: Long, name: String, createdAtMillis: Long, updatedAtMillis: Long)

    /** A sync's apply: a playlist the merge dropped, whose removal record it carries. */
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteForSync(playlistId: Long)

    /** A sync's apply, before the merge's items go in. */
    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: Long)

    /** A sync's pairing (M8-D16): the playlist takes its pair's sync id. */
    @Query("UPDATE playlists SET syncId = :to WHERE profileId = :profileId AND syncId = :from")
    suspend fun setSyncId(profileId: Long, from: String, to: String)
}

/** The one value [PlaylistEntity.watchLaterMark] takes (Watch later's). */
internal const val WATCH_LATER_MARK = 1
