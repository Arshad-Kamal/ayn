package com.arshad.ayn.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.showsShorts
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Subscriptions feed state (ARCH-1/3). The channel strip filters client-side. Ordering, ages, and
 * the clock live in [SubscriptionsFeed].
 */
sealed interface FeedState {
    /** No subscriptions at all (`b3-subscriptions` state 5). */
    data object NoSubscriptions : FeedState

    data object Loading : FeedState

    data class Ready(
        /** Every answered channel's videos merged, newest first. */
        val entries: List<VideoItem>,
        /** Each channel URL that answered to its videos (possibly none), newest first. */
        val videosByChannel: Map<String, List<VideoItem>>,
        /** Channel URLs whose feed failed with no kept feed to show: their videos are missing. */
        val failedChannels: Set<String>,
        /** Video ids wearing the "New" badge ([isNewInFeed]). */
        val newIds: Set<String>,
        /** The instant the entries' ages are measured against. */
        val loadedAt: Instant,
        /** Every answered channel's Shorts merged, newest first (shown only with Show Shorts on). */
        val shorts: List<VideoItem> = emptyList(),
        /** Each answered channel URL to its Shorts (possibly none), newest first. */
        val shortsByChannel: Map<String, List<VideoItem>> = emptyMap(),
    ) : FeedState

    /** No channel answered; [failure] is OFFLINE when any channel couldn't reach YouTube. */
    data class Failed(val failure: AccessFailure) : FeedState
}

data class SubscriptionsUiState(
    /** The strip's channels as of the last landed feed (a load from scratch shows them before the
     *  feed lands); once Ready, latest upload first (none last). */
    val subscriptions: List<Subscription> = emptyList(),
    /** `null` = every channel; else the channel URL the feed is narrowed to. */
    val filter: String? = null,
    val feedState: FeedState = FeedState.Loading,
    /** The session profile's Show Shorts (M7-D13). */
    val showShorts: Boolean = false,
) {
    /** Entries after the channel filter, when the feed is Ready. */
    fun visibleEntries(): List<VideoItem> = when (val state = feedState) {
        is FeedState.Ready -> if (filter == null) state.entries else state.videosByChannel[filter].orEmpty()
        else -> emptyList()
    }

    /** The Shorts shelf (Q42): every channel's Shorts, or the picked channel's; empty while Show
     *  Shorts is off or the feed isn't Ready. */
    fun visibleShorts(): List<VideoItem> = when (val state = feedState) {
        is FeedState.Ready if showShorts -> if (filter == null) state.shorts else state.shortsByChannel[filter].orEmpty()
        else -> emptyList()
    }
}

/**
 * Subscriptions: loads on entry and Retry, and reloads when the subscription store changes. The
 * first load shows the profile's kept feed ([SubscriptionsFeed.stored]) while the fresh one loads,
 * which then lands by [FeedLanding]'s rule; the screen reports its visits ([onVisible]) and key
 * presses ([onKeyPressed]) for it (PERF P9c).
 */
class SubscriptionsViewModel(
    private val feed: SubscriptionsFeed,
    private val store: SubscriptionStore,
    settingsStore: ProfileSettingsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()

    private var load: Job? = null
    private var handedShorts: ShortsList? = null
    private val landing = FeedLanding<Shown>()

    private class Shown(val subscriptions: List<Subscription>, val result: SubscriptionsFeed.Result)

    init {
        viewModelScope.launch {
            settingsStore.showsShorts().collect { on -> _uiState.update { it.copy(showShorts = on) } }
        }
        start(keptFirst = true)
    }

    /** Retry: fetches afresh and lands at once, without the kept feed first. */
    fun refresh() {
        start(keptFirst = false)
    }

    /** A visit starts; [arrived] when it came from the rail or with the app back in the foreground
     *  (not BACK to it), which lands a fresh feed held meanwhile. */
    fun onVisible(arrived: Boolean = false) {
        landing.onVisible(arrived)?.let(::land)
    }

    /** A key was pressed on the screen this visit: a fresh feed arriving now waits ([FeedLanding]). */
    fun onKeyPressed() {
        landing.onKeyPressed()
    }

    /** A superseded load is cancelled, so it can never land late. */
    private fun start(keptFirst: Boolean) {
        load?.cancel()
        landing.reset()
        _uiState.update { it.copy(feedState = FeedState.Loading) }
        load = viewModelScope.launch { followSubscriptions(keptFirst) }
    }

    /**
     * Never returns. This screen's state is kept while another destination is shown, so a subscribe
     * or unsubscribe made there (a channel page, the player) reloads the feed in place: the shown
     * feed and strip stay until the new feed lands, at once, and a newer store change cancels a
     * reload still in flight. Only the first load, from scratch, shows the kept feed first.
     */
    private suspend fun followSubscriptions(keptFirst: Boolean) {
        var firstLoad = keptFirst
        store.subscriptions().collectLatest { subscriptions ->
            landing.reset()
            val keptPhase = firstLoad
            firstLoad = false
            if (subscriptions.isEmpty()) {
                _uiState.update { it.copy(subscriptions = emptyList(), filter = null, feedState = FeedState.NoSubscriptions) }
                return@collectLatest
            }
            // Only a load from scratch shows the strip ahead of the feed.
            _uiState.update { if (it.feedState == FeedState.Loading) it.copy(subscriptions = subscriptions) else it }
            val kept = if (keptPhase && _uiState.value.feedState == FeedState.Loading) feed.stored(subscriptions) else null
            if (kept != null) {
                land(Shown(subscriptions, kept.result))
                landing.keptShown(kept.age)
            }
            val fresh = feed.load(subscriptions)
            landing.onFresh(Shown(subscriptions, kept?.let { fresh.over(it.result) } ?: fresh))?.let(::land)
        }
    }

    private fun land(shown: Shown) {
        _uiState.update { it.landed(shown.subscriptions, shown.result) }
    }

    private fun SubscriptionsUiState.landed(
        subscriptions: List<Subscription>,
        result: SubscriptionsFeed.Result,
    ): SubscriptionsUiState {
        // A filter on a channel no longer subscribed would narrow the feed to nothing.
        val filter = filter?.takeIf { url -> subscriptions.any { it.channelUrl == url } }
        if (result.nothingAnswered) {
            val offline = AccessFailure.OFFLINE in result.failures.values
            val failure = if (offline) AccessFailure.OFFLINE else AccessFailure.UNREADABLE
            return copy(subscriptions = subscriptions, filter = filter, feedState = FeedState.Failed(failure))
        }
        val ready = FeedState.Ready(
            entries = result.entries,
            videosByChannel = result.videosByChannel,
            failedChannels = result.missingChannels,
            newIds = result.entries
                .filter { it.isNewInFeed(result.loadedAt) }
                .map { it.id.value }
                .toSet(),
            loadedAt = result.loadedAt,
            shorts = result.shorts,
            shortsByChannel = result.shortsByChannel,
        )
        return copy(
            subscriptions = subscriptions.sortedByDescending { sub -> result.newestByChannel[sub.channelUrl]?.uploadDate },
            filter = filter,
            feedState = ready,
        )
    }

    /** Channel strip selection: `null` = every channel; a channel URL narrows the feed to it. */
    fun selectFilter(channelUrl: String?) {
        _uiState.update { it.copy(filter = channelUrl) }
    }

    /** The list the shelf's Short is opened from (Q36): the shelf as it shows now, kept for
     *  [shortsReturn]. */
    fun openShorts(): ShortsList = ShortsList(_uiState.value.visibleShorts()).also { handedShorts = it }

    /** The Short BACK from the player returns focus to (Q37): the last one played from the list
     *  handed over, until [shortsReturned]. */
    fun shortsReturn(): VideoId? = handedShorts?.lastPlayed?.value

    fun shortsReturned() {
        handedShorts = null
    }
}
