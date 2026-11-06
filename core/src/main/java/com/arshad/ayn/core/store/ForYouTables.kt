package com.arshad.ayn.core.store

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One video's related list as YouTube gave it (FY-D4), kept for a profile by the writers
 * [RelatedListStore] names; goes with its profile. [items] is the list as JSON ([encodeRelatedItems]), `[]`
 * when only a failed fetch is known. [fetchedAtMillis] is the last list stored (null before any),
 * [failedAtMillis] the last failed fetch since then (cleared by the next stored list).
 * [restrictedMode] is the [RestrictedMode] key the row was written under (KP-D15; NULL for rows
 * from before v8, fetched without the header: Off).
 */
@Entity(
    tableName = "related_lists",
    primaryKeys = ["profileId", "videoId"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RelatedListEntity(
    val profileId: Long,
    val videoId: String,
    val items: String,
    val fetchedAtMillis: Long?,
    val failedAtMillis: Long?,
    val restrictedMode: String?,
)

/** A video the profile marked Not interested (FY-D6); goes with its profile, carried in backups. */
@Entity(
    tableName = "hidden_videos",
    primaryKeys = ["profileId", "videoId"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class HiddenVideoEntity(
    val profileId: Long,
    val videoId: String,
    val hiddenAtMillis: Long,
)

/** A channel the profile asked For you not to recommend (FY-D6), by [canonicalChannelUrl]; goes
 *  with its profile, carried in backups. */
@Entity(
    tableName = "hidden_channels",
    primaryKeys = ["profileId", "channelUrl"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class HiddenChannelEntity(
    val profileId: Long,
    val channelUrl: String,
    val hiddenAtMillis: Long,
)

/**
 * How a For you card fared with the profile (FY-D3, FY-D6): shown [shownCount] times (once per
 * Home visit), last at [lastShownAtMillis], opened at [openedAtMillis] (null until opened). The
 * card's channel ([canonicalChannelUrl]) gives the channel's open rate. Goes with its profile;
 * not in backups.
 */
@Entity(
    tableName = "impressions",
    primaryKeys = ["profileId", "videoId"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImpressionEntity(
    val profileId: Long,
    val videoId: String,
    val channelUrl: String?,
    val shownCount: Int,
    val lastShownAtMillis: Long,
    val openedAtMillis: Long?,
)

/**
 * One For you request to YouTube (FY-D5): when, and how it ended ([FetchOutcome.key]). Device-wide,
 * since YouTube's limit is per address; no video id, so it records no profile's viewing.
 */
@Entity(tableName = "fetch_attempts")
data class FetchAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val outcome: String,
)

/** An impression's last show or opening, whichever came later. */
private const val LAST_TOUCHED = "MAX(lastShownAtMillis, IFNULL(openedAtMillis, 0))"

/** The newest write first: a list stored or a fetch failed, whichever came later. */
private const val NEWEST_FIRST = "MAX(IFNULL(fetchedAtMillis, 0), IFNULL(failedAtMillis, 0)) DESC, videoId"

@Dao
interface RelatedListDao {
    @Query("SELECT * FROM related_lists WHERE profileId = :profileId AND videoId IN (:videoIds)")
    suspend fun lists(profileId: Long, videoIds: List<String>): List<RelatedListEntity>

    @Upsert
    suspend fun upsert(row: RelatedListEntity)

    /** A row for a failure to mark; an existing row keeps its list. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: RelatedListEntity)

    @Query("UPDATE related_lists SET failedAtMillis = :nowMillis WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun markFailed(profileId: Long, videoId: String, nowMillis: Long)

    /** Keeps the profile's [keep] newest rows. */
    @Query(
        "DELETE FROM related_lists WHERE profileId = :profileId AND videoId NOT IN " +
            "(SELECT videoId FROM related_lists WHERE profileId = :profileId ORDER BY $NEWEST_FIRST LIMIT :keep)",
    )
    suspend fun trim(profileId: Long, keep: Int)

    @Query("DELETE FROM related_lists WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun delete(profileId: Long, videoId: String)

    @Query("DELETE FROM related_lists WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)
}

@Dao
interface FetchAttemptDao {
    @Insert
    suspend fun insert(row: FetchAttemptEntity)

    /** Oldest first. */
    @Query("SELECT * FROM fetch_attempts WHERE atMillis >= :sinceMillis ORDER BY atMillis, id")
    suspend fun since(sinceMillis: Long): List<FetchAttemptEntity>

    @Query("DELETE FROM fetch_attempts WHERE atMillis <= :untilMillis")
    suspend fun deleteThrough(untilMillis: Long)
}

@Dao
interface ForYouDao {
    @Query("SELECT videoId FROM hidden_videos WHERE profileId = :profileId")
    fun hiddenVideoIds(profileId: Long): Flow<List<String>>

    @Query("SELECT channelUrl FROM hidden_channels WHERE profileId = :profileId")
    fun hiddenChannelUrls(profileId: Long): Flow<List<String>>

    /** Hiding a video already hidden keeps its first time. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideVideo(row: HiddenVideoEntity)

    /** Record the removal first ([RemovalDao.hiddenVideo], M8-D6). */
    @Query("DELETE FROM hidden_videos WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun unhideVideo(profileId: Long, videoId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideChannel(row: HiddenChannelEntity)

    /** Record the removal first ([RemovalDao.hiddenChannel], M8-D6). */
    @Query("DELETE FROM hidden_channels WHERE profileId = :profileId AND channelUrl = :channelUrl")
    suspend fun unhideChannel(profileId: Long, channelUrl: String)

    /** Every profile's hidden videos, for a backup: by profile, then oldest first. */
    @Query("SELECT * FROM hidden_videos ORDER BY profileId, hiddenAtMillis, videoId")
    suspend fun allHiddenVideos(): List<HiddenVideoEntity>

    /** Every profile's hidden channels, for a backup: by profile, then oldest first. */
    @Query("SELECT * FROM hidden_channels ORDER BY profileId, hiddenAtMillis, channelUrl")
    suspend fun allHiddenChannels(): List<HiddenChannelEntity>

    @Insert
    suspend fun insertHiddenVideos(rows: List<HiddenVideoEntity>)

    @Insert
    suspend fun insertHiddenChannels(rows: List<HiddenChannelEntity>)

    /** A sync's apply (M8-D16): the merge's copies, as they are. */
    @Upsert
    suspend fun upsertHiddenVideos(rows: List<HiddenVideoEntity>)

    /** As [upsertHiddenVideos]. */
    @Upsert
    suspend fun upsertHiddenChannels(rows: List<HiddenChannelEntity>)

    /** A sync's apply: rows the merge dropped, whose removal records it carries. */
    @Delete
    suspend fun deleteHiddenVideos(rows: List<HiddenVideoEntity>)

    /** As [deleteHiddenVideos]. */
    @Delete
    suspend fun deleteHiddenChannels(rows: List<HiddenChannelEntity>)

    /** The profile's impressions last shown or opened after [afterMillis]. */
    @Query("SELECT * FROM impressions WHERE profileId = :profileId AND $LAST_TOUCHED > :afterMillis")
    suspend fun impressions(profileId: Long, afterMillis: Long): List<ImpressionEntity>

    /** A row for a writer to update; an existing row keeps every column. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImpressionIfAbsent(row: ImpressionEntity)

    @Query(
        "UPDATE impressions SET shownCount = shownCount + 1, lastShownAtMillis = :nowMillis, " +
            "channelUrl = COALESCE(:channelUrl, channelUrl) WHERE profileId = :profileId AND videoId = :videoId",
    )
    suspend fun markShown(profileId: Long, videoId: String, channelUrl: String?, nowMillis: Long)

    @Query(
        "UPDATE impressions SET openedAtMillis = :nowMillis, channelUrl = COALESCE(:channelUrl, channelUrl) " +
            "WHERE profileId = :profileId AND videoId = :videoId",
    )
    suspend fun markOpened(profileId: Long, videoId: String, channelUrl: String?, nowMillis: Long)

    /** Drops every profile's impressions last shown or opened at or before [untilMillis]. */
    @Query("DELETE FROM impressions WHERE $LAST_TOUCHED <= :untilMillis")
    suspend fun pruneImpressions(untilMillis: Long)
}
