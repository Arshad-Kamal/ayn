package com.arshad.ayn.core.store

import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/** [ProfileSettingsStore] over Room for the [session]'s profile; a profile switch re-emits. A
 *  profile without a stored row reads, and its first write starts from, this device's [defaults]
 *  (a phone shows Shorts, a TV hides them: M6-D6). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSettingsRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val clock: Clock = Clock.System,
    private val defaults: ProfileSettings = ProfileSettings(),
) : ProfileSettingsStore {
    private val dao: ProfileSettingsDao = database.profileSettingsDao()

    override fun settings(): Flow<ProfileSettingsState> =
        session.selectedId.flatMapLatest { profileId ->
            // Room re-emits on any write to the table, other profiles' included.
            combine(dao.settings(profileId), database.filterDao().kids(profileId)) { row, kids ->
                ProfileSettingsState(profileId, row?.toSettings() ?: defaults, kids = kids == true)
            }.distinctUntilChanged()
        }

    override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean =
        try {
            database.withWriteTransaction {
                val current = dao.row(profileId)?.toSettings() ?: defaults
                dao.upsert(transform(current).toEntity(profileId, clock.now().toEpochMilliseconds()))
            }
            true
        } catch (e: SQLiteException) {
            Log.w(LOG_TAG, "settings write failed: ${e.javaClass.simpleName}")
            false
        }

    private companion object {
        const val LOG_TAG = "KeSettings"
    }
}
