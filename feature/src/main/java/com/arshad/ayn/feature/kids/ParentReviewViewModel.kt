package com.arshad.ayn.feature.kids

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.KidsReviewKeeps
import com.arshad.ayn.core.store.KidsTimeStore
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.RuleEdit
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.library.HistoryDayName
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.attempt
import com.arshad.ayn.feature.library.historyDays
import com.arshad.ayn.feature.library.historyVideos
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A day of the review's week: what the kid used against that day's limit, and the More time the
 *  owner gave. */
data class ReviewDay(
    val date: LocalDate,
    val usedSeconds: Long,
    /** `null`: no limit that day. */
    val limitMinutes: Int?,
    val moreTimeMinutes: Int,
    val isToday: Boolean,
)

sealed interface ReviewUploads {
    data object Loading : ReviewUploads

    /** The approved channels' uploads of the last [NEW_UPLOAD_DAYS] days, newest first, without
     *  the kept and blocked ones; [failedChannels] channels couldn't be read. */
    data class Ready(val videos: List<VideoItem>, val failedChannels: Int) : ReviewUploads

    /** No approved channel could be read. */
    data object Failed : ReviewUploads
}

data class ParentReviewUiState(
    /** Monday to Sunday of this week; empty until read. */
    val week: List<ReviewDay> = emptyList(),
    /** Most recently watched first. */
    val watchedToday: List<HistoryVideo> = emptyList(),
    val uploads: ReviewUploads = ReviewUploads.Loading,
    /** A new upload just blocked (`m6-kids` 16): its notice's Undo takes the block back. */
    val blocked: VideoItem? = null,
) {
    /** Today in [week], once read. */
    val today: ReviewDay? get() = week.firstOrNull { it.isToday }
}

/**
 * The parent review (F12; `kp-kids` state 20), opened from the Parent page in a kids profile's
 * shell after the owner's lock, so the session profile is the kid: its week of watch time against
 * its daily limits, what it watched today, and its approved channels' new uploads, each kept
 * (remembered on this device, Q17) or blocked (an owner rule, KP-D10) with the owner's pass. The
 * session's filter already leaves blocked videos out of the feed. Days are YouTube's ([clock], as
 * the kid's time is counted), the device's ([deviceClock]) until that is known, in [zone].
 */
class ParentReviewViewModel(
    private val session: ProfileSession,
    private val filters: FilterStore,
    private val kidsTime: KidsTimeStore,
    private val watchStore: WatchStore,
    private val subscriptions: SubscriptionStore,
    private val feed: SubscriptionsFeed,
    private val keeps: KidsReviewKeeps,
    clock: ServerClock,
    deviceClock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(ParentReviewUiState())
    val uiState: StateFlow<ParentReviewUiState> = _uiState.asStateFlow()

    private val openedAt: Instant = clock.now() ?: deviceClock.now()
    private var uploadsLoad: Job? = null

    init {
        val today = openedAt.toJavaInstant().atZone(zone).toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        viewModelScope.launch {
            val kid = session.currentId()
            combine(filters.rules(kid), kidsTime.days(kid, monday, monday.plusDays(6))) { rules, days ->
                weekOf(monday, today, rules.restrictions, days)
            }
                .catch { logFailure("week read", it) }
                .collect { week -> _uiState.update { it.copy(week = week) } }
        }
        viewModelScope.launch {
            watchStore.history()
                .map { entries ->
                    historyDays(entries.historyVideos(), openedAt, zone)
                        .firstOrNull { it.name == HistoryDayName.Today }?.videos.orEmpty()
                }
                .catch { logFailure("history read", it) }
                .collect { watched -> _uiState.update { it.copy(watchedToday = watched) } }
        }
        loadUploads()
    }

    fun retryUploads() {
        if (_uiState.value.uploads == ReviewUploads.Failed) loadUploads()
    }

    /** Keep: [video] stays allowed and leaves the list. */
    fun onKeep(owner: OwnerPass, video: VideoItem) {
        viewModelScope.launch {
            val kid = session.currentId()
            if (attempt("keep", LOG_TAG) { keeps.keep(owner, kid, video.id.value) } == true) dropUpload(video)
        }
    }

    /** Block: [video] becomes an owner rule on the kid, so it no longer plays or shows there. A
     *  block this made shows as [ParentReviewUiState.blocked] (one already there has nothing to undo). */
    fun onBlock(owner: OwnerPass, video: VideoItem) {
        viewModelScope.launch {
            val kid = session.currentId()
            val edit = attempt("block", LOG_TAG) { filters.addRestriction(owner, kid, blockRule(video)) }
            if (edit != RuleEdit.Added && edit != RuleEdit.AlreadyThere) return@launch
            dropUpload(video)
            if (edit == RuleEdit.Added) _uiState.update { it.copy(blocked = video) }
        }
    }

    /** Undo on the block's notice: the rule goes and the video returns to the list in its upload
     *  order. A failed removal leaves the block standing. */
    fun onUndoBlock(owner: OwnerPass) {
        val video = _uiState.value.blocked ?: return
        onBlockNoticeClosed()
        viewModelScope.launch {
            val kid = session.currentId()
            if (attempt("undo block", LOG_TAG) { filters.removeRestriction(owner, kid, blockRule(video)) } == true) restoreUpload(video)
        }
    }

    /** The block's notice closed without Undo: the block stands. */
    fun onBlockNoticeClosed() {
        _uiState.update { it.copy(blocked = null) }
    }

    private fun blockRule(video: VideoItem): BlockedVideo = BlockedVideo(video.id.value, video.title, video.uploaderUrl)

    private fun loadUploads() {
        uploadsLoad?.cancel()
        _uiState.update { it.copy(uploads = ReviewUploads.Loading) }
        uploadsLoad = viewModelScope.launch {
            val uploads = attempt("new uploads", LOG_TAG) {
                val kid = session.currentId()
                val channels = subscriptions.subscriptions().first()
                val loaded = feed.load(channels)
                val kept = keeps.kept(kid)
                val since = loaded.loadedAt - NEW_UPLOAD_DAYS.days
                val videos = loaded.entries.filter { video ->
                    val uploaded = video.uploadDate
                    uploaded != null && uploaded >= since && video.id.value !in kept
                }
                if (channels.isNotEmpty() && loaded.failedChannels == channels.size) {
                    ReviewUploads.Failed
                } else {
                    ReviewUploads.Ready(videos, loaded.failedChannels)
                }
            }
            _uiState.update { it.copy(uploads = uploads ?: ReviewUploads.Failed) }
        }
    }

    private fun dropUpload(video: VideoItem) {
        _uiState.update { state ->
            val ready = state.uploads as? ReviewUploads.Ready ?: return@update state
            state.copy(uploads = ready.copy(videos = ready.videos.filterNot { it.id == video.id }))
        }
    }

    /** Back among the uploads, newest first (every listed upload has its date). */
    private fun restoreUpload(video: VideoItem) {
        _uiState.update { state ->
            val ready = state.uploads as? ReviewUploads.Ready ?: return@update state
            if (ready.videos.any { it.id == video.id }) return@update state
            val uploaded = video.uploadDate
            val at = ready.videos.indexOfFirst { listed -> uploaded != null && listed.uploadDate?.let { it < uploaded } == true }
            val videos = ready.videos.toMutableList().apply { add(if (at < 0) size else at, video) }
            state.copy(uploads = ready.copy(videos = videos))
        }
    }

    private fun logFailure(what: String, e: Throwable) {
        Log.w(LOG_TAG, "$what failed: ${e.javaClass.simpleName}")
    }

    private companion object {
        const val LOG_TAG = "KeParentReview"
    }
}

/** How far back an approved channel's upload counts as new in the review (Q17). */
const val NEW_UPLOAD_DAYS = 7

/** Monday to Sunday from [monday]; a day's limit is the owner's for its weekday. */
internal fun weekOf(
    monday: LocalDate,
    today: LocalDate,
    restrictions: ProfileRestrictions,
    days: Map<LocalDate, DayTime>,
): List<ReviewDay> = (0L..6L).map { offset ->
    val date = monday.plusDays(offset)
    val day = days[date] ?: DayTime()
    ReviewDay(
        date = date,
        usedSeconds = day.usedSeconds,
        limitMinutes = restrictions.dailyLimitMinutes[date.dayOfWeek],
        moreTimeMinutes = day.moreTimeMinutes,
        isToday = date == today,
    )
}

/** The scale's floor, so a quiet week's bars don't fill their wells. */
private const val MIN_SCALE_MINUTES = 60

/** The minutes a review bar's full height stands for: the week's most watched or allowed (limit
 *  plus More time) day, at least [MIN_SCALE_MINUTES]. */
fun reviewScaleMinutes(week: List<ReviewDay>): Int =
    week.maxOfOrNull { day -> maxOf((day.usedSeconds / 60).toInt(), (day.limitMinutes ?: 0) + day.moreTimeMinutes) }
        ?.coerceAtLeast(MIN_SCALE_MINUTES) ?: MIN_SCALE_MINUTES
