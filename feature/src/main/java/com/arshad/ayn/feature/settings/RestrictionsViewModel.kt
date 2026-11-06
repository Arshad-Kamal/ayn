package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.MAX_KEYWORD_LENGTH
import com.arshad.ayn.core.store.MAX_PATTERN_LENGTH
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.OwnerGateOpening
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.openOwnerGate
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The owner's restrictions on another profile (`kp-filters` restrictions, patterns, patternbad;
 * KP-D12, D7): opened from that profile's sheet, after the owner's lock while the owner has one
 * (counted on the owner's tries); its pass lives while the page shows (M5-D15). The same rows as
 * Filters (filter packs too, FPK-D15) plus Hide Shorts and Patterns, written as owner
 * restrictions. A pattern that doesn't compile stays in its field with RE2/J's reason. A profile
 * that goes away (removed, restored over) closes the page. Main thread. [timeSource] runs lock waits and must count deep sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestrictionsViewModel(
    private val filters: FilterStore,
    profileStore: ProfileStore,
    private val locks: ProfileLocks,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RestrictionsUiState())
    val uiState: StateFlow<RestrictionsUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { (screen as? RestrictionsScreen.OwnerGate)?.entry },
        write = ::updateEntry,
    )

    /** The owner's pass while the page shows; dropped when it closes ([show]). */
    private var pass: OwnerPass? = null

    init {
        viewModelScope.launch {
            profileStore.profiles().collect { profiles ->
                _uiState.update { it.copy(profiles = profiles) }
                val shown = screen.profileId
                if (shown != null && profiles.none { it.id == shown }) show(RestrictionsScreen.Closed)
            }
        }
        viewModelScope.launch {
            _uiState.map { it.screen.profileId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else filters.rules(id).map { id to it } }
                .collect { loaded -> loaded?.let { (id, rules) -> onRules(id, rules) } }
        }
    }

    private fun onRules(profileId: Long, rules: ProfileRules) {
        _uiState.update { if (it.screen.profileId == profileId) it.copy(rules = rules) else it }
        val state = _uiState.value
        val emptied = when ((state.screen as? RestrictionsScreen.Page)?.sheet) {
            RestrictionsSheet.BlockedChannels -> state.channels.isEmpty()
            RestrictionsSheet.BlockedVideos -> state.videos.isEmpty()
            else -> false
        }
        if (emptied) showSheet(null)
    }

    /** Restrictions on [profileId]'s sheet: the owner's lock, then the page. */
    fun onOpen(profileId: Long) {
        val state = _uiState.value
        val owner = state.owner ?: return
        if (screen != RestrictionsScreen.Closed || profileId == owner.id || state.profiles.none { it.id == profileId }) return
        viewModelScope.launch {
            val opening = locks.openOwnerGate(owner.id)
            if (screen != RestrictionsScreen.Closed) return@launch
            when (opening) {
                is OwnerGateOpening.Free -> openPage(profileId, opening.pass)
                is OwnerGateOpening.Ask -> {
                    show(RestrictionsScreen.OwnerGate(profileId, opening.kind))
                    entry.startTry(opening.kind, check = locks::unlockOwner) { pass -> openPage(profileId, pass) }
                    opening.wait?.let(entry::waitFor)
                }
                OwnerGateOpening.Changed -> Unit
            }
        }
    }

    private fun openPage(profileId: Long, pass: OwnerPass) {
        show(RestrictionsScreen.Page(profileId))
        this.pass = pass
    }

    /** Focus has landed on the page's first row. */
    fun onPageArrived() {
        updatePage { it.copy(arriving = false) }
    }

    fun onSwitch(switch: FilterSwitch, on: Boolean) {
        updateRestrictions {
            when (switch) {
                FilterSwitch.HideLive -> it.copy(hideLive = on)
                FilterSwitch.HideMembersOnly -> it.copy(hideMembersOnly = on)
            }
        }
    }

    fun onHideShorts(on: Boolean) {
        updateRestrictions { it.copy(hideShorts = on) }
    }

    fun onOpenRestrictedMode() {
        openSheet(RestrictionsSheet.RestrictedMode)
    }

    fun onRestrictedModePicked(mode: RestrictedMode) {
        if (sheet != RestrictionsSheet.RestrictedMode) return
        showSheet(null)
        updateRestrictions { it.copy(restrictedMode = mode) }
    }

    fun onOpenLength() {
        openSheet(RestrictionsSheet.Length)
    }

    fun onShorterPicked(minutes: Int?) {
        if (sheet != RestrictionsSheet.Length) return
        updateRestrictions { r -> r.lengthLimits.withShorterThan(minutes)?.let { r.copy(lengthLimits = it) } ?: r }
    }

    fun onLongerPicked(minutes: Int?) {
        if (sheet != RestrictionsSheet.Length) return
        updateRestrictions { r -> r.lengthLimits.withLongerThan(minutes)?.let { r.copy(lengthLimits = it) } ?: r }
    }

    fun onOpenKeywords() {
        if (_uiState.value.rules != null) openSheet(RestrictionsSheet.Keywords())
    }

    fun onOpenPatterns() {
        if (_uiState.value.rules != null) openSheet(RestrictionsSheet.Patterns())
    }

    /** Add a word or Add a pattern: the row turns into its field. */
    fun onAddRule() {
        when (sheet) {
            RestrictionsSheet.Keywords() -> showSheet(RestrictionsSheet.Keywords(RuleDraft()))
            RestrictionsSheet.Patterns() -> showSheet(RestrictionsSheet.Patterns(RuleDraft()))
            else -> Unit
        }
    }

    fun onRuleChange(text: String) {
        val max = if (sheet is RestrictionsSheet.Patterns) MAX_PATTERN_LENGTH else MAX_KEYWORD_LENGTH
        if (text.length > max) return
        updateDraft { if (it.saving) it else RuleDraft(text) }
    }

    /** Done on the keyboard: a blank entry just ends the typing; otherwise it is saved and the
     *  Add row comes back, or the field stays with why it wasn't. */
    fun onRuleDone() {
        val shown = sheet ?: return
        val draft = shown.draft ?: return
        val profileId = screen.profileId ?: return
        val owner = pass ?: return
        if (draft.saving) return
        if (draft.text.isBlank()) {
            showSheet(shown.withDraft(null))
            return
        }
        val rule: ContentRule = if (shown is RestrictionsSheet.Patterns) Pattern(draft.text) else Keyword(draft.text)
        updateDraft { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            val problem = filters.addRestriction(owner, profileId, rule).problem(rule)
            val now = sheet
            if (now?.draft?.saving != true || now::class != shown::class) return@launch
            if (problem == null) showSheet(now.withDraft(null)) else updateDraft { it.copy(saving = false, problem = problem) }
        }
    }

    fun onRemoveRule(text: String) {
        when (sheet) {
            RestrictionsSheet.Keywords() -> removeRestriction(Keyword(text))
            RestrictionsSheet.Patterns() -> removeRestriction(Pattern(text))
            else -> Unit
        }
    }

    fun onOpenBlockedChannels() {
        if (_uiState.value.channels.isNotEmpty()) openSheet(RestrictionsSheet.BlockedChannels)
    }

    fun onUnblockChannel(channel: BlockedChannel) {
        if (sheet == RestrictionsSheet.BlockedChannels) removeRestriction(channel)
    }

    fun onOpenBlockedVideos() {
        if (_uiState.value.videos.isNotEmpty()) openSheet(RestrictionsSheet.BlockedVideos)
    }

    fun onUnblockVideo(video: BlockedVideo) {
        if (sheet == RestrictionsSheet.BlockedVideos) removeRestriction(video)
    }

    fun onOpenPack(pack: FilterPack) {
        if (_uiState.value.rules != null) openSheet(RestrictionsSheet.Pack(pack))
    }

    /** Use this pack, as Filters' (FPK-D2, D7): the suggestions offered are the restrictions the
     *  owner hasn't set on this profile yet. */
    fun onUsePack(pack: FilterPack, on: Boolean) {
        if (sheet != RestrictionsSheet.Pack(pack)) return
        val restrictions = _uiState.value.rules?.restrictions ?: return
        updateRestrictions { it.copy(filterPacks = it.filterPacks.withPack(pack, on)) }
        if (pack == FilterPack.HarmfulForKids && on && !restrictions.filterPacks.harmfulForKids) {
            suggestionOffer(hidesShorts = restrictions.hideShorts, hidesLive = restrictions.hideLive, limits = restrictions.lengthLimits)
                ?.let { showSheet(RestrictionsSheet.Suggestions(it)) }
        }
    }

    fun onPackOption(option: PackOption, on: Boolean) {
        if (sheet == RestrictionsSheet.Pack(option.pack)) updateRestrictions { it.copy(filterPacks = it.filterPacks.withOption(option, on)) }
    }

    /** An Islamic content only channel's switch, from the shown profile's defaults (kids or not). */
    fun onIslamicChannel(channelId: String, on: Boolean) {
        if (sheet != RestrictionsSheet.Pack(FilterPack.IslamicOnly)) return
        val kids = _uiState.value.target?.isKids ?: return
        updateRestrictions { it.copy(filterPacks = it.filterPacks.withIslamicChannel(channelId, on, kids)) }
    }

    fun onSuggestionTicked(suggestion: Suggestion, on: Boolean) {
        val shown = sheet as? RestrictionsSheet.Suggestions ?: return
        showSheet(RestrictionsSheet.Suggestions(shown.offer.withTick(suggestion, on)))
    }

    /** Turn on: the ticked suggestions are set as restrictions, and Harmful for kids' sheet comes back. */
    fun onTurnOnSuggestions() {
        val shown = sheet as? RestrictionsSheet.Suggestions ?: return
        showSheet(RestrictionsSheet.Pack(FilterPack.HarmfulForKids))
        val ticked = shown.offer.ticked
        if (ticked.isNotEmpty()) updateRestrictions { it.withSuggestions(ticked) }
    }

    /** Not now: nothing is set, and Harmful for kids' sheet comes back. */
    fun onNotNow() {
        if (sheet is RestrictionsSheet.Suggestions) showSheet(RestrictionsSheet.Pack(FilterPack.HarmfulForKids))
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

    /** BACK: lock input erases first; a rule being typed is dropped (not while it saves), the
     *  suggestions are Not now, a sheet closes, then the page or the gate closes (back to the
     *  profile's sheet). */
    fun onBack() {
        if (entry.back()) return
        when (val shown = screen) {
            RestrictionsScreen.Closed -> Unit
            is RestrictionsScreen.OwnerGate -> show(RestrictionsScreen.Closed)
            is RestrictionsScreen.Page -> {
                val open = shown.sheet
                val draft = open?.draft
                when {
                    open == null -> show(RestrictionsScreen.Closed)
                    draft != null -> if (!draft.saving) showSheet(open.withDraft(null))
                    open is RestrictionsSheet.Suggestions -> onNotNow()
                    else -> showSheet(null)
                }
            }
        }
    }

    /** Settings moved to another category: an open page closes (its sheets open only over it). */
    fun onLeave() {
        if (screen is RestrictionsScreen.Page) show(RestrictionsScreen.Closed)
    }

    /** A sheet swiped away: it closes, a rule being typed with it (one already saving still saves). */
    fun onDismiss() {
        showSheet(null)
    }

    /** Touch Back on the owner's lock (the phone, whose pads erase with their own key): the gate
     *  closes even with presses entered (they are dropped); ignored while a try is checked. */
    fun onGateLeave() {
        val gate = screen as? RestrictionsScreen.OwnerGate ?: return
        if (!gate.entry.checking) show(RestrictionsScreen.Closed)
    }

    private val screen: RestrictionsScreen get() = _uiState.value.screen

    private val sheet: RestrictionsSheet? get() = (screen as? RestrictionsScreen.Page)?.sheet

    /** Shows [next]; lock input stops, the pass goes once the page closes, and another profile's
     *  rules are dropped. */
    private fun show(next: RestrictionsScreen) {
        entry.clear()
        if (next !is RestrictionsScreen.Page) pass = null
        _uiState.update { it.copy(screen = next, rules = if (next.profileId == it.screen.profileId) it.rules else null) }
    }

    private fun openSheet(next: RestrictionsSheet) {
        updatePage { if (it.sheet == null) it.copy(sheet = next) else it }
    }

    private fun showSheet(next: RestrictionsSheet?) {
        updatePage { it.copy(sheet = next) }
    }

    private fun updatePage(change: (RestrictionsScreen.Page) -> RestrictionsScreen.Page) {
        _uiState.update { state ->
            val page = state.screen as? RestrictionsScreen.Page ?: return@update state
            state.copy(screen = change(page))
        }
    }

    private fun updateDraft(change: (RuleDraft) -> RuleDraft) {
        updatePage { page ->
            val shown = page.sheet ?: return@updatePage page
            val draft = shown.draft ?: return@updatePage page
            page.copy(sheet = shown.withDraft(change(draft)))
        }
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val gate = state.screen as? RestrictionsScreen.OwnerGate ?: return@update state
            state.copy(screen = gate.copy(entry = change(gate.entry)))
        }
    }

    private fun updateRestrictions(transform: (ProfileRestrictions) -> ProfileRestrictions) {
        val profileId = (screen as? RestrictionsScreen.Page)?.profileId ?: return
        val owner = pass ?: return
        viewModelScope.launch { filters.updateRestrictions(owner, profileId, transform) }
    }

    private fun removeRestriction(rule: ContentRule) {
        val profileId = screen.profileId ?: return
        val owner = pass ?: return
        viewModelScope.launch { filters.removeRestriction(owner, profileId, rule) }
    }
}

private val RestrictionsSheet.draft: RuleDraft?
    get() = when (this) {
        is RestrictionsSheet.Keywords -> draft
        is RestrictionsSheet.Patterns -> draft
        else -> null
    }

/** This rule list sheet with [draft]; any other sheet as it is. */
private fun RestrictionsSheet.withDraft(draft: RuleDraft?): RestrictionsSheet = when (this) {
    is RestrictionsSheet.Keywords -> copy(draft = draft)
    is RestrictionsSheet.Patterns -> copy(draft = draft)
    else -> this
}
