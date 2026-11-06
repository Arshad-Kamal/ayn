package com.arshad.ayn.feature.settings

import android.net.Uri
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.SavedBackup
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.profiles.UnlockEntry

/**
 * Settings › Backup & restore, and Import from YouTube (`kp-backup`, § B3p, M5-D14, KP-D17/D24).
 * Export and restore are the owner's ([isOwner]); anyone imports into their own profile. [profiles]
 * are this device's, the owner first, with what each holds. [working]: an export or import runs and
 * the page takes no other action. [toast] shows once, then the UI consumes it.
 */
data class BackupUiState(
    val isOwner: Boolean = false,
    val profiles: List<ProfileHere> = emptyList(),
    val saved: List<SavedBackupRow> = emptyList(),
    val screen: BackupScreen = BackupScreen.Page,
    val working: Boolean = false,
    val toast: BackupToast? = null,
)

data class ProfileHere(val profile: Profile, val contents: ProfileContents)

/** A backup this install saved. [profileCount] once the file has been read; `null` until then and
 *  for a file that can't be read, whose row shows its date only (Q8). */
data class SavedBackupRow(val backup: SavedBackup, val profileCount: Int? = null)

/** What shows over the page. [fileName] is the file's name as listed or as the picker gave it. */
sealed interface BackupScreen {
    data object Page : BackupScreen

    /** Export: "Which profiles?". [chosen] is `null` while All profiles is offered; Choose profiles
     *  ticks every profile here, and [chosen] holds the ticked ids. */
    data class ExportSheet(val chosen: Set<Long>? = null) : BackupScreen

    /** "Backup saved": [profiles] as written; this device's settings went in too. */
    data class Exported(val fileName: String, val profiles: List<BackupProfileSummary>) : BackupScreen

    /** "Backup restored" (or "Not everything was restored") after a Settings restore reopened the
     *  shell ([BackupViewModel.onRestored]). */
    data class Restored(val restored: RestoredBackup) : BackupScreen

    data class Reading(val fileName: String?) : BackupScreen

    /** The file can't be restored; nothing changed. */
    data class Refused(val fileName: String?, val reason: BackupRefusal) : BackupScreen

    /** "Restore which profiles?": a row per profile in the backup, in file order. [choosing] is the
     *  row whose choice sheet is open; [restoring] once Restore was pressed. [deletable]: one of
     *  this install's listed backups, which offers Delete this backup (BKD-D2); [deleteSheet]
     *  its confirm sheet's step. */
    data class RestorePick(
        val fileName: String?,
        val exportedAtMillis: Long,
        val rows: List<RestoreRow>,
        val roomForNew: Int,
        val choosing: Int? = null,
        val restoring: Boolean = false,
        val deletable: Boolean = false,
        val deleteSheet: BackupDelete? = null,
    ) : BackupScreen {
        /** No sheet open and no restore running: the pick's own actions act. */
        val idle: Boolean get() = !restoring && choosing == null && deleteSheet == null

        /** Restore stays off at 0. */
        val restoreCount: Int get() = rows.count { it.choice != RestoreChoice.Skip }

        /** Add as new for row [index] fits in the room the other rows' Add as new leave. */
        fun canAddAsNew(index: Int): Boolean =
            rows[index].choice == RestoreChoice.AddAsNew ||
                rows.filterIndexed { i, row -> i != index && row.choice == RestoreChoice.AddAsNew }.size < roomForNew
    }

    /** An owner-only [action] asks for the owner's [lockKind] lock first; BACK returns to [returnTo]. */
    data class OwnerGate(
        val action: BackupAction,
        val lockKind: LockKind,
        val returnTo: BackupScreen,
        val entry: UnlockEntry = UnlockEntry(),
    ) : BackupScreen

    /** The restore didn't finish; nothing on the device changed. */
    data class RestoreFailed(val fileName: String?) : BackupScreen

    data class ImportRefused(val fileName: String?, val problem: ImportProblem) : BackupScreen
}

/** "Delete this backup?" over the pick: [Asked] while Keep it or Delete backup is chosen, [Running]
 *  once Delete backup was pressed. */
enum class BackupDelete { Asked, Running }

/** A backup profile and its choice: [replaces] is the profile here Replace would swap (KP-D24). */
data class RestoreRow(val profile: BackupProfileSummary, val replaces: Long?, val choice: RestoreChoice)

/** What the owner's pass is asked for: the export sheet, or reading a backup to restore. */
sealed interface BackupAction {
    data object Export : BackupAction

    /** [listed]: one of this install's saved backups, not a picked file. */
    data class Read(val uri: Uri, val fileName: String?, val listed: Boolean = false) : BackupAction
}

enum class ImportProblem {
    /** The file was read but held no channels. */
    NoChannels,
    Unreadable,
    TooLarge,

    /** The channels couldn't be saved; nothing was added. */
    Failed,
}

sealed interface BackupToast {
    data object ExportFailed : BackupToast

    data class Imported(val added: Int, val alreadySubscribed: Int) : BackupToast

    data class Deleted(val fileName: String?) : BackupToast

    data object DeleteFailed : BackupToast
}
