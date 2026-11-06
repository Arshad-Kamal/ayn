package com.arshad.ayn.ui.mobile.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.cardMeta
import com.arshad.ayn.feature.format.historyCardMeta
import com.arshad.ayn.feature.home.HomeLayout
import com.arshad.ayn.feature.home.HomeRow
import com.arshad.ayn.feature.home.HomeTop
import com.arshad.ayn.feature.home.HomeViewModel
import com.arshad.ayn.feature.home.SHOWN_AFTER_MS
import com.arshad.ayn.feature.home.LiveNowState
import com.arshad.ayn.feature.home.SubscriptionsState
import com.arshad.ayn.feature.home.homeLayout
import com.arshad.ayn.feature.home.shownUnder
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.settings.LaunchNotice
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.onUserScroll
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.menu.hiddenLine
import com.arshad.ayn.ui.mobile.settings.launchNoticeLine
import com.arshad.ayn.ui.mobile.shell.pillHeadHeight
import com.arshad.ayn.ui.mobile.shell.ScrollsToTop
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellTopBar
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.theme.AynMotion
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.time.Instant

private const val TopKey = "top"

/**
 * Home on the phone (`m6-home-and-menus` states 1–8, M6-D10/D11) on [HomeViewModel], in
 * [homeLayout]'s rows under the open profile's filter (KP-D19) and this phone's Hero on Home. The
 * hero sits under the see-through top bar, which turns solid once the page scrolls past it; each
 * row swipes sideways; pulling down at the top reloads (M6-D11) and syncs ([onPull], M8-D8). A tap
 * plays ([onPlay], counted as opened, FY-D3); a long press or ⋮ opens the card menu on [menu]
 * (states 9–19; a For you pick's says why and can hide it, whose Hidden snackbar Undo takes back),
 * its card staying lifted behind the dim. Each start tells the ViewModel Home became visible (FY-D9), and For you's cards wholly
 * in view count as shown after [SHOWN_AFTER_MS] while Home is started. This launch's failed sync
 * shows as a snackbar ([syncNotice], M8-D11), its Fix calling [onFixSync]; a Hidden notice
 * stands in front of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePage(
    viewModel: HomeViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    onPull: () -> Unit = {},
    syncNotice: SyncLaunchNotice? = null,
    onFixSync: () -> Unit = {},
) {
    var visits by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(viewModel) {
        visits++
        started = true
        viewModel.onVisible()
        onStopOrDispose { started = false }
    }
    var launchNotice by remember { mutableStateOf<LaunchNotice?>(null) }
    // Only while started: one taken while Ayn is away would go unseen.
    LaunchedEffect(started, syncNotice, chrome.profile.id) {
        if (started) syncNotice?.take(chrome.profile.id)?.let { launchNotice = it }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = homeLayout(state.shownUnder(LocalContentFilter.current), heroOn = LocalDeviceSettings.current.heroOnHome)
    val open: (VideoItem, Boolean) -> Unit = { video, startOver ->
        viewModel.onOpen(video)
        onPlay(video, startOver)
    }
    // The card whose menu is open stays lifted behind the dim (state 9).
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldCard by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    ScrollsToTop(list, chrome.nav.reselected == Tab.Home, chrome.nav::onScrolledToTop)
    val barTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + pillHeadHeight(chrome)
    val heroShown = (layout as? HomeLayout.Browse)?.top is HomeTop.HeroTop || layout == HomeLayout.Loading
    val loading = state.subscriptions == SubscriptionsState.Loading || state.liveNow == LiveNowState.Loading
    // The load a pull started: only that one shows the indicator (not the first load, nor a reload of its own).
    var pulledAt by remember { mutableStateOf<Instant?>(null) }
    val refreshing = loading && state.attemptedAt == pulledAt
    val pull = rememberPullToRefreshState()
    val stamp = lastAttemptText(state.attemptedAt)
    VideoMenuHost(menu, open, onOpenChannel, modifier, onForYouHide = viewModel::onHide) { openMenu ->
        val openCardMenu: (VideoItem, VideoSource, String?) -> Unit = { video, source, card ->
            viewModel.onHiddenNoticeClosed()
            heldCard = card
            openMenu(video, source)
        }
        Box(Modifier.fillMaxSize().onUserScroll { if (viewModel.uiState.value.forYouHidden != null) viewModel.onHiddenNoticeClosed() }) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    viewModel.refresh()
                    pulledAt = viewModel.uiState.value.attemptedAt
                    onPull()
                },
                state = pull,
                // Below the top bar, which would cover it.
                indicator = { PullToRefreshDefaults.Indicator(pull, refreshing, Modifier.align(Alignment.TopCenter).padding(top = barTop)) },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Loading, failed and browsing cross-fade (alpha only, D8); a reload within one kind doesn't.
                AnimatedContent(layout, transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) }, contentKey = { it::class }, label = "home") { shown ->
                    when (shown) {
                        HomeLayout.Loading -> HomeSkeleton()
                        is HomeLayout.Failed -> HomeFailed(
                            failure = shown.failure,
                            stamp = stamp,
                            onRetry = viewModel::refresh,
                            onOpenLibrary = { chrome.nav.onTabTap(Tab.Library) }.takeIf { !chrome.profile.isKids },
                            top = barTop,
                        )
                        is HomeLayout.Browse -> HomeRows(
                            layout = shown,
                            list = list,
                            barTop = barTop,
                            stamp = stamp,
                            onPlay = { open(it, false) },
                            onWatchLater = viewModel::onHeroWatchLater,
                            onOpenChannel = onOpenChannel,
                            onOpenMenu = openCardMenu,
                            heldCard = heldCard.takeIf { menuOpen },
                            onSearch = { chrome.nav.open(SearchKey) },
                            onRetry = viewModel::refresh,
                            visit = visits.takeIf { started },
                            onForYouShown = viewModel::onForYouShown,
                        )
                    }
                }
            }
            HomeTopBar(chrome, list, solid = !heroShown, barTop)
            NoticeSnackbar(state.forYouHidden, ::hiddenLine, viewModel::onUndoHide, viewModel::onHiddenNoticeClosed, Modifier.align(Alignment.BottomCenter))
            NoticeSnackbar(
                notice = launchNotice.takeIf { state.forYouHidden == null },
                line = ::launchNoticeLine,
                onAction = {
                    launchNotice = null
                    onFixSync()
                },
                onClose = { launchNotice = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** The shell's top bar over Home: see-through over the hero until the page scrolls past it (150 ms,
 *  M6-D10); solid when no hero shows. Reads the scroll here, so the page doesn't recompose with it. */
@Composable
private fun HomeTopBar(chrome: ShellChrome, list: LazyListState, solid: Boolean, barTop: Dp) {
    val pastHero by rememberPastHero(list, barTop)
    val ground by animateFloatAsState(if (solid || pastHero) 1f else 0f, AynMotion.press(), label = "bar")
    ShellTopBar(chrome, ground = { ground })
}

@Composable
private fun HomeRows(
    layout: HomeLayout.Browse,
    list: LazyListState,
    barTop: Dp,
    stamp: String,
    onPlay: (VideoItem) -> Unit,
    onWatchLater: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMenu: (video: VideoItem, source: VideoSource, card: String?) -> Unit,
    heldCard: String?,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    visit: Int?,
    onForYouShown: (VideoItem) -> Unit,
) {
    val top = layout.top
    val heroTop = top as? HomeTop.HeroTop
    val forYouLane = rememberLazyListState()
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = if (heroTop != null) 0.dp else barTop, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (top != HomeTop.None && top != HomeTop.HeroOff) {
            item(key = TopKey, contentType = TopKey) {
                when (top) {
                    is HomeTop.HeroTop -> HomeHero(
                        top = top,
                        onPlay = { onPlay(top.hero.video) },
                        onWatchLater = onWatchLater,
                        onMore = {
                            val reason = top.reason
                            onOpenMenu(top.hero.video, if (reason != null) VideoSource.ForYou(reason) else VideoSource.Elsewhere, null)
                        },
                    )
                    HomeTop.Blank -> Statement(
                        icon = R.drawable.ic_nav_subscriptions,
                        kicker = "No subscriptions yet",
                        title = "Follow channels to fill Home",
                        body = "Home shows the newest videos from the channels you follow. Find one with Search, open it and choose Subscribe.",
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 18.dp),
                    ) { PillButton("Search", onSearch, icon = R.drawable.ic_nav_search) }
                    is HomeTop.FeedsFailed -> Statement(
                        icon = R.drawable.ic_alert,
                        kicker = "Part of Home didn’t load",
                        title = "Couldn’t load your subscriptions",
                        body = feedsFailedBody(top.channelCount),
                        compact = true,
                        stamp = stamp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
                    ) { PillButton("Try again", onRetry, icon = R.drawable.ic_retry) }
                    HomeTop.NothingApproved -> Statement(
                        icon = R.drawable.ic_lock,
                        kicker = "Nothing approved yet",
                        title = "Nothing to watch yet",
                        body = "Ask a grown-up to approve some channels or videos.",
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 40.dp),
                    )
                    HomeTop.None, HomeTop.HeroOff -> Unit
                }
            }
        }
        items(layout.rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
            when (row) {
                is HomeRow.ForYou -> Shelf("For you", lane = forYouLane) {
                    items(row.picks, key = { it.video.id.value }) { pick ->
                        val card = cardKey(row, pick.video)
                        VideoCard(
                            video = pick.video,
                            meta = cardMeta(pick.video, row.now),
                            onClick = { onPlay(pick.video) },
                            onOpenMenu = { onOpenMenu(pick.video, VideoSource.ForYou(pick.reason), card) },
                            modifier = cardMotion(),
                            held = heldCard == card,
                        )
                    }
                }
                is HomeRow.Videos -> Shelf(row.title) {
                    items(row.videos, key = { it.id.value }) { video ->
                        val card = cardKey(row, video)
                        VideoCard(
                            video = video,
                            meta = cardMeta(video, row.now),
                            onClick = { onPlay(video) },
                            onOpenMenu = { onOpenMenu(video, VideoSource.Elsewhere, card) },
                            modifier = cardMotion(),
                            held = heldCard == card,
                        )
                    }
                }
                is HomeRow.ContinueWatching -> Shelf("Continue watching") {
                    items(row.videos, key = { it.video.id.value }) { entry ->
                        val card = cardKey(row, entry.video)
                        VideoCard(
                            video = entry.video,
                            meta = historyCardMeta(entry.video, entry.mark),
                            onClick = { onPlay(entry.video) },
                            onOpenMenu = { onOpenMenu(entry.video, VideoSource.Elsewhere, card) },
                            modifier = cardMotion(),
                            progress = entry.mark.barFraction(),
                            held = heldCard == card,
                        )
                    }
                }
                is HomeRow.Channels -> Shelf("Your channels") {
                    items(row.channels, key = { it.subscription.channelUrl }) { card ->
                        ChannelCard(
                            name = card.subscription.name,
                            avatarUrl = card.subscription.avatarUrl,
                            meta = card.latestUpload?.let { ageText(it, row.now) },
                            onClick = { onOpenChannel(card.subscription.channelUrl) },
                        )
                    }
                }
            }
        }
    }
    if (visit != null) {
        val inView = rememberForYouInView(list, forYouLane, layout, if (heroTop != null) barTop else 0.dp)
        key(visit) { ShownTimers(inView, onForYouShown) }
    }
}

/** A card leaving a row (hidden, blocked) fades while its neighbours slide into its place. */
private fun LazyItemScope.cardMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = AynMotion.base(), fadeOutSpec = AynMotion.base())

/** A card's place on Home: its row and video (one video can show in two rows). */
private fun cardKey(row: HomeRow, video: VideoItem): String = "${row.key}/${video.id.value}"

/** Whether the page has scrolled the hero up under the top bar. */
@Composable
private fun rememberPastHero(list: LazyListState, barTop: Dp): State<Boolean> {
    val heroUnderBar = with(LocalDensity.current) { (HeroHeight - barTop).roundToPx() }
    return remember(list, heroUnderBar) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > heroUnderBar } }
}

/**
 * What of For you is in view (FY-D3): the hero with its reason while at least half of it shows,
 * and its row's cards wholly on screen while the row is wholly below [covered] (the top bar, opaque
 * once past the hero; the page's padding keeps it clear otherwise).
 */
@Composable
private fun rememberForYouInView(list: LazyListState, lane: LazyListState, layout: HomeLayout.Browse, covered: Dp): State<List<VideoItem>> {
    val current by rememberUpdatedState(layout)
    val coveredPx = with(LocalDensity.current) { covered.roundToPx() }
    return remember(list, lane, coveredPx) {
        derivedStateOf {
            val shown = current
            val page = list.layoutInfo
            val hero = (shown.top as? HomeTop.HeroTop)?.takeIf { it.reason != null && page.halfShows(TopKey) }?.hero?.video
            val row = shown.rows.firstOrNull { it is HomeRow.ForYou } as? HomeRow.ForYou
            val cards = if (row != null && row.key in page.whollyKeys(coveredPx)) {
                val keys = lane.layoutInfo.whollyKeys()
                row.picks.filter { it.video.id.value in keys }.map { it.video }
            } else {
                emptyList()
            }
            listOfNotNull(hero) + cards
        }
    }
}

private fun LazyListLayoutInfo.halfShows(key: Any): Boolean =
    visibleItemsInfo.any { it.key == key && it.offset + it.size / 2 >= viewportStartOffset }

/** The keys of the items wholly in the viewport, its first [covered] px not counting. */
private fun LazyListLayoutInfo.whollyKeys(covered: Int = 0): Set<Any> =
    visibleItemsInfo.filter { it.offset >= viewportStartOffset + covered && it.offset + it.size <= viewportEndOffset }.mapTo(HashSet()) { it.key }

/** Counts each of [inView] as shown once it stays in view [SHOWN_AFTER_MS] ([onShown]); a video that
 *  leaves view before then starts over. Draws nothing; reading [inView] here keeps scrolling from
 *  recomposing the rows. */
@Composable
private fun ShownTimers(inView: State<List<VideoItem>>, onShown: (VideoItem) -> Unit) {
    val currentOnShown by rememberUpdatedState(onShown)
    inView.value.forEach { video ->
        key(video.id.value) {
            LaunchedEffect(Unit) {
                delay(SHOWN_AFTER_MS)
                currentOnShown(video)
            }
        }
    }
}

private fun feedsFailedBody(channels: Int): String =
    if (channels == 1) {
        "Your channel’s feed didn’t come back. Try again, or open it below."
    } else {
        "None of your $channels channels’ feeds came back. Try again, or open a channel below."
    }

@Composable
private fun lastAttemptText(at: Instant): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return "Last attempt ${format.format(Date(at.toEpochMilliseconds()))}"
}
