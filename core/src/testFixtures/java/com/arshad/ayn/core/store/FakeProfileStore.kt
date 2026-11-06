package com.arshad.ayn.core.store

import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * In-memory [ProfileStore] for the layers above the store. [rows] is `null` until the test
 * sets it (the profiles haven't loaded), then what [profiles] emits. Edits apply the store's
 * name, cap and owner rules on plain lists; a new id is one past any id seen, never reused (as
 * AUTOINCREMENT). [contentsById] is what [contents] emits, set by the test. A kids profile
 * needs the owner row to have a lock, as the store's does, and starts with Harmful for kids on as
 * the owner's restriction in [filters] when one is given (FPK-D19).
 */
class FakeProfileStore(initial: List<Profile>? = null, private val filters: FakeFilterStore? = null) : ProfileStore {
    val rows: MutableStateFlow<List<Profile>?> = MutableStateFlow(initial)
    val contentsById: MutableStateFlow<Map<Long, ProfileContents>> = MutableStateFlow(emptyMap())
    private var lastId = 0L

    override fun profiles(): Flow<List<Profile>> = rows.filterNotNull()

    override fun contents(): Flow<Map<Long, ProfileContents>> = contentsById

    override suspend fun createOwner(name: String, tint: ProfileTint): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        current().firstOrNull { it.isOwner }?.let { return ProfileEdit.Done(it.id) }
        val owner = Profile(newId(), trimmed, tint, isOwner = true, lock = null)
        rows.value = listOf(owner) + current()
        return ProfileEdit.Done(owner.id)
    }

    override suspend fun create(owner: OwnerPass, name: String, tint: ProfileTint, kids: Boolean): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        if (current().size >= MAX_PROFILES) return ProfileEdit.Full
        if (current().any { it.name.equals(trimmed, ignoreCase = true) }) return ProfileEdit.NameTaken
        if (kids && current().none { it.isOwner && it.lock != null }) return ProfileEdit.OwnerLockNeeded
        val profile = Profile(newId(), trimmed, tint, isOwner = false, lock = null, isKids = kids)
        rows.value = current() + profile
        if (kids && filters != null) {
            filters.rulesById.value += profile.id to ProfileRules(KIDS_START_RESTRICTIONS, kids = true)
        }
        return ProfileEdit.Done(profile.id)
    }

    override suspend fun rename(profileId: Long, name: String): ProfileEdit {
        val trimmed = validProfileName(name) ?: return ProfileEdit.NameInvalid
        if (current().any { it.id != profileId && it.name.equals(trimmed, ignoreCase = true) }) return ProfileEdit.NameTaken
        rows.value = current().map { if (it.id == profileId) it.copy(name = trimmed) else it }
        return ProfileEdit.Done(profileId)
    }

    override suspend fun setTint(profileId: Long, tint: ProfileTint) {
        rows.value = current().map { if (it.id == profileId) it.copy(tint = tint) else it }
    }

    override suspend fun remove(owner: OwnerPass, profileId: Long): Boolean {
        val before = current()
        rows.value = before.filterNot { it.id == profileId && !it.isOwner }
        return rows.value!!.size < before.size
    }

    private fun current(): List<Profile> = rows.value.orEmpty()

    private fun newId(): Long {
        lastId = maxOf(lastId, current().maxOfOrNull { it.id } ?: 0) + 1
        return lastId
    }
}
