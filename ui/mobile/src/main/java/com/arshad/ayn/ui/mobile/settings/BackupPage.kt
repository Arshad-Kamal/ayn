package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.settings.BACKUP_SUB
import com.arshad.ayn.feature.settings.BackupScreen
import com.arshad.ayn.feature.settings.BackupUiState
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.EXPORT_HINT
import com.arshad.ayn.feature.settings.IMPORT_SUB
import com.arshad.ayn.feature.settings.PROFILE_CARRIES
import com.arshad.ayn.feature.settings.exportBrief
import com.arshad.ayn.feature.settings.exportChosenLabel
import com.arshad.ayn.feature.settings.savedHint
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.menu.RowCheck
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.profiles.PlateTile
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.shell.SettingsPage

/**
 * Settings › Backup & restore, the owner's (`m6-settings` 37): Export a backup, this phone's
 * backups (each with its profile count once read), Find a backup… and the import row; anyone else
 * gets Import from YouTube (40). The file picker opens through [chooseFile] for [me], and its pick
 * comes back through [backup]. What an import added shows as a snackbar; the export sheet slides
 * over the page; the owner's lock, a file's restore and the results cover the shell. A shell a
 * Settings restore reopened shows its results ([restored]) here first, then reports them shown.
 */
@Composable
internal fun BackupPage(
    me: Profile,
    backup: BackupViewModel,
    chooseFile: (PickRequest) -> Unit,
    restored: () -> RestoredBackup?,
    onRestoredShown: () -> Unit,
    onBack: () -> Unit,
) {
    val state by backup.uiState.collectAsStateWithLifecycle()
    val shown by rememberUpdatedState(onRestoredShown)
    LaunchedEffect(backup) {
        val results = restored() ?: return@LaunchedEffect
        backup.onRestored(results)
        shown()
    }
    val chooseBackup = remember(me.id, chooseFile) { { chooseFile(PickRequest(PickKind.Backup, me.id)) } }
    val chooseImport = remember(me.id, chooseFile) { { chooseFile(PickRequest(PickKind.Import, me.id)) } }
    Box(Modifier.fillMaxSize()) {
        SettingsFrame(SettingsPage.Backup.title(owner = me.isOwner), onBack) {
            if (me.isOwner) ownersRows(state, backup, chooseBackup, chooseImport) else importRows(me, chooseImport)
        }
        NoticeSnackbar(state.toast, ::backupToastLine, onClose = backup::onToastShown, modifier = Modifier.align(Alignment.BottomCenter))
    }
    StageSheet(state.screen as? BackupScreen.ExportSheet, onDismiss = backup::onDismiss, onBack = backup::onBack, stageKey = { Unit }) { sheet ->
        ExportSheet(sheet, state, backup)
    }
    BackupCover(state, me, backup, chooseBackup, chooseImport)
}

private fun LazyListScope.ownersRows(state: BackupUiState, backup: BackupViewModel, onFindBackup: () -> Unit, onImport: () -> Unit) {
    item(key = "lede") { PageLede(BACKUP_SUB) }
    item(key = "export") {
        SettingRow("Export a backup", backup::onExport, hint = EXPORT_HINT, lead = { RowIcon(R.drawable.ic_upload) })
    }
    item(key = "restore") { SubHeading("Restore") }
    items(state.saved, key = { it.backup.uri.toString() }) { row ->
        SettingRow(
            row.backup.fileName,
            onClick = { backup.onSavedBackup(row) },
            hint = savedHint(row.profileCount, monthDay(row.backup.savedAtMillis)),
            lead = { RowIcon(R.drawable.ic_download) },
        )
    }
    item(key = "find") {
        SettingRow(
            "Find a backup…",
            onFindBackup,
            hint = "From another device or after a reinstall (file picker)",
            chevron = false,
            lead = { RowIcon(R.drawable.ic_nav_search) },
        )
    }
    item(key = "import") { ImportRow(OWNER_IMPORT_HINT, onImport) }
}

private fun LazyListScope.importRows(me: Profile, onImport: () -> Unit) {
    item(key = "lede") { PageLede(IMPORT_SUB, profile = me) }
    item(key = "import") { ImportRow(IMPORT_HINT, onImport) }
}

/** Import subscriptions from YouTube: a Takeout or NewPipe file from the file picker. */
@Composable
private fun ImportRow(hint: String, onImport: () -> Unit) {
    SettingRow("Import subscriptions from YouTube", onImport, hint = hint, chevron = false, lead = { RowIcon(R.drawable.ic_nav_subscriptions) })
}

/**
 * Export a backup, "Which profiles?" (`m6-settings` 38): All profiles exports at once; Choose
 * profiles ticks every profile here, then Export N profiles writes the ticked ones.
 */
@Composable
private fun ExportSheet(sheet: BackupScreen.ExportSheet, state: BackupUiState, backup: BackupViewModel) {
    val chosen = sheet.chosen
    SheetColumn(
        kicker = "Export a backup",
        title = "Which profiles?",
        sub = if (chosen == null) "Each goes with its $PROFILE_CARRIES." else null,
        choices = false,
        note = EXPORT_NOTE,
    ) {
        Column(Modifier.selectableGroup()) {
            ChoiceRow("All profiles", on = chosen == null, onClick = backup::onExportAll, hint = spokenList(state.profiles.map { it.profile.name }))
            ChoiceRow("Choose profiles", on = chosen != null, onClick = backup::onExportChoose, hint = "Only the ones you tick")
        }
        if (chosen != null) {
            state.profiles.forEach { (profile, contents) ->
                val on = profile.id in chosen
                SheetRow(
                    profile.name,
                    Modifier.toggleable(on, role = Role.Checkbox) { backup.onExportToggle(profile.id) },
                    hint = exportBrief(contents, profile.isKids),
                    lead = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RowCheck(on)
                            ProfileTile(profile.name, profile.tint, PlateTile)
                        }
                    },
                    labelTrail = { ProfileTags(profile.isOwner, profile.isKids) },
                )
            }
            WideButton(
                exportChosenLabel(chosen.size),
                backup::onExportChosen,
                icon = R.drawable.ic_upload,
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
        }
    }
}
