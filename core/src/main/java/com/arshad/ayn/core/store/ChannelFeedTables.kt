package com.arshad.ayn.core.store

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * One subscribed channel's feed as a profile's last load fetched it (PERF P9c), shown at the next
 * start while the fresh feed loads. [videos] and [shorts] are JSON ([encodeRelatedItems]);
 * [restrictedMode] is the [RestrictedMode] key it was fetched under (KP-D15). Goes with its
 * profile; not in backups.
 */
@Entity(
    tableName = "channel_feeds",
    primaryKeys = ["profileId", "channelUrl"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ChannelFeedEntity(
    val profileId: Long,
    val channelUrl: String,
    val videos: String,
    val shorts: String,
    val fetchedAtMillis: Long,
    val restrictedMode: String,
)

/** A kept feed's channel and fetch time. */
data class ChannelFeedStamp(val channelUrl: String, val fetchedAtMillis: Long)

@Dao
interface ChannelFeedDao {
    @Query("SELECT * FROM channel_feeds WHERE profileId = :profileId AND restrictedMode = :restrictedMode")
    suspend fun feeds(profileId: Long, restrictedMode: String): List<ChannelFeedEntity>

    @Query("SELECT channelUrl, fetchedAtMillis FROM channel_feeds WHERE profileId = :profileId")
    suspend fun stamps(profileId: Long): List<ChannelFeedStamp>

    @Upsert
    suspend fun upsert(rows: List<ChannelFeedEntity>)

    /** Drops the profile's feeds of channels it no longer subscribes to. */
    @Query(
        "DELETE FROM channel_feeds WHERE profileId = :profileId AND channelUrl NOT IN " +
            "(SELECT channelUrl FROM subscriptions WHERE profileId = :profileId)",
    )
    suspend fun prune(profileId: Long)
}
