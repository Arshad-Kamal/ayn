package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockPass
import com.arshad.ayn.core.lock.LockStatus
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.openOwnerGate
import com.arshad.ayn.feature.profiles.UnlockEntry
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings › Profiles (§ B3p, M5-D15): the owner manages every profile (add, reset a lock,
 * remove), each person their own name, colour and lock. Add a profile asks which kind first; a
 * kids profile is added by [KidsAddViewModel], and its row says what it is allowed ([filters]).
 * Owner-only writes ask for the owner's lock first while the owner has one; changing or removing
 * your own lock asks for the current one. A pass is asked for when its flow starts and kept
 * only for that flow (M5-D15): the owner's for the add form, yours while a new lock is chosen
 * and entered. Every intent acts only from the screen it belongs to, and a result that lands
 * after the screen changed is dropped.
 * Main thread. [timeSource] runs lock waits and must count deep sleep (`elapsedRealtime`).
 */
class ProfilesViewModel(
    private val profileStore: ProfileStore,
    private val locks: ProfileLocks,
    filters: FilterStore,
    session: ProfileSession,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { (_uiState.value.screen as? ProfilesScreen.EntryScreen)?.entry },
        write = ::updateEntry,
    )

    private var ownerPass: OwnerPass? = null
    private var lockPass: LockPass? = null

    init {
        viewModelScope.launch {
            val profiles = profileStore.profiles()
            combine(profiles, profileStore.contents(), session.selectedId, kidsRules(profiles, filters)) { list, contents, meId, kids ->
                ProfilesSnapshot(list, contents, meId, kids)
            }.collect(::onProfiles)
        }
    }

    private fun onProfiles(listed: ProfilesSnapshot) {
        val (profiles, contents, meId, kids) = listed
        val me = profiles.firstOrNull { it.id == meId }
        val shown = if (me?.isOwner == true) profiles else listOfNotNull(me)
        val rows = shown.map { ProfileRow(it, contents[it.id] ?: ProfileContents.Empty, kids[it.id]?.summary()) }
        val ownerName = profiles.firstOrNull { it.isOwner }?.name
        _uiState.update { it.copy(me = me, rows = rows, ownerName = ownerName) }
        // A profile whose sheet or confirm is open went away (removed, restored over).
        val about = when (val screen = _uiState.value.screen) {
            is ProfilesScreen.ProfileSheet -> screen.profileId
            is ProfilesScreen.ConfirmRemove -> screen.profileId
            else -> null
        }
        if (about != null && rows.none { it.profile.id == about }) show(ProfilesScreen.Page)
    }

    fun onToastShown() {
        _uiState.update { it.copy(toast = null) }
    }

    /** A row on the owner's page: your own opens your sheet, another its owner sheet. */
    fun onOpenProfile(profileId: Long) {
        if (screen != ProfilesScreen.Page) return
        val me = ownerOnPage() ?: return
        show(if (profileId == me.id) ProfilesScreen.OwnSheet else ProfilesScreen.ProfileSheet(profileId))
    }

    /** Add a profile: which kind first. */
    fun onAddProfile() {
        if (screen != ProfilesScreen.Page || !_uiState.value.canAdd) return
        show(ProfilesScreen.ProfileKind)
    }

    /** Standard profile, from the kind sheet: the owner's lock, then its name and colour. */
    fun onStandardProfile() {
        if (screen == ProfilesScreen.ProfileKind) askOwner(OwnerAction.Add)
    }

    /** A kids profile was added from the kind sheet: back to the page, where its row shows. */
    fun onKidAdded() {
        if (screen == ProfilesScreen.ProfileKind) show(ProfilesScreen.Page)
    }

    fun onResetLock(profileId: Long) {
        if (screen != ProfilesScreen.ProfileSheet(profileId)) return
        askOwner(OwnerAction.ResetLock(profileId))
    }

    fun onRemoveProfile(profileId: Long) {
        if (screen != ProfilesScreen.ProfileSheet(profileId)) return
        show(ProfilesScreen.ConfirmRemove(profileId))
    }

    fun onConfirmRemove() {
        val confirm = screen as? ProfilesScreen.ConfirmRemove ?: return
        askOwner(OwnerAction.Remove(confirm.profileId))
    }

    fun onAddNameChange(name: String) {
        if (name.length > MAX_PROFILE_NAME) return
        updateScreen<ProfilesScreen.AddForm> { if (it.saving) it else it.copy(name = name, problem = null) }
    }

    fun onAddTintChange(tint: ProfileTint) {
        updateScreen<ProfilesScreen.AddForm> { if (it.saving) it else it.copy(tint = tint) }
    }

    fun onAddConfirm() {
        val form = screen as? ProfilesScreen.AddForm ?: return
        val pass = ownerPass ?: return
        if (form.saving) return
        updateScreen<ProfilesScreen.AddForm> { it.copy(saving = true) }
        viewModelScope.launch {
            val problem = profileStore.create(pass, form.name, form.tint).problem()
            if (problem == null) {
                show(ProfilesScreen.Page)
            } else {
                updateScreen<ProfilesScreen.AddForm> { it.copy(saving = false, problem = problem) }
            }
        }
    }

    fun onRename() {
        val me = _uiState.value.me ?: return
        if (screen == ownHome()) show(ProfilesScreen.Rename(me.name))
    }

    fun onRenameChange(name: String) {
        if (name.length > MAX_PROFILE_NAME) return
        updateScreen<ProfilesScreen.Rename> { if (it.saving) it else it.copy(name = name, problem = null) }
    }

    fun onRenameDone() {
        val rename = screen as? ProfilesScreen.Rename ?: return
        val me = _uiState.value.me ?: return
        if (rename.saving) return
        updateScreen<ProfilesScreen.Rename> { it.copy(saving = true) }
        viewModelScope.launch {
            val problem = profileStore.rename(me.id, rename.name).problem()
            if (problem == null) {
                // A sheet swiped away meanwhile stays closed.
                if (screen is ProfilesScreen.Rename) show(ownHome())
            } else {
                updateScreen<ProfilesScreen.Rename> { it.copy(saving = false, problem = problem) }
            }
        }
    }

    fun onColour() {
        if (screen == ownHome()) show(ProfilesScreen.Colour)
    }

    fun onTintPicked(tint: ProfileTint) {
        val me = _uiState.value.me ?: return
        if (screen != ProfilesScreen.Colour) return
        show(ownHome())
        viewModelScope.launch { profileStore.setTint(me.id, tint) }
    }

    /** The Lock row: no lock yet → choose one; a lock → change or remove it. */
    fun onLock() {
        val me = _uiState.value.me ?: return
        val from = screen
        if (from != ownHome()) return
        viewModelScope.launch {
            val pass = locks.passWithoutLock(me.id)
            if (screen != from) return@launch
            if (pass == null) {
                show(ProfilesScreen.LockMenu)
            } else {
                show(ProfilesScreen.ChooseKind)
                lockPass = pass
            }
        }
    }

    fun onLockKind(kind: LockKind) {
        if (screen != ProfilesScreen.ChooseKind) return
        val pass = lockPass ?: return
        show(ProfilesScreen.NewLock(kind))
        entry.startSetup(kind) { secret ->
            val saved = locks.setLock(pass, secret)
            show(ownHome())
            if (saved) _uiState.update { it.copy(toast = ProfilesToast.LockSaved) }
        }
    }

    fun onChangeLock(change: LockChange) {
        val me = _uiState.value.me ?: return
        if (screen != ProfilesScreen.LockMenu) return
        viewModelScope.launch {
            val status = locks.status(me.id)
            if (screen != ProfilesScreen.LockMenu) return@launch
            when (status) {
                LockStatus.None -> show(ownHome()) // removed meanwhile
                is LockStatus.Locked -> {
                    show(ProfilesScreen.CurrentLock(change, status.kind))
                    entry.startTry(status.kind, check = { locks.unlock(me.id, it) }) { pass -> onCurrentLockOpened(change, pass) }
                    status.wait?.let(entry::waitFor)
                }
            }
        }
    }

    private suspend fun onCurrentLockOpened(change: LockChange, pass: LockPass) {
        when (change) {
            LockChange.Change -> {
                show(ProfilesScreen.ChooseKind)
                lockPass = pass
            }
            LockChange.Remove -> {
                show(ownHome())
                // The owner's lock is refused removal while a kids profile exists (KP-D14).
                if (locks.setLock(pass, secret = null)) _uiState.update { it.copy(toast = ProfilesToast.LockRemoved) }
            }
        }
    }

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

    /** BACK: lock input erases first (and holds while a check runs); otherwise each screen steps
     *  back one level. Ignored while a form saves. */
    fun onBack() {
        if (entry.back()) return
        stepBack()
    }

    /** Touch Back on a lock's page (the phone, whose pads erase with their own key): one level up
     *  even with presses entered (they are dropped); ignored while a try is checked or saved. */
    fun onEntryLeave() {
        val shown = screen as? ProfilesScreen.EntryScreen ?: return
        if (!shown.entry.checking) stepBack()
    }

    /** A sheet swiped away (the phone) or left under a closed Restrictions page (TV Settings moved
     *  to another category): back to the page, a name being typed dropped (one already
     *  saving still saves). */
    fun onDismiss() {
        when (screen) {
            ProfilesScreen.ProfileKind, ProfilesScreen.OwnSheet, is ProfilesScreen.ProfileSheet, is ProfilesScreen.ConfirmRemove,
            ProfilesScreen.Colour, ProfilesScreen.ChooseKind, ProfilesScreen.LockMenu, is ProfilesScreen.Rename,
            -> show(ProfilesScreen.Page)
            ProfilesScreen.Page, is ProfilesScreen.EntryScreen, is ProfilesScreen.AddForm -> Unit
        }
    }

    private fun stepBack() {
        val back = when (val shown = screen) {
            ProfilesScreen.Page -> return
            ProfilesScreen.ProfileKind -> ProfilesScreen.Page
            is ProfilesScreen.AddForm -> if (shown.saving) return else ProfilesScreen.Page
            is ProfilesScreen.Rename -> if (shown.saving) return else ownHome()
            ProfilesScreen.OwnSheet, is ProfilesScreen.ProfileSheet -> ProfilesScreen.Page
            is ProfilesScreen.ConfirmRemove -> ProfilesScreen.ProfileSheet(shown.profileId)
            is ProfilesScreen.OwnerGate -> when (val action = shown.action) {
                OwnerAction.Add -> ProfilesScreen.ProfileKind
                is OwnerAction.Remove -> ProfilesScreen.ConfirmRemove(action.profileId)
                is OwnerAction.ResetLock -> ProfilesScreen.ProfileSheet(action.profileId)
            }
            ProfilesScreen.Colour, ProfilesScreen.ChooseKind, ProfilesScreen.LockMenu -> ownHome()
            is ProfilesScreen.CurrentLock -> ProfilesScreen.LockMenu
            is ProfilesScreen.NewLock -> ProfilesScreen.ChooseKind
        }
        show(back)
    }

    /** Runs [action] with the owner's pass: free while the owner has no lock, else after the
     *  owner's lock (counted on the owner's tries). */
    private fun askOwner(action: OwnerAction) {
        val owner = ownerOnPage() ?: return
        val from = screen
        viewModelScope.launch {
            val opening = locks.openOwnerGate(owner.id)
            if (screen != from) return@launch
            when (opening) {
                is OwnerGateOpening.Free -> runOwnerAction(action, opening.pass)
                is OwnerGateOpening.Ask -> {
                    show(ProfilesScreen.OwnerGate(action, opening.kind))
                    entry.startTry(opening.kind, check = locks::unlockOwner) { pass -> runOwnerAction(action, pass) }
                    opening.wait?.let(entry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    /** The page shows before a write, so nothing on the screen can start it twice. */
    private suspend fun runOwnerAction(action: OwnerAction, pass: OwnerPass) {
        when (action) {
            OwnerAction.Add -> {
                show(ProfilesScreen.AddForm(tint = nextTint()))
                ownerPass = pass
            }
            is OwnerAction.Remove -> {
                show(ProfilesScreen.Page)
                profileStore.remove(pass, action.profileId)
            }
            is OwnerAction.ResetLock -> {
                show(ProfilesScreen.Page)
                locks.resetLock(pass, action.profileId)
            }
        }
    }

    private val screen: ProfilesScreen get() = _uiState.value.screen

    private fun ownerOnPage(): Profile? = _uiState.value.me?.takeIf { it.isOwner }

    /** Where your own rows are: the owner's sheet, or a non-owner's page. */
    private fun ownHome(): ProfilesScreen =
        if (_uiState.value.me?.isOwner == true) ProfilesScreen.OwnSheet else ProfilesScreen.Page

    /** The first tint no profile has, in the order new profiles take them. */
    private fun nextTint(): ProfileTint {
        val taken = _uiState.value.rows.map { it.profile.tint }.toSet()
        return ProfileTint.entries.firstOrNull { it !in taken } ?: ProfileTint.Amber
    }

    /** Shows [next]; lock input stops, and a held pass is dropped once its flow is left. */
    private fun show(next: ProfilesScreen) {
        entry.clear()
        if (next !is ProfilesScreen.AddForm) ownerPass = null
        if (next != ProfilesScreen.ChooseKind && next !is ProfilesScreen.NewLock) lockPass = null
        _uiState.update { it.copy(screen = next) }
    }

    private inline fun <reified S : ProfilesScreen> updateScreen(change: (S) -> S) {
        _uiState.update { state ->
            val shown = state.screen as? S ?: return@update state
            state.copy(screen = change(shown))
        }
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val current = state.screen as? ProfilesScreen.EntryScreen ?: return@update state
            val shown = when (current) {
                is ProfilesScreen.OwnerGate -> current.copy(entry = change(current.entry))
                is ProfilesScreen.CurrentLock -> current.copy(entry = change(current.entry))
                is ProfilesScreen.NewLock -> current.copy(entry = change(current.entry))
            }
            state.copy(screen = shown)
        }
    }
}

private data class ProfilesSnapshot(
    val profiles: List<Profile>,
    val contents: Map<Long, ProfileContents>,
    val meId: Long,
    val kids: Map<Long, ProfileRules>,
)

/** Each kids profile's rules by id, re-emitted when one changes or the kids profiles do. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun kidsRules(profiles: Flow<List<Profile>>, filters: FilterStore): Flow<Map<Long, ProfileRules>> =
    profiles.map { list -> list.filter { it.isKids }.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> filters.rules(id).map { id to it } }) { it.toMap() }
            }
        }

private fun ProfileRules.summary(): KidSummary =
    KidSummary(approvedVideos = rules.count { it.rule is ApprovedVideo }, dailyLimitMinutes = restrictions.dailyLimitMinutes)

/** What a create or rename refused, `null` when it went through. */
private fun ProfileEdit.problem(): NameProblem? = when (this) {
    is ProfileEdit.Done -> null
    ProfileEdit.NameInvalid -> NameProblem.Missing
    ProfileEdit.NameTaken -> NameProblem.Taken
    ProfileEdit.Full -> NameProblem.Full
    // This screen adds and renames standard profiles only; a kids profile's add flow checks the owner's lock first.
    ProfileEdit.OwnerLockNeeded -> error("a standard profile never needs the owner's lock")
}
