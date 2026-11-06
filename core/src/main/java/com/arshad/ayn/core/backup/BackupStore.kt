package com.arshad.ayn.core.backup

import android.net.Uri
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.ProfileTint

/**
 * Backup & restore and subscription imports (spec D4 Phase 1, M5-D4/D5/D6/D14, KP-D17/D24).
 * Feature/UI code programs against this interface, never the database or files. Export, restore
 * and deleting a backup are the owner's (§ B3p), so they take an [OwnerPass] (M5-D15).
 */
interface BackupStore {
    /** Writes the profiles [profileIds] names (every profile when `null`), their data and this
     *  device's settings to Downloads as `Ayn-backup-<date>.json`, published only once the written
     *  file reads back as a valid backup. */
    suspend fun export(owner: OwnerPass, profileIds: Set<Long>? = null): ExportResult

    /** The backups this install wrote to Downloads, newest first. Android lists no others
     *  without the file picker (scoped storage). */
    suspend fun savedBackups(): List<SavedBackup>

    /** Reads and checks a backup file, one of [savedBackups] or a picked one. */
    suspend fun open(uri: Uri): BackupRead

    /** Deletes one of [savedBackups] from Downloads (BKD-D2: never another file); false when it
     *  isn't one or Android refused. */
    suspend fun deleteBackup(owner: OwnerPass, uri: Uri): Boolean

    /** How [backup]'s profiles can restore on this device (KP-D24); `null` when the device
     *  can't be read. */
    suspend fun restorePlan(backup: ValidBackup): RestorePlan?

    /**
     * Restores [backup]'s profiles as [choices] says, one per profile in [ValidBackup.profiles]
     * order (KP-D24): Replace keeps the profile's id and lock here, Add as new arrives unlocked.
     * On a device with no owner yet every chosen profile is added, the backup's owner (which
     * must be chosen) as the owner and the current profile, and the backup's device settings
     * apply when every profile is chosen. A failure, or choices the plan doesn't allow, changes
     * nothing. The caller saves and stops playback first (M5-D3).
     */
    suspend fun restore(owner: OwnerPass, backup: ValidBackup, choices: List<RestoreChoice>): RestoreResult

    /** Adds the channels in a Google Takeout `subscriptions.csv` or a NewPipe subscriptions
     *  export to [profileId]'s subscriptions (the profile that asked), even when another profile
     *  is current by the time it writes; a channel already subscribed keeps its date. A kids
     *  profile's channels are approved one by one by the owner, so an import into one is
     *  [ImportResult.Failed] (KP-D10, D11). */
    suspend fun importSubscriptions(uri: Uri, profileId: Long): ImportResult
}

sealed interface ExportResult {
    /** [fileName] is the name Android gave the file in Downloads (it adds " (1)" to a taken one);
     *  [profiles] what went in, as written. */
    data class Saved(val fileName: String, val profiles: List<BackupProfileSummary>) : ExportResult

    data object Failed : ExportResult
}

data class SavedBackup(val uri: Uri, val fileName: String, val savedAtMillis: Long)

sealed interface BackupRead {
    data class Valid(val backup: ValidBackup) : BackupRead

    data class Refused(val reason: BackupRefusal) : BackupRead
}

enum class BackupRefusal {
    /** The file couldn't be opened or read. */
    Unreadable,

    /** Larger than any backup KE would write. */
    TooLarge,

    /** Not readable as a KE backup: another app's export, a Takeout file, or a file cut short
     *  (its marker can't be read until the whole file parses). */
    NotABackup,

    /** Made by a newer KE than this one. */
    NewerVersion,

    /** A complete KE backup whose contents don't hold together (edited, or impossible values). */
    Damaged,
}

/** A backup that passed every check; only [BackupStore.open] makes one. */
class ValidBackup internal constructor(internal val bundle: BackupBundle) {
    val exportedAtMillis: Long get() = bundle.exportedAtMillis

    /** The backup's profiles in file order (the owner, when it holds one, first). */
    val profiles: List<BackupProfileSummary> by lazy { bundle.profiles.map(BackupProfile::summary) }

    /** `null` when the export left the owner out (KP-D24). */
    val ownerName: String? get() = bundle.profiles.firstOrNull { it.owner }?.name

    /** Whether it carries a device's settings (every backup since version 4 does). */
    val hasDeviceSettings: Boolean get() = bundle.device != null
}

/** A profile in a backup, or one just written to one: what it holds. */
data class BackupProfileSummary(
    val name: String,
    val tint: ProfileTint,
    val owner: Boolean,
    val kids: Boolean,
    val counts: ProfileCounts,
)

/** What a profile holds, as the backup results show it (KP-D17). [hidden]: For you's hidden
 *  videos and channels plus blocked videos and channels; [approvedVideos]: a kids profile's
 *  approved videos (its approved channels are its [subscriptions]). */
data class ProfileCounts(
    val subscriptions: Int,
    val history: Int,
    val playlists: Int,
    val playlistVideos: Int,
    val hidden: Int,
    val approvedVideos: Int,
)

/** How a backup can restore here. [firstLaunch]: the device has no owner yet, so every chosen
 *  profile is added and the backup's owner must be chosen; [roomForNew] profiles can still be
 *  added; one entry per backup profile, in order. */
data class RestorePlan(val firstLaunch: Boolean, val roomForNew: Int, val profiles: List<PlannedProfile>)

/** [replaces]: the profile here of the same name and kind, which Replace would swap; `null` when
 *  none (or at first launch). [defaultChoice] is Replace when it can, else Add as new. */
data class PlannedProfile(val summary: BackupProfileSummary, val replaces: Long?) {
    val defaultChoice: RestoreChoice get() = replaces?.let(RestoreChoice::Replace) ?: RestoreChoice.AddAsNew
}

sealed interface RestoreChoice {
    data class Replace(val profileId: Long) : RestoreChoice

    data object AddAsNew : RestoreChoice

    data object Skip : RestoreChoice
}

sealed interface RestoreResult {
    /**
     * [ownerId] is the device's owner after the restore (the backup's at first launch); [profiles]
     * the restored ones in backup order; [deviceSettings] whether the backup's device settings
     * were applied, with [deviceSkips] the values among them this build couldn't apply.
     */
    data class Restored(
        val ownerId: Long,
        val profiles: List<RestoredProfile> = emptyList(),
        val deviceSettings: Boolean = false,
        val deviceSkips: List<SettingSkip> = emptyList(),
    ) : RestoreResult {
        /** "Not everything was restored" (KP-D17). */
        val partial: Boolean get() = deviceSkips.isNotEmpty() || profiles.any { it.skips.isNotEmpty() }
    }

    data object Failed : RestoreResult
}

/** A restored profile, as written: [settingsApplied] of [settingsTotal] setting values took the
 *  backup's value; [skips] names the rest and the owner restriction values skipped. */
data class RestoredProfile(
    val profileId: Long,
    val name: String,
    val replaced: Boolean,
    val counts: ProfileCounts,
    val settingsApplied: Int,
    val settingsTotal: Int,
    val skips: List<SettingSkip>,
)

/**
 * A value in a backup this build can't apply (KP-D17): [key] is its place in the backup
 * (`speedPercent`, `segmentActions.hook`, `restrictions.dailyLimitMinutes.monday`, `device.maxVideoHeight`),
 * [backupValue] as the file holds it, [keptValue] what the setting kept (the replaced profile's or
 * device's value, else the default, as its stored key); `null` when nothing holds that key here.
 */
data class SettingSkip(val key: String, val backupValue: String, val keptValue: String?)

sealed interface ImportResult {
    /** [added] channels are new to the profile; [alreadySubscribed] were there before. Both 0
     *  when the file held no channels it could read. */
    data class Imported(val added: Int, val alreadySubscribed: Int) : ImportResult

    data object Unreadable : ImportResult

    data object TooLarge : ImportResult

    /** The channels couldn't be saved, or the profile is a kids profile; nothing was added. */
    data object Failed : ImportResult
}
