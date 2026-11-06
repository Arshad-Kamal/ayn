package com.arshad.ayn.core.lock

import android.content.Context
import com.arshad.ayn.core.store.DEVICE_PREFERENCES
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where KE was left, kept on disk so a restart after its process died can apply the re-lock
 * rules to the time away (M5-D21): the profile whose shell was open and its last active time on
 * the re-lock clock (`elapsedRealtime`, meaningful within one boot only).
 */
data class AwayRecord(val profileId: Long, val lastActiveAt: Duration)

/** The one [AwayRecord]; `null` when KE was left with no shell open. */
interface AwayRecords {
    suspend fun read(): AwayRecord?

    suspend fun write(record: AwayRecord?)
}

/** [AwayRecords] in the app's private `device` preferences (device-local, never in a backup),
 *  read and written off the main thread. */
class PreferencesAwayRecords(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AwayRecords {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE) }

    override suspend fun read(): AwayRecord? = withContext(io) {
        if (!preferences.contains(PROFILE) || !preferences.contains(LAST_ACTIVE_MS)) return@withContext null
        AwayRecord(preferences.getLong(PROFILE, 0), preferences.getLong(LAST_ACTIVE_MS, 0).milliseconds)
    }

    override suspend fun write(record: AwayRecord?) {
        withContext(io) {
            val edit = preferences.edit()
            if (record == null) {
                edit.remove(PROFILE).remove(LAST_ACTIVE_MS)
            } else {
                edit.putLong(PROFILE, record.profileId).putLong(LAST_ACTIVE_MS, record.lastActiveAt.inWholeMilliseconds)
            }
            edit.apply()
        }
    }

    private companion object {
        const val PROFILE = "awayProfileId"
        const val LAST_ACTIVE_MS = "awayLastActiveAtMs"
    }
}
