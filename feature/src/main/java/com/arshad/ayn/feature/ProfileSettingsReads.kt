package com.arshad.ayn.feature

import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.feature.library.orFailed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The session profile's settings, ending in `null` (logged) when they can't be read. */
fun ProfileSettingsStore.settingsOrNull(): Flow<ProfileSettingsState?> =
    settings().map<ProfileSettingsState, ProfileSettingsState?> { it }.orFailed("profile settings read", null)

/** The session profile's Show Shorts (M7-D13): off until read, and off when unreadable. */
fun ProfileSettingsState?.showsShorts(): Boolean = this?.settings?.showShorts == true

/** [showsShorts] as it changes. */
fun ProfileSettingsStore.showsShorts(): Flow<Boolean> = settingsOrNull().map { it.showsShorts() }.distinctUntilChanged()

/** The session profile's For you switch now (FY-D10): off when its settings can't be read. */
suspend fun ProfileSettingsStore.forYouOn(): Boolean = settingsOrNull().first()?.settings?.forYou == true
