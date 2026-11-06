package com.arshad.ayn.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.settingsOrNull
import com.arshad.ayn.feature.showsShorts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Channel page state (ARCH-1/3). The header (with its tabs) and the selected tab's first page
 * make the page: either failing is a failed page (the header is the page; there is no
 * meaningful partial render), which is the D8 screen-level error with Retry, offline or
 * unreadable by its [AccessFailure].
 */
sealed interface ChannelPageState {
    data object Loading : ChannelPageState

    data class Ready(
        val channel: ChannelItem,
        /** Every tab the page lists, in page order, Shorts included; Videos alone when the header
         *  lists none. */
        val pageTabs: List<ChannelTab>,
        /** The session profile's Show Shorts (M7-D13): the Shorts tab shows only while on. */
        val showShorts: Boolean,
        /** `null` when the channel has no visible tab. */
        val selectedTab: ChannelTab?,
        /** First pages of the tabs loaded so far, kept for revisits; the Shorts tab's grows with
         *  the pages a Shorts run fetches. */
        val tabItems: Map<ChannelTab, List<ListingItem>>,
    ) : ChannelPageState {
        /** Visible tabs in page order. */
        val tabs: List<ChannelTab>
            get() = if (showShorts) pageTabs else pageTabs - ChannelTab.SHORTS

        /** The channel's only tab is Shorts, hidden while Show Shorts is off ("This channel only
         *  posts Shorts", with Turn on Shorts). */
        val shortsOnly: Boolean
            get() = tabs.isEmpty() && ChannelTab.SHORTS in pageTabs

        /** The selected tab's items; `null` while that tab loads. */
        val selectedItems: List<ListingItem>?
            get() = if (selectedTab == null) emptyList() else tabItems[selectedTab]
    }

    data class Failed(val failure: AccessFailure) : ChannelPageState
}

data class ChannelUiState(
    val pageState: ChannelPageState = ChannelPageState.Loading,
    /** Driven by the store's own flow, so it stays true across page reloads. */
    val isSubscribed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val channelUrl: String,
    private val extractor: Extractor,
    private val store: SubscriptionStore,
    private val settingsStore: ProfileSettingsStore,
) : ViewModel() {

    private var generation = 0L // viewModelScope is Main-confined; a plain counter suffices

    /** The tab the user last chose: a reload (Retry) comes back to it. */
    private var wantedTab: ChannelTab? = null

    /** Tabs whose first page is on its way: passing back over one (select-on-focus) asks no twice. */
    private val loadingTabs = mutableSetOf<ChannelTab>()

    /** The session profile's Show Shorts and whose it is (Turn on Shorts writes there). */
    private var showShorts = false
    private var settingsProfileId: Long? = null

    /** The Shorts tab's list, shared with a Shorts run opened from it, and what follows it. */
    private var shortsList: ShortsList? = null
    private var shortsFollow: Job? = null
    private var handedShorts: ShortsList? = null

    /** What the store is asked by: the nav URL until the header lands, then the header's URL;
     *  the store keys either by its canonical form (M5-D19), so write and observe meet. */
    private val storeKey = MutableStateFlow(channelUrl)

    /** The store's last emission — the revert target when a write fails; the flow never
     *  re-emits for a failed write (the table didn't change), so the revert is terminal. */
    private var storeTruth = false

    private val _uiState = MutableStateFlow(ChannelUiState())
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            storeKey.flatMapLatest { key -> store.subscription(key) }.collect { subscription ->
                storeTruth = subscription != null
                _uiState.update { it.copy(isSubscribed = subscription != null) }
            }
        }
        viewModelScope.launch {
            settingsStore.settingsOrNull().collect { state ->
                settingsProfileId = state?.profileId
                applyShowShorts(state.showsShorts())
            }
        }
        load()
    }

    fun load() {
        val run = ++generation
        loadingTabs.clear()
        shortsFollow?.cancel()
        shortsList = null
        _uiState.update { it.copy(pageState = ChannelPageState.Loading) }
        viewModelScope.launch {
            val page = try {
                loadPage(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccessException) {
                ChannelPageState.Failed(e.failure)
            }
            if (run == generation) {
                _uiState.update { it.copy(pageState = page) }
                (page as? ChannelPageState.Ready)?.let { ready ->
                    storeKey.value = ready.channel.url // an id URL even when the nav URL was a handle
                }
                applyShowShorts(showShorts) // it may have changed while the page loaded
            }
        }
    }

    /**
     * Tabs select on focus (the approved mockup; the TV tabs call this once focus rests on one);
     * a loaded tab shows at once. Every answer is kept, also for a tab the user has moved past;
     * only a failure of the still-selected tab fails the page.
     */
    fun selectTab(tab: ChannelTab) {
        val ready = _uiState.value.pageState as? ChannelPageState.Ready ?: return
        if (tab !in ready.tabs || tab == ready.selectedTab) return
        wantedTab = tab
        show(ready, tab)
    }

    /** Turn on Shorts (Q44, on a Shorts-only page): Show Shorts on for the profile whose settings
     *  the page shows (M5-D20); the Shorts tab, then the only one shown, is selected. A failed
     *  write changes nothing. */
    fun turnOnShorts() {
        val profileId = settingsProfileId ?: return
        viewModelScope.launch { settingsStore.update(profileId) { it.copy(showShorts = true) } }
    }

    /** The list a Short on the Shorts tab is opened from (Q36): the tab's own, so a page the run
     *  fetches shows here too; kept for [shortsReturn]. */
    fun openShorts(): ShortsList = (shortsList ?: ShortsList(emptyList())).also { handedShorts = it }

    /** The Short BACK from the player returns focus to (Q37): the last one played from the list
     *  handed over, until [shortsReturned]. */
    fun shortsReturn(): VideoId? = handedShorts?.lastPlayed?.value

    fun shortsReturned() {
        handedShorts = null
    }

    /**
     * Show Shorts changed: the Shorts tab appears or goes. A selection it hid (or none, on a
     * Shorts-only page) moves to the tab Retry would restore, else the first shown tab.
     */
    private fun applyShowShorts(on: Boolean) {
        showShorts = on
        val shown = _uiState.value.pageState as? ChannelPageState.Ready ?: return
        if (shown.showShorts == on) return
        val ready = shown.copy(showShorts = on)
        val selected = ready.selectedTab?.takeIf { it in ready.tabs }
            ?: wantedTab?.takeIf { it in ready.tabs }
            ?: ready.tabs.firstOrNull()
        if (selected == null) {
            _uiState.update { it.copy(pageState = ready.copy(selectedTab = null)) }
        } else {
            show(ready, selected)
        }
    }

    /** Selects [tab] on [ready]; a tab not loaded yet loads. */
    private fun show(ready: ChannelPageState.Ready, tab: ChannelTab) {
        _uiState.update { it.copy(pageState = ready.copy(selectedTab = tab)) }
        if (tab in ready.tabItems || !loadingTabs.add(tab)) return
        val pageRun = generation
        viewModelScope.launch {
            val answer = try {
                tryTab(tab)
            } finally {
                if (pageRun == generation) loadingTabs.remove(tab)
            }
            (answer as? TabAnswer.Items)?.shortsList?.let { followShorts(it, pageRun) }
            _uiState.update { state ->
                val current = state.pageState as? ChannelPageState.Ready
                if (current == null || pageRun != generation) return@update state // a reload replaced the page
                when (answer) {
                    is TabAnswer.Items -> state.copy(pageState = current.copy(tabItems = current.tabItems + (tab to answer.items)))
                    is TabAnswer.Failure if current.selectedTab == tab -> state.copy(pageState = ChannelPageState.Failed(answer.failure))
                    is TabAnswer.Failure -> state // the user has moved past it
                }
            }
        }
    }

    /** The header and a guessed tab load together: the tab Retry restores, else Videos (the
     *  first tab on nearly every channel). A wrong guess costs one discarded request. */
    private suspend fun loadPage(run: Long): ChannelPageState = coroutineScope {
        val guess = wantedTab?.takeIf { it != ChannelTab.SHORTS || showShorts } ?: ChannelTab.VIDEOS
        val header = async { extractor.channel(channelUrl) }
        val guessed = async { tryTab(guess) }
        val channel = header.await()
        // The vendored channel info swallows a tab-parse failure as "no tabs": open on Videos
        // then, as before tabs existed.
        val shell = ChannelPageState.Ready(
            channel = channel,
            pageTabs = channel.tabs.ifEmpty { listOf(ChannelTab.VIDEOS) },
            showShorts = showShorts,
            selectedTab = null,
            tabItems = emptyMap(),
        )
        val selected = wantedTab?.takeIf { it in shell.tabs } ?: shell.tabs.firstOrNull()
        // A wrong guess: its answer is dropped (the blocking extractor call still completes).
        if (selected != guess) guessed.cancel()
        val answer = when (selected) {
            null -> null
            guess -> guessed.await()
            else -> fetchTab(selected)
        }
        val tabItems = when (answer) {
            null -> emptyMap()
            is TabAnswer.Items -> {
                answer.shortsList?.let { followShorts(it, run) }
                mapOf(answer.tab to answer.items)
            }
            is TabAnswer.Failure -> return@coroutineScope ChannelPageState.Failed(answer.failure)
        }
        // A header with no tabs (a Topic channel, or a swallowed tab-parse failure) whose Videos is
        // empty: a Topic channel keeps its uploads only in its RSS feed.
        val feedFillsVideos = channel.tabs.isEmpty() && tabItems[ChannelTab.VIDEOS]?.isEmpty() == true
        shell.copy(
            selectedTab = selected,
            tabItems = if (feedFillsVideos) tabItems + (ChannelTab.VIDEOS to feedVideos(channel.url)) else tabItems,
        )
    }

    /** A tab's first page; the Shorts tab's is its Shorts (M7-D12), as a list that pages on. */
    private suspend fun fetchTab(tab: ChannelTab): TabAnswer.Items {
        val page = extractor.channelTab(channelUrl, tab)
        if (tab != ChannelTab.SHORTS) return TabAnswer.Items(tab, page.items.distinctBy { it.listingKey })
        val list = ShortsList(page.shorts, page.next) { token -> extractor.channelTab(channelUrl, ChannelTab.SHORTS, token) }
        return TabAnswer.Items(tab, list.shorts.value, list)
    }

    /** Keeps the Shorts tab's items as [list] grows (a run's pages), while page [run] stands. */
    private fun followShorts(list: ShortsList, run: Long) {
        if (run != generation) return
        shortsList = list
        shortsFollow?.cancel()
        shortsFollow = viewModelScope.launch {
            list.shorts.collect { shorts ->
                _uiState.update { state ->
                    val ready = state.pageState as? ChannelPageState.Ready
                    if (ready == null || run != generation) return@update state
                    state.copy(pageState = ready.copy(tabItems = ready.tabItems + (ChannelTab.SHORTS to shorts)))
                }
            }
        }
    }

    /**
     * The channel's RSS uploads (at most 15 entries; its Shorts arrive apart and stay off the
     * Videos tab), asked by its canonical URL
     * (the feed wants a `UC…` id, not a handle). A failure leaves the Videos empty, as the
     * Subscriptions feed falls back: the feed throws mostly on a 404, which Retry wouldn't mend.
     */
    private suspend fun feedVideos(canonicalUrl: String): List<ListingItem> =
        try {
            extractor.channelFeed(canonicalUrl).items.distinctBy { it.listingKey }
        } catch (_: AccessException) {
            emptyList()
        }

    /** A tab's first page or why it failed, without failing the caller's scope (a guessed or
     *  moved-past tab's failure matters only while it is selected). */
    private suspend fun tryTab(tab: ChannelTab): TabAnswer =
        try {
            fetchTab(tab)
        } catch (e: AccessException) {
            TabAnswer.Failure(e.failure)
        }

    private sealed interface TabAnswer {
        class Items(val tab: ChannelTab, val items: List<ListingItem>, val shortsList: ShortsList? = null) : TabAnswer
        class Failure(val failure: AccessFailure) : TabAnswer
    }

    /** The subscribe button: subscribe when clean, unsubscribe when stored. The
     *  pill flips optimistically — a double-press never double-writes one direction. A
     *  failed write reverts to [storeTruth], so overlapping failed presses still settle
     *  on what the store actually holds. */
    fun toggleSubscribe() {
        val state = _uiState.value
        val ready = state.pageState as? ChannelPageState.Ready ?: return
        _uiState.update { it.copy(isSubscribed = !state.isSubscribed) }
        viewModelScope.launch {
            try {
                // The canonical key the repository writes (re-stamps on re-subscribe).
                if (state.isSubscribed) store.unsubscribe(ready.channel.url) else store.subscribe(ready.channel)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSubscribed = storeTruth) }
            }
        }
    }
}
