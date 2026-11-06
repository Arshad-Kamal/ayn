package com.arshad.ayn.core.store

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A profile's watch state for one video; goes with its profile. Written in two column groups
 * (M5-D8): the card's display fields, and position/duration/watched/lastWatchedAt. Each writer
 * inserts a skeleton if the row is missing, then updates only its own group.
 */
@Entity(
    tableName = "watch_entries",
    primaryKeys = ["profileId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WatchEntryEntity(
    val profileId: Long,
    val videoId: String,
    val title: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val thumbnailUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val watched: Boolean = false,
    val lastWatchedAtMillis: Long,
    val updatedAtMillis: Long,
    /** Added in v10 (PAF-D6); rows from before it count as not Shorts. */
    @ColumnInfo(defaultValue = "0") val isShort: Boolean = false,
)

/** [WatchThresholds.hasResumePoint] as SQL, over a row's own position and duration; its 90 % bound
 *  is the complement of [WatchThresholds.isWatched], so a change to one changes the others. */
private const val HAS_RESUME_POINT = "positionMs >= ${WatchThresholds.MIN_RESUME_MS} AND durationMs > 0 " +
    "AND positionMs * 100 < durationMs * ${WatchThresholds.WATCHED_PERCENT}"

/** The most recently watched first. */
private const val HISTORY_ORDER = "lastWatchedAtMillis DESC, videoId"

@Dao
interface WatchDao {
    @Query("SELECT * FROM watch_entries WHERE profileId = :profileId ORDER BY $HISTORY_ORDER")
    fun history(profileId: Long): Flow<List<WatchEntryEntity>>

    @Query(
        "SELECT * FROM watch_entries WHERE profileId = :profileId AND $HAS_RESUME_POINT " +
            "ORDER BY $HISTORY_ORDER",
    )
    fun resumePoints(profileId: Long): Flow<List<WatchEntryEntity>>

    @Query("SELECT videoId FROM watch_entries WHERE profileId = :profileId AND watched")
    fun watchedIds(profileId: Long): Flow<List<String>>

    /** Every profile's entries, for a backup: by profile, then in [history] order. */
    @Query("SELECT * FROM watch_entries ORDER BY profileId, $HISTORY_ORDER")
    suspend fun all(): List<WatchEntryEntity>

    @Insert
    suspend fun insertAll(entries: List<WatchEntryEntity>)

    /** A sync's apply (M8-D16): the merge's copies, as they are. */
    @Upsert
    suspend fun upsertAll(entries: List<WatchEntryEntity>)

    /** A sync's apply: rows the merge dropped, whose removal records it carries. */
    @Delete
    suspend fun deleteAll(entries: List<WatchEntryEntity>)

    @Query("SELECT positionMs FROM watch_entries WHERE profileId = :profileId AND videoId = :videoId AND $HAS_RESUME_POINT")
    suspend fun resumePointMs(profileId: Long, videoId: String): Long?

    /** A skeleton row for a writer to update; an existing row keeps every column. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: WatchEntryEntity)

    /** A card without a channel (a search Short's, before its resolve) keeps the stored one; a Short
     *  stays one once played as one (PAF-D6). */
    @Query(
        "UPDATE watch_entries SET title = :title, uploaderName = COALESCE(:uploaderName, uploaderName), " +
            "uploaderUrl = COALESCE(:uploaderUrl, uploaderUrl), thumbnailUrl = :thumbnailUrl, " +
            "isShort = (isShort OR :isShort), updatedAtMillis = :nowMillis WHERE profileId = :profileId AND videoId = :videoId",
    )
    suspend fun updateDisplay(
        profileId: Long,
        videoId: String,
        title: String,
        uploaderName: String?,
        uploaderUrl: String?,
        thumbnailUrl: String?,
        isShort: Boolean,
        nowMillis: Long,
    )

    /** Watched stays set once reached; an unknown [durationMs] keeps the stored one. */
    @Query(
        "UPDATE watch_entries SET positionMs = :positionMs, durationMs = COALESCE(:durationMs, durationMs), " +
            "watched = (watched OR :watched), lastWatchedAtMillis = :nowMillis, updatedAtMillis = :nowMillis " +
            "WHERE profileId = :profileId AND videoId = :videoId",
    )
    suspend fun updatePosition(
        profileId: Long,
        videoId: String,
        positionMs: Long,
        durationMs: Long?,
        watched: Boolean,
        nowMillis: Long,
    )

    /** Record the removal first ([RemovalDao.watchEntry], M8-D6). */
    @Query("DELETE FROM watch_entries WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun delete(profileId: Long, videoId: String)

    /** Record the removals first ([RemovalDao.watchHistory], M8-D6). */
    @Query("DELETE FROM watch_entries WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)
}
