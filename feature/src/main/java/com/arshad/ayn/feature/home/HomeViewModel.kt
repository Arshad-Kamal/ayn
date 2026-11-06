package com.arshad.ayn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.StreamCandidate
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.core.store.channelKey
import com.arshad.ayn.feature.foryou.FetchBudget
import com.arshad.ayn.feature.foryou.ForYouBuilder
import com.arshad.ayn.feature.foryou.ForYouPick
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.ForYouRefresher
import com.arshad.ayn.feature.foryou.PinnedPick
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.attempt
import com.arshad.ayn.feature.library.historyVideos
import com.arshad.ayn.feature.library.orFailed
import com.arshad.ayn.feature.subscriptions.FeedLanding
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.subscriptions.isNewInFeed
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home's rows that come from subscriptions (mockup `b3-home`): the hero, New from your
 * subscriptions and Your channels, all from one feed load. The hero and New from your
 * subscriptions skip videos the profile watched (B-D8, M5-D7) and the videos For you shows (FY-D7).
 *
 * Model discipline (spec D5): val-only data classes, collections frozen at construction.
 */
sealed interface SubscriptionsState {
    data object Loading : SubscriptionsState

    /** The blank-slate hero (`b3-home` state 8); Live now still shows below it. */
    data object NoSubscriptions : SubscriptionsState

    data class Ready(
        /** `null` when For you holds the hero (FY-D7) or no channel's feed produced an unwatched video. */
        val hero: Hero?,
        /** Each channel's newest unwatched video For you doesn't show, newest first, the hero's
         *  excluded (so with For you's hero, the newest leads). RSS entries carry no length
         *  (`durationSeconds` -1); a channel served by the uploads-tab fallback has one. */
        val newFromSubscriptions: List<VideoItem>,
        /** Every subscription, latest upload first; unknown latest uploads last. */
        val channels: List<ChannelCard>,
        /** The instant upload ages are measured against. */
        val loadedAt: Instant,
        /** Every channel's feed failed, so nothing above says what subscriptions hold
         *  (`b3-home` state 9). */
        val feedsFailed: Boolean,
        /** Some channel's feed held a video, watched or not: YouTube's rows came back. */
        val hasUploads: Boolean,
    ) : SubscriptionsState
}

/** Home's hero: For you's first pick, or the newest unwatched subscription upload (FY-D7). Its
 *  video page loads after the rows, so they never wait on it. */
data class Hero(
    /** Once the page is Ready its length replaces the card's (a live stream's 0 never does), its
     *  larger picture the card's, and it marks a stream live (D11; feeds can't tell). */
    val video: VideoItem,
    /** Wears the "New" badge ([isNewInFeed]); never For you's. */
    val isNew: Boolean,
    val page: HeroPage = HeroPage.Loading,
)

/** For you on Home (FY-D7): [hero] is the first pick, for [reason]; [row] holds the picks after it. */
data class ForYouShown(val hero: Hero, val reason: ForYouReason, val row: List<ForYouPick>) {
    /** Every video For you shows: New from your subscriptions leaves them out. */
    val videoIds: Set<VideoId> = row.mapTo(hashSetOf(hero.video.id)) { it.video.id }

    /** Every pick in order, the hero's first. */
    val picks: List<ForYouPick> get() = listOf(ForYouPick(hero.video, reason)) + row
}

/** What a For you card's menu hides (FY-D6): the video (Not interested) or its channel (Don't
 *  recommend this channel). */
enum class ForYouHide { Video, Channel }

/**
 * A hide just made from For you, undoable while its notice shows (FY-D11): [video] is the card
 * it was asked on, [takesPlace] the pick now in that card's place (the next one left, else the
 * last; `null` when For you emptied), [before] what For you showed before it.
 */
data class ForYouHidden(val video: VideoItem, val hide: ForYouHide, val takesPlace: VideoId?, val before: ForYouShown) {
    /** Whether this hide takes [pick] out of For you. */
    fun hides(pick: ForYouPick): Boolean = when (hide) {
        ForYouHide.Video -> pick.video.id == video.id
        ForYouHide.Channel -> video.channelKey()?.let { it == pick.video.channelKey() } == true
    }
}

sealed interface HeroPage {
    data object Loading : HeroPage

    data class Ready(val description: String?, val format: VideoFormat?) : HeroPage

    /** The page failed, or For you's budget had no request for it (FY-D5): the hero shows
     *  without its blurb and format. */
    data object Unavailable : HeroPage

    /** The page refused the video as filtered (a length or live rule its listing couldn't show):
     *  the hero doesn't show. */
    data object Filtered : HeroPage
}

/** The video's best stream: the hero's "1080p · 25 fps". */
data class VideoFormat(val height: Int, val fps: Int?)

/** A Your channels card: [latestUpload] is `null` when the channel's feed failed or is empty. */
data class ChannelCard(val subscription: Subscription, val latestUpload: Instant?)

sealed interface LiveNowState {
    data object Loading : LiveNowState

    data class Ready(val items: List<VideoItem>) : LiveNowState

    data class Failed(val failure: AccessFailure) : LiveNowState
}

data class HomeUiState(
    val subscriptions: SubscriptionsState = SubscriptionsState.Loading,
    val liveNow: LiveNowState = LiveNowState.Loading,
    /** When the current load started: the error statement's "Last attempt" stamp. */
    val attemptedAt: Instant,
    /** Videos left part-way, the most recently watched first; empty when the store can't be read. */
    val continueWatching: List<HistoryVideo> = emptyList(),
    /** The [hero]'s video is in Watch later. */
    val heroInWatchLater: Boolean = false,
    /** For you as ranked when Home last became visible (FY-D9), less what was hidden since; `null`
     *  with no picks or the profile's For you off (FY-D10). */
    val forYou: ForYouShown? = null,
    /** The hide whose notice shows; [HomeViewModel.onHiddenNoticeClosed] consumes it. */
    val forYouHidden: ForYouHidden? = null,
    /** A kids profile's Home (no hero, For you or Live now; KP-D11) with the owner's picks;
     *  `null` for any other profile. */
    val kids: KidsPicks? = null,
    /** A new Home waits for For you's first ranking before it draws a hero (PAF-D5): `true` until
     *  that ranking lands or [HomeViewModel.HeroHoldMs] pass, whichever is first. */
    val heroHeld: Boolean = false,
) {
    /** For you's hero whenever it has a pick, else the newest unwatched subscription upload (FY-D7). */
    val hero: Hero?
        get() = forYou?.hero ?: (subscriptions as? SubscriptionsState.Ready)?.hero

    /**
     * The D8 screen-level error, once both parts settled with nothing from YouTube to show.
     * Live now is the one call every Home makes, so its failure names the kind; a failed
     * row alone just drops out (partial failure is not a Home error state).
     */
    val failure: AccessFailure?
        get() {
            val live = liveNow as? LiveNowState.Failed ?: return null
            return when (val state = subscriptions) {
                SubscriptionsState.Loading -> null
                SubscriptionsState.NoSubscriptions -> live.failure
                is SubscriptionsState.Ready -> live.failure.takeIf { !state.hasUploads }
            }
        }
}

/**
 * Home (ui-rebuild B7, B-D8). One ViewModel per destination; seams constructor-injected and
 * main-safe; only [AccessException] is caught from the extractor, per the seam contract. The profile's stores add Continue watching, the watched
 * filter and the hero's Watch later; a store that can't be read leaves YouTube's rows as they are.
 * Every hero's page hands its related list to [relatedLists] for For you.
 *
 * For you ([forYouBuilder]) is ranked each time Home becomes visible ([onVisible]) and never while it
 * shows (FY-D9); its hero is pinned here between rankings (FY-D7), and its page is one request of
 * the [budget] (FY-D5), Hero off included: its related list feeds For you (FY-D4). Each ranking
 * hands its seeds to the [refresher], whose lists wait for the next ranking. For you never waits
 * on the subscription feed: the feed's rows drop what it shows, and a ranking takes the uploads of
 * the feed loaded last. Hides and impressions go to [forYouStore] (FY-D6, FY-D3).
 *
 * A kids profile's Home ([kidsPicks] set, `kp-kids` kidhome) has no hero, For you or Live now, so
 * it asks YouTube for nothing but the feeds; it adds the owner's picks (KP-D10, D11), and its feed
 * takes in the channels its packs approve (FPK-D13).
 *
 * The first load shows the profile's kept feed ([SubscriptionsFeed.stored]) while the fresh one
 * loads, which then lands by [FeedLanding]'s rule: the screen reports its visits ([onVisible]), key
 * presses ([onKeyPressed]) and the hero's focus ([onTopFocus]) for it (PERF P9c).
 *
 * The watch store's reads run on [compute]: every position save while a video plays re-emits them.
 */
class HomeViewModel(
    private val extractor: Extractor,
    private val feed: SubscriptionsFeed,
    private val subscriptionStore: SubscriptionStore,
    private val watchStore: WatchStore,
    private val playlistStore: PlaylistStore,
    private val relatedLists: RelatedListStore,
    private val forYouBuilder: ForYouBuilder,
    private val forYouStore: ForYouStore,
    private val budget: FetchBudget,
    private val refresher: ForYouRefresher,
    private val kidsPicks: Flow<KidsPicks>? = null,
    private val clock: Clock = Clock.System,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val kidsHome = kidsPicks != null
    private val _uiState = MutableStateFlow(HomeUiState(attemptedAt = clock.now(), kids = KidsPicks.None.takeIf { kidsHome }, heroHeld = !kidsHome))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var load: Job? = null
    private var ranking: Job? = null
    private var topUpRun: Job? = null

    /** For you's hero between rankings (FY-D7). */
    private var pinned: PinnedPick? = null

    /** For you heroes whose page the filter refused: never ranked again while this Home lives. */
    private val refusedForYou = mutableSetOf<VideoId>()

    /** The last loaded feed's uploads, newest first: For you's followed seeds (FY-D2). */
    private var feedUploads: List<VideoItem> = emptyList()

    /** For you's cards recorded as shown on this Home visit (FY-D3: once per visit). */
    private val shownThisVisit = HashSet<VideoId>()

    /** The last hide's store write: its Undo waits for it, so the two land in order. */
    private var hideWrite: Job? = null

    private val landing = FeedLanding<SubscriptionsFeed.Result>()

    /** The feed the rows show for the current subscriptions; `null` until one is in. */
    private val shownFeed = MutableStateFlow<SubscriptionsFeed.Result?>(null)

    init {
        viewModelScope.launch {
            watchStore.resumePoints()
                .map { it.historyVideos().take(ContinueWatchingCount) }
                .flowOn(compute)
                .orFailed("continue watching read", emptyList())
                .collect { videos -> _uiState.update { it.copy(continueWatching = videos) } }
        }
        viewModelScope.launch { followHeroWatchLater() }
        viewModelScope.launch { dressForYouHeroes() }
        kidsPicks?.let { picks ->
            viewModelScope.launch {
                picks.orFailed("kids picks read", KidsPicks.None).collect { kids -> _uiState.update { it.copy(kids = kids) } }
            }
        }
        if (!kidsHome) {
            viewModelScope.launch {
                delay(HeroHoldMs)
                _uiState.update { it.copy(heroHeld = false) }
            }
        }
        start(keptFirst = true)
    }

    /** Retry: YouTube's parts start over, fetched afresh and shown at once, without the kept feed
     *  first; the profile's own rows stay. */
    fun refresh() {
        start(keptFirst = false)
    }

    /** A superseded load is cancelled, so it can never land late. */
    private fun start(keptFirst: Boolean) {
        load?.cancel()
        landing.reset()
        _uiState.update {
            it.copy(subscriptions = SubscriptionsState.Loading, liveNow = LiveNowState.Loading, attemptedAt = clock.now())
        }
        load = viewModelScope.launch {
            launch {
                val liveNow = loadLiveNow()
                _uiState.update { it.copy(liveNow = liveNow) }
            }
            followSubscriptions(keptFirst)
        }
    }

    /** A key was pressed on Home this visit: a fresh feed arriving now waits ([FeedLanding]). */
    fun onKeyPressed() {
        landing.onKeyPressed()
    }

    /** The hero took or lost focus: a fresh feed never swaps it while focused ([FeedLanding]). */
    fun onTopFocus(focused: Boolean) {
        landing.onTopFocus(focused)?.let(::showFeed)
    }

    private fun showFeed(result: SubscriptionsFeed.Result) {
        feedUploads = result.entries
        shownFeed.value = result
    }

    /**
     * Home became visible (opened, or returned to; [arrived] when from the rail or with the app back
     * in the foreground, not BACK to it, which lands a fresh feed held meanwhile). For you is ranked
     * from what the stores hold now and shown as soon as that ranking lands (FY-D9); a newer call
     * supersedes one still in flight. A ranking that fails (a store that can't be read) shows no
     * For you and keeps the pinned hero for the next one, which checks it again.
     */
    fun onVisible(arrived: Boolean = false) {
        landing.onVisible(arrived)?.let(::showFeed)
        if (kidsHome) return
        shownThisVisit.clear()
        _uiState.update { it.copy(forYouHidden = null) }
        ranking?.cancel()
        ranking = viewModelScope.launch {
            val ranked = attempt("for you ranking") { forYouBuilder.rank(pinned, feedUploads, refusedForYou.toSet()) }
            ranked?.let { pinned = it.pinned }
            showForYou(ranked?.picks.orEmpty())
            ranked?.let { topUp(it.seeds) }
        }
    }

    /** One top-up at a time, and a new visit never cuts one off: a request cancelled in flight
     *  counts as a failure, and three in a row would stop For you's requests for an hour. */
    private fun topUp(seeds: List<VideoId>) {
        if (topUpRun?.isActive == true) return
        topUpRun = viewModelScope.launch { attempt("for you top-up") { refresher.topUp(seeds) } }
    }

    /** [video] was in view long enough to count as shown (FY-D3); a For you card is recorded once
     *  per Home visit. */
    fun onForYouShown(video: VideoItem) {
        val forYou = _uiState.value.forYou ?: return
        if (video.id !in forYou.videoIds || !shownThisVisit.add(video.id)) return
        viewModelScope.launch { attempt("for you shown save") { forYouStore.recordShown(listOf(video)) } }
    }

    /** A video opened from Home: For you records it when it is one of its picks (FY-D3). */
    fun onOpen(video: VideoItem) {
        if (_uiState.value.forYou?.videoIds?.contains(video.id) != true) return
        viewModelScope.launch { attempt("for you opened save") { forYouStore.recordOpened(video) } }
    }

    /**
     * Not interested ([ForYouHide.Video]) or Don't recommend this channel ([ForYouHide.Channel], by
     * [channelKey]) on [video], one of For you's picks: what it hides leaves For you at once, the
     * next pick taking a hidden hero's place, and its notice shows until [onHiddenNoticeClosed] or
     * [onUndoHide] (FY-D11). The pin isn't touched: the next ranking drops a hidden hero itself.
     */
    fun onHide(video: VideoItem, hide: ForYouHide) {
        val shown = _uiState.value.forYou ?: return
        val picks = shown.picks
        val at = picks.indexOfFirst { it.video.id == video.id }
        if (at < 0 || (hide == ForYouHide.Channel && video.channelKey() == null)) return
        val hiding = ForYouHidden(video, hide, takesPlace = null, before = shown)
        val kept = picks.filterNot(hiding::hides)
        val takesPlace = picks.drop(at + 1).firstOrNull { !hiding.hides(it) } ?: kept.lastOrNull()
        _uiState.update {
            it.copy(forYou = shownOf(kept, listOf(shown.hero)), forYouHidden = hiding.copy(takesPlace = takesPlace?.video?.id))
        }
        hideWrite = viewModelScope.launch { attempt("for you hide") { storeHide(hiding, hidden = true) } }
    }

    /** Undo on the shown notice: For you shows what it did before the hide (a hero kept since
     *  keeps its page), and the hide is taken back from the store. */
    fun onUndoHide() {
        val hidden = _uiState.value.forYouHidden ?: return
        _uiState.update { state ->
            val kept = state.forYou?.hero?.takeIf { it.video.id == hidden.before.hero.video.id }
            state.copy(forYou = hidden.before.copy(hero = kept ?: hidden.before.hero), forYouHidden = null)
        }
        val write = hideWrite
        viewModelScope.launch {
            write?.join()
            attempt("for you unhide") { storeHide(hidden, hidden = false) }
        }
    }

    /** Writes [change] to the store when [hidden], else takes it back. */
    private suspend fun storeHide(change: ForYouHidden, hidden: Boolean) {
        when (change.hide) {
            ForYouHide.Video -> if (hidden) forYouStore.hideVideo(change.video.id) else forYouStore.unhideVideo(change.video.id)
            ForYouHide.Channel -> {
                // onHide takes no channel hide without a channel.
                val channel = checkNotNull(change.video.channelKey())
                if (hidden) forYouStore.hideChannel(channel) else forYouStore.unhideChannel(channel)
            }
        }
    }

    /** The hide's notice closed without Undo: the hide stands. */
    fun onHiddenNoticeClosed() {
        _uiState.update { it.copy(forYouHidden = null) }
    }

    /** Saves the hero's video to Watch later, or takes it out when it's there (asked of the store,
     *  so a flag still catching up with a new hero can't invert it). A failed write is logged and
     *  changes nothing; OK again retries it. */
    fun onHeroWatchLater() {
        val video = _uiState.value.hero?.video ?: return
        viewModelScope.launch {
            attempt("hero watch later") {
                if (playlistStore.savedIn(video.id).first().watchLater) {
                    playlistStore.removeFromWatchLater(video.id)
                } else {
                    playlistStore.addToWatchLater(video)
                }
            }
        }
    }

    /**
     * Never returns. Home stays on the back stack under every other destination, so a subscribe or
     * unsubscribe made there (a channel page, the player) reloads the rows in place: the old rows
     * stay until the new ones land, at once, and a newer store change cancels a reload still in
     * flight. Only the first load, from scratch, shows the kept feed first. A video watched
     * meanwhile, or a new For you, re-picks the rows from the shown feed, without fetching it again.
     * A kids Home's channels also change with its pack channels ([homeChannels]).
     */
    private suspend fun followSubscriptions(keptFirst: Boolean) {
        var firstLoad = keptFirst
        homeChannels().collectLatest { channels ->
            landing.reset()
            shownFeed.value = null
            val keptPhase = firstLoad
            firstLoad = false
            if (channels.isEmpty()) {
                feedUploads = emptyList()
                _uiState.update { it.copy(subscriptions = SubscriptionsState.NoSubscriptions) }
                return@collectLatest
            }
            coroutineScope {
                launch { showRows(channels) }
                val kept = if (keptPhase && _uiState.value.subscriptions == SubscriptionsState.Loading) feed.stored(channels) else null
                if (kept != null) {
                    showFeed(kept.result)
                    landing.keptShown(kept.age)
                }
                val fresh = feed.load(channels)
                landing.onFresh(kept?.let { fresh.over(it.result) } ?: fresh)?.let(::showFeed)
            }
        }
    }

    /** The channels the feed loads: the profile's subscriptions, and on a kids Home the channels its
     *  packs approve after them. */
    private fun homeChannels(): Flow<List<Subscription>> {
        val picks = kidsPicks ?: return subscriptionStore.subscriptions()
        val packs = picks.map { it.packChannels }.orFailed("kids pack channels read", emptyList()).distinctUntilChanged()
        return combine(subscriptionStore.subscriptions(), packs, ::withPackChannels)
    }

    /** Never returns: the rows from each feed [shownFeed] holds, re-picked as the watched set and
     *  For you change. */
    private suspend fun showRows(subscriptions: List<Subscription>) {
        val forYouIds = _uiState.map { it.forYou?.videoIds }.distinctUntilChanged()
        val watched = watchStore.watchedIds()
            // Position saves while a video plays re-emit the same set.
            .distinctUntilChanged()
            .flowOn(compute)
            .orFailed("watched read", emptySet())
        combine(shownFeed.filterNotNull(), watched, forYouIds) { result, ids, forYou -> subscriptionRows(subscriptions, result, ids, forYou) }
            .collectLatest { rows -> showSubscriptions(keepShownHeroPage(rows)) }
    }

    /** Shows [rows], then dresses a new hero from its video page; a newer watched set or store
     *  change cancels that fetch (the callers' collectLatest). */
    private suspend fun showSubscriptions(rows: SubscriptionsState.Ready) {
        _uiState.update { it.copy(subscriptions = rows) }
        val hero = rows.hero?.takeIf { it.page == HeroPage.Loading } ?: return
        val dressed = withPage(hero)
        _uiState.update { state ->
            val ready = state.subscriptions as? SubscriptionsState.Ready ?: return@update state
            state.copy(subscriptions = ready.copy(hero = dressed))
        }
    }

    /** Shows the ranked [picks]. A hero already shown keeps its page unless the page failed, which
     *  is then tried again. A hide made while this ranking ran still holds: when the ranking read
     *  the stores before the hide's write, it shows without what the hide took out and the hide's
     *  Undo brings back this ranking; otherwise its Undo brings back what showed before. */
    private fun showForYou(picks: List<ForYouPick>) {
        _uiState.update { state ->
            val hidden = state.forYouHidden
            val known = listOfNotNull(state.forYou?.hero, hidden?.before?.hero).filter { it.page != HeroPage.Unavailable }
            val ranked = shownOf(picks, known)
            if (hidden == null || ranked == null || picks.none(hidden::hides)) return@update state.copy(forYou = ranked, heroHeld = false)
            state.copy(forYou = shownOf(picks.filterNot(hidden::hides), known), forYouHidden = hidden.copy(before = ranked), heroHeld = false)
        }
    }

    /** [picks]' first as the hero (one of [known] when it shows that video, with its page), the
     *  rest as the row; `null` with no picks. */
    private fun shownOf(picks: List<ForYouPick>, known: List<Hero>): ForYouShown? {
        val first = picks.firstOrNull() ?: return null
        val hero = known.firstOrNull { it.video.id == first.video.id } ?: Hero(first.video, isNew = false)
        return ForYouShown(hero, first.reason, picks.drop(1))
    }

    /** Never returns: dresses each For you hero still loading, through the [budget]. A started
     *  request finishes even when its hero is replaced meanwhile (a hidden hero's Undo comes within
     *  seconds, and a cancelled request counts as failed toward the stop); its page then goes only
     *  to a hidden hero's Undo. A hero replaced before its turn is skipped. */
    private suspend fun dressForYouHeroes() {
        _uiState
            .map { state -> state.forYou?.hero?.takeIf { it.page == HeroPage.Loading } }
            .distinctUntilChangedBy { it?.video?.id }
            .conflate()
            .collect { hero ->
                val current = _uiState.value.forYou?.hero
                if (hero == null || current?.video?.id != hero.video.id || current?.page != HeroPage.Loading) return@collect
                // Refused already (a ranking or an Undo begun before the refusal): no second request.
                if (hero.video.id in refusedForYou) {
                    dropRefusedForYouHero(hero)
                    return@collect
                }
                val dressed = withBudgetedPage(hero)
                if (dressed.page == HeroPage.Filtered) {
                    dropRefusedForYouHero(hero)
                    return@collect
                }
                _uiState.update { state ->
                    // A hero hidden while its page loaded keeps the page for its Undo.
                    val shown = state.forYou?.takeIf { it.hero.video.id == hero.video.id }
                    val hidden = state.forYouHidden?.takeIf { it.before.hero.video.id == hero.video.id }
                    state.copy(
                        forYou = shown?.copy(hero = dressed) ?: state.forYou,
                        forYouHidden = hidden?.copy(before = hidden.before.copy(hero = dressed)) ?: state.forYouHidden,
                    )
                }
            }
    }

    /** The filter refused [hero]'s page: the next pick leads, pinned in its place (FY-D7; its page
     *  loads in turn), and no ranking picks it again. A hide's Undo that brings it back drops it
     *  again here, without a request. */
    private fun dropRefusedForYouHero(hero: Hero) {
        val refused = hero.video.id
        refusedForYou += refused
        _uiState.update { state ->
            val shown = state.forYou?.takeIf { it.hero.video.id == refused } ?: return@update state
            state.copy(forYou = shownOf(shown.row, known = emptyList()))
        }
        if (pinned?.pick?.video?.id == refused) {
            pinned = _uiState.value.forYou?.let { PinnedPick(ForYouPick(it.hero.video, it.reason), clock.now()) }
        }
    }

    /** Never returns: whether the shown hero is in Watch later, following the hero as it changes. */
    private suspend fun followHeroWatchLater() {
        _uiState
            .map { it.hero?.video?.id }
            .distinctUntilChanged()
            .collectLatest { heroId ->
                // A new hero starts unsaved until its own read lands, never with the last one's flag.
                _uiState.update { it.copy(heroInWatchLater = false) }
                if (heroId == null) return@collectLatest
                playlistStore.savedIn(heroId)
                    .map { it.watchLater }
                    .orFailed("hero watch later read", false)
                    .collect { saved -> _uiState.update { it.copy(heroInWatchLater = saved) } }
            }
    }

    /** A reload that keeps the shown hero keeps its loaded page: the blurb never blinks out, and
     *  the page isn't fetched again; one the filter refused stays refused. A page that failed is
     *  retried. */
    private fun keepShownHeroPage(ready: SubscriptionsState.Ready): SubscriptionsState.Ready {
        val hero = ready.hero ?: return ready
        val shown = (_uiState.value.subscriptions as? SubscriptionsState.Ready)?.hero
        if (shown == null || shown.video.id != hero.video.id || (shown.page !is HeroPage.Ready && shown.page != HeroPage.Filtered)) return ready
        return ready.copy(hero = shown.copy(isNew = hero.isNew))
    }

    private suspend fun loadLiveNow(): LiveNowState =
        if (kidsHome) {
            LiveNowState.Ready(emptyList())
        } else {
            fetchLiveNow()
        }

    private suspend fun fetchLiveNow(): LiveNowState =
        try {
            val items = extractor.browse(BrowseKiosk.LIVE).items
                .filterIsInstance<VideoItem>()
                // A page repeating a video id would crash the LazyRow key (CMP-LAZY-01).
                .distinctBy { it.id.value }
            LiveNowState.Ready(items)
        } catch (e: AccessException) {
            LiveNowState.Failed(e.failure)
        }

    /** The rows from [result]; while For you has picks ([forYouIds] holds what it shows) it owns
     *  the hero, and its videos are passed over like watched ones (FY-D7). A kids Home has no hero. */
    private fun subscriptionRows(
        subscriptions: List<Subscription>,
        result: SubscriptionsFeed.Result,
        watched: Set<VideoId>,
        forYouIds: Set<VideoId>?,
    ): SubscriptionsState.Ready {
        val passedOver = if (forYouIds == null) watched else watched + forYouIds
        val newestPerChannel = result.videosByChannel.values
            .mapNotNull { videos -> videos.firstOrNull { it.id !in passedOver } }
            // A collaboration upload can be the newest in two subscribed feeds: show it once.
            .distinctBy { it.id.value }
            .sortedWith(SubscriptionsFeed.NewestFirst)
        val hero = if (forYouIds != null || kidsHome) null else newestPerChannel.firstOrNull()?.let { Hero(it, isNew = it.isNewInFeed(result.loadedAt)) }
        return SubscriptionsState.Ready(
            hero = hero,
            newFromSubscriptions = newestPerChannel.drop(if (hero == null) 0 else 1).take(NewFromSubscriptionsCount),
            channels = subscriptions
                .map { ChannelCard(it, result.newestByChannel[it.channelUrl]?.uploadDate) }
                .sortedByDescending { it.latestUpload },
            loadedAt = result.loadedAt,
            feedsFailed = result.nothingAnswered,
            hasUploads = result.newestByChannel.isNotEmpty(),
        )
    }

    /** [withPage] as one request of For you's [budget]: none when the budget is spent or stopped
     *  (or its log can't be read), and the outcome recorded, a request cut off in flight too
     *  ([FetchBudget.recordCutOff]). */
    private suspend fun withBudgetedPage(hero: Hero): Hero {
        if (attempt("fetch budget read") { budget.allows() } != true) return hero.copy(page = HeroPage.Unavailable)
        val dressed = try {
            withPage(hero)
        } catch (e: CancellationException) {
            budget.recordCutOff()
            throw e
        }
        // A page the filter refused was fetched: it doesn't count toward For you's stop.
        val outcome = if (dressed.page is HeroPage.Ready || dressed.page == HeroPage.Filtered) FetchOutcome.Fetched else FetchOutcome.Failed
        attempt("fetch attempt save") { budget.record(outcome) }
        return dressed
    }

    private suspend fun withPage(hero: Hero): Hero {
        val page = try {
            extractor.video(hero.video.id)
        } catch (e: AccessException) {
            return hero.copy(page = HeroPage.Unavailable)
        } catch (e: VideoFiltered) {
            return hero.copy(page = HeroPage.Filtered)
        }
        // For you keeps the page's related list (FY-D4); the hero doesn't wait for the write.
        viewModelScope.launch { attempt("related list save") { relatedLists.record(page.id, page.relatedVideos) } }
        // A live stream reports length 0, which must not replace the card's "unknown".
        val length = page.durationSeconds.takeIf { it > 0 } ?: hero.video.durationSeconds
        return hero.copy(
            video = hero.video.copy(
                durationSeconds = length,
                isLive = hero.video.isLive || page.isLive,
                thumbnailUrl = page.thumbnailUrl ?: hero.video.thumbnailUrl,
            ),
            page = HeroPage.Ready(description = page.description, format = bestVideoFormat(page.streams)),
        )
    }

    companion object {
        /** Cards in New from your subscriptions. */
        const val NewFromSubscriptionsCount = 8

        /** Cards in Continue watching (Q21); Library's History has the rest. */
        const val ContinueWatchingCount = 12

        /** How long a new Home waits for For you's first ranking before it draws the subscription
         *  hero (PAF-D5: cold-start rankings took 0.9–1.0 s on the Shield). */
        const val HeroHoldMs = 1_000L
    }
}

/** The tallest video stream, the higher frame rate on a tie; `null` when none states a height. */
internal fun bestVideoFormat(streams: List<StreamCandidate>): VideoFormat? =
    streams
        .filter { it.kind != StreamKind.AUDIO }
        .mapNotNull { stream -> stream.height?.let { VideoFormat(it, stream.fps) } }
        .maxWithOrNull(compareBy({ it.height }, { it.fps }))
