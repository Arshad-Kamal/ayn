package com.arshad.ayn.core.store

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The profile last opened on this device, where the picker starts (§ B3p). A device-local
 * value (M5-D16): no profile owns it and backups don't carry it; an id no profile has any more
 * (removed, or replaced by a restore) just matches nothing.
 */
interface LastUsedProfile {
    suspend fun get(): Long?

    suspend fun set(profileId: Long)
}

/** [LastUsedProfile] in the app's private `SharedPreferences`, read and written off the main
 *  thread (the first read loads the file). */
class PreferencesLastUsedProfile(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LastUsedProfile {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE) }

    override suspend fun get(): Long? = withContext(io) {
        if (preferences.contains(KEY)) preferences.getLong(KEY, 0) else null
    }

    override suspend fun set(profileId: Long) {
        withContext(io) { preferences.edit().putLong(KEY, profileId).apply() }
    }

    private companion object {
        const val KEY = "lastUsedProfileId"
    }
}

/** The app's private preferences for device-local values no profile owns and no backup carries. */
internal const val DEVICE_PREFERENCES = "device"
