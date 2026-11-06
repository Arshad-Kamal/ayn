package com.arshad.ayn.core.store

import android.content.Context
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The videos the owner kept in a kids profile's parent review (F12, Q17): a kept video leaves the
 * review's new uploads. Device-local per kids profile like [KidsPin] (no backup carries it); a
 * keep is no longer read after [KEPT_DAYS], long after its upload has left the review's week, and
 * goes at the kid's next keep. A removed kid's keeps stay (profile ids are never reused).
 */
interface KidsReviewKeeps {
    /** The video ids kept for [profileId] in the last [KEPT_DAYS] days. */
    suspend fun kept(profileId: Long): Set<String>

    /** `false` when the keep couldn't be written. */
    suspend fun keep(owner: OwnerPass, profileId: Long, videoId: String): Boolean
}

/** [KidsReviewKeeps] in the app's private `device` preferences: one string set per kids profile,
 *  each entry `<kept at millis>:<video id>`, pruned on each keep. */
class PreferencesKidsReviewKeeps(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : KidsReviewKeeps {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE) }

    override suspend fun kept(profileId: Long): Set<String> = withContext(io) { fresh(profileId).keys }

    override suspend fun keep(owner: OwnerPass, profileId: Long, videoId: String): Boolean = withContext(io) {
        val entries = fresh(profileId) + (videoId to nowMillis())
        val stored = entries.map { (id, at) -> "$at$SEPARATOR$id" }.toSet()
        preferences.edit().putStringSet(key(profileId), stored).commit()
    }

    /** Video id → kept at, leaving out keeps older than [KEPT_DAYS] and entries this build can't read. */
    private fun fresh(profileId: Long): Map<String, Long> {
        val oldest = nowMillis() - KEPT_DAYS * MILLIS_PER_DAY
        return preferences.getStringSet(key(profileId), null).orEmpty()
            .mapNotNull { entry ->
                val at = entry.substringBefore(SEPARATOR).toLongOrNull()
                val id = entry.substringAfter(SEPARATOR, missingDelimiterValue = "")
                if (at == null || at < oldest || id.isEmpty()) null else id to at
            }
            .toMap()
    }

    private companion object {
        const val SEPARATOR = ':'
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        fun key(profileId: Long): String = "reviewKept.$profileId"
    }
}
