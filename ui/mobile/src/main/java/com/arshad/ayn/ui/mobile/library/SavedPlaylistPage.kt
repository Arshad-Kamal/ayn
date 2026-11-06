package com.arshad.ayn.ui.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.MAX_PLAYLIST_NAME
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.library.PlaylistNaming
import com.arshad.ayn.feature.library.PlaylistSheet
import com.arshad.ayn.feature.library.SavedPlaylistState
import com.arshad.ayn.feature.library.SavedPlaylistViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.deletePlaylistText
import com.arshad.ayn.feature.library.playlistCountText
import com.arshad.ayn.feature.library.playlistMeta
import com.arshad.ayn.feature.library.savedRowLine
import com.arshad.ayn.feature.library.updatedAt
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.PageBar
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.RoundButton
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.common.listItemMotion
import com.arshad.ayn.ui.mobile.common.pageBarBottom
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.menu.ConfirmSheetContent
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.playlist.EmptyCover
import com.arshad.ayn.ui.mobile.playlist.HeadKey
import com.arshad.ayn.ui.mobile.playlist.HeadMeta
import com.arshad.ayn.ui.mobile.playlist.PlaylistHead
import com.arshad.ayn.ui.mobile.playlist.rememberHeadPassed
import com.arshad.ayn.ui.mobile.search.ListPictureWidth
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlin.time.Instant

private val RowGap = 14.dp
private val SheetInset = 16.dp

/**
 * One of the profile's own playlists (`m6-library-and-playlist` 9–14; M6-D15) on
 * [SavedPlaylistViewModel]: the cover over its colour, the count, running time and last change,
 * Play all, Shuffle and ⋯, then its videos numbered in saved order with where the profile left
 * each. Past the head the bar turns solid with the name. ⋯ opens the playlist's sheet: Name
 * (renamed in place) and Delete playlist, which asks first in the sheet (Q25); a playlist deleted
 * or gone leaves the page ([onGone]). A row plays from itself on through the playlist; a long press
 * or ⋮ opens its card menu on [menu], whose Remove takes the row out (no Undo, Q26).
 */
@Composable
internal fun SavedPlaylistPage(
    viewModel: SavedPlaylistViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    time: ShellTime = ShellTime(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlist = state.playlist
    val ready = playlist as? SavedPlaylistState.Ready
    val gone = playlist == SavedPlaylistState.Gone
    val currentOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(gone) { if (gone) currentOnGone() }
    val now = remember(playlist) { time.clock.now() }
    val list = rememberLazyListState()
    val barBottom = pageBarBottom()
    val passed by rememberHeadPassed(list, barBottom)
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<String?>(null) }
    val playFrom: (VideoItem, Boolean) -> Unit = { video, startOver -> viewModel.playFrom(video, startOver)?.let(onPlay) }
    VideoMenuHost(menu, playFrom, onOpenChannel, modifier) { openMenu ->
        Box(Modifier.fillMaxSize().background(AynColors.Ground)) {
            when (playlist) {
                SavedPlaylistState.Loading, SavedPlaylistState.Gone -> Unit
                SavedPlaylistState.Failed -> Statement(
                    icon = R.drawable.ic_alert,
                    kicker = "Couldn’t read this phone",
                    title = "This playlist couldn’t load",
                    body = "Its videos are still on this phone; Ayn couldn’t read them just now. Try again.",
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = barBottom + 70.dp).semantics { liveRegion = LiveRegionMode.Polite },
                ) { PillButton("Try again", viewModel::retry, Modifier.height(48.dp), icon = R.drawable.ic_retry) }
                is SavedPlaylistState.Ready -> LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = HeadKey, contentType = HeadKey) {
                        SavedHead(playlist, now, time, viewModel, onPlay)
                    }
                    if (playlist.videos.isEmpty()) {
                        item(key = "empty", contentType = "statement") { EmptyPlaylist() }
                    }
                    itemsIndexed(playlist.videos, key = { _, video -> "row:${video.id.value}" }, contentType = { _, _ -> "row" }) { index, video ->
                        val row = "row:${video.id.value}"
                        ResultRow(
                            video = video,
                            progress = playlist.marks[video.id].barFraction(),
                            onClick = { playFrom(video, false) },
                            onOpenMenu = {
                                heldRow = row
                                openMenu(video, VideoSource.Playlist(playlist.summary.id))
                            },
                            held = menuOpen && heldRow == row,
                            meta = savedRowLine(video, now),
                            number = index + 1,
                            pictureWidth = ListPictureWidth,
                            modifier = listItemMotion().padding(top = if (index == 0) 22.dp else 0.dp, bottom = RowGap),
                        )
                    }
                }
            }
            val title = ready?.summary?.name?.takeIf { passed }
            PageBar(title = title, solid = passed || ready == null, onBack = chrome.nav::back, lifted = passed)
        }
    }
    StageSheet(
        sheet = state.sheet?.takeIf { ready != null },
        onDismiss = viewModel::onCloseSheet,
        onBack = viewModel::onSheetBack,
        // Renaming edits the options in place, so typing doesn't cross-fade.
        stageKey = { sheet -> if (sheet is PlaylistSheet.Renaming) PlaylistSheet.Options else sheet },
    ) { sheet ->
        val summary = ready?.summary ?: return@StageSheet
        when (sheet) {
            PlaylistSheet.Options, is PlaylistSheet.Renaming -> PlaylistOptions(summary, (sheet as? PlaylistSheet.Renaming)?.naming, viewModel)
            PlaylistSheet.ConfirmDelete -> ConfirmSheetContent(
                kicker = "Playlist",
                title = "Delete “${summary.name}”?",
                body = deletePlaylistText(summary.videoCount),
                keep = "Keep playlist",
                confirm = "Delete playlist",
                onKeep = viewModel::onKeepPlaylist,
                onConfirm = viewModel::onConfirmDelete,
            )
        }
    }
}

/** The head: cover, "Playlist", the name, "8 videos · 3 h 39 min · Updated today", then Play all,
 *  Shuffle and ⋯ (only ⋯ while the playlist is empty). */
@Composable
private fun SavedHead(ready: SavedPlaylistState.Ready, now: Instant, time: ShellTime, viewModel: SavedPlaylistViewModel, onPlay: (PlayerRoute) -> Unit) {
    val summary = ready.summary
    PlaylistHead(
        coverUrl = summary.coverThumbnailUrl,
        kicker = "Playlist",
        title = summary.name,
        top = pageBarBottom() + 8.dp,
        meta = {
            HeadMeta(
                buildAnnotatedString {
                    withStyle(weightSpan(650, AynColors.Ink)) { append(playlistCountText(summary.videoCount)) }
                    withStyle(SpanStyle(color = AynColors.Ink3)) { append(MetaSeparator) }
                    append(playlistMeta(summary.totalDurationSeconds, summary.updatedAt, now, time.zone))
                },
            )
        },
        actions = {
            if (ready.videos.isNotEmpty()) {
                PillButton("Play all", { viewModel.playAll()?.let(onPlay) }, Modifier.height(52.dp), icon = R.drawable.ic_play)
                RoundButton(R.drawable.ic_shuffle, "Shuffle", { viewModel.shuffle()?.let(onPlay) })
            }
            RoundButton(R.drawable.ic_more_horiz, "Playlist options", viewModel::onOptions)
        },
    )
}

/** State 14: nothing saved yet, under a hairline. */
@Composable
private fun EmptyPlaylist() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
        HorizontalDivider(color = AynColors.Line)
        Statement(
            icon = R.drawable.ic_list,
            kicker = "Nothing saved here yet",
            title = "Long-press any video to save it here",
            body = "Its menu has Save to playlist. Videos go in the order you save them; Play all and Shuffle show up once there’s one.",
            compact = true,
            modifier = Modifier.padding(top = 34.dp),
        )
    }
}

/**
 * The playlist's ⋯ sheet (states 11–12): its head, then Name (tap to rename: the row becomes the
 * field with the keyboard up, its Done key saves) and Delete playlist (asks first; dimmed while
 * renaming). Back while renaming keeps the old name.
 */
@Composable
private fun PlaylistOptions(summary: PlaylistSummary, renaming: PlaylistNaming?, viewModel: SavedPlaylistViewModel) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 20.dp).imePadding()) {
        SheetHead(summary)
        if (renaming != null) {
            NameField(renaming, viewModel)
        } else {
            SheetRow("Name", tap(viewModel::onRename), hint = summary.name, icon = R.drawable.ic_edit)
        }
        SheetRow(
            "Delete playlist",
            if (renaming == null) tap(viewModel::onDelete) else Modifier,
            hint = "Asks first",
            icon = R.drawable.ic_trash,
            danger = true,
            dimmed = renaming != null,
        )
    }
}

/** The name being edited, the cursor at its end; Done saves (nothing while blank or saving). */
@Composable
private fun NameField(renaming: PlaylistNaming, viewModel: SavedPlaylistViewModel) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    // The field keeps its own selection and composition (the cursor starts at the end); the text
    // is the ViewModel's, which can refuse an edit (too long, a save under way).
    var value by remember { mutableStateOf(TextFieldValue(renaming.name, TextRange(renaming.name.length))) }
    val shown = if (value.text == renaming.name) value else TextFieldValue(renaming.name, TextRange(renaming.name.length))
    TextField(
        value = shown,
        onValueChange = { typed ->
            value = typed
            viewModel.onRenameChange(typed.text)
        },
        label = { Text("Name") },
        singleLine = true,
        readOnly = renaming.saving,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.onRenameDone() }),
        supportingText = { Text("Up to $MAX_PLAYLIST_NAME characters. Names can repeat. The ✓ key saves; Back keeps the old name.") },
        colors = aynFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SheetInset, vertical = 6.dp).focusRequester(field),
    )
}

/** The playlist the sheet acts on: its cover, name and "Playlist · 8 videos", over a hairline. */
@Composable
private fun SheetHead(summary: PlaylistSummary) {
    Column {
        Row(
            Modifier.padding(start = SheetInset, end = SheetInset, top = 2.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(104.dp, 58.dp).clip(RoundedCornerShape(10.dp)).background(AynColors.Surface2)) {
                if (summary.coverThumbnailUrl != null) Picture(summary.coverThumbnailUrl) else EmptyCover(iconSize = 22.dp)
            }
            Column {
                Text(
                    summary.name,
                    style = MaterialTheme.typography.bodyLarge.atWeight(640).copy(fontSize = 15.sp, lineHeight = 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Playlist$MetaSeparator${playlistCountText(summary.videoCount)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = AynColors.Ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AynColors.Line))
    }
}
