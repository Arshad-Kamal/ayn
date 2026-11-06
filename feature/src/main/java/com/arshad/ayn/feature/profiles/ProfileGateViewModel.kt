package com.arshad.ayn.feature.profiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.lock.AwayRecord
import com.arshad.ayn.core.lock.AwayRecords
import com.arshad.ayn.core.lock.LaunchGate
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.LockStatus
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.lock.RelockDecision
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.core.lock.RelockReason
import com.arshad.ayn.core.lock.launchGate
import com.arshad.ayn.core.lock.pinnedProfile
import com.arshad.ayn.core.lock.reopenAfterRestart
import com.arshad.ayn.core.store.KidsPin
import com.arshad.ayn.core.store.LastUsedProfile
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.ProfileTint
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ends the current profile's playback before the session changes (M5-D3): its place saved,
 *  the player stopped and its item cleared. */
fun interface ProfilePlayback {
    fun end()

    /** Whether the player holds a video (playing, paused, stopped or ended). */
    fun holdsItem(): Boolean = false
}

/** Hands a Settings restore over to the gate: the restore wrote its chosen profiles, the open
 *  one possibly among them (under its id); [ownerId] is the device's owner, whose shell was open. */
fun interface RestoreHandover {
    fun onRestored(ownerId: Long, backup: RestoredBackup)
}

/**
 * The root gate (M5-D16, § B3p): where a cold start goes, first launch, the picker, unlocking,
 * and which profile's shell is open. It acts on [relock]'s decisions (re-lock or the picker) as
 * they come and consumes them. A switch to another profile ends playback and clears the old
 * shell's ViewModels ([shellViewModels]) before the gate selects the new profile, so none of the
 * old shell's work runs as it; reopening the profile whose shell was last open resumes it. A
 * Settings restore ([RestoreHandover]) reopens the owner's shell fresh. A file picked in the file picker
 * waits here until what asked for it shows (M5-D20, Q29): first launch's restore screen, or the
 * Settings of the profile that asked ([SettingsPicks]); it never reaches another profile.
 * While KE is stopped it keeps where KE was left in [awayRecords]; a [restarted] gate (KE's
 * process died while away) reopens that profile when the re-lock rules allow (M5-D21).
 * A kids profile is pinned ([kidsPin]) before it opens and every start opens the pinned one
 * first; it opens only once the owner has a lock, and only the owner's lock, asked from its rail,
 * takes it back to the picker (KP-D9, D14, D23). While its time ([kidsClock]) is up or it's
 * bedtime, its shell is closed for Time's up, where the owner's lock gives More time or leaves for
 * the picker (KP-D8, D9, D26). Those steps are [TimeStoppedSteps]'s, first launch's restore is
 * [FirstLaunchRestore]'s, and the owner's lock asked from a kids profile is [OwnerFromKids]'s.
 * Activity-scoped, main thread (as [RelockMonitor] is). [timeSource] runs the wait countdown
 * ([LockEntryInput]) and must keep counting while the device sleeps (Android's `elapsedRealtime`),
 * as the stored waits do. With [keepsPlaybackOnRelock] (a phone, M6-D8) a re-lock keeps the
 * player's video playing and shows its profile's unlock; the picker and that unlock carry it as
 * [GateUiState.keptFor], and opening another profile ends it.
 */
class ProfileGateViewModel(
    private val profileStore: ProfileStore,
    private val locks: ProfileLocks,
    private val session: ProfileSession,
    private val relock: RelockMonitor,
    private val lastUsed: LastUsedProfile,
    backups: BackupStore,
    private val playback: ProfilePlayback,
    private val awayRecords: AwayRecords,
    private val kidsPin: KidsPin,
    private val kidsClock: KidsClock,
    private val restarted: Boolean,
    timeSource: TimeSource,
    private val keepsPlaybackOnRelock: Boolean = false,
) : ViewModel(), RestoreHandover, SettingsPicks {
    private val _uiState = MutableStateFlow(GateUiState())
    val uiState: StateFlow<GateUiState> = _uiState.asStateFlow()

    /** The one file picked and not yet taken. */
    private val waitingPick = MutableStateFlow<PickedFile?>(null)

    override val settingsPick: StateFlow<PickedFile?> =
        combine(waitingPick, _uiState) { pick, state -> pick?.takeIf { it.isSettingsPickOf(state.openProfileId) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The profile whose shell [GateUiState.shellKey] holds, if one opened in this gate's life. */
    private var shellProfileId: Long? = null

    /** The open shell's ViewModels: kept while its profile reopens, replaced by a new store with
     *  each new [GateUiState.shellKey]. */
    var shellViewModels = ViewModelStore()
        private set

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { _uiState.value.screen.lockEntry },
        write = ::updateEntry,
    )
    private val ownerAsk = OwnerFromKids(viewModelScope, locks, entry, screen = { _uiState.value.screen })
    private val timeStopped = TimeStoppedSteps(
        scope = viewModelScope,
        kidsClock = kidsClock,
        entry = entry,
        ownerAsk = ownerAsk,
        state = { _uiState.value },
        show = ::show,
        leave = ::leaveKids,
    )
    private val restore = FirstLaunchRestore(
        scope = viewModelScope,
        backups = backups,
        locks = locks,
        playback = playback,
        entry = entry,
        screen = { _uiState.value.screen },
        show = ::show,
        open = { open(it) },
        profileWithId = ::profileWithId,
    )

    /** The picker was opened from the rail over an unlocked profile, so BACK returns to it, also
     *  after a detour into another profile's unlock screen. */
    private var pickerReturns = false

    /** A Settings restore's results that landed with the shell closed, for its owner's next open. */
    private var restoreWaiting: Pair<Long, RestoredBackup>? = null

    init {
        viewModelScope.launch {
            profileStore.profiles().collect { profiles ->
                _uiState.update { state ->
                    // The open profile as it is now: a lock set or removed changes what the rail does.
                    val screen = (state.screen as? GateScreen.Open)
                        ?.let { open -> profiles.firstOrNull { it.id == open.profile.id }?.let(GateScreen::Open) }
                    state.copy(profiles = profiles, screen = screen ?: state.screen)
                }
                if (_uiState.value.screen == GateScreen.Loading) launchInto(profiles)
            }
        }
        viewModelScope.launch { relock.decision.collect(::onDecision) }
        // A kids shell opening, and the time changing, both decide whether it shows.
        viewModelScope.launch {
            val kidShown = _uiState.map { it.screen.kidShown() }.distinctUntilChanged()
            combine(kidsClock.time, kidShown) { time, _ -> time }.collect(timeStopped::onTime)
        }
        // M5-D21: while KE is stopped, where it was left. Nothing is written before the gate has
        // decided where it starts, so a record a restart still has to read is never cleared.
        viewModelScope.launch {
            val left = _uiState.map { (it.screen == GateScreen.Loading) to it.openProfileId }.distinctUntilChanged()
            combine(relock.awayFrom, left, ::Pair).collect { (away, where) ->
                val (loading, open) = where
                if (away != null && !loading) awayRecords.write(open?.let { AwayRecord(it, away) })
            }
        }
        // Q29: while a shell shows, only a Settings pick of its profile may wait; any other goes,
        // whether it landed first or the shell opened first.
        viewModelScope.launch {
            combine(waitingPick, _uiState.map { it.openProfileId }.distinctUntilChanged(), ::Pair).collect { (pick, open) ->
                if (pick != null && open != null && !pick.isSettingsPickOf(open)) waitingPick.compareAndSet(pick, null)
            }
        }
        // A restore pick lands before or after the welcome shows (the profiles load after a
        // process restart); it is taken once both are there.
        viewModelScope.launch {
            combine(waitingPick, _uiState) { pick, state ->
                pick?.takeIf { it.request.kind == PickKind.Restore && state.screen.takesRestoreFile() }
            }.filterNotNull().collect { pick ->
                if (waitingPick.compareAndSet(pick, null)) onRestoreFilePicked(pick.uri, pick.fileName)
            }
        }
    }

    private suspend fun launchInto(profiles: List<Profile>) {
        val reopened = pinnedProfile(profiles, kidsPin.pinned())
            ?: if (restarted) reopenAfterRestart(profiles, awayRecords.read(), relock.now()) else null
        if (reopened != null) {
            enter(reopened)
            return
        }
        when (val gate = launchGate(profiles)) {
            LaunchGate.FirstLaunch -> show(GateScreen.Welcome)
            LaunchGate.Picker -> show(GateScreen.Picker(focusId = lastUsedAmong(profiles)))
            is LaunchGate.Open -> enter(profiles.first { it.id == gate.profileId })
        }
    }

    private suspend fun lastUsedAmong(profiles: List<Profile>): Long? {
        val last = lastUsed.get()
        return profiles.firstOrNull { it.id == last }?.id ?: profiles.firstOrNull()?.id
    }

    private fun onDecision(decision: RelockDecision) {
        if (decision == RelockDecision.Stay) return
        val open = _uiState.value.screen as? GateScreen.Open
        if (open != null) {
            pickerReturns = false
            val isRelock = decision == RelockDecision.Relock
            if (isRelock && keepsPlaybackOnRelock && open.profile.lock != null && playback.holdsItem()) {
                startUnlock(open.profile, relockReason = relock.relockReason)
            } else {
                if (isRelock) playback.end()
                show(GateScreen.Picker(focusId = open.profile.id))
            }
        }
        // Consumed even with no shell open: the monitor only raises a stronger decision, so one
        // left pending would swallow the next.
        relock.consume()
    }

    // First launch

    fun onCreateProfile() {
        show(GateScreen.NameOwner())
    }

    fun onNameChange(name: String) {
        if (name.length > MAX_PROFILE_NAME) return
        updateNaming { it.copy(name = name, nameMissing = false) }
    }

    fun onTintChange(tint: ProfileTint) {
        updateNaming { it.copy(tint = tint) }
    }

    fun onCreateOwner() {
        val naming = _uiState.value.screen as? GateScreen.NameOwner ?: return
        if (naming.saving) return
        if (naming.name.isBlank()) {
            updateNaming { it.copy(nameMissing = true) }
            return
        }
        updateNaming { it.copy(saving = true) }
        viewModelScope.launch {
            when (val edit = profileStore.createOwner(naming.name, naming.tint)) {
                is ProfileEdit.Done -> open(profileWithId(edit.profileId))
                // The first profile can't be taken, full or kids; only a name the store refuses is left.
                ProfileEdit.NameInvalid, ProfileEdit.NameTaken, ProfileEdit.Full, ProfileEdit.OwnerLockNeeded ->
                    updateNaming { it.copy(saving = false, nameMissing = true) }
            }
        }
    }

    /** BACK or Back from naming (unless the owner is being saved) and from the restore screens. */
    fun onFirstLaunchBack() {
        when (val screen = _uiState.value.screen) {
            is GateScreen.NameOwner -> if (!screen.saving) show(GateScreen.Welcome)
            is GateScreen.Restore -> restore.onBack()
            else -> Unit
        }
    }

    fun onRestoreFilePicked(uri: Uri, fileName: String?) {
        restore.onRestoreFile(uri, fileName)
    }

    fun onChooseProfiles() {
        restore.onChooseProfiles()
    }

    fun onToggleRestoreProfile(index: Int) {
        restore.onToggle(index)
    }

    fun onRestore() {
        restore.onRestore()
    }

    fun onRestoreDone() {
        restore.onDone()
    }

    fun onLockMyProfile() {
        restore.onLockMyProfile()
    }

    /** Touch Back on the restore results' new lock (the phone): back to Choose a lock. */
    fun onRestoreLockLeave() {
        restore.onLockLeave()
    }

    /** A Settings restore landed: the owner's shell, open over it, reopens fresh with [backup] as
     *  [GateUiState.restored], so it starts on its results (Q9, KP-D17). Replaced profiles keep
     *  their ids, so the shell never resumes. The owner's lock was just asked for the restore and
     *  a replaced owner keeps it, so it isn't asked again. One that lands while the shell is
     *  closed (a re-lock meanwhile) shows when the owner's profile is the next to open. */
    override fun onRestored(ownerId: Long, backup: RestoredBackup) {
        if (_uiState.value.screen !is GateScreen.Open) {
            restoreWaiting = ownerId to backup
            return
        }
        viewModelScope.launch { open(profileWithId(ownerId), restored = backup) }
    }

    /** The shell has shown [GateUiState.restored]. */
    fun onRestoredShown() {
        _uiState.update { it.copy(restored = null) }
    }

    // File picks

    /**
     * The file picker returned [uri] for [request] (also into an activity recreated after KE's
     * process was gone); [readName] reads the name the picker gives it, main-safe. It replaces any
     * pick still waiting, and waits itself unless another profile's shell is open.
     */
    fun onFilePicked(request: PickRequest, uri: Uri, readName: suspend () -> String?) {
        waitingPick.value = null
        viewModelScope.launch { waitingPick.value = PickedFile(request, uri, readName()) }
    }

    override fun takeSettingsPick(pick: PickedFile): Boolean =
        pick.isSettingsPickOf(_uiState.value.openProfileId) && waitingPick.compareAndSet(pick, null)

    // Picker and rail

    fun onPick(profile: Profile) {
        if (_uiState.value.screen !is GateScreen.Picker) return
        if (profile.isKids || profile.lock == null) {
            viewModelScope.launch { enter(profile) }
        } else {
            startUnlock(profile)
        }
    }

    /** The rail's profile slot: a kids profile asks for the owner's lock to leave; a locked
     *  profile locks now; an unlocked one gets the picker over it. */
    fun onRailProfile() {
        val open = _uiState.value.screen as? GateScreen.Open ?: return
        if (open.profile.isKids) {
            startLeavingKids(open.profile)
            return
        }
        val locked = open.profile.lock != null
        if (locked) playback.end()
        pickerReturns = !locked
        backToPicker(open.profile.id)
    }

    /** BACK on the picker: `true` when it went back to the session, `false` when KE should close. */
    fun onPickerBack(): Boolean {
        val picker = _uiState.value.screen as? GateScreen.Picker ?: return false
        val current = shellProfileId?.let { id -> _uiState.value.profiles.firstOrNull { it.id == id } }
        if (!picker.canReturn || current == null) return false
        show(GateScreen.Open(current))
        return true
    }

    // Unlock

    private fun startUnlock(profile: Profile, relockReason: RelockReason? = null) {
        val kind = profile.lock ?: return
        show(GateScreen.Unlock(profile, relockReason = relockReason))
        entry.startTry(kind, check = { locks.unlock(profile.id, it) }, opened = { open(profile) })
        viewModelScope.launch {
            val status = locks.status(profile.id)
            // Left (BACK, another profile) while the status was read: it no longer applies.
            val unlock = _uiState.value.screen as? GateScreen.Unlock
            if (unlock?.profile?.id != profile.id || unlock?.leaving != null) return@launch
            when (status) {
                LockStatus.None -> open(profile) // its lock went away meanwhile
                is LockStatus.Locked -> status.wait?.let(entry::waitFor)
            }
        }
    }

    fun onPress(press: Press) {
        entry.press(press)
    }

    fun onDigit(digit: Char) {
        entry.digit(digit)
    }

    /** OK on the sequence pad: too few presses is a hint, not a try. */
    fun onSubmitSequence() {
        entry.submitSequence()
    }

    /** An erase key (the keypad's, the touch sequence pad's): the last digit or press goes. */
    fun onErase() {
        entry.erase()
    }

    /** BACK on the unlock screen: erases the last press or digit; with nothing entered (or while
     *  waiting) it returns to the picker, or to the kids profile it was leaving. Ignored while a
     *  try is checked. */
    fun onUnlockBack() {
        val unlock = _uiState.value.screen as? GateScreen.Unlock ?: return
        if (entry.back()) return
        leaveUnlock(unlock)
    }

    /** Back on a touch unlock, whose pads have their own erase key: it leaves even with presses
     *  entered (they are dropped), as BACK with nothing entered does. Ignored while a try is checked. */
    fun onUnlockLeave() {
        val unlock = _uiState.value.screen as? GateScreen.Unlock ?: return
        if (!unlock.entry.checking) leaveUnlock(unlock)
    }

    private fun leaveUnlock(unlock: GateScreen.Unlock) {
        entry.clear()
        val leaving = unlock.leaving
        if (leaving != null) {
            show(GateScreen.Open(_uiState.value.profiles.firstOrNull { it.id == leaving.id } ?: leaving))
            return
        }
        backToPicker(unlock.profile.id)
    }

    // Kids profiles (KP-D9, D14, D23)

    /** Opens [profile]; a kids profile only once the owner has a lock and its pin is written. */
    private suspend fun enter(profile: Profile) {
        if (!profile.isKids) {
            open(profile)
            return
        }
        val owner = ownerAmong(_uiState.value.profiles)
        when {
            owner == null -> backToPicker(profile.id)
            locks.passWithoutLock(owner.id) != null -> show(GateScreen.OwnerLockNeeded(profile))
            else -> pinAndOpen(profile)
        }
    }

    /** A pin that isn't written leaves [kid] closed: it must never show unpinned. */
    private suspend fun pinAndOpen(kid: Profile) {
        if (kidsPin.pin(kid.id)) open(kid) else backToPicker(kid.id)
    }

    /** Lock my profile: on to choosing the owner's lock. */
    fun onOwnerLockStart() {
        val needed = _uiState.value.screen as? GateScreen.OwnerLockNeeded ?: return
        if (needed.step == OwnerLockStep.Ask) show(needed.copy(step = OwnerLockStep.ChooseKind))
    }

    /** The owner's lock kind, chosen while a kids profile waits for it or from first launch's
     *  restore results. */
    fun onOwnerLockKind(kind: LockKind) {
        when (val screen = _uiState.value.screen) {
            is GateScreen.OwnerLockNeeded -> {
                if (screen.step != OwnerLockStep.ChooseKind) return
                show(screen.copy(step = OwnerLockStep.NewLock(kind)))
                entry.startSetup(kind) { secret -> onOwnerLockEntered(screen.kid, secret) }
            }
            is GateScreen.Restore -> restore.onLockKind(kind)
            else -> Unit
        }
    }

    private suspend fun onOwnerLockEntered(kid: Profile, secret: LockSecret) {
        val owner = ownerAmong(_uiState.value.profiles)
        val locked = owner != null && locks.setOwnerLockIfNone(owner.id, secret)
        entry.clear()
        if (locked) pinAndOpen(kid) else backToPicker(kid.id)
    }

    /** BACK while the owner's lock is needed: erases the last press or digit first, then steps
     *  back (the new lock, its kind, why, the picker). Ignored while the lock saves. */
    fun onOwnerLockBack() {
        val needed = _uiState.value.screen as? GateScreen.OwnerLockNeeded ?: return
        if (entry.back()) return
        entry.clear()
        when (needed.step) {
            OwnerLockStep.Ask -> backToPicker(needed.kid.id)
            OwnerLockStep.ChooseKind -> show(needed.copy(step = OwnerLockStep.Ask))
            is OwnerLockStep.NewLock -> show(needed.copy(step = OwnerLockStep.ChooseKind))
        }
    }

    /** Touch Back on the new owner's lock (the phone), whose pad has its own erase key: back to
     *  Choose a lock even with presses entered. Ignored while the lock saves. */
    fun onOwnerLockLeave() {
        val needed = _uiState.value.screen as? GateScreen.OwnerLockNeeded ?: return
        val lock = needed.step as? OwnerLockStep.NewLock ?: return
        if (lock.entry.checking) return
        entry.clear()
        show(needed.copy(step = OwnerLockStep.ChooseKind))
    }

    /** The owner's lock, asked from [kid] and counted on [kid]'s own tries, takes it out. */
    private fun startLeavingKids(kid: Profile) {
        val owner = ownerAmong(_uiState.value.profiles) ?: return
        show(GateScreen.Unlock(owner, leaving = kid))
        ownerAsk.ask(kid, owner, asking = { (it as? GateScreen.Unlock)?.leaving?.id == kid.id }, opened = ::leaveKids)
    }

    /** Leave <kid>'s profile on its Parent page: the page's [pass] (the owner's lock, asked as it
     *  opened) takes the open kids profile out, as the rail's Leave does. */
    fun onLeaveKids(pass: OwnerPass) {
        val open = _uiState.value.screen as? GateScreen.Open ?: return
        if (open.profile.isKids) viewModelScope.launch { leaveKids(pass) }
    }

    /** Unpinned with the owner's [pass], its playback ended, then the picker on the owner with no
     *  way back. The session stays the kid's until another profile opens, as after a re-lock. */
    private suspend fun leaveKids(pass: OwnerPass) {
        kidsPin.unpin(pass)
        playback.end()
        entry.clear()
        pickerReturns = false
        show(GateScreen.Picker(focusId = ownerAmong(_uiState.value.profiles)?.id, canReturn = false))
    }

    /** The UI has shaken the rejected dots away. */
    fun onRejectionShown() {
        entry.rejectionShown()
    }

    // Time's up and bedtime (KP-D8, D26)

    fun onMoreTime() {
        timeStopped.onMoreTime()
    }

    fun onSwitchProfile() {
        timeStopped.onSwitchProfile()
    }

    fun onMoreTimePick(minutes: Int) {
        timeStopped.onMoreTimePick(minutes)
    }

    fun onStoppedBack() {
        timeStopped.onBack()
    }

    fun onStoppedLeave() {
        timeStopped.onLeave()
    }

    // Shared

    /** Opens [profile]'s shell; [restored] goes with it in the same state, so only that shell sees it.
     *  The opening counts as activity: a screen-off seen on the gate before it (no profile open
     *  then, so nothing was decided) doesn't lock the profile just opened. */
    private suspend fun open(profile: Profile, restored: RestoredBackup? = null) {
        val results = restored ?: restoreWaiting?.takeIf { it.first == profile.id }?.second
        restoreWaiting = null
        val previous = session.currentIdOrNull
        if (previous != null && previous != profile.id) playback.end()
        val resumes = shellProfileId == profile.id && results == null
        // Nothing from here to the shellKey update may suspend: a shell still on screen (a
        // Settings restore) must not compose again against the cleared store.
        if (!resumes) {
            shellViewModels.clear()
            shellViewModels = ViewModelStore()
        }
        session.select(profile.id)
        relock.consume()
        entry.clear()
        pickerReturns = false
        shellProfileId = profile.id
        _uiState.update {
            it.copy(
                screen = GateScreen.Open(profile),
                shellKey = if (resumes) it.shellKey else it.shellKey + 1,
                restored = results,
                keptFor = null,
            )
        }
        lastUsed.set(profile.id)
    }

    /** The picker, focus on [focusId]; BACK returns to the shell only when it came from there. */
    private fun backToPicker(focusId: Long?) {
        show(GateScreen.Picker(focusId = focusId, canReturn = pickerReturns))
    }

    override fun onCleared() {
        shellViewModels.clear()
    }

    private suspend fun profileWithId(id: Long): Profile =
        profileStore.profiles().first { list -> list.any { it.id == id } }.first { it.id == id }

    private fun show(screen: GateScreen) {
        _uiState.update { it.copy(screen = screen, keptFor = keptUnder(screen, it.profiles)) }
    }

    /** The last shell's profile, while [screen] covers it on a phone and its player holds a video. */
    private fun keptUnder(screen: GateScreen, profiles: List<Profile>): Profile? {
        val covers = screen is GateScreen.Picker || (screen is GateScreen.Unlock && screen.leaving == null)
        if (!keepsPlaybackOnRelock || !covers || !playback.holdsItem()) return null
        return profiles.firstOrNull { it.id == shellProfileId }
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val screen = when (val shown = state.screen) {
                is GateScreen.Unlock -> shown.copy(entry = change(shown.entry))
                is GateScreen.OwnerLockNeeded -> {
                    val step = shown.step as? OwnerLockStep.NewLock ?: return@update state
                    shown.copy(step = step.copy(entry = change(step.entry)))
                }
                is GateScreen.Restore -> {
                    val done = shown.step as? RestoreStep.Done ?: return@update state
                    val lock = done.lock as? OwnerLockStep.NewLock ?: return@update state
                    shown.copy(step = done.copy(lock = lock.copy(entry = change(lock.entry))))
                }
                is GateScreen.TimeStopped -> {
                    val unlock = shown.step as? StoppedStep.Unlock ?: return@update state
                    shown.copy(step = unlock.copy(entry = change(unlock.entry)))
                }
                else -> return@update state
            }
            state.copy(screen = screen)
        }
    }

    private fun updateNaming(change: (GateScreen.NameOwner) -> GateScreen.NameOwner) {
        _uiState.update { state ->
            val naming = state.screen as? GateScreen.NameOwner ?: return@update state
            state.copy(screen = change(naming))
        }
    }
}

private val GateUiState.openProfileId: Long?
    get() = (screen as? GateScreen.Open)?.profile?.id

/** The lock entry this screen shows, if any. */
private val GateScreen.lockEntry: UnlockEntry?
    get() = when (this) {
        is GateScreen.Unlock -> entry
        is GateScreen.OwnerLockNeeded -> (step as? OwnerLockStep.NewLock)?.entry
        is GateScreen.Restore -> ((step as? RestoreStep.Done)?.lock as? OwnerLockStep.NewLock)?.entry
        is GateScreen.TimeStopped -> (step as? StoppedStep.Unlock)?.entry
        else -> null
    }

/** The kids profile this screen shows, as its shell or stopped; changes only when that does. */
private fun GateScreen.kidShown(): Pair<Long, Boolean>? = when (this) {
    is GateScreen.Open -> profile.id.takeIf { profile.isKids }?.let { it to true }
    is GateScreen.TimeStopped -> kid.id to false
    else -> null
}

internal fun ownerAmong(profiles: List<Profile>): Profile? = profiles.firstOrNull { it.isOwner }

/** A pick from the Settings of [open]'s shell: the only kind that may wait while a shell shows (Q29). */
private fun PickedFile.isSettingsPickOf(open: Long?): Boolean =
    open != null && request.kind != PickKind.Restore && request.profileId == open
