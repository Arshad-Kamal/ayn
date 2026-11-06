package com.arshad.ayn.ui.tv.home

import com.arshad.ayn.feature.home.HomeLayout
import com.arshad.ayn.feature.home.HomeRow
import com.arshad.ayn.feature.home.HomeTop
import com.arshad.ayn.feature.home.homeLayout
import com.arshad.ayn.feature.home.shownUnder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.feature.foryou.ForYouPick
import com.arshad.ayn.feature.home.ForYouHidden
import com.arshad.ayn.feature.home.HomeViewModel
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.CardRow
import com.arshad.ayn.ui.tv.common.ChannelAvatarCard
import com.arshad.ayn.ui.tv.common.ChannelCardWidth
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.LocalDeviceSettings
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.ui.tv.common.VideoCardWidth
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.cardMeta
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.indexOfFocusKey
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusSlot
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.historyCardMeta
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import java.util.Date
import kotlin.time.Instant

/** The zone of whatever sits above the rows (hero or statement); each row's zone is its key. */
private const val TopZone = "top"

// Master mockup geometry (px ÷ 2): the hero is 395 dp tall with its copy from 98 dp; a
// focused row's heading settles 160 dp from the top (state 9's compact hero: 192 dp).
internal val HeroHeight = 395.dp
internal val HeroCopyTop = 98.dp
private val RowPivot = 160.dp
private val CompactRowPivot = 192.dp
private val RowGap = 22.dp
internal val CardSpacing = 14.dp
private val ChannelSpacing = 20.dp
internal val StatementTop = 118.dp

// `b3-home` nohero: the first row's heading 32 dp from the top, where a focused row settles.
private val HeroOffTop = 32.dp

/**
 * Home (`b3-home` states 1–9) on [HomeViewModel]. Rows rise under the hero as focus moves down
 * (the list scrolls the focused zone to the mockup's stop; only the rows near view are composed,
 * PERF-D10), the focused card lights the room, and focus lands once content does ([ScreenFocus]
 * entry).
 * Holding OK on a row's video, or the hero's ⋯, opens its card menu on [menu] ([onRailShade]
 * while it shows); a For you pick's menu says why and can hide it (FY-D8, FY-D11). Each start
 * (opened, or returned to) tells the ViewModel Home became visible, so For you ranks then and
 * never while Home shows (FY-D9); its cards in view, its hero with the top focused, count as shown
 * after [SHOWN_AFTER_MS], and a pick played counts as opened (FY-D3). A kids profile's Home
 * (`kp-kids` kidhome) is its rows from the top, the owner's picks among them ([homeLayout]).
 * Each start says whether it is an arrival ([consumeArrival]: from the rail, or the app back in
 * the foreground), and every key press and the hero's focus are reported, for when a fresh
 * subscriptions feed lands over the kept one (PERF P9c).
 */
@Composable
internal fun HomeScreen(
    viewModel: HomeViewModel,
    menu: VideoMenuViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onSearch: () -> Unit,
    onOpenLibrary: (() -> Unit)?,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    consumeArrival: () -> Boolean = { false },
) {
    var visits by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(viewModel) {
        visits++
        started = true
        viewModel.onVisible(arrived = consumeArrival())
        onStopOrDispose { started = false }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter = LocalContentFilter.current
    val heroOn = LocalDeviceSettings.current.heroOnHome
    // Filters every row and parses channel URLs: once per state, not per recomposition (a visit's
    // start and stop recompose this too).
    val layout = remember(state, filter, heroOn) { homeLayout(state.shownUnder(filter), heroOn = heroOn) }
    val attempt = lastAttemptText(state.attemptedAt)
    val open: (VideoItem, Boolean) -> Unit = { video, startOver ->
        viewModel.onOpen(video)
        onPlay(video, startOver)
    }
    VideoMenuHost(menu, open, onOpenChannel, onRailShade, modifier, onForYouHide = viewModel::onHide) { pageModifier, openMenu ->
        ScreenFocus(
            onNothingToFocus = onNothingToFocus,
            modifier = pageModifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) viewModel.onKeyPressed()
                false
            },
            initialFocusKey = layout.initialFocusKey,
            entryKey = layout.entryKey,
            focusHeldElsewhere = focusHeldElsewhere,
            // The Hidden notice's Undo takes focus as the menu closes (FY-D11).
            returnKey = UndoKey.takeIf { state.forYouHidden != null },
        ) {
            when (layout) {
                HomeLayout.Loading -> HomeSkeleton()
                is HomeLayout.Failed -> HomeFailed(layout.failure, attempt, onRetry = viewModel::refresh, onOpenLibrary = onOpenLibrary)
                is HomeLayout.Browse -> HomeBrowse(
                    layout = layout,
                    attempt = attempt,
                    calls = BrowseCalls(
                        onPlay = { video -> open(video, false) },
                        onWatchLater = viewModel::onHeroWatchLater,
                        onOpenMenu = openMenu,
                        onOpenForYouMenu = { pick -> menu.open(pick.video, VideoSource.ForYou(pick.reason)) },
                        onOpenChannel = onOpenChannel,
                        onSearch = onSearch,
                        onRetry = viewModel::refresh,
                    ),
                    hidden = state.forYouHidden,
                    onUndoHide = viewModel::onUndoHide,
                    onHiddenNoticeClosed = viewModel::onHiddenNoticeClosed,
                    visit = visits.takeIf { started },
                    onForYouShown = viewModel::onForYouShown,
                    onTopFocus = viewModel::onTopFocus,
                )
            }
        }
    }
}

@Composable
private fun lastAttemptText(at: Instant): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return "Last attempt ${format.format(Date(at.toEpochMilliseconds()))}"
}

/** Home's error; a kids profile has no Library, so no [onOpenLibrary] (`null`) and no word of it. */
@Composable
private fun HomeFailed(failure: AccessFailure, attempt: String, onRetry: () -> Unit, onOpenLibrary: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        val offline = failure == AccessFailure.OFFLINE
        Statement(
            kicker = failureKicker(failure),
            title = "Home couldn’t load",
            body = listOfNotNull(
                if (offline) "None of your rows came back. Check that the Shield is online, then try again." else UnreadableReply,
                onOpenLibrary?.let { if (offline) "Your library and settings still work offline." else "Your library and settings still work." },
            ).joinToString(" "),
            stamp = attempt,
            modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop),
        ) {
            PillButton(R.drawable.ic_retry, "Try again", RetryKey, onRetry)
            onOpenLibrary?.let { PillButton(R.drawable.ic_nav_library, "Open Library", OpenLibraryKey, it, style = ControlStyle.Secondary) }
        }
    }
}

@Composable
private fun HomeBrowse(
    layout: HomeLayout.Browse,
    attempt: String,
    calls: BrowseCalls,
    hidden: ForYouHidden?,
    onUndoHide: () -> Unit,
    onHiddenNoticeClosed: () -> Unit,
    visit: Int?,
    onForYouShown: (VideoItem) -> Unit,
    onTopFocus: (Boolean) -> Unit,
) {
    var zone by rememberSaveable { mutableStateOf(TopZone) }
    val list = rememberLazyListState()
    val cardLight = remember { FocusedCardLight() }
    val pivot = with(LocalDensity.current) {
        when (layout.top) {
            is HomeTop.FeedsFailed -> CompactRowPivot
            HomeTop.HeroOff -> HeroOffTop
            else -> RowPivot
        }.roundToPx()
    }
    val rowKeys = layout.rows.map { it.key }
    val placed = remember { PlacedOnce() }
    // The focused zone's stop, again when rows land or leave around it (Live now slotting in).
    LaunchedEffect(zone, rowKeys, pivot) {
        // The first known position is where the screen already was (its list state is restored on
        // return): no slide then.
        if (zone == TopZone) {
            list.stopAt(TopZone, index = 0, stop = 0, animate = placed.done)
        } else {
            // A zone whose row left keeps the list where it is.
            val row = rowKeys.indexOf(zone).takeIf { it >= 0 } ?: return@LaunchedEffect
            list.stopAt(zone, index = row + 1, stop = pivot, animate = placed.done)
        }
        placed.done = true
    }
    val away = animateFloatAsState(if (zone == TopZone) 0f else 1f, KeMotion.move(), label = "away")
    val focusedRow = layout.rows.firstOrNull { it.key == zone }
    // One face for the backdrop and the copy: one wait, one lookup.
    val heroTop = layout.top as? HomeTop.HeroTop
    val heroFace = heroTop?.let { rememberCardFace(it.hero.video) }
    val heroFocused = zone == TopZone && heroTop != null
    LaunchedEffect(heroFocused) { onTopFocus(heroFocused) }
    // For you's cards wholly on screen in its row.
    var forYouShowing by remember { mutableStateOf(emptyList<VideoItem>()) }
    // Hero off (`b3-home` nohero): For you's row landing after the feed's rows took focus takes
    // it to its first card, unless a key was pressed since. A return to Home (a restored row
    // zone) keeps the focus BACK restores, even when For you's first picks land then.
    val keys = remember { KeysPressed() }
    val leadingPick = (layout.rows.firstOrNull() as? HomeRow.ForYou)?.picks?.firstOrNull()?.takeIf { layout.top == HomeTop.HeroOff }
    val ledAtStart = remember { leadingPick != null || zone != TopZone }
    val memory = LocalFocusMemory.current
    LaunchedEffect(leadingPick != null) {
        if (leadingPick == null || ledAtStart || keys.any || zone == TopZone) return@LaunchedEffect
        withFrameNanos { }
        memory?.focus(forYouKey(leadingPick.video.id))
    }

    // Every row's faces are asked for up front, composed or not, so a row scrolled to has them.
    layout.rows.forEach { row -> key(row.key) { PrefetchRowFaces(row) } }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) keys.any = true
                false
            },
    ) {
        when (layout.top) {
            is HomeTop.HeroTop -> HeroBackdrop(heroFace?.thumbnailUrl, away)
            else -> Box(Modifier.fillMaxSize().restingRoomLight())
        }
        CardRoomLight(cardLight, lit = focusedRow is HomeRow.Videos || focusedRow is HomeRow.ContinueWatching || focusedRow is HomeRow.ForYou)
        ProvideBringIntoView(NoFocusScroll) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = KeLayout.SafeVertical),
            ) {
                item(key = TopZone, contentType = TopZone) {
                    HomeTopItem(
                        layout = layout,
                        heroFace = heroFace,
                        away = away,
                        attempt = attempt,
                        calls = calls,
                        modifier = Modifier
                            .zIndex(if (zone == TopZone) 1f else 0f)
                            .onFocusChanged { if (it.hasFocus) zone = TopZone },
                    )
                }
                // Keyed: Live now can land after the other rows and slot in between them.
                items(layout.rows, key = { it.key }, contentType = { it::class }) { row ->
                    val rowModifier = Modifier
                        .zIndex(if (zone == row.key) 1f else 0f)
                        .onFocusChanged { if (it.hasFocus) zone = row.key }
                        .padding(bottom = RowGap)
                    when (row) {
                        is HomeRow.ForYou -> ForYouRow(row, calls.onPlay, calls.onOpenForYouMenu, cardLight::onCardFocused, onShowing = { forYouShowing = it }, rowModifier)
                        is HomeRow.Videos -> VideoRow(row, calls.onPlay, calls.onOpenMenu, onCardFocused = cardLight::onCardFocused, rowModifier)
                        is HomeRow.Channels -> ChannelsRow(row, calls.onOpenChannel, rowModifier)
                        is HomeRow.ContinueWatching -> ContinueRow(row, calls.onPlay, calls.onOpenMenu, onCardFocused = cardLight::onCardFocused, rowModifier)
                    }
                }
            }
        }
        // The row counts as in view while the top or the row itself holds focus.
        val forYouRow = layout.rows.firstOrNull { it is HomeRow.ForYou }
        val inView = buildList {
            if (zone == TopZone && heroTop?.reason != null) add(heroTop.hero.video)
            if (forYouRow != null && (zone == TopZone || zone == forYouRow.key)) addAll(forYouShowing)
        }
        ForYouOnHome(hidden, heroTop, inView, visit, onUndoHide, onHiddenNoticeClosed, onForYouShown)
    }
}

/** What Home's top and rows do when used. */
private class BrowseCalls(
    val onPlay: (VideoItem) -> Unit,
    val onWatchLater: () -> Unit,
    val onOpenMenu: (VideoItem) -> Unit,
    val onOpenForYouMenu: (ForYouPick) -> Unit,
    val onOpenChannel: (String) -> Unit,
    val onSearch: () -> Unit,
    val onRetry: () -> Unit,
)

/** Whatever sits above the rows: the hero, a statement, or the room the first row starts under. */
@Composable
private fun HomeTopItem(
    layout: HomeLayout.Browse,
    heroFace: CardFace?,
    away: State<Float>,
    attempt: String,
    calls: BrowseCalls,
    modifier: Modifier,
) {
    when (val top = layout.top) {
        is HomeTop.HeroTop -> HeroSection(
            top = top,
            face = heroFace,
            away = away,
            onPlay = calls.onPlay,
            onWatchLater = calls.onWatchLater,
            onMore = { video ->
                val reason = top.reason
                if (reason != null) calls.onOpenForYouMenu(ForYouPick(video, reason)) else calls.onOpenMenu(video)
            },
            modifier = modifier,
        )
        HomeTop.Blank -> Box(modifier.fillMaxWidth().height(HeroHeight).fadeAway(away)) {
            Statement(
                kicker = Kicker(R.drawable.ic_nav_subscriptions, "No subscriptions yet"),
                title = "Follow channels to fill Home",
                body = "Home shows the newest videos from the channels you follow. Find one with Search, open it and choose Subscribe.",
                modifier = Modifier.padding(start = KeLayout.ContentStart, top = HeroCopyTop),
            ) {
                PillButton(R.drawable.ic_nav_search, "Search", SearchKey, calls.onSearch)
            }
        }
        is HomeTop.FeedsFailed -> Box(
            modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = 1f - 0.65f * away.value }
                .padding(start = KeLayout.ContentStart, top = 84.dp, bottom = 32.dp),
        ) {
            Statement(
                kicker = Kicker(R.drawable.ic_alert, "Part of Home didn’t load"),
                title = "Couldn’t load your subscriptions",
                body = feedsFailedBody(top.channelCount),
                compact = true,
                stamp = attempt,
            ) {
                PillButton(R.drawable.ic_retry, "Try again", RetryKey, calls.onRetry)
            }
        }
        HomeTop.NothingApproved -> Box(modifier.fillMaxWidth().height(HeroHeight)) {
            // Nothing here takes focus: the rail does (ScreenFocus).
            Statement(
                kicker = Kicker(R.drawable.ic_shield, "Nothing approved yet"),
                title = "Nothing to watch yet",
                body = "Ask a grown-up to approve some channels or videos.",
                modifier = Modifier.padding(start = KeLayout.ContentStart, top = HeroCopyTop),
            ) {}
        }
        HomeTop.None -> Spacer(modifier.height(RowPivot))
        HomeTop.HeroOff -> Spacer(modifier.height(HeroOffTop))
    }
}

/**
 * Scrolls so the item keyed [key], at [index], starts [stop] px from the list's top (animated when
 * [animate]). One not laid out yet (a restored zone's first layout, a row that just landed) is
 * given a frame; one still out of view then (focus reached it beyond the screen) is jumped to.
 */
private suspend fun LazyListState.stopAt(key: String, index: Int, stop: Int, animate: Boolean) {
    if (distanceTo(key, stop) == null) withFrameNanos { }
    val distance = distanceTo(key, stop)
    when {
        distance == null -> {
            scrollToItem(index)
            scrollBy(-stop.toFloat())
        }
        animate -> animateScrollBy(distance, KeMotion.move())
        else -> scrollBy(distance)
    }
}

/** How far to scroll for the item keyed [key] to start [stop] px from the top; `null` while it
 *  isn't laid out. */
private fun LazyListState.distanceTo(key: String, stop: Int): Float? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { (it.offset - stop).toFloat() }

/** Home moves by its own stops (the zone effect), so a focus change asks no scroll of the list;
 *  TV's default would pivot the focused card instead. */
private object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** A row's DeArrow faces, asked for while Home shows whether or not the row is composed. */
@Composable
private fun PrefetchRowFaces(row: HomeRow) {
    when (row) {
        is HomeRow.ForYou -> PrefetchFaces(row.picks) { row.picks.map { it.video } }
        is HomeRow.Videos -> PrefetchFaces(row.videos) { row.videos }
        is HomeRow.ContinueWatching -> PrefetchFaces(row.videos) { row.videos.map { it.video } }
        is HomeRow.Channels -> Unit
    }
}

private fun feedsFailedBody(channels: Int): String =
    if (channels == 1) {
        "Your channel’s feed didn’t come back. Try again, or open it below."
    } else {
        "None of your $channels channels’ feeds came back. Try again, or open a channel below."
    }

@Composable
private fun VideoRow(
    row: HomeRow.Videos,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardRow(title = row.title, itemWidth = VideoCardWidth, itemSpacing = CardSpacing, modifier = modifier) { rowFocused ->
        itemsIndexed(row.videos, key = { _, video -> video.id.value }) { index, video ->
            VideoCard(
                video = video,
                meta = cardMeta(video, row.now).orEmpty(),
                focusKey = "${row.key}:${video.id.value}",
                onClick = { onPlay(video) },
                rowFocused = rowFocused,
                onFocused = onCardFocused,
                onLongClick = { onOpenMenu(video) },
                focusSlot = FocusSlot(row.key, index),
            )
        }
    }
}

/**
 * Continue watching: each card's bar and what's left; OK resumes where the profile left off. A
 * video played from here leads the row on BACK, so the row comes back showing it; a finished one
 * leaves the row, and its slot hands focus to the card now in its place.
 */
@Composable
private fun ContinueRow(
    row: HomeRow.ContinueWatching,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusKey = { entry: HistoryVideo -> "${row.key}:${entry.video.id.value}" }
    val reveal = row.videos.indexOfFocusKey(LocalFocusMemory.current?.rememberedKey, focusKey)
    CardRow(title = "Continue watching", itemWidth = VideoCardWidth, itemSpacing = CardSpacing, modifier = modifier, reveal = reveal) { rowFocused ->
        itemsIndexed(row.videos, key = { _, entry -> entry.video.id.value }) { index, entry ->
            VideoCard(
                video = entry.video,
                meta = historyCardMeta(entry.video, entry.mark),
                focusKey = focusKey(entry),
                onClick = { onPlay(entry.video) },
                progress = entry.mark.barFraction(),
                rowFocused = rowFocused,
                onFocused = onCardFocused,
                onLongClick = { onOpenMenu(entry.video) },
                focusSlot = FocusSlot(row.key, index),
            )
        }
    }
}

@Composable
private fun ChannelsRow(row: HomeRow.Channels, onOpenChannel: (String) -> Unit, modifier: Modifier = Modifier) {
    CardRow(title = "Your channels", itemWidth = ChannelCardWidth, itemSpacing = ChannelSpacing, modifier = modifier) { rowFocused ->
        itemsIndexed(row.channels, key = { _, card -> card.subscription.channelUrl }) { index, card ->
            ChannelAvatarCard(
                name = card.subscription.name,
                avatarUrl = card.subscription.avatarUrl,
                meta = card.latestUpload?.let { ageText(it, row.now) },
                focusKey = "channel:${card.subscription.channelUrl}",
                onClick = { onOpenChannel(card.subscription.channelUrl) },
                rowFocused = rowFocused,
                focusSlot = FocusSlot(row.key, index),
            )
        }
    }
}

/** The hero copy fades and lifts out while the rows hold focus (one 200 ms move for both; the
 *  master times the fade at 180). */
internal fun Modifier.fadeAway(away: State<Float>): Modifier = graphicsLayer {
    alpha = 1f - away.value
    translationY = -12.dp.toPx() * away.value
}

// Written by the scroll effect only: no composition reads it.
private class PlacedOnce {
    var done: Boolean = false
}

// Written by Home's key preview, read by the Hero-off landing effect: no composition reads it.
private class KeysPressed {
    var any: Boolean = false
}

/** The loading pool: warm where the picture will land, breathing (alpha only). */
@Composable
internal fun rememberBreath(): State<Float> =
    rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BreathMs), RepeatMode.Reverse),
        label = "breath",
    )

private const val BreathMs = 2400
