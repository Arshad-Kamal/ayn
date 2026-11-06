package com.arshad.ayn.ui.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.BackupAction
import com.arshad.ayn.feature.settings.BackupScreen
import com.arshad.ayn.feature.settings.BackupUiState
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.backupCounts
import com.arshad.ayn.feature.settings.backupGateKicker
import com.arshad.ayn.feature.settings.choiceText
import com.arshad.ayn.feature.settings.deleteBackupSub
import com.arshad.ayn.feature.settings.exportedLede
import com.arshad.ayn.feature.settings.exportedRows
import com.arshad.ayn.feature.settings.importProblemCopy
import com.arshad.ayn.feature.settings.replaceHint
import com.arshad.ayn.feature.settings.restoreButton
import com.arshad.ayn.feature.settings.restorePickLede
import com.arshad.ayn.feature.settings.restorePickNote
import com.arshad.ayn.feature.settings.restoredLede
import com.arshad.ayn.feature.settings.restoredRows
import com.arshad.ayn.feature.settings.restoredTitle
import com.arshad.ayn.feature.settings.shownSkips
import com.arshad.ayn.feature.settings.skipHint
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.menu.ConfirmSheetContent
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.FilePage
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.profiles.PlateTile
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.Statement
import com.arshad.ayn.ui.mobile.theme.AynColors

/**
 * The pages over the shell from Settings › Backup & restore or Import (`m6-settings` 39, 41–45, 48–49):
 * the owner's lock before an export or a restore, a file being read, a file that can't be used, a
 * restore's "Restore which profiles?" with each row's choice (or deleting the backup) in a sheet, a restore that didn't
 * finish, and an export's or a restore's results. Touch Back leaves the lock's page even with taps
 * entered; the pads' ⌫ erases. [me] is the open profile, whose lock the gate asks for;
 * [onChooseBackup] and [onChooseImport] open the file picker again.
 */
@Composable
internal fun BackupCover(state: BackupUiState, me: Profile, backup: BackupViewModel, onChooseBackup: () -> Unit, onChooseImport: () -> Unit) {
    val shown = state.screen
    if (shown == BackupScreen.Page || shown is BackupScreen.ExportSheet) return
    val here = state.profiles.map { it.profile }
    val entry = remember(backup) {
        EntryActions(
            onPress = backup::onPress,
            onSubmitSequence = backup::onSubmitSequence,
            onDigit = backup::onDigit,
            onErase = backup::onErase,
            onBack = backup::onEntryLeave,
            onRejectionShown = backup::onRejectionShown,
        )
    }
    FullCover {
        when (shown) {
            is BackupScreen.OwnerGate -> LockEntryPage(me, shown.lockKind, shown.entry, gateCopy(shown, me), entry)
            is BackupScreen.Reading -> FilePage(shown.fileName ?: CHOSEN_FILE, backup::onBack, foot = {}) {
                CircularProgressIndicator(Modifier.padding(top = 32.dp).size(28.dp), color = AynColors.Ink2, strokeWidth = 2.5.dp)
            }
            is BackupScreen.Refused -> FileProblem(shown.fileName, settingsRefusalCopy(shown.reason), onChooseBackup, backup::onBack)
            is BackupScreen.ImportRefused -> FileProblem(shown.fileName, importProblemCopy(shown.problem), onChooseImport, backup::onBack)
            is BackupScreen.RestoreFailed -> FilePage(
                shown.fileName ?: CHOSEN_FILE,
                backup::onBack,
                foot = {
                    WideButton("Try again", backup::onRestore)
                    WideButton("Choose another file", onChooseBackup, kind = ButtonKind.Tonal)
                },
            ) { Statement(RESTORE_FAILED_TITLE, RESTORE_FAILED_LINE) }
            is BackupScreen.RestorePick -> RestorePickPage(shown, here, backup)
            is BackupScreen.Exported -> ExportedPage(shown, backup::onBack)
            is BackupScreen.Restored -> RestoredPage(shown, here, backup::onBack)
            BackupScreen.Page, is BackupScreen.ExportSheet -> Unit
        }
    }
}

private fun gateCopy(gate: BackupScreen.OwnerGate, owner: Profile): EntryCopy = EntryCopy(
    label = backupGateKicker(gate.action),
    line = enterLockLine(owner.name, gate.lockKind),
    hint = backupGateHint(gate.action, gate.lockKind),
    waitTail = GO_BACK_WAIT_TAIL,
    labelIcon = when (gate.action) {
        BackupAction.Export -> R.drawable.ic_upload
        is BackupAction.Read -> R.drawable.ic_download
    },
)

/** A file that can't be used (state 45, an import with no channels): why, then Choose another file or Back. */
@Composable
private fun FileProblem(fileName: String?, copy: Pair<String, String>, onChooseFile: () -> Unit, onBack: () -> Unit) {
    FilePage(
        fileName ?: CHOSEN_FILE,
        onBack,
        foot = {
            WideButton("Choose another file", onChooseFile)
            WideButton("Back", onBack, kind = ButtonKind.Tonal)
        },
    ) { Statement(copy.first, copy.second) }
}

/** A sheet over "Restore which profiles?": a row's choice, or deleting the backup. */
private sealed interface PickSheet {
    data class Choice(val index: Int) : PickSheet

    data object Delete : PickSheet
}

/**
 * "Restore which profiles?" (state 41): each profile in the file with its choice; a tap opens its
 * choice sheet (42). Restore N profiles, Keep what's here, or (one of this phone's listed backups,
 * BKD-D2) Delete this backup, which asks first (49). While the restore runs the button shows it
 * and the page takes nothing else.
 */
@Composable
private fun RestorePickPage(pick: BackupScreen.RestorePick, here: List<Profile>, backup: BackupViewModel) {
    BackHandler(onBack = backup::onBack)
    val date = monthDay(pick.exportedAtMillis)
    val idle = !pick.restoring
    val sheet = if (pick.deleteSheet != null) PickSheet.Delete else pick.choosing?.let { PickSheet.Choice(it) }
    SetupPage(
        title = "Restore which profiles?",
        lede = restorePickLede(date, pick.rows.size),
        bar = { BackBar(backup::onBack, label = pick.fileName ?: CHOSEN_FILE, labelIcon = R.drawable.ic_file) },
        note = restorePickNote(pick.rows),
        foot = {
            WideButton(
                restoreButton(pick.restoreCount),
                backup::onRestore,
                kind = ButtonKind.Danger,
                enabled = pick.restoreCount > 0,
                busy = pick.restoring,
            )
            WideButton("Keep what’s here", backup::onBack, kind = ButtonKind.Tonal, enabled = idle)
            if (pick.deletable) {
                WideButton("Delete this backup", backup::onDeleteBackup, kind = ButtonKind.Quiet, icon = R.drawable.ic_trash, enabled = idle)
            }
        },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            pick.rows.forEachIndexed { index, row ->
                val profile = row.profile
                SettingRow(
                    profile.name,
                    onClick = { backup.onRestoreRow(index) },
                    hint = backupCounts(profile),
                    value = choiceText(profile.name, row.choice),
                    enabled = idle,
                    lead = { ProfileTile(profile.name, profile.tint, PlateTile) },
                    labelTrail = { ProfileTags(profile.owner, profile.kids) },
                )
            }
        }
        PhoneLine(PICK_PHONE_LINE)
    }
    StageSheet(sheet, onDismiss = backup::onDismiss, onBack = backup::onBack, stageKey = { it }) { shown ->
        when (shown) {
            is PickSheet.Choice -> RestoreChoiceSheet(pick, shown.index, here, date, backup)
            PickSheet.Delete -> ConfirmSheetContent(
                kicker = "Saved $date",
                title = "Delete this backup?",
                body = deleteBackupSub(pick.fileName, pick.rows.size),
                keep = "Keep it",
                confirm = "Delete backup",
                onKeep = backup::onKeepBackup,
                onConfirm = backup::onConfirmDelete,
                foot = { SettingsSheetNote(DELETE_NOTE) },
            )
        }
    }
}

/** A row's choice (state 42): Replace (when a profile of its name is here), Add as new (dimmed
 *  with no room), Don't restore; the note says which lock a replaced profile keeps. */
@Composable
private fun RestoreChoiceSheet(pick: BackupScreen.RestorePick, index: Int, here: List<Profile>, date: String, backup: BackupViewModel) {
    val row = pick.rows.getOrNull(index) ?: return
    val name = row.profile.name
    val replaces = row.replaces
    val room = pick.canAddAsNew(index)
    Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetKicker("In the backup from $date")
        SheetHead(name, row.profile.tint, backupCounts(row.profile))
        Column(Modifier.selectableGroup()) {
            if (replaces != null) {
                ChoiceRow(
                    "Replace $name",
                    on = row.choice is RestoreChoice.Replace,
                    onClick = { backup.onRestoreChoice(RestoreChoice.Replace(replaces)) },
                    hint = replaceHint(name, date),
                )
            }
            Dimmed(lit = room) {
                ChoiceRow(
                    "Add as new",
                    on = row.choice == RestoreChoice.AddAsNew,
                    onClick = { backup.onRestoreChoice(RestoreChoice.AddAsNew) },
                    hint = addAsNewHint(name, replaces != null, room),
                    enabled = room,
                )
            }
            ChoiceRow(
                "Don’t restore",
                on = row.choice == RestoreChoice.Skip,
                onClick = { backup.onRestoreChoice(RestoreChoice.Skip) },
                hint = skipHint(name, replaces != null),
            )
        }
        replaces?.let { id -> choiceNote(name, here.firstOrNull { it.id == id }?.lock) }?.let { SettingsSheetNote(it) }
        SheetFoot()
    }
}

/** "Backup saved" (state 39): where the file went and what each profile carried; Done returns to the page. */
@Composable
private fun ExportedPage(exported: BackupScreen.Exported, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    SetupPage(
        title = "Backup saved",
        lede = exportedLede(exported.profiles.size),
        bar = { FileKicker("Downloads/${exported.fileName}", R.drawable.ic_file) },
        foot = { WideButton("Done", onDone) },
    ) {
        ResultCards(exportedRows(exported.profiles))
        PhoneLine(EXPORTED_PHONE_LINE)
    }
}

/** A Settings restore's results (states 43–44): what didn't carry over, then each profile; Done
 *  lands on the page. */
@Composable
private fun RestoredPage(shown: BackupScreen.Restored, here: List<Profile>, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val restored = shown.restored
    val result = restored.result
    SetupPage(
        title = restoredTitle(result),
        lede = restoredLede(result, monthDay(restored.exportedAtMillis)),
        bar = { FileKicker(restored.fileName ?: CHOSEN_FILE, if (result.partial) R.drawable.ic_alert else R.drawable.ic_download) },
        foot = { WideButton("Done", onDone) },
    ) {
        SkipCards(shownSkips(result, here))
        ResultCards(restoredRows(result, here, firstLaunch = false))
        PhoneLine(restoredPhoneLine(result, firstLaunch = false))
    }
}
