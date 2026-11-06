package com.arshad.ayn.core.store

import android.content.Context
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The kids profile Ayn is pinned to (KP-D9): every route to the picker consults it first, and only
 * the owner's lock clears it. Device-local like [LastUsedProfile] (no backup carries it); an id no
 * profile has any more (removed, or replaced by a restore) matches nothing.
 */
interface KidsPin {
    suspend fun pinned(): Long?

    /** `false` when the pin couldn't be written: the kids profile must not open unpinned. */
    suspend fun pin(profileId: Long): Boolean

    suspend fun unpin(owner: OwnerPass)
}

/** [KidsPin] in the app's private `device` preferences. Written with `commit()`: a pin must be on
 *  disk before the kids profile shows, or a process death could drop it. */
class PreferencesKidsPin(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : KidsPin {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE) }

    override suspend fun pinned(): Long? = withContext(io) {
        if (preferences.contains(KEY)) preferences.getLong(KEY, 0) else null
    }

    override suspend fun pin(profileId: Long): Boolean = withContext(io) { preferences.edit().putLong(KEY, profileId).commit() }

    override suspend fun unpin(owner: OwnerPass) {
        withContext(io) { preferences.edit().remove(KEY).commit() }
    }

    private companion object {
        const val KEY = "pinnedKidsProfileId"
    }
}
