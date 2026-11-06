package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.openOwnerGate
import com.arshad.ayn.feature.profiles.setOwnerLockIfNone
import java.time.DayOfWeek
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adding a kids profile from the owner's Settings › Profiles (`kp-kids` nolock…kidtime; KP-D3,
 * D8, D10, D14). It starts with the owner's lock (M5-D15) and keeps that pass for the flow; while
 * the owner has no lock it asks for one first, since only the owner's lock takes a child out of
 * a kids profile. Then the name and colour, the approved channels (the owner's subscriptions, or
 * any channel YouTube's search finds) and the time rules, all written when Add is pressed: the
 * profile, its restrictions, its channels. A write that fails removes the new profile again, so
 * a kids profile never stays with only part of its rules; the step says so and Add retries.
 * Leaving the flow meanwhile doesn't stop the writes (only the process dying between them can
 * leave part of them: accepted, KP.9).
 * Every intent acts only from the screen it belongs to. Main thread. [subscriptions] are the open
 * profile's, the owner's here; [extractor] searches YouTube for Find a channel. [timeSource] runs
 * lock waits and must count deep sleep.
 */
class KidsAddViewModel(
    private val profileStore: ProfileStore,
    private val locks: ProfileLocks,
    private val filters: FilterStore,
    subscriptions: SubscriptionStore,
    private val extractor: Extractor,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(KidsAddUiState())
    val uiState: StateFlow<KidsAddUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = ::shownEntry,
        write = ::updateEntry,
    )

    private var profiles: List<Profile> = emptyList()

    /** The owner's pass while the flow shows; dropped when it closes ([show]). */
    private var pass: OwnerPass? = null
    private var search: Job? = null

    init {
        viewModelScope.launch {
            profileStore.profiles().collect { list ->
                profiles = list
                _uiState.update { it.copy(owner = list.firstOrNull { p -> p.isOwner }) }
            }
        }
        viewModelScope.launch {
            subscriptions.subscriptions().collect { channels -> _uiState.update { it.copy(ownChannels = channels) } }
        }
    }

    /** Kids profile, from Add a profile's kind sheet: the owner's lock (or a first one), then step 1. */
    fun onStart() {
        val owner = _uiState.value.owner ?: return
        if (screen != KidsAddScreen.Closed || profiles.size >= MAX_PROFILES) return
        _uiState.update { it.copy(draft = KidDraft(tint = nextTint())) }
        viewModelScope.launch {
            val opening = locks.openOwnerGate(owner.id)
            if (screen != KidsAddScreen.Closed) return@launch
            when (opening) {
                // Free: the owner has no lock, which a kids profile can't do without (KP-D14).
                is OwnerGateOpening.Free -> {
                    show(KidsAddScreen.OwnerLockNeeded())
                    pass = opening.pass
                }
                is OwnerGateOpening.Ask -> {
                    show(KidsAddScreen.OwnerGate(opening.kind))
                    entry.startTry(opening.kind, check = locks::unlockOwner) { granted ->
                        show(KidsAddScreen.Form())
                        pass = granted
                    }
                    opening.wait?.let(entry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    /** Lock my profile: on to choosing the owner's lock. */
    fun onOwnerLockStart() {
        val needed = screen as? KidsAddScreen.OwnerLockNeeded ?: return
        if (needed.step == OwnerLockStep.Ask) showKeepingPass(needed.copy(step = OwnerLockStep.ChooseKind))
    }

    fun onOwnerLockKind(kind: LockKind) {
        val needed = screen as? KidsAddScreen.OwnerLockNeeded ?: return
        if (needed.step != OwnerLockStep.ChooseKind) return
        showKeepingPass(needed.copy(step = OwnerLockStep.NewLock(kind)))
        entry.startSetup(kind, ::onOwnerLockEntered)
    }

    private suspend fun onOwnerLockEntered(secret: LockSecret) {
        val owner = _uiState.value.owner
        val locked = owner != null && locks.setOwnerLockIfNone(owner.id, secret)
        showKeepingPass(if (locked) KidsAddScreen.Form() else KidsAddScreen.OwnerLockNeeded())
    }

    fun onNameChange(name: String) {
        if (name.length > MAX_PROFILE_NAME || screen !is KidsAddScreen.Form) return
        updateDraft { it.copy(name = name) }
        showKeepingPass(KidsAddScreen.Form())
    }

    fun onTintChange(tint: ProfileTint) {
        if (screen is KidsAddScreen.Form) updateDraft { it.copy(tint = tint) }
    }

    /** Continue on step 1: a name no profile has, then the channels. */
    fun onFormContinue() {
        if (screen !is KidsAddScreen.Form) return
        val problem = nameProblem(_uiState.value.draft.name)
        showKeepingPass(if (problem == null) KidsAddScreen.Channels() else KidsAddScreen.Form(problem))
    }

    /** OK on a channel of the channels step: approves it, or takes its approval back. */
    fun onToggleChannel(channel: ChannelItem) {
        val channels = screen as? KidsAddScreen.Channels ?: return
        if (channels.find != null) return
        updateDraft { draft ->
            if (draft.approves(channel.url)) draft.withoutChannel(channel.url) else draft.copy(channels = listOf(channel) + draft.channels)
        }
    }

    /** OK on one of the owner's subscriptions. */
    fun onToggleSubscription(subscription: Subscription) {
        onToggleChannel(subscription.toChannelItem())
    }

    fun onFindChannel() {
        val channels = screen as? KidsAddScreen.Channels ?: return
        if (channels.find == null) showKeepingPass(KidsAddScreen.Channels(FindChannel()))
    }

    fun onFindQueryChange(query: String) {
        updateFind { if (it.results == FindResults.Searching) it else it.copy(query = query) }
    }

    /** The keyboard's Done in Find a channel: YouTube's search, keeping the channels it finds. */
    fun onFindSearch() {
        val find = (screen as? KidsAddScreen.Channels)?.find ?: return
        val query = find.query.trim()
        if (query.isEmpty() || find.results == FindResults.Searching) return
        updateFind { it.copy(results = FindResults.Searching) }
        search = viewModelScope.launch {
            val results = try {
                val channels = extractor.search(query).items.filterIsInstance<ChannelItem>()
                if (channels.isEmpty()) FindResults.NoMatch(query) else FindResults.Found(query, channels)
            } catch (_: AccessException) {
                FindResults.Failed
            }
            // A search answering after its sheet closed (and opened again) changes nothing.
            updateFind { if (it.results == FindResults.Searching) it.copy(results = results) else it }
        }
    }

    /** OK on a found channel: approved (once), and back to the channels step. */
    fun onFindPick(channel: ChannelItem) {
        val find = (screen as? KidsAddScreen.Channels)?.find ?: return
        if (find.results !is FindResults.Found) return
        updateDraft { if (it.approves(channel.url)) it else it.copy(channels = listOf(channel) + it.channels) }
        showKeepingPass(KidsAddScreen.Channels())
    }

    fun onChannelsContinue() {
        val channels = screen as? KidsAddScreen.Channels ?: return
        if (channels.find == null) showKeepingPass(KidsAddScreen.Time())
    }

    fun onDayLimit(day: DayOfWeek) {
        openTimeSheet(TimeSheet.DayLimit(day))
    }

    /** A day's limit from its sheet: minutes in 15-minute steps, `null` = No limit. */
    fun onDayLimitPicked(minutes: Int?) {
        val sheet = timeSheet() as? TimeSheet.DayLimit ?: return
        if (!isPickableLimit(minutes)) return
        updateDraft { draft ->
            val limits = if (minutes == null) draft.dailyLimitMinutes - sheet.day else draft.dailyLimitMinutes + (sheet.day to minutes)
            draft.copy(dailyLimitMinutes = limits)
        }
        closeTimeSheet()
    }

    fun onBedtimeStart() {
        openTimeSheet(TimeSheet.BedtimeStart)
    }

    fun onBedtimeEnd() {
        openTimeSheet(TimeSheet.BedtimeEnd)
    }

    /** A bedtime bound from its sheet (minutes after midnight, 30-minute steps); the other bound's
     *  time isn't offered, since the window can't be empty. */
    fun onBedtimePicked(minute: Int) {
        val sheet = timeSheet() ?: return
        val bedtime = _uiState.value.draft.bedtime
        val picked = when (sheet) {
            TimeSheet.BedtimeStart -> Bedtime.orNull(minute, bedtime.endMinute)
            TimeSheet.BedtimeEnd -> Bedtime.orNull(bedtime.startMinute, minute)
            is TimeSheet.DayLimit -> return
        }
        if (picked == null || !isPickableBedtimeMinute(minute)) return
        updateDraft { it.copy(bedtime = picked) }
        closeTimeSheet()
    }

    fun onBedtimeToggle() {
        val time = screen as? KidsAddScreen.Time ?: return
        if (time.sheet == null && !time.saving) updateDraft { it.copy(bedtimeOn = !it.bedtimeOn) }
    }

    /** Add <name>: the profile, then its time rules and channels; any write failing removes it. */
    fun onAdd() {
        val time = screen as? KidsAddScreen.Time ?: return
        val pass = pass ?: return
        if (time.sheet != null || time.saving) return
        val draft = _uiState.value.draft
        showKeepingPass(KidsAddScreen.Time(saving = true))
        viewModelScope.launch {
            withContext(NonCancellable) {
                when (val created = profileStore.create(pass, draft.name, draft.tint, kids = true)) {
                    is ProfileEdit.Done -> {
                        if (writeRules(pass, created.profileId, draft)) {
                            show(KidsAddScreen.Closed)
                            _uiState.update { it.copy(added = created.profileId) }
                        } else {
                            profileStore.remove(pass, created.profileId)
                            showKeepingPass(KidsAddScreen.Time(failed = true))
                        }
                    }
                    ProfileEdit.NameInvalid -> showKeepingPass(KidsAddScreen.Form(NameProblem.Missing))
                    ProfileEdit.NameTaken -> showKeepingPass(KidsAddScreen.Form(NameProblem.Taken))
                    ProfileEdit.Full -> showKeepingPass(KidsAddScreen.Form(NameProblem.Full))
                    // The owner's lock went away meanwhile (KP-D14).
                    ProfileEdit.OwnerLockNeeded -> showKeepingPass(KidsAddScreen.OwnerLockNeeded())
                }
            }
        }
    }

    /** The time rules, then the channels oldest first, so the newest approved reads first. */
    private suspend fun writeRules(pass: OwnerPass, profileId: Long, draft: KidDraft): Boolean {
        val timed = filters.updateRestrictions(pass, profileId) {
            it.copy(dailyLimitMinutes = draft.dailyLimitMinutes, bedtime = draft.bedtime.takeIf { draft.bedtimeOn })
        }
        return timed && draft.channels.asReversed().all { filters.approveChannel(pass, profileId, it) }
    }

    /** The Profiles page has focused the new profile's row. */
    fun onAddedShown() {
        _uiState.update { it.copy(added = null) }
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

    /** BACK: lock input erases first (and holds while a check runs); a sheet closes; otherwise
     *  each step goes back one, and the first closes the flow (the kind sheet waits under it).
     *  Ignored while Add saves. */
    fun onBack() {
        if (entry.back()) return
        stepBack()
    }

    /** Touch Back on a lock's page (the phone, whose pads erase with their own key): one step back
     *  even with presses entered (they are dropped); ignored while a try is checked or saved. */
    fun onEntryLeave() {
        val shown = shownEntry() ?: return
        if (!shown.checking) stepBack()
    }

    /** A sheet swiped away (the phone): Find a channel or a time sheet closes, Choose a lock goes
     *  back to why a lock is needed. */
    fun onDismiss() {
        val sheetShown = when (val shown = screen) {
            is KidsAddScreen.Channels -> shown.find != null
            is KidsAddScreen.Time -> shown.sheet != null
            is KidsAddScreen.OwnerLockNeeded -> shown.step == OwnerLockStep.ChooseKind
            KidsAddScreen.Closed, is KidsAddScreen.OwnerGate, is KidsAddScreen.Form -> false
        }
        if (sheetShown) stepBack()
    }

    private fun stepBack() {
        when (val shown = screen) {
            KidsAddScreen.Closed -> Unit
            is KidsAddScreen.OwnerGate, is KidsAddScreen.Form -> show(KidsAddScreen.Closed)
            is KidsAddScreen.OwnerLockNeeded -> when (shown.step) {
                OwnerLockStep.Ask -> show(KidsAddScreen.Closed)
                OwnerLockStep.ChooseKind -> showKeepingPass(shown.copy(step = OwnerLockStep.Ask))
                is OwnerLockStep.NewLock -> showKeepingPass(shown.copy(step = OwnerLockStep.ChooseKind))
            }
            is KidsAddScreen.Channels -> showKeepingPass(if (shown.find != null) KidsAddScreen.Channels() else KidsAddScreen.Form())
            is KidsAddScreen.Time -> when {
                shown.saving -> Unit
                shown.sheet != null -> closeTimeSheet()
                else -> showKeepingPass(KidsAddScreen.Channels())
            }
        }
    }

    private val screen: KidsAddScreen get() = _uiState.value.screen

    private fun nameProblem(name: String): NameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameProblem.Missing
            profiles.any { it.name.equals(trimmed, ignoreCase = true) } -> NameProblem.Taken
            profiles.size >= MAX_PROFILES -> NameProblem.Full
            else -> null
        }
    }

    /** The first tint no profile has, in the order new profiles take them. */
    private fun nextTint(): ProfileTint {
        val taken = profiles.map { it.tint }.toSet()
        return ProfileTint.entries.firstOrNull { it !in taken } ?: ProfileTint.Violet
    }

    private fun timeSheet(): TimeSheet? = (screen as? KidsAddScreen.Time)?.sheet

    private fun openTimeSheet(sheet: TimeSheet) {
        val time = screen as? KidsAddScreen.Time ?: return
        if (time.sheet == null && !time.saving) showKeepingPass(KidsAddScreen.Time(sheet = sheet))
    }

    private fun closeTimeSheet() {
        showKeepingPass(KidsAddScreen.Time())
    }

    /** Shows [next]: lock input stops, a search in flight is dropped, and the pass goes with a
     *  closing flow. */
    private fun show(next: KidsAddScreen) {
        if (next == KidsAddScreen.Closed) pass = null
        showKeepingPass(next)
    }

    private fun showKeepingPass(next: KidsAddScreen) {
        entry.clear()
        search?.cancel()
        search = null
        _uiState.update { it.copy(screen = next) }
    }

    private fun updateDraft(change: (KidDraft) -> KidDraft) {
        _uiState.update { it.copy(draft = change(it.draft)) }
    }

    private fun updateFind(change: (FindChannel) -> FindChannel) {
        _uiState.update { state ->
            val channels = state.screen as? KidsAddScreen.Channels ?: return@update state
            val find = channels.find ?: return@update state
            state.copy(screen = channels.copy(find = change(find)))
        }
    }

    private fun shownEntry(): UnlockEntry? = when (val shown = screen) {
        is KidsAddScreen.OwnerGate -> shown.entry
        is KidsAddScreen.OwnerLockNeeded -> (shown.step as? OwnerLockStep.NewLock)?.entry
        else -> null
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val next = when (val shown = state.screen) {
                is KidsAddScreen.OwnerGate -> shown.copy(entry = change(shown.entry))
                is KidsAddScreen.OwnerLockNeeded -> {
                    val step = shown.step as? OwnerLockStep.NewLock ?: return@update state
                    shown.copy(step = step.copy(entry = change(step.entry)))
                }
                else -> return@update state
            }
            state.copy(screen = next)
        }
    }
}

private fun KidDraft.withoutChannel(channelUrl: String): KidDraft {
    val key = canonicalChannelUrl(channelUrl)
    return copy(channels = channels.filterNot { canonicalChannelUrl(it.url) == key })
}

/** A subscription as the channel approving takes it; what a subscription doesn't keep is unknown. */
private fun Subscription.toChannelItem(): ChannelItem = ChannelItem(
    name = name,
    url = channelUrl,
    avatarUrl = avatarUrl,
    subscriberCount = -1,
    description = null,
    verified = false,
)
