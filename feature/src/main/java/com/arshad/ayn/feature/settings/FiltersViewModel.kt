package com.arshad.ayn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.MAX_KEYWORD_LENGTH
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SelfRule
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
 * Settings › Filters (`kp-filters`; KP-D12 preferences): the session profile's own Restricted
 * Mode, live and members-only switches, length bounds, blocked channels and videos and keywords,
 * and its filter packs (FPK-D1); no lock is asked, as the profile can undo each. A change goes to
 * the profile whose filters show (M7-D9); a failed write leaves the stored value showing. A sheet
 * opens only from the page; the suggestions replace Harmful for kids' sheet and return to it. A
 * Restricted Mode choice closes its sheet, length picks don't (two groups). A list sheet closes
 * when its last rule goes. On the owner's page a blocked channel or video the owner also set on
 * other profiles asks where to unblock it ([FiltersSheet.UnblockWhere], KP.15): everywhere lifts
 * those restrictions too, with the owner's pass of their own session ([ProfileLocks]). Main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FiltersViewModel(
    private val profileSettings: ProfileSettingsStore,
    private val filters: FilterStore,
    profileStore: ProfileStore,
    private val locks: ProfileLocks,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FiltersUiState())
    val uiState: StateFlow<FiltersUiState> = _uiState.asStateFlow()

    init {
        val settings = profileSettings.settings()
        viewModelScope.launch {
            settings.collect { state ->
                _uiState.update { if (it.profile?.profileId == state.profileId) it.copy(profile = state) else it.copy(profile = state, rules = null) }
            }
        }
        viewModelScope.launch {
            settings.map { it.profileId }.distinctUntilChanged()
                .flatMapLatest { id -> filters.rules(id).map { id to it } }
                .collect { (id, rules) -> onRules(id, rules) }
        }
        val profiles = profileStore.profiles()
        viewModelScope.launch {
            profiles.collect { list -> _uiState.update { it.copy(owner = list.firstOrNull { p -> p.isOwner }, profiles = list) } }
        }
        viewModelScope.launch {
            combine(settings.map { it.profileId }.distinctUntilChanged(), profiles) { id, list -> id to list }
                .flatMapLatest { (id, list) -> elsewhereRules(id, list) }
                .collect { rules -> _uiState.update { it.copy(elsewhere = rules) } }
        }
    }

    /** Every other profile's rules while [profileId] is the owner's; none otherwise. */
    private fun elsewhereRules(profileId: Long, profiles: List<Profile>): Flow<Map<Long, ProfileRules>> {
        val ownersPage = profiles.any { it.id == profileId && it.isOwner }
        val others = profiles.filter { it.id != profileId }
        if (!ownersPage || others.isEmpty()) return flowOf(emptyMap())
        return combine(others.map { other -> filters.rules(other.id).map { other.id to it } }) { it.toMap() }
    }

    private fun onRules(profileId: Long, rules: ProfileRules) {
        _uiState.update { if (it.profile?.profileId == profileId) it.copy(rules = rules) else it }
        // Unblocked meanwhile (another screen, a restore): nothing left to ask, back to its list.
        (sheet as? FiltersSheet.UnblockWhere)?.let { shown ->
            val stillBlocked = _uiState.value.rules?.rules.orEmpty().any { !it.byOwner && it.rule::class == shown.rule::class && it.rule.key == shown.rule.key }
            if (!stillBlocked) backToList(shown, focus = null)
        }
        val state = _uiState.value
        val emptied = when (state.sheet) {
            FiltersSheet.BlockedChannels -> state.ownChannels.isEmpty()
            FiltersSheet.BlockedVideos -> state.ownVideos.isEmpty()
            else -> false
        }
        if (emptied) close()
    }

    fun onSwitch(switch: FilterSwitch, on: Boolean) {
        updateProfile {
            when (switch) {
                FilterSwitch.HideLive -> it.copy(hideLive = on)
                FilterSwitch.HideMembersOnly -> it.copy(hideMembersOnly = on)
            }
        }
    }

    fun onOpenRestrictedMode() {
        if (_uiState.value.profile != null) open(FiltersSheet.RestrictedMode)
    }

    fun onRestrictedModePicked(mode: RestrictedMode) {
        if (sheet != FiltersSheet.RestrictedMode) return
        close()
        updateProfile { it.copy(restrictedMode = mode) }
    }

    fun onOpenLength() {
        if (_uiState.value.profile != null) open(FiltersSheet.Length)
    }

    /** [minutes] is one of [SHORTER_THAN_MINUTES], or `null` for Off. A longer-than bound not
     *  above it goes (only a restored value can be). */
    fun onShorterPicked(minutes: Int?) {
        if (sheet != FiltersSheet.Length) return
        updateProfile { settings -> settings.lengthLimits.withShorterThan(minutes)?.let { settings.copy(lengthLimits = it) } ?: settings }
    }

    /** [minutes] is one of [LONGER_THAN_MINUTES], or `null` for Off. A shorter-than bound not
     *  below it goes (only a restored value can be). */
    fun onLongerPicked(minutes: Int?) {
        if (sheet != FiltersSheet.Length) return
        updateProfile { settings -> settings.lengthLimits.withLongerThan(minutes)?.let { settings.copy(lengthLimits = it) } ?: settings }
    }

    fun onOpenKeywords() {
        if (_uiState.value.rules != null) open(FiltersSheet.Keywords())
    }

    fun onAddKeyword() {
        if (sheet == FiltersSheet.Keywords()) show(FiltersSheet.Keywords(RuleDraft()))
    }

    fun onKeywordChange(text: String) {
        if (text.length > MAX_KEYWORD_LENGTH) return
        updateDraft { if (it.saving) it else RuleDraft(text) }
    }

    /** Done on the keyboard: a blank word just ends the typing; otherwise the word is saved and
     *  the Add row comes back, or the field stays with why it wasn't. */
    fun onKeywordDone() {
        val draft = (sheet as? FiltersSheet.Keywords)?.draft ?: return
        val profileId = _uiState.value.profile?.profileId ?: return
        if (draft.saving) return
        if (draft.text.isBlank()) {
            show(FiltersSheet.Keywords())
            return
        }
        updateDraft { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            val keyword = Keyword(draft.text)
            val problem = filters.addOwn(profileId, keyword).problem(keyword)
            if ((sheet as? FiltersSheet.Keywords)?.draft?.saving != true) return@launch
            if (problem == null) show(FiltersSheet.Keywords()) else updateDraft { it.copy(saving = false, problem = problem) }
        }
    }

    fun onRemoveKeyword(word: String) {
        if (sheet == FiltersSheet.Keywords()) removeOwn(Keyword(word))
    }

    fun onOpenBlockedChannels() {
        if (_uiState.value.ownChannels.isNotEmpty()) open(FiltersSheet.BlockedChannels)
    }

    fun onUnblockChannel(channel: BlockedChannel) {
        if (sheet == FiltersSheet.BlockedChannels) unblock(channel)
    }

    fun onOpenBlockedVideos() {
        if (_uiState.value.ownVideos.isNotEmpty()) open(FiltersSheet.BlockedVideos)
    }

    fun onUnblockVideo(video: BlockedVideo) {
        if (sheet == FiltersSheet.BlockedVideos) unblock(video)
    }

    /** Unblock in all profiles: the profile's own block goes, and the owner's restriction on each
     *  other profile carrying it; the list comes back, focus on the row after it. Without the
     *  owner's pass (not the owner's session) nothing is removed. */
    fun onUnblockEverywhere() {
        val shown = sheet as? FiltersSheet.UnblockWhere ?: return
        val profileId = _uiState.value.profile?.profileId ?: return
        val others = _uiState.value.alsoIn(shown.rule).map { it.id }
        backToList(shown, focus = keyAfter(shown.rule))
        viewModelScope.launch {
            val pass = locks.ownerPassInOwnSession() ?: return@launch
            filters.removeOwn(profileId, shown.rule)
            others.forEach { filters.removeRestriction(pass, it, shown.rule) }
        }
    }

    /** Only in your profile: the other profiles keep it blocked. */
    fun onUnblockHereOnly() {
        val shown = sheet as? FiltersSheet.UnblockWhere ?: return
        backToList(shown, focus = keyAfter(shown.rule))
        removeOwn(shown.rule)
    }

    fun onOpenPack(pack: FilterPack) {
        if (_uiState.value.profile != null) open(FiltersSheet.Pack(pack))
    }

    /** Use this pack (FPK-D2: Halal only and Islamic content only one at a time). Harmful for kids
     *  going on offers the suggestions the profile doesn't have yet (FPK-D7), setting none. */
    fun onUsePack(pack: FilterPack, on: Boolean) {
        if (sheet != FiltersSheet.Pack(pack)) return
        val settings = _uiState.value.profile?.settings ?: return
        updateProfile { it.copy(filterPacks = it.filterPacks.withPack(pack, on)) }
        if (pack == FilterPack.HarmfulForKids && on && !settings.filterPacks.harmfulForKids) {
            suggestionOffer(hidesShorts = !settings.showShorts, hidesLive = settings.hideLive, limits = settings.lengthLimits)
                ?.let { show(FiltersSheet.Suggestions(it)) }
        }
    }

    fun onPackOption(option: PackOption, on: Boolean) {
        if (sheet == FiltersSheet.Pack(option.pack)) updateProfile { it.copy(filterPacks = it.filterPacks.withOption(option, on)) }
    }

    /** An Islamic content only channel's switch (FPK-D12); an id the list doesn't carry is ignored. */
    fun onIslamicChannel(channelId: String, on: Boolean) {
        if (sheet != FiltersSheet.Pack(FilterPack.IslamicOnly)) return
        val kids = _uiState.value.profile?.kids ?: return
        updateProfile { it.copy(filterPacks = it.filterPacks.withIslamicChannel(channelId, on, kids)) }
    }

    fun onSuggestionTicked(suggestion: Suggestion, on: Boolean) {
        val shown = sheet as? FiltersSheet.Suggestions ?: return
        show(FiltersSheet.Suggestions(shown.offer.withTick(suggestion, on)))
    }

    /** Turn on: the ticked suggestions are set, and Harmful for kids' sheet comes back. */
    fun onTurnOnSuggestions() {
        val shown = sheet as? FiltersSheet.Suggestions ?: return
        show(FiltersSheet.Pack(FilterPack.HarmfulForKids))
        val ticked = shown.offer.ticked
        if (ticked.isNotEmpty()) updateProfile { it.withSuggestions(ticked) }
    }

    /** Not now: nothing is set, and Harmful for kids' sheet comes back. */
    fun onNotNow() {
        if (sheet is FiltersSheet.Suggestions) show(FiltersSheet.Pack(FilterPack.HarmfulForKids))
    }

    /** A sheet swiped away: it closes, a word being typed with it (one already saving still saves). */
    fun onDismiss() {
        close()
    }

    /** BACK: a word being typed is dropped (not while it saves); Unblock where? returns to its
     *  list with the rule still blocked, focus on its row; the suggestions are Not now; otherwise
     *  the sheet closes. */
    fun onBack() {
        val shown = sheet ?: return
        if (shown is FiltersSheet.UnblockWhere) return backToList(shown, focus = shown.rule.key)
        if (shown is FiltersSheet.Suggestions) return onNotNow()
        val draft = (shown as? FiltersSheet.Keywords)?.draft
        when {
            draft == null -> close()
            !draft.saving -> show(FiltersSheet.Keywords())
        }
    }

    /** A block the owner also set elsewhere asks where first; any other unblocks at once. */
    private fun unblock(rule: SelfRule) {
        if (_uiState.value.alsoIn(rule).isEmpty()) removeOwn(rule) else show(FiltersSheet.UnblockWhere(rule))
    }

    private fun backToList(shown: FiltersSheet.UnblockWhere, focus: String?) {
        _uiState.update { it.copy(sheet = shown.list, listFocus = focus) }
    }

    /** The key of the row after [rule] in its list, else the one before; `null` when it is alone. */
    private fun keyAfter(rule: SelfRule): String? {
        val state = _uiState.value
        val keys = if (rule is BlockedVideo) state.ownVideos.map { it.rule.key } else state.ownChannels.map { it.rule.key }
        val index = keys.indexOf(rule.key)
        return keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1)
    }

    private val sheet: FiltersSheet? get() = _uiState.value.sheet

    private fun open(sheet: FiltersSheet) {
        _uiState.update { if (it.sheet == null) it.copy(sheet = sheet, listFocus = null) else it }
    }

    private fun show(sheet: FiltersSheet) {
        _uiState.update { it.copy(sheet = sheet) }
    }

    private fun close() {
        _uiState.update { it.copy(sheet = null) }
    }

    private fun updateDraft(change: (RuleDraft) -> RuleDraft) {
        _uiState.update { state ->
            val draft = (state.sheet as? FiltersSheet.Keywords)?.draft ?: return@update state
            state.copy(sheet = FiltersSheet.Keywords(change(draft)))
        }
    }

    private fun updateProfile(transform: (ProfileSettings) -> ProfileSettings) {
        val profileId = _uiState.value.profile?.profileId ?: return
        viewModelScope.launch { profileSettings.update(profileId, transform) }
    }

    private fun removeOwn(rule: SelfRule) {
        val profileId = _uiState.value.profile?.profileId ?: return
        viewModelScope.launch { filters.removeOwn(profileId, rule) }
    }
}
