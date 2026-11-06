package com.arshad.ayn.ui.tv.channel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.feature.channel.tabLabel
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.channel.ChannelLayout
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.channel.TabBody
import com.arshad.ayn.feature.channel.TabContent
import com.arshad.ayn.feature.channel.channelLayout
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Appearing
import com.arshad.ayn.ui.tv.common.GridCardTitleSize
import com.arshad.ayn.ui.tv.common.GridCellWidth
import com.arshad.ayn.ui.tv.common.GridColumnGap
import com.arshad.ayn.ui.tv.common.GridRowGap
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.PagePill
import com.arshad.ayn.ui.tv.common.PagePillHeight
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PlaylistCard
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.ShortCard
import com.arshad.ayn.ui.tv.common.ShortCardWidth
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.TOAST_MS
import com.arshad.ayn.ui.tv.common.indexOfFocusKey
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.Toast
import com.arshad.ayn.ui.tv.common.TopAlign
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.rememberPictureLight
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.feature.format.dateText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.tv.common.screenHeight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.SelectDwell
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import kotlinx.coroutines.delay

// b3-channel page geometry (px ÷ 2): the tabs (28 dp) start where the header ends and the cards
// 11 dp below them. While the cards hold focus the page rises 265 dp, so the tabs (faded out)
// sit 95 dp down and a focused row settles at their bottom, under the top scrim's fade.
private val FoldShift = 265.dp
private val TabsHeight = PagePillHeight
private val TabGap = 7.dp
private val GridTop = 11.dp
private val CellWidth = GridCellWidth
private val ColumnGap = GridColumnGap
private val RowGap = GridRowGap
// b3-channel shorts (px ÷ 2): Short cards six across, 17 dp apart, rows 20 dp apart.
private val ShortColumnGap = 17.dp
private val ShortRowGap = 20.dp
private val HeaderRise = 12.dp
private val StatementTop = 118.dp
private val ShortsStatementTop = 18.dp
private val TabEmptyTop = 17.dp
private val TabEmptyWidth = 500.dp
private val TabSkeletonWidths = listOf(60.dp, 75.dp)
private const val SkeletonCards = 4

/** A selected tab rests as a light keyline pill (master `.tab.is-sel`). */
private val TabSelected = ControlStyle.Pill.copy(
    restFill = Color.White.copy(alpha = 0.14f),
    restContent = KeColors.Ink,
    restKeyline = Color.White.copy(alpha = 0.5f),
)

/**
 * A channel page (`b3-channel` states 1–4, 6–7 and the v8 gaps) on [ChannelViewModel]: the
 * header over the channel's banner with Subscribe its one action, the tabs selecting on focus,
 * and the selected tab's cards; while the cards hold focus the header folds into a compact title.
 * A Short on the Shorts tab plays through [onPlayShort] with the tab's list as its run (M7-D13).
 * Holding OK on a video or Short opens its card menu on [menu] ([onRailShade] while it shows),
 * without Go to channel: every video here is this channel's (Q19). A [kids] profile's page has
 * no Subscribe and no Turn on Shorts: those are its own choices, which it doesn't make (KP-D11, D12).
 */
@Composable
internal fun ChannelScreen(
    viewModel: ChannelViewModel,
    menu: VideoMenuViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    onBack: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    kids: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = channelLayout(state, LocalContentFilter.current)
    val playShort: (VideoItem) -> Unit = { short -> onPlayShort(short, viewModel.openShorts()) }
    // Read as the page comes back from the Shorts player (a covered destination leaves composition).
    var shortsReturn by remember { mutableStateOf(viewModel.shortsReturn()) }
    VideoMenuHost(menu, onPlay, onOpenChannel = null, onRailShade, modifier, onPlayShort = playShort) { pageModifier, openMenu ->
        ScreenFocus(
            onNothingToFocus = onNothingToFocus,
            modifier = pageModifier,
            initialFocusKey = layout.initialFocusKey,
            entryKey = layout.entryKey,
            focusHeldElsewhere = focusHeldElsewhere,
            returnKey = shortsReturn?.let(::cardKey),
            onReturned = {
                shortsReturn = null
                viewModel.shortsReturned()
            },
        ) {
            when (layout) {
                ChannelLayout.Loading -> ChannelSkeleton()
                is ChannelLayout.Failed -> ChannelFailed(layout.failure, onRetry = viewModel::load, onBack = onBack)
                is ChannelLayout.Page -> ChannelPage(
                    layout = layout,
                    onSelectTab = viewModel::selectTab,
                    onToggleSubscribe = if (kids) null else viewModel::toggleSubscribe,
                    onTurnOnShorts = if (kids) null else viewModel::turnOnShorts,
                    onPlay = { video -> onPlay(video, false) },
                    onPlayShort = playShort,
                    onOpenMenu = openMenu,
                    onOpenShortMenu = { short -> menu.open(short, VideoSource.Elsewhere, short = true) },
                    onOpenPlaylist = onOpenPlaylist,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun ChannelPage(
    layout: ChannelLayout.Page,
    onSelectTab: (ChannelTab) -> Unit,
    onToggleSubscribe: (() -> Unit)?,
    onTurnOnShorts: (() -> Unit)?,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    onBack: () -> Unit,
) {
    val channel = layout.channel
    var folded by rememberSaveable { mutableStateOf(false) }
    val fold = animateFloatAsState(if (folded) 1f else 0f, KeMotion.move(), label = "fold")
    // Sampled once the backdrop has the banner, so the two don't download it side by side.
    var bannerLoaded by remember(channel.bannerUrl) { mutableStateOf(false) }
    val light by rememberPictureLight(channel.bannerUrl.takeIf { bannerLoaded })
    // An unsubscribe is confirmed for a while; subscribing again (the undo) takes it away.
    var toastShown by remember { mutableStateOf(false) }
    var toastRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(toastRun) {
        delay(TOAST_MS)
        toastShown = false
    }
    val toggleSubscribe = onToggleSubscribe?.let { toggle ->
        {
            if (layout.subscribed) {
                toastShown = true
                toastRun++
            }
            toggle()
        }
    }

    Box(Modifier.fillMaxSize()) {
        ChannelBackdrop(channel.bannerUrl, fold, onBannerLoaded = { bannerLoaded = true })
        when (val body = layout.body) {
            TabBody.ShortsOnly -> Column {
                HeaderCopy(channel, layout.subscribed, layout.latestVideos, light, toggleSubscribe)
                Statement(
                    kicker = Kicker(R.drawable.ic_shorts, "Shorts are turned off"),
                    title = "This channel only posts Shorts",
                    body = if (onTurnOnShorts == null) {
                        "Shorts don’t show in a kids profile."
                    } else {
                        "Turn Shorts on to see them here. That turns them on for this profile everywhere in Ayn: channels, Subscriptions and search."
                    },
                    smallTitle = true,
                    modifier = Modifier.padding(start = KeLayout.ContentStart, top = ShortsStatementTop),
                ) {
                    onTurnOnShorts?.let { PillButton(R.drawable.ic_shorts, "Turn on Shorts", ShortsOnKey, it, style = ControlStyle.Secondary) }
                    PillButton(R.drawable.ic_back, "Back", BackKey, onBack, style = ControlStyle.Secondary)
                }
            }
            is TabContent -> FoldingPage(
                layout = layout,
                body = body,
                light = light,
                fold = fold,
                folded = folded,
                onFoldedChange = { folded = it },
                onToggleSubscribe = toggleSubscribe,
                onSelectTab = onSelectTab,
                onPlay = onPlay,
                onPlayShort = onPlayShort,
                onOpenMenu = onOpenMenu,
                onOpenShortMenu = onOpenShortMenu,
                onOpenPlaylist = onOpenPlaylist,
            )
        }
        UnsubscribedToast(
            visible = toastShown && !layout.subscribed,
            channelName = channel.name,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/**
 * The header, then the tabs over the selected tab's cards. Focus decides the fold: the cards
 * taking it raise the page by one transform (the header fading up, the mini title appearing),
 * the header or the tabs taking it bring the page back with the cards at their start.
 */
@Composable
private fun FoldingPage(
    layout: ChannelLayout.Page,
    body: TabContent,
    light: Color,
    fold: State<Float>,
    folded: Boolean,
    onFoldedChange: (Boolean) -> Unit,
    onToggleSubscribe: (() -> Unit)?,
    onSelectTab: (ChannelTab) -> Unit,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
) {
    val tab = body.tab
    val returnKey = LocalFocusMemory.current?.returnKey
    val reveal = (body as? TabContent.Cards)?.items?.indexOfFocusKey(returnKey) { cardKey(it) }
    val gridState = rememberSaveable(tab, saver = revealingGridSaver(reveal)) { LazyGridState() }
    LaunchedEffect(folded, gridState) {
        if (!folded) gridState.animateScrollToItem(0)
    }
    // Tall enough to reach the screen's bottom once the page has risen.
    val tabAreaHeight = screenHeight() - (HeaderHeight - FoldShift)
    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .graphicsLayer { translationY = -FoldShift.toPx() * fold.value },
    ) {
        Box(
            Modifier
                .height(HeaderHeight)
                .onFocusChanged { if (it.hasFocus) onFoldedChange(false) }
                .graphicsLayer {
                    alpha = 1f - fold.value
                    translationY = -HeaderRise.toPx() * fold.value
                },
        ) {
            HeaderCopy(layout.channel, layout.subscribed, layout.latestVideos, light, onToggleSubscribe)
        }
        Box(Modifier.fillMaxWidth().height(tabAreaHeight)) {
            // Each tab (and its cards landing after Loading) fades in; the tabs of cards share one
            // grid, which updates its cards in place.
            Appearing(key = tab to body::class) {
                when (body) {
                    is TabContent.Cards if tab == ChannelTab.SHORTS -> ShortsGrid(
                        shorts = remember(body) { body.items.filterIsInstance<VideoItem>() },
                        state = gridState,
                        onPlay = onPlayShort,
                        onOpenMenu = onOpenShortMenu,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) onFoldedChange(true) },
                    )
                    is TabContent.Cards -> TabGrid(
                        items = body.items,
                        state = gridState,
                        onPlay = onPlay,
                        onOpenMenu = onOpenMenu,
                        onOpenPlaylist = onOpenPlaylist,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) onFoldedChange(true) },
                    )
                    is TabContent.Loading -> GridSkeleton(rememberSheen(), Modifier.padding(top = TabsHeight + GridTop))
                    is TabContent.Empty -> TabEmpty(tab, onlyTab = layout.tabs.size == 1)
                }
            }
            TabRow(
                tabs = layout.tabs,
                selected = tab,
                onSelect = onSelectTab,
                modifier = Modifier
                    .onFocusChanged { if (it.hasFocus) onFoldedChange(false) }
                    .graphicsLayer { alpha = 1f - fold.value },
            )
        }
    }
    TopScrim(fold)
    MiniTitle(layout.channel, tab, fold)
}

/**
 * [LazyGridState]'s own saved form, restored with [reveal]'s row at the cards' top when given, so
 * focus can return to that card (Q37). A Short's row rests there when D-pad focuses it too:
 * [TopAlign] brings the focused, scaled and lifted card's top to the tabs' bottom.
 */
private fun revealingGridSaver(reveal: Int?): Saver<LazyGridState, *> = listSaver(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { (index, offset) -> LazyGridState(reveal ?: index, if (reveal == null) offset else 0) },
)

/**
 * The tabs, selecting on focus (TVF-6) once focus has rested on a tab for [SelectDwellMs] (OK
 * selects at once), so tabs D-pad only passes over start no fetch; focus leaving the tabs first
 * selects nothing. Focus coming into them (down from Subscribe, up from the cards) lands on the
 * selected tab, so passing through never picks another.
 */
@Composable
private fun TabRow(
    tabs: List<ChannelTab>,
    selected: ChannelTab,
    onSelect: (ChannelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTab = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val dwell = remember(scope) { SelectDwell(scope) }
    Row(
        modifier = modifier
            .padding(start = KeLayout.ContentStart)
            .height(TabsHeight)
            .focusProperties { onEnter = { selectedTab.requestFocus() } }
            .onFocusChanged { if (!it.hasFocus) dwell.cancel() }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(TabGap),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            PagePill(
                onClick = { dwell.now { onSelect(tab) } },
                style = if (isSelected) TabSelected else ControlStyle.Pill,
                modifier = (if (isSelected) Modifier.focusRequester(selectedTab) else Modifier)
                    .focusMemory(tabKey(tab))
                    // Also on the selected tab: that replaces a pending selection with no change.
                    .onFocusChanged { if (it.isFocused) dwell.rest { onSelect(tab) } },
            ) {
                TintedText(tabLabel(tab), style = MaterialTheme.typography.labelLarge.atWeight(600))
            }
        }
    }
}

/**
 * A tab's cards in four columns under the tabs. A focused card's row settles at the tabs'
 * bottom ([TopAlign], the mockup's anchored scroll); rows that scroll past it pass under the
 * tabs' place, which the folded page has faded out. A focus group, so moving up keeps to the
 * cards until the first row, then goes to the tabs. Holding OK on a video calls [onOpenMenu].
 */
@Composable
private fun TabGrid(
    items: List<ListingItem>,
    state: LazyGridState,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { TabsHeight.toPx() }) }
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = GridCardTitleSize)
    PrefetchFaces(items) { items.filterIsInstance<VideoItem>() }
    ProvideBringIntoView(align) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CellWidth),
            state = state,
            contentPadding = PaddingValues(
                start = KeLayout.ContentStart,
                end = KeLayout.SafeHorizontal,
                top = TabsHeight + GridTop,
                bottom = KeLayout.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            modifier = modifier.fillMaxSize().focusGroup(),
        ) {
            items(items, key = ::cardKey, contentType = { if (it is PlaylistItem) "playlist" else "video" }) { item ->
                when (item) {
                    is VideoItem -> VideoCard(
                        video = item,
                        meta = if (item.isLive) watchingText(item.viewCount) else item.dateText(),
                        focusKey = cardKey(item),
                        onClick = { onPlay(item) },
                        width = CellWidth,
                        titleStyle = titleStyle,
                        onLongClick = { onOpenMenu(item) },
                    )
                    is PlaylistItem -> PlaylistCard(
                        playlist = item,
                        focusKey = cardKey(item),
                        onClick = { onOpenPlaylist(item) },
                        width = CellWidth,
                        titleStyle = titleStyle,
                    )
                    // channelLayout drops channel cards: a channel's tabs hold none.
                    is ChannelItem -> Unit
                }
            }
        }
    }
}

/**
 * State 5: the Shorts tab's Shorts six across, laid out and scrolled as [TabGrid]'s cards. Holding
 * OK on one calls [onOpenMenu].
 */
@Composable
private fun ShortsGrid(
    shorts: List<VideoItem>,
    state: LazyGridState,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { TabsHeight.toPx() }) }
    ProvideBringIntoView(align) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(ShortCardWidth),
            state = state,
            contentPadding = PaddingValues(
                start = KeLayout.ContentStart,
                end = KeLayout.SafeHorizontal,
                top = TabsHeight + GridTop,
                bottom = KeLayout.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(ShortColumnGap),
            verticalArrangement = Arrangement.spacedBy(ShortRowGap),
            modifier = modifier.fillMaxSize().focusGroup(),
        ) {
            items(shorts, key = ::cardKey) { short ->
                ShortCard(
                    video = short,
                    meta = viewsText(short.viewCount),
                    focusKey = cardKey(short),
                    onClick = { onPlay(short) },
                    onLongClick = { onOpenMenu(short) },
                )
            }
        }
    }
}

/**
 * v8 tab-empty: a compact line where the cards would be; focus stays on the tab. A channel's
 * [onlyTab] (a Topic channel reports none, so it opens on an empty Videos) points nowhere else.
 */
@Composable
private fun TabEmpty(tab: ChannelTab, onlyTab: Boolean) {
    Column(Modifier.padding(start = KeLayout.ContentStart, top = TabsHeight + TabEmptyTop, end = KeLayout.SafeHorizontal)) {
        Text("Nothing in ${tabLabel(tab)} yet", style = MaterialTheme.typography.displayMedium.copy(fontSize = 23.sp))
        Text(
            text = if (onlyTab) "This channel hasn’t put anything here." else "This channel hasn’t put anything here. Its other tabs may have more.",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 1.4.em),
            color = KeColors.Ink2,
            modifier = Modifier.padding(top = 7.dp).widthIn(max = TabEmptyWidth),
        )
    }
}

/** A row of cards in the grid's geometry under one [sheen] (nothing focusable). */
@Composable
private fun GridSkeleton(sheen: State<Float>, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = KeLayout.ContentStart), horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
        repeat(SkeletonCards) {
            Column(Modifier.width(CellWidth)) {
                Spacer(Modifier.fillMaxWidth().height(CellWidth / ThumbAspect).skeleton(sheen))
                SkeletonLine(0.86f, top = 8.dp, sheen)
                SkeletonLine(0.52f, top = 6.dp, sheen)
            }
        }
    }
}

/** v8 loading: header, tabs and cards in their final shape, no focus until the page lands (TVF-7). */
@Composable
private fun ChannelSkeleton() {
    val sheen = rememberSheen()
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        HeaderSkeleton(sheen)
        Column(Modifier.padding(top = HeaderHeight)) {
            Row(Modifier.padding(start = KeLayout.ContentStart), horizontalArrangement = Arrangement.spacedBy(TabGap)) {
                TabSkeletonWidths.forEach { Spacer(Modifier.size(it, TabsHeight).skeleton(sheen, CircleShape)) }
            }
            GridSkeleton(sheen, Modifier.padding(top = GridTop))
        }
    }
}

/** State 7 and v8 error-yt: offline, or YouTube's reply unreadable; Back returns to where the user was. */
@Composable
private fun ChannelFailed(failure: AccessFailure, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        Statement(
            kicker = failureKicker(failure),
            title = "This channel couldn’t load",
            body = if (failure == AccessFailure.OFFLINE) {
                "Check that the Shield is online, then try again. Back returns you to where you were."
            } else {
                UnreadableReply
            },
            modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop),
        ) {
            PillButton(R.drawable.ic_retry, "Try again", RetryKey, onRetry)
            PillButton(R.drawable.ic_back, "Back", BackKey, onBack, style = ControlStyle.Secondary)
        }
    }
}

/** State 2's confirmation, bottom right: it rises in and fades out (D8, transform and alpha). */
@Composable
private fun UnsubscribedToast(visible: Boolean, channelName: String, modifier: Modifier = Modifier) {
    Toast(visible, R.drawable.ic_check, "Unsubscribed from $channelName", "press Subscribe to undo", modifier)
}
