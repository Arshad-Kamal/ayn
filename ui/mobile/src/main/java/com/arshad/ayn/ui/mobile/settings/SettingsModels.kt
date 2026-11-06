package com.arshad.ayn.ui.mobile.settings

import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncViewModel
import kotlinx.coroutines.flow.StateFlow

/** Builds Settings' ViewModels in each page's entry store ([kidsAdd] beside Profiles'); [savedBackups]
 *  counts the backups this phone keeps and [syncStatus] is sync's state (the index's lines). Only the
 *  Backup page builds [backup] and the Sync page [sync], for the opening's profile: the one takes a
 *  waiting file pick, the other asks for that profile's owner lock. [chooseFile] opens the file picker. */
internal class SettingsModels(
    val preferences: () -> PreferencesViewModel,
    val filters: () -> FiltersViewModel,
    val profiles: () -> ProfilesViewModel,
    val kidsAdd: () -> KidsAddViewModel,
    val restrictions: () -> RestrictionsViewModel,
    val backup: (profileId: Long) -> BackupViewModel,
    val sync: (profileId: Long) -> SyncViewModel,
    val savedBackups: suspend () -> Int,
    val syncStatus: StateFlow<SyncStatus>,
    val chooseFile: (PickRequest) -> Unit,
)
