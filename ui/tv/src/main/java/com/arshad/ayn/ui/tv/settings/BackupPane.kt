package com.arshad.ayn.ui.tv.settings

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.arshad.ayn.feature.settings.BACKUP_SUB
import com.arshad.ayn.feature.settings.BackupUiState
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.EXPORT_HINT
import com.arshad.ayn.feature.settings.IMPORT_SUB
import com.arshad.ayn.feature.settings.savedHint
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.FocusSlot
import com.arshad.ayn.ui.tv.focus.focusMemory

// Focus keys of the Backup & restore and Import pages' rows (ScreenFocus memory).
internal const val EXPORT_KEY = "export"
private const val FIND_BACKUP_KEY = "find-backup"
private const val IMPORT_KEY = "import"

private fun savedKey(uri: Uri): String = "saved-$uri"

// The saved backups' slots, Find a backup… last among them: focus coming back after a delete
// lands on the next backup, or on Find a backup… when the deleted one was the last.
private const val SAVED_GROUP = "saved"

/**
 * Settings › Backup & restore, the owner's (`kp-backup` backup; `b3-profiles` exportfail):
 * Export a backup (Which profiles?), this install's backups (each with its profile count once read, Q8), Find a
 * backup… in the file picker ([onFindBackup]), and the import row ([onImport]).
 */
@Composable
internal fun BackupPane(state: BackupUiState, backup: BackupViewModel, onFindBackup: () -> Unit, onImport: () -> Unit) {
    SettingsPane("Backup & restore", BACKUP_SUB) {
        SettingRow(
            label = "Export a backup",
            hint = EXPORT_HINT,
            chevron = true,
            onClick = backup::onExport,
            modifier = Modifier.focusMemory(EXPORT_KEY),
            lead = { RowIcon(R.drawable.ic_upload, it) },
        )
        PaneSubHeading("Restore")
        state.saved.forEachIndexed { index, row ->
            key(row.backup.uri) {
                SettingRow(
                    label = row.backup.fileName,
                    hint = savedHint(row.profileCount, monthDay(row.backup.savedAtMillis)),
                    chevron = true,
                    onClick = { backup.onSavedBackup(row) },
                    modifier = Modifier.focusMemory(savedKey(row.backup.uri), FocusSlot(SAVED_GROUP, index)),
                    lead = { RowIcon(R.drawable.ic_download, it) },
                )
            }
        }
        SettingRow(
            label = "Find a backup…",
            hint = "From another device or after a reinstall (file picker)",
            chevron = true,
            onClick = onFindBackup,
            modifier = Modifier.focusMemory(FIND_BACKUP_KEY, FocusSlot(SAVED_GROUP, state.saved.size)),
            lead = { RowIcon(R.drawable.ic_nav_search, it) },
        )
        ImportRow("The subscriptions.csv file from Google Takeout, into your profile", onImport)
    }
}

/** Settings › Import from YouTube, for anyone but the owner (`b3-profiles` importpage, imported). */
@Composable
internal fun ImportPane(onImport: () -> Unit) {
    SettingsPane("Import from YouTube", IMPORT_SUB) {
        ImportRow("The subscriptions.csv file from Google Takeout, or a NewPipe export (file picker)", onImport)
    }
}

/** Import subscriptions from YouTube: a Takeout or NewPipe file from the file picker. */
@Composable
private fun ImportRow(hint: String, onImport: () -> Unit) {
    SettingRow(
        label = "Import subscriptions from YouTube",
        hint = hint,
        chevron = true,
        onClick = onImport,
        modifier = Modifier.focusMemory(IMPORT_KEY),
        lead = { RowIcon(R.drawable.ic_nav_subscriptions, it) },
    )
}
