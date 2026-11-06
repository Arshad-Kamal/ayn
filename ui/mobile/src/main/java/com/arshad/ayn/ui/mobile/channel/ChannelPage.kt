package com.arshad.ayn.ui.mobile.channel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.channel.ChannelLayout
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.channel.TabBody
import com.arshad.ayn.feature.channel.TabContent
import com.arshad.ayn.feature.channel.channelLayout
import com.arshad.ayn.feature.channel.tabLabel
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.blurb
import com.arshad.ayn.feature.format.latestVideosText
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.format.videoCountText
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.SelectChip
import com.arshad.ayn.ui.mobile.common.listItemMotion
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.NoticeLine
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.PageBar
import com.arshad.ayn.ui.mobile.common.pageBarBottom
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.rememberPictureLight
import com.arshad.ayn.ui.mobile.home.SkeletonBar
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.menu.openShort
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.search.ResultSkeleton
import com.arshad.ayn.ui.mobile.search.ShortCard
import com.arshad.ayn.ui.mobile.search.rowMeta
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.rememberShortsReturn
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlinx.coroutines.flow.Flow

private val BannerHeight = 206.dp
private val HeadRise = 44.dp
private val AvatarSize = 80.dp
private val RowGap = 14.dp
private val ShortGap = 6.dp
private const val ShortsAcross = 3
private const val TabsKey = "tabs"
private const val TabsIndex = 1

/** The Shorts tab's first row of three follows the tabs. */
private const val ShortsRowsIndex = TabsIndex + 1

/**
 * A channel's page on the phone (`m6-subscriptions-and-channel` 11–21, M6-D14) on
 * [ChannelViewModel], opened over the tab it came from: the banner under a see-through back
 * button, the header with Subscribe (none in a kids profile), the tabs as chips, then the selected
 * tab in Search's compact rows (Shorts three across, playlists as rows). Past the header the bar
 * turns solid with the name and the tabs stay under it. Unsubscribing shows a snackbar whose Undo
 * subscribes again (Q23). A long press or ⋮ opens the card menu on [menu], without Go to channel
 * (every video here is this channel's). A Short plays in the Shorts player running through the
 * tab, and coming back scrolls to the one last played and lights it.
 */
@Composable
internal fun ChannelPage(
    viewModel: ChannelViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    marks: Flow<Map<VideoId, WatchMark>>,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter = LocalContentFilter.current
    val layout = remember(state, filter) { channelLayout(state, filter) }
    val watched by marks.collectAsStateWithLifecycle(emptyMap())
    val kids = chrome.profile.isKids
    val list = rememberLazyListState()
    val barBottom = pageBarBottom()
    val stuck by rememberTabsStuck(list, barBottom)
    val page = layout as? ChannelLayout.Page
    var unsubscribedFrom by remember { mutableStateOf<String?>(null) }
    val toggleSubscribe: (() -> Unit)? = if (kids) {
        null
    } else {
        {
            if (page?.subscribed == true) unsubscribedFrom = page.channel.name
            viewModel.toggleSubscribe()
        }
    }
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<String?>(null) }
    val shortsBack = rememberShortsReturn(chrome, viewModel::openShorts, onPlayShort, viewModel::shortsReturn, viewModel::shortsReturned)
    val playShort: (VideoItem) -> Unit = shortsBack::play
    VideoMenuHost(menu, onPlay, onOpenChannel = null, modifier, onPlayShort = playShort) { openMenu ->
        val actions = PageActions(
            marks = watched,
            heldRow = heldRow.takeIf { menuOpen },
            onPlay = { onPlay(it, false) },
            onOpenMenu = { video ->
                heldRow = video.listingKey
                openMenu(video, VideoSource.Elsewhere)
            },
            onPlayShort = playShort,
            onOpenShortMenu = menu::openShort,
            onOpenPlaylist = onOpenPlaylist,
            litShort = shortsBack.lit,
        )
        Box(Modifier.fillMaxSize().background(AynColors.Ground)) {
            AnimatedContent(
                layout,
                transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) },
                contentKey = { it::class },
                label = "channel",
            ) { shown ->
                when (shown) {
                    ChannelLayout.Loading -> ChannelSkeleton()
                    is ChannelLayout.Failed -> ChannelFailed(shown.failure, onRetry = viewModel::load, onBack = chrome.nav::back, top = barBottom)
                    is ChannelLayout.Page -> ChannelList(
                        layout = shown,
                        list = list,
                        barBottom = barBottom,
                        stuck = stuck,
                        actions = actions,
                        onToggleSubscribe = toggleSubscribe,
                        onTurnOnShorts = viewModel::turnOnShorts.takeIf { !kids },
                        onSelectTab = viewModel::selectTab,
                    )
                }
            }
            val overBanner = layout == ChannelLayout.Loading || (page?.channel?.bannerUrl != null && !stuck)
            PageBar(title = page?.channel?.name?.takeIf { stuck }, solid = !overBanner, onBack = chrome.nav::back)
            val tabs = (page?.body as? TabContent)?.let { body -> page.tabs to body.tab }
            AnimatedVisibility(stuck && tabs != null, enter = fadeIn(AynMotion.press()), exit = fadeOut(AynMotion.press()), modifier = Modifier.padding(top = barBottom)) {
                tabs?.let { (all, selected) -> TabChips(all, selected, viewModel::selectTab, stuck = true) }
            }
            NoticeSnackbar(
                notice = unsubscribedFrom.takeIf { page?.subscribed == false },
                line = { name -> NoticeLine("Unsubscribed from $name", "its videos leave Subscriptions") },
                onAction = {
                    unsubscribedFrom = null
                    viewModel.toggleSubscribe()
                },
                onClose = { unsubscribedFrom = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** What the page's rows do and show. */
private class PageActions(
    val marks: Map<VideoId, WatchMark>,
    val heldRow: String?,
    val onPlay: (VideoItem) -> Unit,
    val onOpenMenu: (VideoItem) -> Unit,
    val onPlayShort: (VideoItem) -> Unit,
    val onOpenShortMenu: (VideoItem) -> Unit,
    val onOpenPlaylist: (PlaylistItem) -> Unit,
    /** The Short the Shorts player came back from, lit in the Shorts tab for a moment. */
    val litShort: VideoId?,
)

/** Whether the tabs have scrolled up under the bar (they then stay there, M6-D14). */
@Composable
private fun rememberTabsStuck(list: LazyListState, barBottom: Dp): State<Boolean> {
    val barPx = with(LocalDensity.current) { barBottom.roundToPx() }
    return remember(list, barPx) {
        derivedStateOf {
            val tabs = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == TabsKey }
            if (tabs != null) tabs.offset < barPx else list.firstVisibleItemIndex > 1
        }
    }
}

@Composable
private fun ChannelList(
    layout: ChannelLayout.Page,
    list: LazyListState,
    barBottom: Dp,
    stuck: Boolean,
    actions: PageActions,
    onToggleSubscribe: (() -> Unit)?,
    onTurnOnShorts: (() -> Unit)?,
    onSelectTab: (ChannelTab) -> Unit,
) {
    val body = layout.body
    val tab = (body as? TabContent)?.tab
    // Another tab picked with the tabs held under the bar: its rows start just below them.
    var shownTab by rememberSaveable { mutableStateOf(tab) }
    val density = LocalDensity.current
    LaunchedEffect(tab) {
        if (tab == shownTab) return@LaunchedEffect
        shownTab = tab
        if (!stuck) return@LaunchedEffect
        list.scrollToItem(TabsIndex)
        val tabsHeight = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == TabsKey }?.size ?: 0
        list.scrollBy(tabsHeight - with(density) { (barBottom + StuckTabsHeight).toPx() })
    }
    // The run may have paged past the rows on screen: the lit Short's row comes in, one row's room above it.
    LaunchedEffect(actions.litShort) {
        val lit = actions.litShort ?: return@LaunchedEffect
        val cards = (body as? TabContent.Cards)?.takeIf { it.tab == ChannelTab.SHORTS } ?: return@LaunchedEffect
        val row = shortsRows(cards).indexOfFirst { run -> run.any { it.id == lit } }
        if (row < 0) return@LaunchedEffect
        val index = ShortsRowsIndex + row
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index - 1)
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        // The header is item 0, the tabs item TabsIndex.
        item(key = "header", contentType = "header") { ChannelHeader(layout, barBottom, onToggleSubscribe) }
        when (body) {
            TabBody.ShortsOnly -> item(key = "shorts-only", contentType = "statement") { ShortsOnly(onTurnOnShorts) }
            is TabContent -> {
                // Under the held copy while stuck, so TalkBack reads one set of tabs.
                item(key = TabsKey, contentType = TabsKey) { TabChips(layout.tabs, body.tab, onSelectTab, hidden = stuck) }
                tabBody(body, layout.tabs.size, actions)
            }
        }
    }
}

private fun LazyListScope.tabBody(body: TabContent, tabCount: Int, actions: PageActions) {
    val tab = body.tab
    when (body) {
        is TabContent.Loading -> item(key = "$tab/skeleton", contentType = "skeleton") {
            val pulse = rememberPulse()
            Column(verticalArrangement = Arrangement.spacedBy(RowGap)) { repeat(4) { ResultSkeleton(pulse) } }
        }
        is TabContent.Empty -> item(key = "$tab/empty", contentType = "empty") { TabEmpty(tab, onlyTab = tabCount == 1) }
        is TabContent.Cards if tab == ChannelTab.SHORTS -> {
            items(shortsRows(body), key = { "$tab/${it.first().id.value}" }, contentType = { "shorts" }) { run ->
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = RowGap), horizontalArrangement = Arrangement.spacedBy(ShortGap)) {
                    run.forEach { short ->
                        ShortCard(short, { actions.onPlayShort(short) }, { actions.onOpenShortMenu(short) }, Modifier.weight(1f), lit = short.id == actions.litShort)
                    }
                    repeat(ShortsAcross - run.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        is TabContent.Cards -> items(body.items, key = { "$tab/${it.listingKey}" }, contentType = { it::class.simpleName }) { item ->
            TabItemRow(item, actions, listItemMotion().padding(bottom = RowGap))
        }
    }
}

/** The Shorts tab's Shorts, three to a row as the list draws them. */
private fun shortsRows(body: TabContent.Cards): List<List<VideoItem>> = body.items.filterIsInstance<VideoItem>().chunked(ShortsAcross)

@Composable
private fun TabItemRow(item: ListingItem, actions: PageActions, modifier: Modifier) {
    when (item) {
        is VideoItem -> ResultRow(
            video = item,
            progress = actions.marks[item.id].barFraction(),
            onClick = { actions.onPlay(item) },
            onOpenMenu = { actions.onOpenMenu(item) },
            held = actions.heldRow == item.listingKey,
            meta = rowMeta(item, withChannel = false),
            modifier = modifier,
        )
        is PlaylistItem -> PlaylistRow(item, { actions.onOpenPlaylist(item) }, modifier)
        // channelLayout leaves channel cards out: a channel's tabs hold none.
        is ChannelItem -> Unit
    }
}

/** The banner (under the bar), then avatar, name, the counts, the description and Subscribe. */
@Composable
private fun ChannelHeader(layout: ChannelLayout.Page, barBottom: Dp, onToggleSubscribe: (() -> Unit)?) {
    val channel = layout.channel
    val light by rememberPictureLight(channel.avatarUrl)
    Column {
        if (channel.bannerUrl != null) {
            Box(Modifier.fillMaxWidth().height(BannerHeight).background(AynColors.Surface1)) {
                Picture(channel.bannerUrl)
                Box(Modifier.fillMaxSize().background(BannerFade))
            }
        }
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .let { if (channel.bannerUrl != null) it.risenBy(HeadRise) else it.padding(top = barBottom + 12.dp) },
        ) {
            Box(
                Modifier
                    .size(AvatarSize)
                    .graphicsLayer {
                        shadowElevation = 16.dp.toPx()
                        shape = CircleShape
                        spotShadowColor = light
                        ambientShadowColor = light
                    }
                    .background(AynColors.Ground, CircleShape)
                    .padding(3.dp),
            ) { ChannelAvatar(channel.name, channel.avatarUrl, monogramSize = 26f) }
            Text(channel.name, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                buildAnnotatedString {
                    subscribersText(channel.subscriberCount)?.let { withStyle(weightSpan(650, AynColors.Ink)) { append(it) } }
                    layout.latestVideos?.let {
                        if (length > 0) append(" · ")
                        append(latestVideosText(it))
                    }
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = AynColors.Ink2,
                modifier = Modifier.padding(top = 8.dp),
            )
            channel.description?.let(::blurb)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AynColors.Ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            if (onToggleSubscribe != null) {
                if (layout.subscribed) {
                    PillButton("Subscribed", onToggleSubscribe, Modifier.padding(top = 16.dp).height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_check)
                } else {
                    PillButton("Subscribe", onToggleSubscribe, Modifier.padding(top = 16.dp).height(48.dp), icon = R.drawable.ic_plus)
                }
            }
        }
    }
}

private val BannerFade = Brush.verticalGradient(
    0f to Color(0xFF0D0907).copy(alpha = 0.35f),
    0.3f to Color.Transparent,
    0.55f to Color.Transparent,
    1f to AynColors.Ground,
)

private val ChipHeight = 36.dp
private val TabsTop = 6.dp
private val StuckTabsBottom = 10.dp
/** The held tabs' height: their padding and a chip. */
private val StuckTabsHeight = TabsTop + ChipHeight + StuckTabsBottom

/** Places the content [rise] higher and gives that space back to what follows (`.chead`'s negative margin). */
private fun Modifier.risenBy(rise: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val risePx = rise.roundToPx()
    layout(placeable.width, (placeable.height - risePx).coerceAtLeast(0)) { placeable.place(0, -risePx) }
}

/** The tabs as chips (`.ctabs`); [stuck] under the bar, they sit on the ground with a hairline; [hidden] (the list's copy under it) they leave semantics. */
@Composable
private fun TabChips(tabs: List<ChannelTab>, selected: ChannelTab, onSelect: (ChannelTab) -> Unit, stuck: Boolean = false, hidden: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AynColors.Ground)
            .drawBehind { if (stuck) drawRect(AynColors.Line, topLeft = Offset(0f, size.height - 1.dp.toPx())) }
            .let { if (hidden) it.clearAndSetSemantics { } else it.selectableGroup() }
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = TabsTop, bottom = if (stuck) StuckTabsBottom else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab -> SelectChip(tabLabel(tab), tab == selected, { onSelect(tab) }, Modifier.height(ChipHeight)) }
    }
}

/** A playlist on its tab: its picture with the count, its title and "Playlist · N videos". A tap opens it. */
@Composable
private fun PlaylistRow(playlist: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val count = videoCountText(playlist.videoCount)
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(168.dp).padding(top = 6.dp)) {
            Box(Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).padding(horizontal = 10.dp).fillMaxWidth().height(12.dp).background(AynColors.Surface3, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp)))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(11.dp)).background(AynColors.Surface2)) {
                Picture(playlist.thumbnailUrl)
                if (playlist.videoCount >= 0) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).clearAndSetSemantics { }.padding(6.dp).background(AynColors.Ground.copy(alpha = 0.8f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(13.dp))
                        Text("${playlist.videoCount}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 6.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyMedium.atWeight(620).copy(fontSize = 15.sp, lineHeight = 19.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("Playlist", count).joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TabEmpty(tab: ChannelTab, onlyTab: Boolean) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 28.dp)) {
        Text("Nothing in ${tabLabel(tab)} yet", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 28.sp))
        Text(
            if (onlyTab) "This channel hasn’t put anything here yet." else "This channel hasn’t put anything here. Its other tabs may have more.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
            color = AynColors.Ink3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** State 19: the only tab is Shorts and Show Shorts is off; a kids profile gets the line, no button. */
@Composable
private fun ShortsOnly(onTurnOnShorts: (() -> Unit)?) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp)) {
        HorizontalDivider(color = AynColors.Line)
        Statement(
            icon = R.drawable.ic_shorts,
            kicker = "Shorts are turned off",
            title = "This channel only posts Shorts",
            body = if (onTurnOnShorts == null) {
                "Shorts don’t show in a kids profile."
            } else {
                "Turn Shorts on to see them here. That turns them on for this profile everywhere in Ayn: channels, Subscriptions and search."
            },
            compact = true,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 34.dp),
        ) { onTurnOnShorts?.let { PillButton("Turn on Shorts", it, Modifier.height(48.dp), icon = R.drawable.ic_shorts) } }
    }
}

@Composable
private fun ChannelFailed(failure: AccessFailure, onRetry: () -> Unit, onBack: () -> Unit, top: Dp) {
    val offline = failure == AccessFailure.OFFLINE
    Statement(
        icon = if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
        kicker = if (offline) "No connection to YouTube" else "YouTube changed something",
        title = "This channel couldn’t load",
        body = if (offline) "Check that the phone is online, then try again. Back returns you to where you were." else UnreadableReply,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = top + 60.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        PillButton("Try again", onRetry, Modifier.height(48.dp), icon = R.drawable.ic_retry)
        PillButton("Back", onBack, Modifier.height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_back)
    }
}

/** State 18: only the address is known; the banner, header, tabs and rows wait in their shape. */
@Composable
private fun ChannelSkeleton() {
    val pulse = rememberPulse()
    Column(Modifier.fillMaxSize()) {
        SkeletonBar(pulse, Modifier.fillMaxWidth().height(BannerHeight), RoundedCornerShape(0.dp))
        Column(Modifier.padding(horizontal = 20.dp).risenBy(HeadRise)) {
            SkeletonBar(pulse, Modifier.size(AvatarSize), CircleShape)
            SkeletonBar(pulse, Modifier.padding(top = 14.dp).size(200.dp, 34.dp))
            SkeletonBar(pulse, Modifier.padding(top = 12.dp).size(240.dp, 12.dp))
            SkeletonBar(pulse, Modifier.padding(top = 14.dp).size(300.dp, 12.dp))
            SkeletonBar(pulse, Modifier.padding(top = 18.dp).size(150.dp, 48.dp), CircleShape)
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(84.dp, 76.dp, 92.dp).forEach { SkeletonBar(pulse, Modifier.size(it, 36.dp), CircleShape) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) { repeat(2) { ResultSkeleton(pulse) } }
    }
}
