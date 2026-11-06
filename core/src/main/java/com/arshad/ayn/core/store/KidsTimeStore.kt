package com.arshad.ayn.core.store

import android.util.Log
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import com.arshad.ayn.core.lock.OwnerPass
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A kids profile's watch time per local day (KP-D8, D18): what it used and the More time the
 * owner gave. Device-local history of one profile: goes with it, never in backups; days older
 * than [KEPT_DAYS] are dropped as new time is counted.
 */
interface KidsTimeStore {
    /** [profileId]'s days from [from] through [through], re-emitted on change; a day missing from
     *  the map has nothing counted. */
    fun days(profileId: Long, from: LocalDate, through: LocalDate): Flow<Map<LocalDate, DayTime>>

    /** Counts [seconds] more watched on [day]; `false` when the write failed. */
    suspend fun addUsed(profileId: Long, day: LocalDate, seconds: Long): Boolean

    /** Gives [profileId] [minutes] more on [day], counted from now: the kid may watch until
     *  [DayTime.allowedUntilSeconds] = the later of what's used and an earlier grant's end, plus
     *  [minutes] (KP-D22). `false` when [minutes] is out of range or the write failed. */
    suspend fun addMoreTime(owner: OwnerPass, profileId: Long, day: LocalDate, minutes: Int): Boolean
}

data class DayTime(
    val usedSeconds: Long = 0,
    /** All the More time the owner gave that day, for the parent review. */
    val moreTimeMinutes: Int = 0,
    /** The used time More time lasts to; nothing is granted while [usedSeconds] has reached it. */
    val allowedUntilSeconds: Long = 0,
)

/** How many days of watch time are kept: two weeks, for the parent review's week (F12). */
const val KEPT_DAYS = 14L

/** [day] is an ISO local date (`2026-10-10`), so text order is date order. */
@Entity(
    tableName = "kids_time",
    primaryKeys = ["profileId", "day"],
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class KidsTimeEntity(
    val profileId: Long,
    val day: String,
    val usedSeconds: Long,
    val moreTimeMinutes: Int,
    val allowedUntilSeconds: Long,
)

@Dao
interface KidsTimeDao {
    @Query("SELECT * FROM kids_time WHERE profileId = :profileId AND day BETWEEN :from AND :through")
    fun days(profileId: Long, from: String, through: String): Flow<List<KidsTimeEntity>>

    /** A row for an update to add to; an existing row keeps its counts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: KidsTimeEntity)

    @Query("UPDATE kids_time SET usedSeconds = usedSeconds + :seconds WHERE profileId = :profileId AND day = :day")
    suspend fun addUsed(profileId: Long, day: String, seconds: Long)

    @Query(
        "UPDATE kids_time SET moreTimeMinutes = moreTimeMinutes + :minutes, " +
            "allowedUntilSeconds = MAX(usedSeconds, allowedUntilSeconds) + :minutes * 60 " +
            "WHERE profileId = :profileId AND day = :day",
    )
    suspend fun addMoreTime(profileId: Long, day: String, minutes: Int)

    @Query("DELETE FROM kids_time WHERE profileId = :profileId AND day < :oldest")
    suspend fun dropBefore(profileId: Long, oldest: String)

    /** Every day kept for [profileId]: a restore's Replace carries them over (KP-D24). */
    @Query("SELECT * FROM kids_time WHERE profileId = :profileId")
    suspend fun allFor(profileId: Long): List<KidsTimeEntity>

    @Insert
    suspend fun insertAll(rows: List<KidsTimeEntity>)
}

/** [KidsTimeStore] over Room. */
class KidsTimeRepository(private val database: KeDatabase) : KidsTimeStore {
    private val dao: KidsTimeDao = database.kidsTimeDao()

    override fun days(profileId: Long, from: LocalDate, through: LocalDate): Flow<Map<LocalDate, DayTime>> =
        dao.days(profileId, from.toString(), through.toString())
            .map { rows ->
                rows.associate { LocalDate.parse(it.day) to DayTime(it.usedSeconds, it.moreTimeMinutes, it.allowedUntilSeconds) }
            }
            .distinctUntilChanged()

    override suspend fun addUsed(profileId: Long, day: LocalDate, seconds: Long): Boolean {
        if (seconds <= 0) return true
        return write("time write") {
            dao.dropBefore(profileId, day.minusDays(KEPT_DAYS - 1).toString())
            dao.insertIfAbsent(emptyDay(profileId, day))
            dao.addUsed(profileId, day.toString(), seconds)
        }
    }

    override suspend fun addMoreTime(owner: OwnerPass, profileId: Long, day: LocalDate, minutes: Int): Boolean {
        if (minutes !in 1..MINUTES_PER_DAY) return false
        return write("more time write") {
            dao.insertIfAbsent(emptyDay(profileId, day))
            dao.addMoreTime(profileId, day.toString(), minutes)
        }
    }

    private suspend fun write(what: String, block: suspend () -> Unit): Boolean = try {
        database.withWriteTransaction { block() }
        true
    } catch (e: SQLiteException) {
        Log.w(LOG_TAG, "$what failed: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val LOG_TAG = "KeKidsTime"

        fun emptyDay(profileId: Long, day: LocalDate): KidsTimeEntity =
            KidsTimeEntity(profileId, day.toString(), usedSeconds = 0, moreTimeMinutes = 0, allowedUntilSeconds = 0)
    }
}
