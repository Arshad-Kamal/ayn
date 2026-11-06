package com.arshad.ayn.core.store

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Query
import androidx.room3.Upsert
import java.util.UUID

/**
 * A removal for sync (M8-D6, M8-D12): [itemKey] of [kind] left the profile [profileSyncId] at
 * [removedAtMillis]. Without it a merge would bring the item back from a device that still has it.
 * Keyed by the profile's sync id with no foreign key, so a removed profile's own record outlives
 * the profile.
 */
@Entity(tableName = "removals", primaryKeys = ["profileSyncId", "kind", "itemKey"])
data class RemovalEntity(
    val profileSyncId: String,
    /** One of [RemovalKind]'s values. */
    val kind: String,
    val itemKey: String,
    val removedAtMillis: Long,
)

/** [RemovalEntity.kind]'s values; each names the item key it records. */
object RemovalKind {
    /** The channel's canonical URL. */
    const val SUBSCRIPTION = "subscription"

    /** The video id of a history entry. */
    const val WATCH = "watch"

    /** The playlist's sync id. */
    const val PLAYLIST = "playlist"

    /** `<playlist sync id>/<video id>`. */
    const val PLAYLIST_ITEM = "playlistItem"

    /** The video id. */
    const val HIDDEN_VIDEO = "hiddenVideo"

    /** The channel's canonical URL. */
    const val HIDDEN_CHANNEL = "hiddenChannel"

    /** `<1 by the owner, else 0>/<rule kind>/<value>`. */
    const val RULE = "rule"

    /** The profile's own sync id. */
    const val PROFILE = "profile"
}

/**
 * Writes the removal record of a row about to be deleted, in the delete's write transaction: each
 * call selects the row itself, so a row that isn't there records nothing. A repeat removal moves
 * the record's time.
 */
@Dao
interface RemovalDao {
    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.SUBSCRIPTION}', s.channelUrl, :nowMillis FROM subscriptions s " +
            "JOIN profiles p ON p.id = s.profileId WHERE s.profileId = :profileId AND s.channelUrl = :channelUrl",
    )
    suspend fun subscription(profileId: Long, channelUrl: String, nowMillis: Long)

    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.WATCH}', w.videoId, :nowMillis FROM watch_entries w " +
            "JOIN profiles p ON p.id = w.profileId WHERE w.profileId = :profileId AND w.videoId = :videoId",
    )
    suspend fun watchEntry(profileId: Long, videoId: String, nowMillis: Long)

    /** Every entry of the profile's history (clearing it). */
    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.WATCH}', w.videoId, :nowMillis FROM watch_entries w " +
            "JOIN profiles p ON p.id = w.profileId WHERE w.profileId = :profileId",
    )
    suspend fun watchHistory(profileId: Long, nowMillis: Long)

    /** One of the profile's own playlists (Watch later is never deleted). */
    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.PLAYLIST}', l.syncId, :nowMillis FROM playlists l " +
            "JOIN profiles p ON p.id = l.profileId " +
            "WHERE l.id = :playlistId AND l.profileId = :profileId AND l.watchLaterMark IS NULL",
    )
    suspend fun playlist(profileId: Long, playlistId: Long, nowMillis: Long)

    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.PLAYLIST_ITEM}', l.syncId || '/' || i.videoId, :nowMillis " +
            "FROM playlist_items i JOIN playlists l ON l.id = i.playlistId JOIN profiles p ON p.id = l.profileId " +
            "WHERE i.playlistId = :playlistId AND i.videoId = :videoId",
    )
    suspend fun playlistItem(playlistId: Long, videoId: String, nowMillis: Long)

    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.HIDDEN_VIDEO}', h.videoId, :nowMillis FROM hidden_videos h " +
            "JOIN profiles p ON p.id = h.profileId WHERE h.profileId = :profileId AND h.videoId = :videoId",
    )
    suspend fun hiddenVideo(profileId: Long, videoId: String, nowMillis: Long)

    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.HIDDEN_CHANNEL}', h.channelUrl, :nowMillis FROM hidden_channels h " +
            "JOIN profiles p ON p.id = h.profileId WHERE h.profileId = :profileId AND h.channelUrl = :channelUrl",
    )
    suspend fun hiddenChannel(profileId: Long, channelUrl: String, nowMillis: Long)

    @Query(
        "$RECORD SELECT p.syncId, '${RemovalKind.RULE}', r.byOwner || '/' || r.kind || '/' || r.value, :nowMillis " +
            "FROM content_rules r JOIN profiles p ON p.id = r.profileId " +
            "WHERE r.profileId = :profileId AND r.byOwner = :byOwner AND r.kind = :kind AND r.value = :value",
    )
    suspend fun rule(profileId: Long, byOwner: Boolean, kind: String, value: String, nowMillis: Long)

    /** A non-owner profile: its own record in place of its others, which a removed profile no
     *  longer needs. Two statements: call it inside the delete's write transaction, as every
     *  record is. */
    suspend fun profile(profileId: Long, nowMillis: Long) {
        dropItemRecordsOf(profileId)
        recordProfile(profileId, nowMillis)
    }

    /** A step of [profile]: deletes a non-owner profile's removal records other than its own. */
    @Query(
        "DELETE FROM removals WHERE kind != '${RemovalKind.PROFILE}' " +
            "AND profileSyncId = (SELECT syncId FROM profiles WHERE id = :profileId AND ownerMark IS NULL)",
    )
    suspend fun dropItemRecordsOf(profileId: Long)

    /** A step of [profile]. */
    @Query(
        "$RECORD SELECT syncId, '${RemovalKind.PROFILE}', syncId, :nowMillis FROM profiles " +
            "WHERE id = :profileId AND ownerMark IS NULL",
    )
    suspend fun recordProfile(profileId: Long, nowMillis: Long)

    /** Every record, by profile, kind and key. */
    @Query("SELECT * FROM removals ORDER BY profileSyncId, kind, itemKey")
    suspend fun all(): List<RemovalEntity>

    /** A sync's apply (M8-D16): the merge's records, as they are. */
    @Upsert
    suspend fun upsertAll(records: List<RemovalEntity>)

    /** A sync's apply: records the merge doesn't carry (past 90 days, or re-keyed by a pairing). */
    @Delete
    suspend fun deleteAll(records: List<RemovalEntity>)
}

private const val RECORD = "INSERT OR REPLACE INTO removals (profileSyncId, kind, itemKey, removedAtMillis)"

/** Every profile's Watch later has this sync id, so two devices' Watch laters pair (M8-D12). */
internal const val WATCH_LATER_SYNC_ID = "watch-later"

/** A new random sync id for a profile or playlist (M8-D5). */
internal fun newSyncId(): String = UUID.randomUUID().toString()
