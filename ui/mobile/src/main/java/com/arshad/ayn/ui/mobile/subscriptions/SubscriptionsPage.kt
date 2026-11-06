package com.arshad.ayn.ui.mobile.subscriptions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.latestVideosText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.subscriptions.FeedBody
import com.arshad.ayn.feature.subscriptions.FeedState
import com.arshad.ayn.feature.subscriptions.SubscriptionsLayout
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.feature.format.channelsText
import com.arshad.ayn.feature.subscriptions.dayNote
import com.arshad.ayn.feature.subscriptions.dayTitle
import com.arshad.ayn.feature.subscriptions.everyChannelDays
import com.arshad.ayn.feature.subscriptions.subscriptionsLayout
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.listItemMotion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.menu.openShort
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.search.ResultSkeleton
import com.arshad.ayn.ui.mobile.search.ShortsShelf
import com.arshad.ayn.ui.mobile.search.rowMeta
import com.arshad.ayn.ui.mobile.shell.ScrollsToTop
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellTopBar
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.rememberShortsReturn
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.weightSpan
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Subscriptions on the phone (`m6-subscriptions-and-channel` states 1–10, M6-D14) on
 * [SubscriptionsViewModel], under the open profile's filter (KP-D19): the channel strip, then every
 * channel's feed by day in Search's compact rows with their watch bars from [marks], or a picked
 * channel's latest with Open channel; the Shorts shelf after the first day (a pick's first video)
 * while Show Shorts is on, played as one run (coming back lights the one last played). The strip
 * scrolls away with the feed; pulling down at the top refreshes and syncs ([onPull], M8-D8); a tap
 * on the tab while it shows scrolls up. A tap plays, a long press or ⋮ opens the card menu on
 * [menu], its row held behind the dim. Each start says whether it is an
 * arrival ([consumeArrival]), and every touch counts as a key press, for when a fresh feed lands
 * over the kept one (PERF P9c).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionsPage(
    viewModel: SubscriptionsViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    marks: Flow<Map<VideoId, WatchMark>>,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    consumeArrival: () -> Boolean,
    modifier: Modifier = Modifier,
    onPull: () -> Unit = {},
) {
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible(arrived = consumeArrival())
        onStopOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val watched by marks.collectAsStateWithLifecycle(emptyMap())
    val locale = LocalConfiguration.current.locales[0]
    val contentFilter = LocalContentFilter.current
    val zone = ZoneId.systemDefault()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    // Grouped once per feed, so a pick in the strip (and All again) doesn't regroup it.
    val feed = state.feedState
    val days = remember(feed, zone, firstDayOfWeek, contentFilter) {
        (feed as? FeedState.Ready)?.let { everyChannelDays(it, zone, firstDayOfWeek, contentFilter) }
    }
    val layout = remember(state, days, contentFilter) { subscriptionsLayout(state, zone, firstDayOfWeek, contentFilter, days) }
    val list = rememberLazyListState()
    ScrollsToTop(list, chrome.nav.reselected == Tab.Subscriptions, chrome.nav::onScrolledToTop)
    // Only a load a pull started shows the indicator.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(feed) { if (feed != FeedState.Loading) pulled = false }
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<String?>(null) }
    val shortsBack = rememberShortsReturn(chrome, viewModel::openShorts, onPlayShort, viewModel::shortsReturn, viewModel::shortsReturned)
    val playShort: (VideoItem) -> Unit = shortsBack::play
    VideoMenuHost(menu, onPlay, onOpenChannel, modifier, onPlayShort = playShort) { openMenu ->
        val rows = FeedRows(
            marks = watched,
            heldRow = heldRow.takeIf { menuOpen },
            onPlay = { onPlay(it, false) },
            onOpenMenu = { video, row ->
                heldRow = row
                openMenu(video, VideoSource.Elsewhere)
            },
            onPlayShort = playShort,
            onOpenShortMenu = menu::openShort,
            litShort = shortsBack.lit,
        )
        Column(Modifier.fillMaxSize().reportsTouches(viewModel::onKeyPressed)) {
            SubscriptionsTopBar(chrome, list)
            PullToRefreshBox(
                isRefreshing = pulled && feed == FeedState.Loading,
                onRefresh = {
                    pulled = true
                    viewModel.refresh()
                    onPull()
                },
                modifier = Modifier.weight(1f),
            ) {
                // Loading and the rest cross-fade (alpha only, D8); a feed landing over another doesn't.
                AnimatedContent(
                    layout,
                    transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) },
                    contentKey = { it is SubscriptionsLayout.Loading },
                    label = "subscriptions",
                ) { shown ->
                    when (shown) {
                        is SubscriptionsLayout.Loading -> SubscriptionsSkeleton(shown.channelCount)
                        else -> SubscriptionsList(
                            layout = shown,
                            picked = state.filter,
                            list = list,
                            rows = rows,
                            onSelect = viewModel::selectFilter,
                            onRetry = viewModel::refresh,
                            onOpenChannel = onOpenChannel,
                            onFindChannel = { chrome.nav.open(SearchKey) },
                        )
                    }
                }
            }
        }
    }
}

/** What the feed's rows do and show. */
private class FeedRows(
    val marks: Map<VideoId, WatchMark>,
    val heldRow: String?,
    val onPlay: (VideoItem) -> Unit,
    val onOpenMenu: (video: VideoItem, row: String) -> Unit,
    val onPlayShort: (VideoItem) -> Unit,
    val onOpenShortMenu: (VideoItem) -> Unit,
    /** The Short the Shorts player came back from, lit on its shelf for a moment. */
    val litShort: VideoId?,
)

/** Every touch on the page is reported ([onTouch]), not taken. */
private fun Modifier.reportsTouches(onTouch: () -> Unit): Modifier = pointerInput(onTouch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch()
    }
}

/** The top bar, lifting once the list scrolls; reads the scroll here so the page doesn't recompose with it. */
@Composable
private fun SubscriptionsTopBar(chrome: ShellChrome, list: LazyListState) {
    val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val lift by animateFloatAsState(if (scrolled) 1f else 0f, AynMotion.press(), label = "lift")
    ShellTopBar(chrome, lift = { lift })
}

@Composable
private fun SubscriptionsList(
    layout: SubscriptionsLayout,
    picked: String?,
    list: LazyListState,
    rows: FeedRows,
    onSelect: (String?) -> Unit,
    onRetry: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onFindChannel: () -> Unit,
) {
    val feed = layout as? SubscriptionsLayout.Feed
    val selected = feed?.selected?.channelUrl
    // A new pick starts at the top; its rows (keyed by the pick) fade in. Saved, so the page coming
    // back from a channel or the player keeps its place.
    var shownPick by rememberSaveable { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != shownPick) {
            shownPick = selected
            list.scrollToItem(0)
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val updated = clockTime(feed?.loadedAt)
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        when (layout) {
            is SubscriptionsLayout.Loading -> Unit
            SubscriptionsLayout.Blank -> item(key = StatementKey, contentType = StatementKey) {
                Statement(
                    icon = R.drawable.ic_nav_subscriptions,
                    kicker = "Nothing here yet",
                    title = "Follow channels without an account",
                    body = "Subscribe from any channel page and new videos land here, newest first. Your list stays on this phone.",
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 64.dp),
                ) { PillButton("Find a channel", onFindChannel, Modifier.height(StatementButton), icon = R.drawable.ic_nav_search) }
            }
            is SubscriptionsLayout.Failed -> {
                item(key = StripKey, contentType = StripKey) {
                    ChannelStrip(layout.channels, picked, onSelect)
                }
                item(key = StatementKey, contentType = StatementKey) { FailedStatement(layout.failure, layout.channelCount, onRetry) }
            }
            is SubscriptionsLayout.Feed -> {
                item(key = StripKey, contentType = StripKey) { ChannelStrip(layout.channels, selected, onSelect) }
                feedHead(layout, updated, onRetry, onOpenChannel)
                feedBody(layout.body, layout.loadedAt, selected ?: AllKey, locale, rows, onRetry, onOpenChannel)
            }
        }
    }
}

/** The partial note, or the line under the strip: every channel's count and when it updated, or the pick's. */
private fun LazyListScope.feedHead(layout: SubscriptionsLayout.Feed, updated: String?, onRetry: () -> Unit, onOpenChannel: (String) -> Unit) {
    val picked = layout.selected
    when {
        layout.failedNames.isNotEmpty() -> item(key = NoteKey, contentType = NoteKey) { PartialNote(layout.failedNames, onRetry) }
        picked == null -> item(key = LineKey, contentType = LineKey) {
            FeedLine(metaLine(channelsText(layout.channels.size), updated?.let { "updated $it" }))
        }
        else -> item(key = LineKey, contentType = LineKey) {
            val body = layout.body
            FeedLine(
                text = when (body) {
                    is FeedBody.Latest -> body.videos.takeIf { it.isNotEmpty() }?.let { latestVideosText(it.size) }
                    is FeedBody.ChannelFailed -> "didn’t refresh"
                    is FeedBody.ChannelEmpty -> "no videos"
                    is FeedBody.Days -> null
                }.orEmpty(),
                name = picked.name,
                // An empty channel's statement carries Open channel itself.
                onOpenChannel = { onOpenChannel(picked.channelUrl) }.takeIf { body !is FeedBody.ChannelEmpty },
            )
        }
    }
}

/**
 * The feed under the head: every channel's days with the shelf after the first, or the pick's
 * latest with its shelf after the first video, or the pick's statement. Row keys carry [pick], so
 * another pick's rows fade in rather than move.
 */
private fun LazyListScope.feedBody(
    body: FeedBody,
    now: Instant,
    pick: String,
    locale: Locale,
    rows: FeedRows,
    onRetry: () -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    when (body) {
        is FeedBody.Days -> {
            if (body.groups.isEmpty()) shelf(body.shorts, "From your channels", pick, rows)
            body.groups.forEachIndexed { index, group ->
                val title = dayTitle(group.day, locale)
                item(key = "$pick/day:$title", contentType = DayKey) { DayHeading(title, dayNote(group.day, locale), listItemMotion()) }
                videoRows(group.videos, now, withChannel = true, pick, rows)
                if (index == 0) shelf(body.shorts, "From your channels", pick, rows)
            }
        }
        is FeedBody.Latest -> {
            val name = body.channel.name
            if (body.videos.isNotEmpty()) {
                item(key = "$pick/latest", contentType = DayKey) { DayHeading("Latest from $name", note = null, listItemMotion()) }
            }
            videoRows(body.videos.take(1), now, withChannel = false, pick, rows)
            shelf(body.shorts, "From $name", pick, rows)
            videoRows(body.videos.drop(1), now, withChannel = false, pick, rows)
        }
        is FeedBody.ChannelFailed -> item(key = "$pick/statement", contentType = StatementKey) {
            val name = body.channel.name
            Statement(
                icon = R.drawable.ic_alert,
                kicker = "$name didn’t answer",
                title = "Nothing to show from $name",
                body = "Its videos will show here once it answers. Try again, or pick another channel.",
                compact = true,
                modifier = CompactStatement,
            ) { PillButton("Try again", onRetry, Modifier.height(StatementButton), icon = R.drawable.ic_retry) }
        }
        is FeedBody.ChannelEmpty -> item(key = "$pick/statement", contentType = StatementKey) {
            val name = body.channel.name
            Statement(
                icon = R.drawable.ic_nav_subscriptions,
                kicker = "No videos in your feed",
                title = "Nothing from $name yet",
                body = "This channel has no videos to list here. Its page shows everything it has.",
                compact = true,
                modifier = CompactStatement,
            ) {
                PillButton("Open channel", { onOpenChannel(body.channel.channelUrl) }, Modifier.height(StatementButton), icon = R.drawable.ic_nav_subscriptions)
            }
        }
    }
}

private fun LazyListScope.videoRows(videos: List<VideoItem>, now: Instant, withChannel: Boolean, pick: String, rows: FeedRows) {
    items(videos, key = { rowKey(pick, it) }, contentType = { RowKey }) { video ->
        val row = rowKey(pick, video)
        ResultRow(
            video = video,
            progress = rows.marks[video.id].barFraction(),
            onClick = { rows.onPlay(video) },
            onOpenMenu = { rows.onOpenMenu(video, row) },
            held = rows.heldRow == row,
            meta = rowMeta(video, now, withChannel),
            modifier = listItemMotion().padding(bottom = RowGap),
        )
    }
}

/** A row's key, also what marks it held while its menu is open. */
private fun rowKey(pick: String, video: VideoItem): String = "$pick/video:${video.id.value}"

private fun LazyListScope.shelf(shorts: List<VideoItem>, title: String, pick: String, rows: FeedRows) {
    if (shorts.isEmpty()) return
    item(key = "$pick/shorts", contentType = ShelfKey) {
        ShortsShelf(shorts, rows.onPlayShort, rows.onOpenShortMenu, listItemMotion().padding(bottom = 14.dp), title = title, lit = rows.litShort)
    }
}

/** A day's heading (`h3.day`, Subscriptions' and History's): its title, and its [note] beside it. */
@Composable
internal fun DayHeading(title: String, note: String?, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(title)
            note?.let {
                withStyle(MaterialTheme.typography.bodySmall.toSpanStyle().copy(color = AynColors.Ink3)) { append("  $it") }
            }
        },
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp, lineHeight = 24.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
    )
}

/** The line under the strip (`.smeta`): [name] (a pick's) in ink, then [text]; a pick's Open channel at its end. */
@Composable
private fun FeedLine(text: String, name: String? = null, onOpenChannel: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 2.dp).height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                name?.let {
                    withStyle(weightSpan(650, AynColors.Ink)) { append(it) }
                    if (text.isNotEmpty()) append(" · ")
                }
                append(text)
            },
            style = MaterialTheme.typography.bodySmall,
            color = AynColors.Ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onOpenChannel != null) OpenChannelChip(onOpenChannel)
    }
}

@Composable
private fun FailedStatement(failure: AccessFailure, channelCount: Int, onRetry: () -> Unit) {
    val offline = failure == AccessFailure.OFFLINE
    Statement(
        icon = if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
        kicker = if (offline) "No connection to YouTube" else "YouTube changed something",
        title = "Subscriptions couldn’t refresh",
        body = when {
            !offline -> "Your list is safe on this phone. $UnreadableReply"
            channelCount == 1 -> "Your channel didn’t answer. Your list is safe on this phone; try again in a moment."
            else -> "None of your $channelCount channels answered. Your list is safe on this phone; try again in a moment."
        },
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) { PillButton("Try again", onRetry, Modifier.height(StatementButton), icon = R.drawable.ic_retry) }
}

/** Loading (state 5): the strip in its shape (as many avatars as the list holds) and rows as skeletons. */
@Composable
private fun SubscriptionsSkeleton(channelCount: Int) {
    val pulse = rememberPulse()
    Column(Modifier.fillMaxSize()) {
        StripSkeleton(channelCount, pulse)
        Column(Modifier.padding(top = 70.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { ResultSkeleton(pulse) }
        }
    }
}

/** "8:41 PM" in the phone's clock format. */
@Composable
private fun clockTime(at: Instant?): String? {
    at ?: return null
    return android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(at.toEpochMilliseconds()))
}

private val StatementButton = 48.dp
private val RowGap = 14.dp
private val CompactStatement = Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp)
private const val AllKey = "all"
private const val StripKey = "strip"
private const val NoteKey = "note"
private const val LineKey = "line"
private const val StatementKey = "statement"
private const val DayKey = "day"
private const val RowKey = "row"
private const val ShelfKey = "shorts"
