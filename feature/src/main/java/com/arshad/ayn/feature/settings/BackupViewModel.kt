package com.arshad.ayn.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.backup.ExportResult
import com.arshad.ayn.core.backup.ImportResult
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestorePlan
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.ValidBackup
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.ProfilePlayback
import com.arshad.ayn.feature.profiles.RestoreHandover
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.profiles.SettingsPicks
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.openOwnerGate
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings › Backup & restore and Import from YouTube (`kp-backup`, § B3p, M5-D14/D15, KP-D17/D24).
 * The owner's lock is asked first (while the owner has one), and its pass lives for that flow
 * only. Export: "Which profiles?" (all, or the ones ticked) → write → "Backup saved" with what went
 * in. Restore, from a listed backup (each read in the background for its profile count, Q8) or a
 * picked file: read → "Restore which profiles?" with a choice per profile (Replace, Add as new,
 * Don't restore) → playback ends → restore → [handover] reopens the shell fresh with the results.
 * A listed backup's pick also offers Delete this backup: a confirm sheet, then the file goes from
 * Downloads and the list (BKD-D1/D2).
 * Anyone imports subscriptions into their own profile. A file picked for this shell's Settings
 * comes from [picks] once the profile has loaded, also after KE's process was gone meanwhile
 * (M5-D20). [profileId] is the shell opening's profile: everything here goes by it, never by
 * whichever profile the session holds by then (Q29). Main thread. [timeSource] runs lock waits and
 * must count deep sleep (`elapsedRealtime`).
 */
class BackupViewModel(
    private val backups: BackupStore,
    profileStore: ProfileStore,
    private val locks: ProfileLocks,
    private val playback: ProfilePlayback,
    private val handover: RestoreHandover,
    private val picks: SettingsPicks,
    private val profileId: Long,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { (screen as? BackupScreen.OwnerGate)?.entry },
        write = ::updateEntry,
    )

    /** This opening's profile once loaded; set last in [onProfiles], so a pick it lets through
     *  sees the rest of the state. */
    private val me = MutableStateFlow<Profile?>(null)

    /** The owner's pass for the export, restore or delete flow on screen; dropped when it ends ([show]). */
    private var pass: OwnerPass? = null

    /** The backup being restored or deleted: the file it came from, then the backup once read. */
    private var source: BackupAction.Read? = null
    private var picked: ValidBackup? = null

    /** The choices a failed restore ran with, for Try again. */
    private var failedPick: BackupScreen.RestorePick? = null
    private var reading: Job? = null
    private var listing: Job? = null

    init {
        viewModelScope.launch {
            combine(profileStore.profiles(), profileStore.contents(), ::Pair)
                .collect { (profiles, contents) -> onProfiles(profiles, contents) }
        }
        viewModelScope.launch {
            combine(picks.settingsPick, me) { pick, loaded -> pick?.takeIf { loaded != null } }
                .filterNotNull()
                .collect(::onSettingsPick)
        }
    }

    private fun onProfiles(profiles: List<Profile>, contents: Map<Long, ProfileContents>) {
        val wasOwner = me.value?.isOwner == true
        val profile = profiles.firstOrNull { it.id == profileId }
        val isOwner = profile?.isOwner == true
        _uiState.update { state ->
            state.copy(
                isOwner = isOwner,
                profiles = profiles.map { ProfileHere(it, contents[it.id] ?: ProfileContents.Empty) },
            )
        }
        if (isOwner && !wasOwner) listSaved()
        me.value = profile
    }

    private fun onSettingsPick(pick: PickedFile) {
        if (pick.request.profileId != profileId || !picks.takeSettingsPick(pick)) return
        when (pick.request.kind) {
            PickKind.Backup -> onBackupFilePicked(pick.uri, pick.fileName)
            PickKind.Import -> onImportFilePicked(pick.uri, pick.fileName)
            PickKind.Restore -> Unit // the gate's
        }
    }

    fun onToastShown() {
        _uiState.update { it.copy(toast = null) }
    }

    /** The shell reopened after this device's Settings restore ([RestoreHandover]): its results
     *  show until Done or BACK. */
    fun onRestored(restored: RestoredBackup) {
        show(BackupScreen.Restored(restored))
    }

    // Export

    /** Export a backup: the owner's lock, then "Which profiles?". */
    fun onExport() {
        if (onOwnersPage()) askOwner(BackupAction.Export)
    }

    /** All profiles: exports at once; while Choose profiles is on, back to All profiles first. */
    fun onExportAll() {
        val sheet = screen as? BackupScreen.ExportSheet ?: return
        if (sheet.chosen == null) export(profileIds = null) else show(sheet.copy(chosen = null))
    }

    /** Choose profiles: every profile here starts ticked. */
    fun onExportChoose() {
        val sheet = screen as? BackupScreen.ExportSheet ?: return
        if (sheet.chosen == null) show(sheet.copy(chosen = _uiState.value.profiles.mapTo(LinkedHashSet()) { it.profile.id }))
    }

    fun onExportToggle(profileId: Long) {
        val sheet = screen as? BackupScreen.ExportSheet ?: return
        val chosen = sheet.chosen ?: return
        show(sheet.copy(chosen = if (profileId in chosen) chosen - profileId else chosen + profileId))
    }

    /** Export N profiles: the ticked ones; none ticked does nothing. */
    fun onExportChosen() {
        val chosen = (screen as? BackupScreen.ExportSheet)?.chosen ?: return
        if (chosen.isNotEmpty()) export(chosen)
    }

    private fun export(profileIds: Set<Long>?) {
        val owner = pass ?: return
        show(BackupScreen.Page)
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = backups.export(owner, profileIds)
            _uiState.update { it.copy(working = false) }
            when (result) {
                is ExportResult.Saved -> {
                    if (screen == BackupScreen.Page) show(BackupScreen.Exported(result.fileName, result.profiles))
                    listSaved()
                }
                ExportResult.Failed -> _uiState.update { it.copy(toast = BackupToast.ExportFailed) }
            }
        }
    }

    // Restore

    /** One of this install's backups, from the list. */
    fun onSavedBackup(row: SavedBackupRow) {
        if (onOwnersPage()) askOwner(BackupAction.Read(row.backup.uri, row.backup.fileName, listed = true))
    }

    /** A file picked in the file picker (Find a backup…, Choose another file). */
    fun onBackupFilePicked(uri: Uri, fileName: String?) {
        val choosing = when (screen) {
            BackupScreen.Page, is BackupScreen.Refused, is BackupScreen.RestoreFailed -> true
            else -> false
        }
        if (!choosing || !_uiState.value.isOwner || _uiState.value.working) return
        askOwner(BackupAction.Read(uri, fileName))
    }

    /** A row of "Restore which profiles?": opens its choice sheet. */
    fun onRestoreRow(index: Int) {
        val pick = screen as? BackupScreen.RestorePick ?: return
        if (pick.idle && index in pick.rows.indices) show(pick.copy(choosing = index))
    }

    /** The open sheet's choice: Replace only its planned profile, Add as new only while there is
     *  room ([BackupScreen.RestorePick.canAddAsNew]). */
    fun onRestoreChoice(choice: RestoreChoice) {
        val pick = screen as? BackupScreen.RestorePick ?: return
        val index = pick.choosing ?: return
        val row = pick.rows[index]
        val allowed = when (choice) {
            is RestoreChoice.Replace -> choice.profileId == row.replaces
            RestoreChoice.AddAsNew -> pick.canAddAsNew(index)
            RestoreChoice.Skip -> true
        }
        if (!allowed) return
        show(pick.copy(rows = pick.rows.toMutableList().also { it[index] = row.copy(choice = choice) }, choosing = null))
    }

    /** Restore N profiles, or Try again after a failed restore (the same choices, or the file read
     *  again when the restore never got its plan). */
    fun onRestore() {
        when (val shown = screen) {
            is BackupScreen.RestorePick -> if (shown.idle && shown.restoreCount > 0) restore(shown)
            is BackupScreen.RestoreFailed -> failedPick?.let(::restore) ?: source?.let(::read)
            else -> Unit
        }
    }

    // Delete

    /** Delete this backup, on a listed backup's pick: its confirm sheet, Keep it first. */
    fun onDeleteBackup() {
        val pick = screen as? BackupScreen.RestorePick ?: return
        if (pick.deletable && pick.idle) show(pick.copy(deleteSheet = BackupDelete.Asked))
    }

    /** Keep it: the sheet closes and the pick stays. */
    fun onKeepBackup() {
        val pick = screen as? BackupScreen.RestorePick ?: return
        if (pick.deleteSheet == BackupDelete.Asked) show(pick.copy(deleteSheet = null))
    }

    /** Delete backup: the file goes; back on the page without its row. A failed delete keeps the
     *  row and the pick, and says so. */
    fun onConfirmDelete() {
        val pick = screen as? BackupScreen.RestorePick ?: return
        val file = source ?: return
        val owner = pass ?: return
        if (pick.deleteSheet != BackupDelete.Asked) return
        show(pick.copy(deleteSheet = BackupDelete.Running))
        viewModelScope.launch {
            if (backups.deleteBackup(owner, file.uri)) {
                _uiState.update { state ->
                    state.copy(saved = state.saved.filterNot { it.backup.uri == file.uri }, toast = BackupToast.Deleted(file.fileName))
                }
                show(BackupScreen.Page)
                listSaved()
            } else {
                show(pick.copy(deleteSheet = null))
                _uiState.update { it.copy(toast = BackupToast.DeleteFailed) }
            }
        }
    }

    // Import

    /** A Takeout or NewPipe subscriptions file picked for import, into your own profile. */
    fun onImportFilePicked(uri: Uri, fileName: String?) {
        if (screen !is BackupScreen.Page && screen !is BackupScreen.ImportRefused) return
        if (_uiState.value.working) return
        show(BackupScreen.Page)
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = backups.importSubscriptions(uri, profileId)
            _uiState.update { it.copy(working = false) }
            val problem = when (result) {
                is ImportResult.Imported if result.added == 0 && result.alreadySubscribed == 0 -> ImportProblem.NoChannels
                is ImportResult.Imported -> {
                    _uiState.update { it.copy(toast = BackupToast.Imported(result.added, result.alreadySubscribed)) }
                    return@launch
                }
                ImportResult.Unreadable -> ImportProblem.Unreadable
                ImportResult.TooLarge -> ImportProblem.TooLarge
                ImportResult.Failed -> ImportProblem.Failed
            }
            show(BackupScreen.ImportRefused(fileName, problem))
        }
    }

    // Lock entry

    fun onPress(press: Press) {
        entry.press(press)
    }

    fun onDigit(digit: Char) {
        entry.digit(digit)
    }

    fun onSubmitSequence() {
        entry.submitSequence()
    }

    fun onErase() {
        entry.erase()
    }

    fun onRejectionShown() {
        entry.rejectionShown()
    }

    /** BACK, or Done on a results screen: lock input erases first (and holds while a check runs);
     *  a choice or delete sheet closes, Choose profiles goes back to All profiles, the owner gate
     *  returns to where it was asked, anything else to the page. Ignored while a restore or a
     *  delete runs. */
    fun onBack() {
        if (entry.back()) return
        val back = when (val shown = screen) {
            BackupScreen.Page -> return
            is BackupScreen.RestorePick if shown.restoring || shown.deleteSheet == BackupDelete.Running -> return
            is BackupScreen.RestorePick if shown.deleteSheet == BackupDelete.Asked -> shown.copy(deleteSheet = null)
            is BackupScreen.RestorePick if shown.choosing != null -> shown.copy(choosing = null)
            is BackupScreen.ExportSheet if shown.chosen != null -> shown.copy(chosen = null)
            is BackupScreen.OwnerGate -> shown.returnTo
            is BackupScreen.ExportSheet, is BackupScreen.Exported, is BackupScreen.Restored, is BackupScreen.Reading,
            is BackupScreen.Refused, is BackupScreen.RestorePick, is BackupScreen.RestoreFailed, is BackupScreen.ImportRefused,
            -> BackupScreen.Page
        }
        show(back)
    }

    /** Touch Back on the owner's lock (the phone, whose pads erase with their own key): back to
     *  where it was asked even with presses entered (they are dropped); ignored while a try is checked. */
    fun onEntryLeave() {
        val gate = screen as? BackupScreen.OwnerGate ?: return
        if (!gate.entry.checking) show(gate.returnTo)
    }

    /** A sheet swiped away (the phone): the export sheet closes to the page, a row's choice sheet
     *  or the delete sheet to "Restore which profiles?" (not while the restore or delete runs). */
    fun onDismiss() {
        when (val shown = screen) {
            is BackupScreen.ExportSheet -> show(BackupScreen.Page)
            is BackupScreen.RestorePick if shown.deleteSheet == BackupDelete.Asked -> show(shown.copy(deleteSheet = null))
            is BackupScreen.RestorePick if shown.choosing != null && !shown.restoring -> show(shown.copy(choosing = null))
            else -> Unit
        }
    }

    /** Runs [action] with the owner's pass: the one this flow holds, free while the owner has no
     *  lock, else after the lock. */
    private fun askOwner(action: BackupAction) {
        val owner = me.value?.takeIf { it.isOwner } ?: return
        pass?.let { held ->
            runOwnerAction(action, held)
            return
        }
        val from = screen
        viewModelScope.launch {
            val opening = locks.openOwnerGate(owner.id)
            // Left meanwhile, or an export another press started is running.
            if (screen != from || _uiState.value.working) return@launch
            when (opening) {
                is OwnerGateOpening.Free -> runOwnerAction(action, opening.pass)
                is OwnerGateOpening.Ask -> {
                    show(BackupScreen.OwnerGate(action, opening.kind, returnTo = from))
                    entry.startTry(opening.kind, check = locks::unlockOwner) { granted -> runOwnerAction(action, granted) }
                    opening.wait?.let(entry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    private fun runOwnerAction(action: BackupAction, granted: OwnerPass) {
        pass = granted
        when (action) {
            BackupAction.Export -> show(BackupScreen.ExportSheet())
            is BackupAction.Read -> read(action)
        }
    }

    private fun read(file: BackupAction.Read) {
        show(BackupScreen.Reading(file.fileName))
        source = file
        reading = viewModelScope.launch {
            val read = backups.open(file.uri)
            val plan = (read as? BackupRead.Valid)?.let { backups.restorePlan(it.backup) }
            reading = null // landed: the screen it shows mustn't cancel it
            when {
                read is BackupRead.Refused -> show(BackupScreen.Refused(file.fileName, read.reason))
                plan == null -> show(BackupScreen.RestoreFailed(file.fileName))
                else -> {
                    val backup = (read as BackupRead.Valid).backup
                    show(restorePick(file, backup, plan))
                    picked = backup
                }
            }
        }
    }

    private fun restore(pick: BackupScreen.RestorePick) {
        val backup = picked ?: return
        val owner = pass ?: return
        show(pick.copy(choosing = null, restoring = true))
        viewModelScope.launch {
            playback.end()
            when (val result = backups.restore(owner, backup, pick.rows.map { it.choice })) {
                // The open profile may be among the replaced ones: the gate reopens this shell fresh,
                // with the results.
                is RestoreResult.Restored ->
                    handover.onRestored(result.ownerId, RestoredBackup(pick.fileName, backup.exportedAtMillis, result))
                RestoreResult.Failed -> {
                    show(BackupScreen.RestoreFailed(pick.fileName))
                    failedPick = pick
                }
            }
        }
    }

    /** Lists this install's backups, then reads each not yet counted in turn for its profile
     *  count; a count already known carries over. */
    private fun listSaved() {
        listing?.cancel()
        listing = viewModelScope.launch {
            val saved = backups.savedBackups()
            val known = _uiState.value.saved.associate { it.backup.uri to it.profileCount }
            val rows = saved.map { SavedBackupRow(it, known[it.uri]) }
            _uiState.update { state -> state.copy(saved = rows) }
            rows.filter { it.profileCount == null }.forEach { (backup) ->
                val count = (backups.open(backup.uri) as? BackupRead.Valid)?.backup?.profiles?.size ?: return@forEach
                _uiState.update { state ->
                    state.copy(saved = state.saved.map { if (it.backup == backup) it.copy(profileCount = count) else it })
                }
            }
        }
    }

    private val screen: BackupScreen get() = _uiState.value.screen

    private fun onOwnersPage(): Boolean =
        screen == BackupScreen.Page && _uiState.value.isOwner && !_uiState.value.working

    /** Shows [next]; lock input stops and a read in flight is dropped. The owner's pass and the
     *  backup being restored are kept only while their flow is on screen, so a flow sets what it
     *  keeps ([source], [picked], [failedPick]) after showing its screen, never before. */
    private fun show(next: BackupScreen) {
        entry.clear()
        reading?.cancel()
        reading = null
        val restoreGoesOn = next is BackupScreen.RestorePick || next is BackupScreen.RestoreFailed ||
            next is BackupScreen.Refused || (next is BackupScreen.OwnerGate && next.action is BackupAction.Read)
        if (!restoreGoesOn) {
            source = null
            picked = null
        }
        if (next !is BackupScreen.RestoreFailed) failedPick = null
        if (!restoreGoesOn && next !is BackupScreen.ExportSheet && next !is BackupScreen.Reading) pass = null
        _uiState.update { it.copy(screen = next) }
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val gate = state.screen as? BackupScreen.OwnerGate ?: return@update state
            state.copy(screen = gate.copy(entry = change(gate.entry)))
        }
    }
}

/** "Restore which profiles?" for [backup], read from [file]: Replace where the plan names a
 *  profile, else Add as new while there is room, else Don't restore. */
private fun restorePick(file: BackupAction.Read, backup: ValidBackup, plan: RestorePlan): BackupScreen.RestorePick {
    var room = plan.roomForNew
    val rows = plan.profiles.map { planned ->
        val replaces = planned.replaces
        val choice = when {
            replaces != null -> RestoreChoice.Replace(replaces)
            room > 0 -> RestoreChoice.AddAsNew.also { room-- }
            else -> RestoreChoice.Skip
        }
        RestoreRow(planned.summary, replaces, choice)
    }
    return BackupScreen.RestorePick(file.fileName, backup.exportedAtMillis, rows, plan.roomForNew, deletable = file.listed)
}
