package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.feature.settings.BackupAction
import com.arshad.ayn.feature.settings.BackupToast
import com.arshad.ayn.feature.settings.NOT_A_BACKUP_IN_SETTINGS
import com.arshad.ayn.feature.settings.backupOwnerOnly
import com.arshad.ayn.feature.settings.importedText
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.ToastLine
import com.arshad.ayn.ui.tv.profiles.refusalCopy

// The TV's Backup & restore, first launch's restore and Import from YouTube copy (`kp-backup` v1, `b3-profiles` v8);
// shared text: feature/settings/BackupText.kt.

/** The device section's settings (KP-D17). */
internal const val DEVICE_SETTINGS = "interface size, quality cap, frame-rate matching, clock and Hero on Home"

/** The lead of every line about the device section, in bold. */
internal const val DEVICE_LEAD = "This Shield’s settings"

/** The owner gate's line: only the owner does [action], then whose lock it asks for. */
internal fun backupGateLine(action: BackupAction, ownerName: String, kind: LockKind): String = "${backupOwnerOnly(action)}. ${enterLock(ownerName, kind)}"

internal const val PICK_DEVICE_LINE = "in the backup aren’t used: they’re restored only on a Shield with no profiles yet."

/** Under "Delete this backup?"'s choices. */
internal const val DELETE_NOTE = "Other backups and the profiles on this Shield stay as they are."

/** Add as new: [replaces] says a profile of that name is here; with no [room] it's unavailable. */
internal fun addAsNewHint(name: String, replaces: Boolean, room: Boolean): String = when {
    !room -> "No room: this Shield would have ${MAX_PROFILES + 1} profiles, and $MAX_PROFILES is the most"
    replaces -> "A second $name beside the one here, unlocked"
    else -> "$name joins this Shield, unlocked"
}

/** Under the choices: the lock a replaced profile keeps; `null` when it has none. */
internal fun choiceNote(name: String, lock: LockKind?): String? = when (lock) {
    LockKind.Sequence -> "$name’s D-pad sequence on this Shield stays either way."
    LockKind.Pin -> "$name’s PIN on this Shield stays either way."
    null -> null
}

/** First launch's "Restore this backup?": who comes back with what, who owns the Shield after. */
internal fun firstRestoreText(profiles: List<BackupProfileSummary>, deviceSettings: Boolean): String {
    val names = spokenList(profiles.map { it.name })
    val one = profiles.size == 1
    val keeps = if (one) "everything it keeps" else "everything each keeps"
    val device = if (deviceSettings) ", and $DEVICE_SETTINGS_SAID" else ""
    val owner = profiles.firstOrNull { it.owner }?.let { " ${it.name} becomes the owner." }.orEmpty()
    val unlocked = if (one) "it arrives unlocked" else "every profile arrives unlocked"
    return "It brings back ${plural(profiles.size, "profile")} ($names) with $keeps$device.$owner Locks aren’t in backups, so $unlocked."
}

private const val DEVICE_SETTINGS_SAID = "this Shield’s settings: $DEVICE_SETTINGS"

/** First launch's Choose profiles: each chosen profile is added, the owner always. */
internal fun firstPickLede(ownerName: String): String =
    "Nothing is on this Shield yet, so each profile you choose is added. $ownerName, the backup’s owner, always comes back as the owner."

/** First launch's Choose profiles, under the list: the device section comes back only with every profile. */
internal fun firstPickDeviceLine(leftOut: List<String>): String =
    if (leftOut.isEmpty()) {
        "come back too: $DEVICE_SETTINGS."
    } else {
        "come back only with every profile. With ${spokenList(leftOut)} left out, they start at their defaults."
    }

/** Why a picked file can't start this Shield: it holds no owner (Q18). */
internal const val NO_OWNER_TITLE = "This backup has no owner profile"
internal const val NO_OWNER_BODY = "Create your profile, then restore it from Settings."

internal const val EXPORTED_DEVICE_LINE = "are in the file too: $DEVICE_SETTINGS."

/** The device line after a restore: applied (first launch with every profile) or left as it was. */
internal fun restoredDeviceLine(result: RestoreResult.Restored, firstLaunch: Boolean): String = when {
    result.deviceSettings -> "restored: $DEVICE_SETTINGS."
    firstLaunch -> "start at their defaults: a backup’s come back only with every profile."
    result.partial -> "stayed as they are."
    else -> "stayed as they are: a backup’s are restored only on a Shield with no profiles yet."
}

/** Why a file picked in Settings can't be restored: first launch's copy, except that a file that
 *  isn't a backup points at the import row (the profile exists by now). */
internal fun settingsRefusalCopy(reason: BackupRefusal): Pair<String, String> =
    if (reason == BackupRefusal.NotABackup) {
        refusalCopy(reason).first to NOT_A_BACKUP_IN_SETTINGS
    } else {
        refusalCopy(reason)
    }

internal fun BackupToast.line(): ToastLine = when (this) {
    BackupToast.ExportFailed -> ToastLine(R.drawable.ic_alert, "The backup wasn’t saved", "nothing changed; try again", warn = true)
    is BackupToast.Imported -> importedText(added, alreadySubscribed).let { (text, muted) -> ToastLine(R.drawable.ic_check, text, muted) }
    is BackupToast.Deleted -> ToastLine(R.drawable.ic_check, "Deleted", fileName)
    BackupToast.DeleteFailed -> ToastLine(R.drawable.ic_alert, "Couldn’t delete the backup", null, warn = true)
}
