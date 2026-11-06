package com.arshad.ayn.core.store

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** A profile's subscription to a channel; goes with its profile. */
@Entity(
    tableName = "subscriptions",
    primaryKeys = ["profileId", "channelUrl"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SubscriptionEntity(
    val profileId: Long,
    /** The channel's canonical URL ([canonicalChannelUrl]): one row per channel (M5-D19). */
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAtMillis: Long,
    /** Written on every change; the D4 LWW key (ARCH-7). */
    val updatedAtMillis: Long,
)

/** A profile's subscriptions, the most recent subscribe first. */
private const val SUBSCRIPTION_ORDER = "subscribedAtMillis DESC, channelUrl"

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions WHERE profileId = :profileId ORDER BY $SUBSCRIPTION_ORDER")
    fun subscriptions(profileId: Long): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE profileId = :profileId AND channelUrl = :channelUrl")
    fun subscription(profileId: Long, channelUrl: String): Flow<SubscriptionEntity?>

    /** Every profile's subscriptions, for a backup: by profile, then in [subscriptions] order. */
    @Query("SELECT * FROM subscriptions ORDER BY profileId, $SUBSCRIPTION_ORDER")
    suspend fun all(): List<SubscriptionEntity>

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Insert
    suspend fun insertAll(subscriptions: List<SubscriptionEntity>)

    /** A sync's apply (M8-D16): the merge's copies, as they are. */
    @Upsert
    suspend fun upsertAll(subscriptions: List<SubscriptionEntity>)

    /** A sync's apply: rows the merge dropped, whose removal records it carries. */
    @Delete
    suspend fun deleteAll(subscriptions: List<SubscriptionEntity>)

    /** An already-stored channel keeps its row; returns each row's id, -1 where nothing was written. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(subscriptions: List<SubscriptionEntity>): List<Long>

    /** Returns the rows removed (0 or 1). Record the removal first ([RemovalDao.subscription], M8-D6). */
    @Query("DELETE FROM subscriptions WHERE profileId = :profileId AND channelUrl = :channelUrl")
    suspend fun delete(profileId: Long, channelUrl: String): Int
}
