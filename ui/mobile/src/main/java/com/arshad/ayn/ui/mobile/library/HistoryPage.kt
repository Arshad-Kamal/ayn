package com.arshad.ayn.ui.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.library.HistoryState
import com.arshad.ayn.feature.library.HistoryVideo
import com.arshad.ayn.feature.library.HistoryViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.dayHeading
import com.arshad.ayn.feature.library.historyMarkText
import com.arshad.ayn.feature.library.localDate
import com.arshad.ayn.feature.library.timeOfDayText
import com.arshad.ayn.feature.library.videosText
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.PageBar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.listItemMotion
import com.arshad.ayn.ui.mobile.common.pageBarBottom
import com.arshad.ayn.ui.mobile.common.rememberScrolled
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.menu.ConfirmSheetContent
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.search.ListPictureWidth
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.subscriptions.DayHeading
import com.arshad.ayn.ui.mobile.theme.AynColors
import java.time.ZoneId
import java.util.Locale

private val RowGap = 14.dp

/**
 * All history (`m6-library-and-playlist` 5–8; M6-D15) on [HistoryViewModel], opened from the
 * Library: the profile's videos by day, newest first, each with its channel, where it was left and
 * when. A tap resumes it ([onPlay]); a long press or ⋮ opens its card menu on [menu], whose Remove
 * from history takes the row out (no Undo, Q26). Clear history sits in the bar while there is
 * history and asks first in a sheet (Q25); Keep history, Back or a tap on the dim keeps it.
 */
@Composable
internal fun HistoryPage(
    viewModel: HistoryViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    time: ShellTime = ShellTime(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history = state.history
    val shown = (history as? HistoryState.Ready)?.takeIf { it.count > 0 }
    val list = rememberLazyListState()
    val barBottom = pageBarBottom()
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<String?>(null) }
    val playVideo = { video: VideoItem, startOver: Boolean -> onPlay(PlayerRoute(video, startOver = startOver)) }
    VideoMenuHost(menu, playVideo, onOpenChannel, modifier) { openMenu ->
        Box(Modifier.fillMaxSize().background(AynColors.Ground)) {
            LazyColumn(state = list, contentPadding = PaddingValues(top = barBottom, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                when {
                    history == HistoryState.Loading -> Unit
                    history == HistoryState.Failed -> item(key = "statement", contentType = "statement") {
                        Statement(
                            icon = R.drawable.ic_alert,
                            kicker = "Couldn’t read this phone",
                            title = "History couldn’t load",
                            body = "Your history is still on this phone; Ayn couldn’t read it just now. Try again.",
                            modifier = StatementPadding.semantics { liveRegion = LiveRegionMode.Polite },
                        ) { PillButton("Try again", viewModel::retry, Modifier.height(48.dp), icon = R.drawable.ic_retry) }
                    }
                    shown == null -> item(key = "statement", contentType = "statement") {
                        Statement(
                            icon = R.drawable.ic_later,
                            kicker = "Nothing watched yet",
                            title = "Your history starts with the next video",
                            body = "Videos you play show up here by day, with your place saved so you can pick up where you stopped.",
                            modifier = StatementPadding,
                        ) { PillButton("Go to Home", { chrome.nav.onTabTap(Tab.Home) }, Modifier.height(48.dp), icon = R.drawable.ic_nav_home) }
                    }
                    else -> historyDays(shown, time, heldRow.takeIf { menuOpen }, onPlay = { playVideo(it, false) }) { video, row ->
                        heldRow = row
                        openMenu(video, VideoSource.History)
                    }
                }
            }
            PageBar(title = "History", solid = true, onBack = chrome.nav::back, lifted = rememberScrolled(list)) {
                if (shown != null) {
                    PillButton("Clear history", viewModel::onClearHistory, Modifier.height(40.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_trash)
                }
            }
        }
    }
    StageSheet(
        sheet = shown?.count?.takeIf { state.confirmingClear },
        onDismiss = viewModel::onKeepHistory,
        onBack = viewModel::onKeepHistory,
        stageKey = { Unit },
    ) { count ->
        ConfirmSheetContent(
            kicker = "History",
            title = "Clear all watch history?",
            body = "This removes ${videosText(count)} and every saved position from this phone. It can’t be undone; playlists and subscriptions stay.",
            keep = "Keep history",
            confirm = "Clear history",
            onKeep = viewModel::onKeepHistory,
            onConfirm = viewModel::onConfirmClear,
        )
    }
}

private val StatementPadding = Modifier.padding(start = 24.dp, end = 24.dp, top = 70.dp)


private fun LazyListScope.historyDays(
    shown: HistoryState.Ready,
    time: ShellTime,
    heldRow: String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (video: VideoItem, row: String) -> Unit,
) {
    shown.days.forEach { day ->
        item(key = "day:${day.date}", contentType = "day") {
            val locale = LocalConfiguration.current.locales[0]
            val today = remember(shown) { time.clock.now().localDate(time.zone) }
            val (title, sub) = remember(day, today, locale) { dayHeading(day, today, locale) }
            DayHeading(title, sub, listItemMotion())
        }
        items(day.videos, key = { "row:${it.video.id.value}" }, contentType = { "row" }) { entry ->
            val row = "row:${entry.video.id.value}"
            val locale = LocalConfiguration.current.locales[0]
            val use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
            ResultRow(
                video = entry.video,
                progress = entry.mark.barFraction(),
                onClick = { onPlay(entry.video) },
                onOpenMenu = { onOpenMenu(entry.video, row) },
                held = heldRow == row,
                meta = historyMeta(entry, time.zone, locale, use24Hour),
                pictureWidth = ListPictureWidth,
                modifier = listItemMotion().padding(bottom = RowGap),
            )
        }
    }
}

/** "Veritasium", then "34:51 of 47:42 · 8:12 PM" (`.hrow`). */
private fun historyMeta(entry: HistoryVideo, zone: ZoneId, locale: Locale, use24Hour: Boolean): String {
    val left = listOfNotNull(historyMarkText(entry.mark), timeOfDayText(entry.lastWatchedAt, zone, locale, use24Hour)).joinToString(" · ")
    return listOfNotNull(entry.video.uploaderName, left).joinToString("\n")
}
