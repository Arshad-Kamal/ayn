package com.arshad.ayn.feature.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.lock.LockStatus
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.feature.profiles.LockEntryInput
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.settings.TimeSheet
import com.arshad.ayn.feature.settings.isPickableBedtimeMinute
import com.arshad.ayn.feature.settings.isPickableLimit
import java.time.DayOfWeek
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Parent page in kids profile [kidId]'s shell (`kp-kids` parentgate, parent; KP-D10, D11):
 * each time it shows ([onShown]) it asks for the owner's lock, counted on the kid's own tries
 * (KP-D9; free while the owner has none), and its pass lives only while it shows: [onHidden]
 * drops it, so a page left open never stays open for the kid. With the pass the owner removes
 * approved channels and videos, sets each day's limit and bedtime (owner restrictions), opens the
 * review, or leaves the kid's profile through [leave] without a second ask. Main thread.
 * [timeSource] runs lock waits and must count deep sleep.
 */
class ParentViewModel(
    private val kidId: Long,
    profileStore: ProfileStore,
    private val locks: ProfileLocks,
    private val filters: FilterStore,
    private val leave: (OwnerPass) -> Unit,
    timeSource: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ParentUiState())
    val uiState: StateFlow<ParentUiState> = _uiState.asStateFlow()

    private val entry = LockEntryInput(
        scope = viewModelScope,
        timeSource = timeSource,
        read = { (screen as? ParentScreen.OwnerGate)?.entry },
        write = ::updateEntry,
    )

    private var pass: OwnerPass? = null
    private var showing = false
    private var asking: Job? = null

    /** The owner's pass while the page shows, for the review's Keep and Block (M5-D15); `null`
     *  otherwise. */
    val ownerPass: OwnerPass? get() = pass

    init {
        viewModelScope.launch {
            profileStore.profiles().collect { profiles ->
                _uiState.update { state ->
                    state.copy(kid = profiles.firstOrNull { it.id == kidId }, owner = profiles.firstOrNull { it.isOwner })
                }
            }
        }
        viewModelScope.launch {
            filters.rules(kidId).collect { rules ->
                _uiState.update { state ->
                    state.copy(rules = rules, bedtimeOff = rules.restrictions.bedtime ?: state.bedtimeOff)
                }
                if (sheet == ParentSheet.ApprovedVideos && _uiState.value.videos.isEmpty()) showSheet(null)
            }
        }
        viewModelScope.launch {
            filters.approvedChannels(kidId).collect { channels ->
                _uiState.update { it.copy(channels = channels) }
                if (sheet == ParentSheet.ApprovedChannels && channels.isEmpty()) showSheet(null)
            }
        }
    }

    /** The page is on screen: the owner's lock, then the page. */
    fun onShown() {
        if (showing) return
        showing = true
        asking = viewModelScope.launch {
            when (val status = locks.ownerStatusFromKids(kidId)) {
                LockStatus.None -> locks.ownerPassWithoutLock()?.let(::openPage)
                is LockStatus.Locked -> {
                    show(ParentScreen.OwnerGate(status.kind))
                    entry.startTry(status.kind, check = { locks.unlockOwnerFromKids(kidId, it) }, opened = ::openPage)
                    status.wait?.let(entry::waitFor)
                }
            }
        }
    }

    /** The page went off screen (closed, the shell closed, Ayn left): the pass and any entry go. */
    fun onHidden() {
        showing = false
        asking?.cancel()
        show(ParentScreen.Closed)
    }

    private fun openPage(owner: OwnerPass) {
        if (!showing || screen is ParentScreen.Page || screen == ParentScreen.Review) return
        show(ParentScreen.Page())
        pass = owner
    }

    fun onOpenReview() {
        if (screen == ParentScreen.Page()) show(ParentScreen.Review)
    }

    fun onOpenChannels() {
        if (_uiState.value.channels.isNotEmpty()) openSheet(ParentSheet.ApprovedChannels)
    }

    fun onOpenVideos() {
        if (_uiState.value.videos.isNotEmpty()) openSheet(ParentSheet.ApprovedVideos)
    }

    fun onOpenDailyLimits() {
        openSheet(ParentSheet.DailyLimits)
    }

    fun onOpenBedtime() {
        openSheet(ParentSheet.Bedtime)
    }

    /** Takes [channelUrl] off the kid's approved channels; the sheet closes with the last one. */
    fun onRemoveChannel(channelUrl: String) {
        val owner = pass ?: return
        if (sheet != ParentSheet.ApprovedChannels) return
        viewModelScope.launch { filters.removeApprovedChannel(owner, kidId, channelUrl) }
    }

    /** Takes [video] off the kid's approved videos; the sheet closes with the last one. */
    fun onRemoveVideo(video: ApprovedVideo) {
        val owner = pass ?: return
        if (sheet != ParentSheet.ApprovedVideos) return
        viewModelScope.launch { filters.removeRestriction(owner, kidId, video) }
    }

    fun onDayLimit(day: DayOfWeek) {
        if (sheet == ParentSheet.DailyLimits) showSheet(ParentSheet.Time(TimeSheet.DayLimit(day)))
    }

    /** [minutes] a day on the open sheet's weekday (`null`: no limit); back to the days. */
    fun onDayLimitPicked(minutes: Int?) {
        val day = ((sheet as? ParentSheet.Time)?.sheet as? TimeSheet.DayLimit)?.day ?: return
        if (!isPickableLimit(minutes)) return
        showSheet(ParentSheet.DailyLimits)
        updateRestrictions { r ->
            r.copy(dailyLimitMinutes = if (minutes == null) r.dailyLimitMinutes - day else r.dailyLimitMinutes + (day to minutes))
        }
    }

    fun onBedtimeStart() {
        if (sheet == ParentSheet.Bedtime) showSheet(ParentSheet.Time(TimeSheet.BedtimeStart))
    }

    fun onBedtimeEnd() {
        if (sheet == ParentSheet.Bedtime) showSheet(ParentSheet.Time(TimeSheet.BedtimeEnd))
    }

    /** Bedtime on (with the last times it had) or off. */
    fun onBedtimeToggle() {
        if (sheet != ParentSheet.Bedtime) return
        val off = _uiState.value.bedtimeOff
        updateRestrictions { r -> r.copy(bedtime = if (r.bedtime == null) off else null) }
    }

    /** A bound of bedtime at [minute] of the day; bedtime is on from then. Back to Bedtime. */
    fun onBedtimePicked(minute: Int) {
        val bound = (sheet as? ParentSheet.Time)?.sheet ?: return
        if (bound is TimeSheet.DayLimit || !isPickableBedtimeMinute(minute)) return
        showSheet(ParentSheet.Bedtime)
        val off = _uiState.value.bedtimeOff
        updateRestrictions { r ->
            val now = r.bedtime ?: off
            val picked = if (bound == TimeSheet.BedtimeStart) Bedtime.orNull(minute, now.endMinute) else Bedtime.orNull(now.startMinute, minute)
            picked?.let { r.copy(bedtime = it) } ?: r
        }
    }

    /** Leave <kid>'s profile: out to the picker with this page's pass. */
    fun onLeave() {
        val owner = pass ?: return
        if (screen !is ParentScreen.Page) return
        leave(owner)
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

    /** The open sheet was swiped or tapped away (the phone): it closes, a time sheet with its list. */
    fun onDismiss() {
        showSheet(null)
    }

    /** Whether touch Back on the gate's pad (the phone, whose pad has its own erase key) may close
     *  the page, presses entered or not (the UI goes back to the kid's Home and [onHidden] drops
     *  them): not while a try is checked. */
    fun canLeaveGate(): Boolean = (screen as? ParentScreen.OwnerGate)?.entry?.checking != true

    /**
     * BACK: lock input erases first; a time sheet returns to its list, another sheet closes, the
     * review returns to the page. `false` when the page itself closes (from the gate or the page):
     * the UI goes back to the kid's Home.
     */
    fun onBack(): Boolean {
        if (entry.back()) return true
        return when (val shown = screen) {
            ParentScreen.Closed, is ParentScreen.OwnerGate -> false
            ParentScreen.Review -> {
                show(ParentScreen.Page())
                true
            }
            is ParentScreen.Page -> when (val open = shown.sheet) {
                null -> false
                is ParentSheet.Time -> {
                    showSheet(if (open.sheet is TimeSheet.DayLimit) ParentSheet.DailyLimits else ParentSheet.Bedtime)
                    true
                }
                else -> {
                    showSheet(null)
                    true
                }
            }
        }
    }

    private val screen: ParentScreen get() = _uiState.value.screen

    private val sheet: ParentSheet? get() = (screen as? ParentScreen.Page)?.sheet

    /** Shows [next]; lock input stops, and the pass goes unless [next] is the page or the review. */
    private fun show(next: ParentScreen) {
        entry.clear()
        if (next !is ParentScreen.Page && next != ParentScreen.Review) pass = null
        _uiState.update { it.copy(screen = next) }
    }

    private fun openSheet(next: ParentSheet) {
        _uiState.update { state ->
            val page = state.screen as? ParentScreen.Page ?: return@update state
            if (page.sheet == null) state.copy(screen = page.copy(sheet = next)) else state
        }
    }

    private fun showSheet(next: ParentSheet?) {
        _uiState.update { state ->
            val page = state.screen as? ParentScreen.Page ?: return@update state
            state.copy(screen = page.copy(sheet = next))
        }
    }

    private fun updateEntry(change: (UnlockEntry) -> UnlockEntry) {
        _uiState.update { state ->
            val gate = state.screen as? ParentScreen.OwnerGate ?: return@update state
            state.copy(screen = gate.copy(entry = change(gate.entry)))
        }
    }

    private fun updateRestrictions(transform: (ProfileRestrictions) -> ProfileRestrictions) {
        val owner = pass ?: return
        viewModelScope.launch { filters.updateRestrictions(owner, kidId, transform) }
    }
}
