package com.arshad.ayn.core.store

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow

/**
 * In-memory [ProfileSettingsStore] for the layers above the store: [selected] is the session
 * profile (`null` until the test opens one, so [settings] waits); a profile with nothing in
 * [byProfile] has the defaults, as a missing row does. [failWrites] makes [update] change
 * nothing and return `false` (a removed profile's foreign key); [failReads], set before a read
 * starts, makes that read fail (a database that can't be read).
 */
class FakeProfileSettingsStore(selectedId: Long? = null) : ProfileSettingsStore {
    val selected: MutableStateFlow<Long?> = MutableStateFlow(selectedId)
    val byProfile: MutableStateFlow<Map<Long, ProfileSettings>> = MutableStateFlow(emptyMap())
    var failWrites: Boolean = false
    var failReads: Boolean = false

    /** The profile ids [update] was called with, in order. */
    val updatedIds: MutableList<Long> = mutableListOf()

    override fun settings(): Flow<ProfileSettingsState> =
        if (failReads) {
            flow { throw IOException("settings unreadable") }
        } else {
            combine(selected.filterNotNull(), byProfile) { id, rows -> ProfileSettingsState(id, rows[id] ?: ProfileSettings()) }
                .distinctUntilChanged()
        }

    override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean {
        updatedIds += profileId
        if (failWrites) return false
        val rows = byProfile.value
        byProfile.value = rows + (profileId to transform(rows[profileId] ?: ProfileSettings()))
        return true
    }

    fun of(profileId: Long): ProfileSettings = byProfile.value[profileId] ?: ProfileSettings()
}

/** In-memory [DeviceSettingsStore]: [current] is what [settings] emits, `null` until it loads. */
class FakeDeviceSettingsStore(initial: DeviceSettings? = DeviceSettings()) : DeviceSettingsStore {
    val current: MutableStateFlow<DeviceSettings?> = MutableStateFlow(initial)

    override fun settings(): Flow<DeviceSettings> = current.filterNotNull()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) {
        current.value = transform(current.value ?: DeviceSettings())
    }
}
