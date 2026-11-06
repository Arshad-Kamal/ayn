package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ProfileStore] over Room. Checks and writes share one write transaction, so two edits can't
 * both pass a name or count check; the unique indexes back them up. A removed profile leaves a
 * removal record for sync (M8-D6).
 */
class ProfileRepository(
    private val database: KeDatabase,
    private val clock: Clock = Clock.System,
) : ProfileStore {
    private val dao: ProfileDao = database.profileDao()

    override fun profiles(): Flow<List<Profile>> = dao.profiles().map { rows -> rows.map(ProfileEntity::toProfile) }

    override fun contents(): Flow<Map<Long, ProfileContents>> = dao.contents().map { rows ->
        rows.associate { row ->
            row.profileId to ProfileContents(
                subscriptionCount = row.subscriptionCount,
                playlistCount = row.playlistCount,
                historyCount = row.historyCount,
            )
        }
    }

    override suspend fun createOwner(name: String, tint: ProfileTint): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        return database.withWriteTransaction {
            val existing = dao.owner()
            if (existing != null) return@withWriteTransaction ProfileEdit.Done(existing.id)
            val now = clock.now().toEpochMilliseconds()
            val id = dao.insert(
                ProfileEntity(name = trimmed, tint = tint.key, ownerMark = OWNER_MARK, createdAtMillis = now, updatedAtMillis = now),
            )
            ProfileEdit.Done(id)
        }
    }

    override suspend fun create(owner: OwnerPass, name: String, tint: ProfileTint, kids: Boolean): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        return database.withWriteTransaction {
            when {
                dao.count() >= MAX_PROFILES -> ProfileEdit.Full
                dao.nameTaken(trimmed, exceptId = NO_ID) -> ProfileEdit.NameTaken
                kids && !dao.ownerLocked() -> ProfileEdit.OwnerLockNeeded
                else -> {
                    val now = clock.now().toEpochMilliseconds()
                    val id = dao.insert(
                        ProfileEntity(
                            name = trimmed,
                            tint = tint.key,
                            ownerMark = null,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            kids = kids,
                        ),
                    )
                    if (kids) database.filterDao().upsertRestrictions(KIDS_START_RESTRICTIONS.toEntity(id, now))
                    ProfileEdit.Done(id)
                }
            }
        }
    }

    override suspend fun rename(profileId: Long, name: String): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        return database.withWriteTransaction {
            if (dao.nameTaken(trimmed, exceptId = profileId)) {
                ProfileEdit.NameTaken
            } else {
                dao.rename(profileId, trimmed, clock.now().toEpochMilliseconds())
                ProfileEdit.Done(profileId)
            }
        }
    }

    override suspend fun setTint(profileId: Long, tint: ProfileTint) {
        dao.setTint(profileId, tint.key, clock.now().toEpochMilliseconds())
    }

    override suspend fun remove(owner: OwnerPass, profileId: Long): Boolean = database.withWriteTransaction {
        database.removalDao().profile(profileId, clock.now().toEpochMilliseconds())
        dao.removeNonOwner(profileId) > 0
    }
}

private const val NO_ID = -1L

/** A new kids profile starts with Harmful for kids on as the owner's restriction (FPK-D1, D19). */
internal val KIDS_START_RESTRICTIONS = ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true))

/** [raw] trimmed, or `null` when that is blank or longer than [MAX_PROFILE_NAME]. */
internal fun validProfileName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_PROFILE_NAME }

private fun ProfileEntity.toProfile(): Profile = Profile(
    id = id,
    name = name,
    tint = ProfileTint.of(tint),
    isOwner = ownerMark != null,
    lock = lockHash?.let { LockKind.of(lockKind) },
    isKids = kids,
)
