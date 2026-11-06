package com.arshad.ayn.feature.profiles

import android.net.Uri
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.ValidBackup
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * First launch's restore on the gate (`kp-backup` firstrestore…firstdone, KP-D24, D25): the picked
 * file is read, its profiles confirmed or chosen and restored (every chosen profile added, the
 * backup's owner as the owner and the session), then the results, where the owner may set a lock
 * before [open] opens it. Drives [GateScreen.Restore] through [screen] and [show]; [profileWithId]
 * waits for a restored profile to load. Main thread, like its host ViewModel.
 */
internal class FirstLaunchRestore(
    private val scope: CoroutineScope,
    private val backups: BackupStore,
    private val locks: ProfileLocks,
    private val playback: ProfilePlayback,
    private val entry: LockEntryInput,
    private val screen: () -> GateScreen,
    private val show: (GateScreen) -> Unit,
    private val open: suspend (Profile) -> Unit,
    private val profileWithId: suspend (Long) -> Profile,
) {
    private var pickedBackup: ValidBackup? = null
    private var backupRead: Job? = null

    /** The confirm or choice whose restore failed, for Try again. */
    private var failedFrom: RestoreStep? = null

    /** A file picked in the file picker to restore from; [fileName] as it named it. Taken
     *  only on first launch's welcome or restore screen, and not while a restore runs (a picker
     *  that returns after the restore opened a profile changes nothing). */
    fun onRestoreFile(uri: Uri, fileName: String?) {
        if (!screen().takesRestoreFile()) return
        backupRead?.cancel()
        pickedBackup = null
        show(GateScreen.Restore(fileName, RestoreStep.Reading))
        backupRead = scope.launch {
            val step = when (val read = backups.open(uri)) {
                is BackupRead.Valid if read.backup.ownerName == null -> RestoreStep.NoOwner
                is BackupRead.Valid -> {
                    pickedBackup = read.backup
                    RestoreStep.Confirm(read.backup.profiles, read.backup.hasDeviceSettings)
                }
                is BackupRead.Refused -> RestoreStep.Refused(read.reason)
            }
            update(step)
        }
    }

    /** Choose profiles: every profile starts ticked. */
    fun onChooseProfiles() {
        val confirm = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Confirm ?: return
        if (!confirm.restoring) {
            update(RestoreStep.Choose(confirm.profiles, confirm.deviceSettings, chosen = confirm.profiles.indices.toSet()))
        }
    }

    /** Ticks or unticks profile [index]; the backup's owner always comes back. */
    fun onToggle(index: Int) {
        val choose = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Choose ?: return
        if (choose.restoring || choose.profiles.getOrNull(index)?.owner != false) return
        val chosen = if (index in choose.chosen) choose.chosen - index else choose.chosen + index
        update(choose.copy(chosen = chosen))
    }

    /** Restore everything, Restore N profiles, or Try again with the choices that failed. Every
     *  chosen profile is added, the backup's owner as the owner and the session (KP-D24). */
    fun onRestore() {
        val step = (screen() as? GateScreen.Restore)?.step ?: return
        val backup = pickedBackup ?: return
        val from = when (step) {
            is RestoreStep.Confirm, is RestoreStep.Choose -> step.takeUnless { it.running }
            RestoreStep.Failed -> failedFrom
            else -> null
        } ?: return
        val chosen = (from as? RestoreStep.Choose)?.chosen ?: backup.profiles.indices.toSet()
        update(from.started())
        scope.launch {
            // First launch has no owner, so the pass is free and the plan adds; otherwise an owner
            // exists, whose restores go through Settings (M5.7), so this one is refused.
            val pass = locks.ownerPassWithoutLock()
            val plan = pass?.let { backups.restorePlan(backup) }
            if (pass == null || plan?.firstLaunch != true) {
                failed(from)
                return@launch
            }
            playback.end()
            val choices = backup.profiles.indices.map { if (it in chosen) RestoreChoice.AddAsNew else RestoreChoice.Skip }
            when (val result = backups.restore(pass, backup, choices)) {
                is RestoreResult.Restored -> update(RestoreStep.Done(result, exportedAtMillis = backup.exportedAtMillis))
                RestoreResult.Failed -> failed(from)
            }
        }
    }

    /** Done on the results: the restored owner's shell opens (the store has selected it). */
    fun onDone() {
        val done = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Done ?: return
        if (done.lock == null) scope.launch { open(profileWithId(done.result.ownerId)) }
    }

    /** Lock my profile, on the results: on to choosing the owner's lock. */
    fun onLockMyProfile() {
        val done = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Done ?: return
        if (done.lock == null) update(done.copy(lock = OwnerLockStep.ChooseKind))
    }

    /** The owner's lock [kind], chosen on the results. */
    fun onLockKind(kind: LockKind) {
        val done = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Done ?: return
        if (done.lock != OwnerLockStep.ChooseKind) return
        update(done.copy(lock = OwnerLockStep.NewLock(kind)))
        entry.startSetup(kind) { secret -> onOwnerLock(done, secret) }
    }

    /** BACK or Back, unless a restore is running: Choose profiles goes back to its confirm, the
     *  results' lock steps step back (Done itself opens the owner), anything else to the welcome. */
    fun onBack() {
        val step = (screen() as? GateScreen.Restore)?.step ?: return
        when {
            step.running -> return
            step is RestoreStep.Choose -> update(RestoreStep.Confirm(step.profiles, step.deviceSettings))
            step is RestoreStep.Done -> doneBack(step)
            else -> {
                backupRead?.cancel()
                pickedBackup = null
                failedFrom = null
                show(GateScreen.Welcome)
            }
        }
    }

    /** Touch Back on the owner's new lock (the phone, whose pads erase with their own key): back to
     *  Choose a lock even with presses entered; ignored while the lock saves. */
    fun onLockLeave() {
        val done = (screen() as? GateScreen.Restore)?.step as? RestoreStep.Done ?: return
        val lock = done.lock as? OwnerLockStep.NewLock ?: return
        if (lock.entry.checking) return
        entry.clear()
        update(done.copy(lock = OwnerLockStep.ChooseKind))
    }

    private fun doneBack(done: RestoreStep.Done) {
        if (entry.back()) return
        entry.clear()
        when (done.lock) {
            null -> onDone()
            OwnerLockStep.Ask, OwnerLockStep.ChooseKind -> update(done.copy(lock = null))
            is OwnerLockStep.NewLock -> update(done.copy(lock = OwnerLockStep.ChooseKind))
        }
    }

    /** The owner's new lock, set from the results: saved, then the owner opens; a lock that
     *  isn't saved returns to the results. */
    private suspend fun onOwnerLock(done: RestoreStep.Done, secret: LockSecret) {
        val owner = profileWithId(done.result.ownerId)
        entry.clear()
        if (locks.setOwnerLockIfNone(owner.id, secret)) open(owner) else update(done.copy(lock = null))
    }

    private fun failed(from: RestoreStep) {
        failedFrom = from
        update(RestoreStep.Failed)
    }

    private fun update(step: RestoreStep) {
        val restore = screen() as? GateScreen.Restore ?: return
        show(restore.copy(step = step))
    }
}

/** First launch's welcome, or its restore screen before a restore runs. */
internal fun GateScreen.takesRestoreFile(): Boolean =
    this == GateScreen.Welcome || (this is GateScreen.Restore && !step.running && step !is RestoreStep.Done)

/** A restore runs from this step: it stays on screen and takes nothing. */
private val RestoreStep.running: Boolean
    get() = when (this) {
        is RestoreStep.Confirm -> restoring
        is RestoreStep.Choose -> restoring
        else -> false
    }

/** [this] confirm or choice with its restore running. */
private fun RestoreStep.started(): RestoreStep = when (this) {
    is RestoreStep.Confirm -> copy(restoring = true)
    is RestoreStep.Choose -> copy(restoring = true)
    else -> this
}
