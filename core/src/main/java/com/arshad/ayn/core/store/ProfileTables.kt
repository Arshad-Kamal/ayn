package com.arshad.ayn.core.store

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * A profile (B-D9, `06-tv-ui-rebuild.md` § B3p). [ownerMark] is [OWNER_MARK] for the owner and NULL
 * for everyone else; its unique index makes a second owner fail the write (SQLite UNIQUE admits
 * many NULLs). Every read tests ownership as `ownerMark IS NOT NULL`.
 * Names are unique ignoring case. The lock columns (kind, salted PBKDF2 hash, tries, wait-until;
 * `core.lock.LockRepository`) live here so removing or restoring a profile clears them with it.
 * [kids] marks a kids profile (KP-D9..D14); set when it is created, never changed. [syncId] names
 * the profile across devices (M8-D5); the local [id] doesn't.
 */
@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["ownerMark"], unique = true),
        Index(value = ["syncId"], unique = true),
    ],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    /** A [ProfileTint] key. */
    val tint: String,
    val ownerMark: Int?,
    val lockKind: String? = null,
    val lockHash: String? = null,
    val lockSalt: String? = null,
    val lockIterations: Int = 0,
    val failedTries: Int = 0,
    val waitUntilMillis: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Added in v8; rows from before it are not kids profiles. */
    @ColumnInfo(defaultValue = "0") val kids: Boolean = false,
    /** Added in v12, which gave every row its own; new rows get one here. The column's `''`
     *  default only serves the migration's `ADD COLUMN`. */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

@Dao
interface ProfileDao {
    /** The owner first, then the rest in the order they were added. */
    @Query("SELECT * FROM profiles ORDER BY $PROFILE_ORDER")
    fun profiles(): Flow<List<ProfileEntity>>

    /** Every profile's counts; re-emitted when any of the counted tables changes. */
    @Query(
        "SELECT p.id AS profileId, " +
            "(SELECT COUNT(*) FROM subscriptions s WHERE s.profileId = p.id) AS subscriptionCount, " +
            "(SELECT COUNT(*) FROM playlists l WHERE l.profileId = p.id AND l.watchLaterMark IS NULL) AS playlistCount, " +
            "(SELECT COUNT(*) FROM watch_entries w WHERE w.profileId = p.id) AS historyCount " +
            "FROM profiles p",
    )
    fun contents(): Flow<List<ProfileContentsRow>>

    /** [profiles] once, for a backup. */
    @Query("SELECT * FROM profiles ORDER BY $PROFILE_ORDER")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE ownerMark IS NOT NULL")
    suspend fun owner(): ProfileEntity?

    /** Whether the owner exists and has a lock (KP-D14). */
    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE ownerMark IS NOT NULL AND lockHash IS NOT NULL)")
    suspend fun ownerLocked(): Boolean

    /** Whether a kids profile exists (the owner's lock stays while one does, KP-D14). */
    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE kids = 1)")
    suspend fun kidsExist(): Boolean

    /** Whether [id] is a kids profile; `false` for an unknown id. */
    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE id = :id AND kids = 1)")
    suspend fun isKids(id: Long): Boolean

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun profile(id: Long): ProfileEntity?

    @Query("UPDATE profiles SET failedTries = :failedTries, waitUntilMillis = :waitUntilMillis WHERE id = :id")
    suspend fun setTries(id: Long, failedTries: Int, waitUntilMillis: Long?)

    /** Sets a lock, or clears it when every value is null; either way its tries start over. */
    @Query(
        "UPDATE profiles SET lockKind = :kind, lockHash = :hash, lockSalt = :salt, lockIterations = :iterations, " +
            "failedTries = 0, waitUntilMillis = NULL WHERE id = :id",
    )
    suspend fun setLock(id: Long, kind: String?, hash: String?, salt: String?, iterations: Int)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    /** Whether another profile than [exceptId] already has [name], ignoring case. */
    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE name = :name AND id != :exceptId)")
    suspend fun nameTaken(name: String, exceptId: Long): Boolean

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Query("UPDATE profiles SET name = :name, updatedAtMillis = :nowMillis WHERE id = :id")
    suspend fun rename(id: Long, name: String, nowMillis: Long)

    @Query("UPDATE profiles SET tint = :tint, updatedAtMillis = :nowMillis WHERE id = :id")
    suspend fun setTint(id: Long, tint: String, nowMillis: Long)

    /** Removes a non-owner profile and, by cascade, everything it holds; returns rows removed.
     *  Record the removal first ([RemovalDao.profile], M8-D6). */
    @Query("DELETE FROM profiles WHERE id = :id AND ownerMark IS NULL")
    suspend fun removeNonOwner(id: Long): Int

    /** Removes [id], the owner too, and by cascade everything it holds: a restore's Replace,
     *  which inserts the row again under the same id (KP-D24). */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun removeToReplace(id: Long): Int

    /** A sync's apply (M8-D16): the merge's copy of the row's synced fields; lock, owner mark
     *  and kind stay this device's. */
    @Query(
        "UPDATE profiles SET name = :name, tint = :tint, createdAtMillis = :createdAtMillis, " +
            "updatedAtMillis = :updatedAtMillis WHERE id = :id",
    )
    suspend fun setRow(id: Long, name: String, tint: String, createdAtMillis: Long, updatedAtMillis: Long)

    /** A sync's pairing (M8-D16): the profile takes its pair's sync id. */
    @Query("UPDATE profiles SET syncId = :to WHERE syncId = :from")
    suspend fun setSyncId(from: String, to: String)
}

/** One row of [ProfileDao.contents]. */
data class ProfileContentsRow(val profileId: Long, val subscriptionCount: Int, val playlistCount: Int, val historyCount: Int)

private const val PROFILE_ORDER = "ownerMark IS NULL, id"

/** The one value [ProfileEntity.ownerMark] takes (the owner's). */
internal const val OWNER_MARK = 1
