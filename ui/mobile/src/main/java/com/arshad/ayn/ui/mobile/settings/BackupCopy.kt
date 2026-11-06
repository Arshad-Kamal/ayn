package com.arshad.ayn.ui.mobile.settings

import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.feature.settings.BackupAction
import com.arshad.ayn.feature.settings.BackupToast
import com.arshad.ayn.feature.settings.NOT_A_BACKUP_IN_SETTINGS
import com.arshad.ayn.feature.settings.backupOwnerOnly
import com.arshad.ayn.feature.settings.importedText
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.mobile.common.NoticeLine
import com.arshad.ayn.ui.mobile.profiles.refusalCopy

// The phone's Backup & restore, Import and first launch's restore results copy (`m6-settings` 37–47);
// text both UIs show: feature/settings/BackupText.kt.

/** A file the picker gave no name for. */
internal const val CHOSEN_FILE = "The chosen file"

/** The device section's settings on a phone (KP-D17). */
internal const val PHONE_SETTINGS = "quality cap, background audio, clock and Hero on Home"

/** The lead of every line about the device section, in bold. */
internal const val PHONE_LEAD = "This phone’s settings"

internal const val OWNER_IMPORT_HINT = "The subscriptions.csv file from Google Takeout, into your profile"
internal const val IMPORT_HINT = "The subscriptions.csv file from Google Takeout, or a NewPipe export (file picker)"

internal const val EXPORT_NOTE = "This phone’s settings go in every backup. Locks never do."
internal const val EXPORTED_PHONE_LINE = "are in the file too: $PHONE_SETTINGS."
internal const val PICK_PHONE_LINE = "in the backup aren’t used: they’re restored only on a phone with no profiles yet."

/** Under "Delete this backup?"'s buttons. */
internal const val DELETE_NOTE = "Other backups and the profiles on this phone stay as they are."

internal fun backupGateHint(action: BackupAction, kind: LockKind): String = "${backupOwnerOnly(action)} · ${erasesHint(kind)} · Back cancels"

/** Add as new: [replaces] says a profile of that name is here; with no [room] it's unavailable. */
internal fun addAsNewHint(name: String, replaces: Boolean, room: Boolean): String = when {
    !room -> "No room: this phone would have ${MAX_PROFILES + 1} profiles, and $MAX_PROFILES is the most"
    replaces -> "A second $name beside the one here, unlocked"
    else -> "$name joins this phone, unlocked"
}

/** Under a row's choices: the lock a replaced profile keeps; `null` when it has none. */
internal fun choiceNote(name: String, lock: LockKind?): String? = when (lock) {
    LockKind.Sequence -> "$name’s sequence on this phone stays either way."
    LockKind.Pin -> "$name’s PIN on this phone stays either way."
    null -> null
}

/** After a restore: this phone's settings applied (first launch with every profile) or left as they were. */
internal fun restoredPhoneLine(result: RestoreResult.Restored, firstLaunch: Boolean): String = when {
    result.deviceSettings -> "restored: $PHONE_SETTINGS."
    firstLaunch -> "start at their defaults: a backup’s come back only with every profile."
    result.partial -> "stayed as they are."
    else -> "stayed as they are: a backup’s are restored only on a phone with no profiles yet."
}

/** First launch's Choose profiles, under the list: this phone's settings come back only with every profile. */
internal fun firstPickPhoneLine(leftOut: List<String>): String =
    if (leftOut.isEmpty()) {
        "come back too: $PHONE_SETTINGS."
    } else {
        "come back only with every profile. With ${spokenList(leftOut)} left out, they start at their defaults."
    }

/** First launch's Choose profiles: each chosen profile is added, the owner always. */
internal fun firstPickLede(ownerName: String): String =
    "Nothing is on this phone yet, so each profile you choose is added. $ownerName, the backup’s owner, always comes back as the owner."

/** Why a file picked in Settings can't be restored: first launch's copy, except that a file that
 *  isn't a backup points at the import row (the profile exists by now). */
internal fun settingsRefusalCopy(reason: BackupRefusal): Pair<String, String> =
    if (reason == BackupRefusal.NotABackup) {
        refusalCopy(reason).first to NOT_A_BACKUP_IN_SETTINGS
    } else {
        refusalCopy(reason)
    }

internal const val RESTORE_FAILED_TITLE = "The restore didn’t finish"
internal const val RESTORE_FAILED_LINE = "Nothing on this phone changed. Try again, or choose another file."

internal fun backupToastLine(toast: BackupToast): NoticeLine = when (toast) {
    BackupToast.ExportFailed -> NoticeLine("The backup wasn’t saved", "nothing changed; try again", action = null, warn = true)
    is BackupToast.Imported -> importedText(toast.added, toast.alreadySubscribed).let { (text, muted) -> NoticeLine(text, muted, action = null) }
    is BackupToast.Deleted -> NoticeLine("Deleted", toast.fileName, action = null)
    BackupToast.DeleteFailed -> NoticeLine("Couldn’t delete the backup", null, action = null, warn = true)
}
