package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.feature.library.dayHeading
import com.arshad.ayn.feature.library.historyRowLine
import com.arshad.ayn.feature.library.localDate
import com.arshad.ayn.feature.library.timeOfDayText
import com.arshad.ayn.feature.library.videosText
import com.arshad.ayn.feature.format.barFraction
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.HistoryDay
import com.arshad.ayn.feature.library.HistoryState
import com.arshad.ayn.feature.library.HistoryViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.ListRowInset
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.PagePill
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.TopAlign
import com.arshad.ayn.ui.tv.common.VideoListRow
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.atWeight

// `b3-library` history geometry (px ÷ 2): the list starts 75 dp down under a 12 dp top fade, its
// rows (144 × 81 thumbnails, 15 dp gaps) at most 700 dp wide, their band included; a day's heading has 11 dp above it
// (none for the first) and 5 below. A focused row settles one heading below the list's top. Clear
// history sits at the page head's right, 31 dp down.
private val ListTop = 75.dp
private val ListFade = 12.dp
private val ListPadding = 10.dp
private val ListEnd = 150.dp
private val RowMaxWidth = 700.dp
private val ThumbWidth = 144.dp
private val RowGap = 15.dp
private val DayTop = 11.dp
private val DayBottom = 5.dp
private val DayLine = 20.dp
private val ClearTop = 31.dp
private val DaySubSize = 12.sp

private const val CLEAR_KEY = "clear-history"

/**
 * All history (`b3-library` history, empty, confirm) on [HistoryViewModel]: the profile's videos
 * by day, newest first, each with where it was left and when; Clear history asks first, on a
 * screen covering the shell (the rail hides, [onRailShade]; Keep history focused). Closing it
 * returns focus to Clear history, or to the empty statement once history is gone. Holding OK on a
 * row opens its menu on [menu]; closing it returns focus to the row (or where it was).
 */
@Composable
internal fun HistoryScreen(
    viewModel: HistoryViewModel,
    menu: VideoMenuViewModel,
    time: ShellTime,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onGoHome: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history = state.history
    val confirming = state.confirmingClear
    val videoMenu by menu.menu.collectAsStateWithLifecycle()
    val menuFrom = rememberMenuCameFrom(videoMenu)
    val playVideo = { video: VideoItem, startOver: Boolean -> onPlay(PlayerRoute(video, startOver = startOver)) }
    BackHandler(enabled = confirming, onBack = viewModel::onKeepHistory)
    BackHandler(enabled = videoMenu != null, onBack = menu::onBack)
    val clear = remember { FocusRequester() }
    val page = remember { FocusRequester() }
    FocusOnClose(open = confirming, target = clear)
    FocusOnClose(open = menuHoldsFocus(menu, videoMenu), target = page)
    val shown = (history as? HistoryState.Ready)?.takeIf { it.count > 0 }

    Overlays(
        sheet = videoMenu.asSheet(),
        cover = layerOf(shown?.count?.takeIf { confirming }, videoMenu.asCover()),
        onRailShade = onRailShade,
        modifier = modifier,
        sheetKey = ::menuIdentity,
        // The confirm keeps its screen while the count it names changes.
        coverKey = { cover -> cover.key { CLEAR_KEY } },
        sheetContent = { sheet -> VideoMenuSheet(sheet, menuFrom, menu, playVideo, onOpenChannel) },
        coverContent = { cover ->
            when (cover) {
                is Layer.Own -> ConfirmClear(cover.value, onKeep = viewModel::onKeepHistory, onClear = viewModel::onConfirmClear)
                is Layer.Menu -> VideoMenuCover(cover.menu, menu)
            }
        },
    ) {
        WithMenuNotice(menu) {
            ScreenFocus(
                onNothingToFocus = onNothingToFocus,
                modifier = Modifier.focusRequester(page),
                initialFocusKey = when {
                    history == HistoryState.Failed -> RETRY_KEY
                    shown != null -> shown.days.first().videos.first().let { historyKey(it.video) }
                    else -> GO_HOME_KEY
                },
                // Focus waits for the first read; a change of layout enters again.
                entryKey = when (history) {
                    HistoryState.Loading -> null
                    HistoryState.Failed -> HistoryState.Failed
                    is HistoryState.Ready -> shown != null
                },
                focusHeldElsewhere = focusHeldElsewhere,
            ) {
                Box(Modifier.fillMaxSize().restingRoomLight()) {
                    when {
                        history == HistoryState.Loading -> Unit
                        history == HistoryState.Failed -> Statement(
                            kicker = Kicker(R.drawable.ic_alert, "Couldn’t read this Shield"),
                            title = "History couldn’t load",
                            body = "Your history is still on this Shield; Ayn couldn’t read it just now. Try again.",
                            modifier = Modifier.padding(start = KeLayout.ContentStart, top = LibraryStatementTop),
                        ) {
                            PillButton(R.drawable.ic_retry, "Try again", RETRY_KEY, viewModel::retry)
                        }
                        shown == null -> Statement(
                            kicker = Kicker(R.drawable.ic_later, "Nothing watched yet"),
                            title = "Your history starts with the next video",
                            body = "Videos you play show up here by day, with your place saved so you can pick up where you stopped.",
                            modifier = Modifier.padding(start = KeLayout.ContentStart, top = LibraryStatementTop),
                        ) {
                            PillButton(R.drawable.ic_nav_home, "Go to Home", GO_HOME_KEY, onGoHome)
                        }
                        else -> HistoryList(shown.days, time, onPlay = { video -> playVideo(video, false) }, onOpenMenu = { video -> menu.open(video, VideoSource.History) })
                    }
                    PageHead("History", aside = null)
                    if (shown != null) {
                        PagePill(
                            onClick = viewModel::onClearHistory,
                            style = ControlStyle.Pill.copy(growFrom = TransformOrigin(1f, 0.5f)),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = ClearTop, end = KeLayout.SafeHorizontal)
                                .focusRequester(clear)
                                .focusMemory(CLEAR_KEY),
                        ) {
                            TintedIcon(painterResource(R.drawable.ic_trash), contentDescription = null, modifier = Modifier.size(14.dp))
                            TintedText("Clear history", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryList(days: List<HistoryDay>, time: ShellTime, onPlay: (VideoItem) -> Unit, onOpenMenu: (VideoItem) -> Unit) {
    val density = LocalDensity.current
    val align = remember(density) { TopAlign(with(density) { (ListPadding + DayTop + DayLine + DayBottom).toPx() }) }
    val rowLight = remember { FocusedCardLight() }
    var rowsFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val today = remember(days) { time.clock.now().localDate(time.zone) }

    CardRoomLight(rowLight, lit = rowsFocused)
    PrefetchFaces(days) { days.flatMap { day -> day.videos.map { it.video } } }
    ProvideBringIntoView(align) {
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(
                start = KeLayout.ContentStart - ListRowInset,
                top = ListPadding,
                end = KeLayout.SafeHorizontal - ListRowInset,
                bottom = ListEnd,
            ),
            modifier = Modifier
                .padding(top = ListTop)
                .fillMaxSize()
                .topFade(ListFade)
                .onFocusChanged { rowsFocused = it.hasFocus },
        ) {
            days.forEachIndexed { index, day ->
                item(key = "day:${day.date}", contentType = "day") {
                    val (title, sub) = remember(day, today, locale) { dayHeading(day, today, locale) }
                    DayHeading(title, sub, Modifier.padding(start = ListRowInset, top = if (index == 0) 0.dp else DayTop, bottom = DayBottom))
                }
                items(day.videos, key = { historyKey(it.video) }, contentType = { "row" }) { entry ->
                    VideoListRow(
                        video = entry.video,
                        line = historyRowLine(entry.video, entry.mark),
                        focusKey = historyKey(entry.video),
                        onClick = { onPlay(entry.video) },
                        onFocused = rowLight::onCardFocused,
                        modifier = Modifier.widthIn(max = RowMaxWidth),
                        thumbWidth = ThumbWidth,
                        gap = RowGap,
                        trailing = timeOfDayText(entry.lastWatchedAt, time.zone, locale, use24Hour),
                        progress = entry.mark.barFraction(),
                        onLongClick = { onOpenMenu(entry.video) },
                    )
                }
            }
        }
    }
}

/** A day's heading (`.group-h`): its name, then the date or weekday beside it. */
@Composable
private fun DayHeading(title: String, sub: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(
            sub,
            style = MaterialTheme.typography.bodyMedium.atWeight(450).copy(fontSize = DaySubSize),
            color = KeColors.Ink3,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

/** Clear all watch history? (`confirm`): what goes and what stays; Keep history is focused first. */
@Composable
private fun ConfirmClear(count: Int, onKeep: () -> Unit, onClear: () -> Unit) {
    val keep = rememberInitialFocus()
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow("History", Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_trash) }
                GuidedTitle("Clear all watch history?")
                GuidedBody(
                    "This removes ${videosText(count)} and every saved position from this Shield. " +
                        "It can’t be undone; playlists and subscriptions stay.",
                )
            },
            actions = {
                GuidedButton(R.drawable.ic_back, "Keep history", ControlStyle.Secondary, onKeep, Modifier.focusRequester(keep))
                GuidedButton(R.drawable.ic_trash, "Clear history", ControlStyle.Danger, onClear)
            },
        )
    }
}
