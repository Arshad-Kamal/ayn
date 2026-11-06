package com.arshad.ayn.core.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The profile KE is being used as (M5-D3). Per-profile stores read and write through it, so
 * their interfaces don't change. Until a profile is selected, reads wait (screens stay in
 * their loading state rather than flash an empty one) and so do writes; the launch gate selects
 * one before any screen shows.
 */
class ProfileSession {
    private val selected = MutableStateFlow<Long?>(null)

    /** The selected profile's id and every later selection, waiting for the first. */
    val selectedId: Flow<Long> = selected.filterNotNull()

    /** The selected profile's id now, `null` before the first selection. */
    val currentIdOrNull: Long? get() = selected.value

    fun select(profileId: Long) {
        selected.value = profileId
    }

    /** The selected profile's id, waiting for the first selection. */
    suspend fun currentId(): Long = selectedId.first()
}
