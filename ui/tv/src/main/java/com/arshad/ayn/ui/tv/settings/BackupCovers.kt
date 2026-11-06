package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.BackupAction
import com.arshad.ayn.feature.settings.BackupScreen
import com.arshad.ayn.feature.settings.BackupUiState
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.PROFILE_CARRIES
import com.arshad.ayn.feature.settings.backupCounts
import com.arshad.ayn.feature.settings.backupGateKicker
import com.arshad.ayn.feature.settings.choiceText
import com.arshad.ayn.feature.settings.deleteBackupSub
import com.arshad.ayn.feature.settings.exportBrief
import com.arshad.ayn.feature.settings.exportChosenLabel
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
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus

/** A file the provider gave no name for. */
private const val ChosenFile = "The chosen file"

/**
 * A screen covering the shell over Settings › Backup & restore or Import from YouTube
 * (`kp-backup` exported, restorepick, restorechoice, deletesheet, restored, restoredpartial; `b3-profiles`
 * reading, srefused, gaterestore, sfailed, importnone). [owner] is the open profile, whose lock
 * the owner gate asks for; [onChooseBackup] and [onChooseImport] open the file picker for a
 * backup or a subscriptions file.
 */
@Composable
internal fun BackupCover(
    shown: BackupScreen,
    state: BackupUiState,
    owner: Profile?,
    backup: BackupViewModel,
    onChooseBackup: () -> Unit,
    onChooseImport: () -> Unit,
) {
    val here = state.profiles.map { it.profile }
    when (shown) {
        is BackupScreen.Reading -> ReadingCover(shown.fileName)
        is BackupScreen.Refused -> FileProblem(shown.fileName, settingsRefusalCopy(shown.reason), onChooseBackup, backup::onBack)
        is BackupScreen.RestorePick -> RestorePickCover(shown, here, backup)
        is BackupScreen.OwnerGate -> owner?.let { BackupOwnerGate(shown, it, backup) }
        is BackupScreen.RestoreFailed -> RestoreFailedCover(shown.fileName, backup::onRestore, onChooseBackup)
        is BackupScreen.ImportRefused -> FileProblem(shown.fileName, importProblemCopy(shown.problem), onChooseImport, backup::onBack)
        is BackupScreen.Exported -> ExportedCover(shown, backup::onBack)
        is BackupScreen.Restored -> RestoredCover(shown, here, backup::onBack)
        // The page itself, and the export sheet over it (SettingsScreen's layers).
        BackupScreen.Page, is BackupScreen.ExportSheet -> Unit
    }
}

/**
 * A guided screen about one file (`.guided.confirm`): the file's name after [icon], then [title]
 * and [body] when there are any, and [actions] at the right.
 */
@Composable
private fun FileCover(
    fileName: String?,
    @DrawableRes icon: Int,
    title: String?,
    body: String?,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow(fileName ?: ChosenFile, Modifier.padding(bottom = 11.dp)) { KickerIcon(icon) }
                title?.let { GuidedTitle(it) }
                body?.let { GuidedBody(it) }
            },
            actions = actions,
        )
    }
}

/** The file is being read (`reading`): only its name shows. Nothing here acts, but focus waits on
 *  the cover so the page under it can't take the remote's keys meanwhile. */
@Composable
private fun ReadingCover(fileName: String?) {
    val hold = rememberInitialFocus()
    FileCover(fileName, R.drawable.ic_download, title = null, body = null, modifier = Modifier.focusRequester(hold).focusable()) {}
}

/** A file that can't be used (`srefused`, `importnone`): what's wrong, then Choose another file or Back. */
@Composable
private fun FileProblem(fileName: String?, copy: Pair<String, String>, onChooseFile: () -> Unit, onBack: () -> Unit) {
    val first = rememberInitialFocus()
    FileCover(fileName, R.drawable.ic_alert, copy.first, copy.second) {
        GuidedButton(R.drawable.ic_download, "Choose another file", ControlStyle.Primary, onChooseFile, Modifier.focusRequester(first))
        GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, onBack)
    }
}

/** A sheet over "Restore which profiles?": a row's choice, or deleting the backup. */
private sealed interface PickSheet {
    data class Choice(val index: Int) : PickSheet

    data object Delete : PickSheet
}

/**
 * "Restore which profiles?" (`restorepick`): each profile in the file with its choice; OK on a row
 * opens its choice sheet (`restorechoice`) over the page. Restore N profiles, Keep what's here, or
 * (one of this install's listed backups, BKD-D2) Delete this backup, which asks first
 * (`deletesheet`). A sheet closing returns focus to what opened it. While the restore runs the
 * page stays up (its buttons do nothing then).
 */
@Composable
private fun RestorePickCover(pick: BackupScreen.RestorePick, here: List<Profile>, backup: BackupViewModel) {
    val first = rememberInitialFocus()
    val rowFocus = remember(first) { mutableMapOf(0 to first) }
    fun rowRequester(index: Int): FocusRequester = rowFocus.getOrPut(index) { FocusRequester() }
    val deleteFocus = remember { FocusRequester() }
    var opener by remember { mutableStateOf(first) }
    val sheet = if (pick.deleteSheet != null) PickSheet.Delete else pick.choosing?.let { PickSheet.Choice(it) }
    val date = monthDay(pick.exportedAtMillis)
    FocusOnClose(open = sheet != null, target = opener)
    Overlays<PickSheet, Unit>(
        sheet = sheet,
        cover = null,
        onRailShade = {},
        sheetContent = { shown ->
            when (shown) {
                is PickSheet.Choice -> RestoreChoiceSheet(pick, shown.index, here, date, backup)
                PickSheet.Delete -> DeleteBackupSheet(pick, date, backup)
            }
        },
        coverContent = {},
    ) {
        SetupPage(
            icon = R.drawable.ic_download,
            kicker = pick.fileName ?: ChosenFile,
            title = "Restore which profiles?",
            lede = restorePickLede(date, pick.rows.size),
            note = restorePickNote(pick.rows),
            foot = {
                FootButton(R.drawable.ic_download, restoreButton(pick.restoreCount), backup::onRestore, style = ControlStyle.Danger)
                FootButton(R.drawable.ic_back, "Keep what’s here", backup::onBack)
                if (pick.deletable) {
                    FootButton(
                        R.drawable.ic_trash,
                        "Delete this backup",
                        onClick = {
                            opener = deleteFocus
                            backup.onDeleteBackup()
                        },
                        modifier = Modifier.focusRequester(deleteFocus),
                    )
                }
            },
        ) {
            Column(Modifier.padding(top = 17.dp).width(650.dp).widenStart(RowInset)) {
                pick.rows.forEachIndexed { index, row ->
                    SettingRow(
                        label = row.profile.name,
                        hint = backupCounts(row.profile),
                        value = choiceText(row.profile.name, row.choice),
                        onClick = {
                            opener = rowRequester(index)
                            backup.onRestoreRow(index)
                        },
                        modifier = Modifier.focusRequester(rowRequester(index)),
                        valueMaxWidth = 180.dp,
                        lead = { ProfilePlate(row.profile.name, row.profile.tint, RowPlate) },
                        labelTrail = { tones -> ProfileTags(row.profile.owner, row.profile.kids, tones) },
                    )
                }
            }
            DeviceLine(PICK_DEVICE_LINE)
        }
    }
}

/** A row's choice (`restorechoice`): Replace (when a profile of its name is here), Add as new
 *  (unavailable with no room), Don't restore. */
@Composable
private fun RestoreChoiceSheet(pick: BackupScreen.RestorePick, index: Int, here: List<Profile>, date: String, backup: BackupViewModel) {
    val row = pick.rows[index]
    val name = row.profile.name
    val replaces = row.replaces
    val focus = rememberSheetFocus(
        when (row.choice) {
            is RestoreChoice.Replace -> REPLACE_KEY
            RestoreChoice.AddAsNew -> ADD_AS_NEW_KEY
            RestoreChoice.Skip -> SKIP_KEY
        },
    )
    val room = pick.canAddAsNew(index)
    SettingsSheet(
        kicker = "In the backup from $date",
        title = { PlateTitle(name, row.profile.tint) },
        sub = backupCounts(row.profile),
        note = replaces?.let { id -> choiceNote(name, here.firstOrNull { it.id == id }?.lock) },
    ) {
        if (replaces != null) {
            SettingRow(
                label = "Replace $name",
                hint = replaceHint(name, date),
                minHeight = SheetRowMinHeight,
                onClick = { backup.onRestoreChoice(RestoreChoice.Replace(replaces)) },
                modifier = focus.on(REPLACE_KEY),
                lead = { RowRadio(on = row.choice is RestoreChoice.Replace, tones = it) },
            )
        }
        SettingRow(
            label = "Add as new",
            hint = addAsNewHint(name, replaces != null, room),
            minHeight = SheetRowMinHeight,
            focusable = room,
            onClick = { backup.onRestoreChoice(RestoreChoice.AddAsNew) },
            modifier = focus.on(ADD_AS_NEW_KEY).alpha(if (room) 1f else UnavailableAlpha),
            lead = { RowRadio(on = row.choice == RestoreChoice.AddAsNew, tones = it) },
        )
        SettingRow(
            label = "Don’t restore",
            hint = skipHint(name, replaces != null),
            minHeight = SheetRowMinHeight,
            onClick = { backup.onRestoreChoice(RestoreChoice.Skip) },
            modifier = focus.on(SKIP_KEY),
            lead = { RowRadio(on = row.choice == RestoreChoice.Skip, tones = it) },
        )
    }
}

private const val REPLACE_KEY = "replace"
private const val ADD_AS_NEW_KEY = "add"
private const val SKIP_KEY = "skip"

/** "Delete this backup?" (`deletesheet`, BKD-D1): Keep it (focused as it opens) or Delete backup. */
@Composable
private fun DeleteBackupSheet(pick: BackupScreen.RestorePick, date: String, backup: BackupViewModel) {
    val focus = rememberSheetFocus(KEEP_KEY)
    SettingsSheet(
        kicker = "Saved $date",
        title = { SheetTitle("Delete this backup?") },
        sub = deleteBackupSub(pick.fileName, pick.rows.size),
        note = DELETE_NOTE,
    ) {
        SettingRow(
            label = "Keep it",
            hint = "Back to Restore which profiles?",
            minHeight = SheetRowMinHeight,
            onClick = backup::onKeepBackup,
            modifier = focus.on(KEEP_KEY),
            lead = { RowIcon(R.drawable.ic_back, it) },
        )
        SettingRow(
            label = "Delete backup",
            hint = "Your profiles here aren’t changed",
            minHeight = SheetRowMinHeight,
            onClick = backup::onConfirmDelete,
            lead = { RowIcon(R.drawable.ic_trash, it) },
        )
    }
}

private const val KEEP_KEY = "keep"

/** The restore didn't finish (`sfailed`): nothing changed; Try again or Choose another file. */
@Composable
private fun RestoreFailedCover(fileName: String?, onRestore: () -> Unit, onChooseFile: () -> Unit) {
    val first = rememberInitialFocus()
    FileCover(fileName, R.drawable.ic_alert, "The restore didn’t finish", "Nothing on this Shield changed. Try again, or choose another file.") {
        GuidedButton(R.drawable.ic_retry, "Try again", ControlStyle.Primary, onRestore, Modifier.focusRequester(first))
        GuidedButton(R.drawable.ic_download, "Choose another file", ControlStyle.Secondary, onChooseFile)
    }
}

/** "Backup saved" (`exported`): where the file went and what each profile carried; Done. */
@Composable
private fun ExportedCover(exported: BackupScreen.Exported, onDone: () -> Unit) {
    val done = rememberInitialFocus()
    SetupPage(
        icon = R.drawable.ic_upload,
        kicker = "Downloads/${exported.fileName}",
        title = "Backup saved",
        lede = exportedLede(exported.profiles.size),
        foot = { FootButton(R.drawable.ic_check, "Done", onDone, Modifier.focusRequester(done), ControlStyle.Primary) },
    ) {
        ResultsTable(exportedRows(exported.profiles))
        DeviceLine(EXPORTED_DEVICE_LINE)
    }
}

/** A Settings restore's results (`restored`, `restoredpartial`); Done returns to Backup & restore. */
@Composable
private fun RestoredCover(shown: BackupScreen.Restored, here: List<Profile>, onDone: () -> Unit) {
    val done = rememberInitialFocus()
    val restored = shown.restored
    val result = restored.result
    SetupPage(
        icon = if (result.partial) R.drawable.ic_alert else R.drawable.ic_download,
        kicker = restored.fileName ?: ChosenFile,
        title = restoredTitle(result),
        lede = restoredLede(result, monthDay(restored.exportedAtMillis)),
        foot = { FootButton(R.drawable.ic_check, "Done", onDone, Modifier.focusRequester(done), ControlStyle.Primary) },
    ) {
        SkipsList(shownSkips(result, here))
        ResultsTable(restoredRows(result, here, firstLaunch = false))
        DeviceLine(restoredDeviceLine(result, firstLaunch = false))
    }
}

/** The owner's lock before an export or a restore (`gaterestore`; "Exporting a backup"). */
@Composable
private fun BackupOwnerGate(gate: BackupScreen.OwnerGate, owner: Profile, backup: BackupViewModel) {
    val actions = remember(backup) {
        UnlockActions(
            onPress = backup::onPress,
            onSubmitSequence = backup::onSubmitSequence,
            onDigit = backup::onDigit,
            onErase = backup::onErase,
            onBack = backup::onBack,
            onRejectionShown = backup::onRejectionShown,
        )
    }
    val icon = when (gate.action) {
        BackupAction.Export -> R.drawable.ic_upload
        is BackupAction.Read -> R.drawable.ic_download
    }
    val copy = EntryCopy(
        kicker = EntryKicker(icon, backupGateKicker(gate.action)),
        line = backupGateLine(gate.action, owner.name, gate.lockKind),
        hint = cancelHint(gate.lockKind),
        waitTail = SettingsWaitTail,
    )
    LockEntryScreen(owner, gate.lockKind, gate.entry, copy, actions)
}

/**
 * Export a backup, "Which profiles?" (`exportsheet`, `exportchoose`): All profiles exports at once;
 * Choose profiles ticks every profile here, then Export N profiles writes the ticked ones.
 */
@Composable
internal fun ExportSheet(sheet: BackupScreen.ExportSheet, state: BackupUiState, backup: BackupViewModel) {
    val chosen = sheet.chosen
    val firstProfileKey = state.profiles.firstOrNull()?.let { exportKey(it.profile.id) } ?: CHOOSE_KEY
    val focus = rememberSheetFocus(if (chosen == null) ALL_KEY else firstProfileKey)
    // Choose profiles opens the list under it on its first profile.
    val choosing = chosen != null
    LaunchedEffect(choosing) {
        if (!choosing) return@LaunchedEffect
        withFrameNanos { }
        focus.focus(firstProfileKey)
    }
    SettingsSheet(
        kicker = "Export a backup",
        title = { SheetTitle("Which profiles?") },
        sub = if (chosen == null) "Each goes with its $PROFILE_CARRIES." else null,
        note = "This Shield’s settings go in every backup. Locks never do.",
        scroll = true,
    ) {
        SettingRow(
            label = "All profiles",
            hint = spokenList(state.profiles.map { it.profile.name }),
            minHeight = SheetRowMinHeight,
            onClick = backup::onExportAll,
            modifier = focus.on(ALL_KEY),
            lead = { RowRadio(on = chosen == null, tones = it) },
        )
        SettingRow(
            label = "Choose profiles",
            hint = if (chosen == null) "Only the ones you tick" else null,
            minHeight = SheetRowMinHeight,
            onClick = backup::onExportChoose,
            modifier = focus.on(CHOOSE_KEY),
            lead = { RowRadio(on = chosen != null, tones = it) },
        )
        if (chosen != null) {
            state.profiles.forEach { (profile, contents) ->
                SettingRow(
                    label = profile.name,
                    hint = exportBrief(contents, profile.isKids),
                    minHeight = SheetRowMinHeight,
                    labelMaxLines = 1,
                    onClick = { backup.onExportToggle(profile.id) },
                    modifier = focus.on(exportKey(profile.id)).padding(start = NestInset),
                    lead = { tones ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowCheck(profile.id in chosen, tones)
                            ProfilePlate(profile.name, profile.tint, RowPlate, Modifier.padding(start = 11.dp))
                        }
                    },
                    labelTrail = { tones -> ProfileTags(profile.isOwner, profile.isKids, tones) },
                )
            }
            SettingRow(
                label = exportChosenLabel(chosen.size),
                minHeight = SheetRowMinHeight,
                focusable = chosen.isNotEmpty(),
                onClick = backup::onExportChosen,
                modifier = focus.on(EXPORT_CHOSEN_KEY),
                lead = { RowIcon(R.drawable.ic_upload, it) },
            )
        }
    }
}

private const val ALL_KEY = "all"
private const val CHOOSE_KEY = "choose"
private const val EXPORT_CHOSEN_KEY = "export-chosen"

private fun exportKey(profileId: Long): String = "export-$profileId"

