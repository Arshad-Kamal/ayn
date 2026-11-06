package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.feature.library.deletePlaylistText
import com.arshad.ayn.feature.library.playlistCountText
import com.arshad.ayn.feature.library.playlistMeta
import com.arshad.ayn.feature.library.savedRowLine
import com.arshad.ayn.feature.library.updatedAt
import com.arshad.ayn.feature.library.updatedText
import com.arshad.ayn.feature.format.barFraction
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.feature.library.PlaylistSheet
import com.arshad.ayn.feature.library.SavedPlaylistState
import com.arshad.ayn.feature.library.SavedPlaylistViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.RoundButton
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberCameFrom
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.playlist.ListStart
import com.arshad.ayn.ui.tv.playlist.PageTop
import com.arshad.ayn.ui.tv.playlist.PlayAllKey
import com.arshad.ayn.ui.tv.playlist.PlaylistBackdrop
import com.arshad.ayn.ui.tv.playlist.PlaylistHeader
import com.arshad.ayn.ui.tv.playlist.PlaylistRows
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus
import com.arshad.ayn.ui.tv.settings.DangerRowIcon
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.SettingEditRow
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SettingsSheet
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetTitle
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.weightSpan
import kotlin.time.Instant

// `b3-library` playlist, plempty (px ÷ 2): the header's actions 8 dp apart; an empty playlist's
// statement in the list column, 75 dp below the page top and at most 430 dp wide.
private val ActionGap = 8.dp
private val EmptyTop = PageTop + 75.dp
private val EmptyWidth = 430.dp

internal const val SHUFFLE_KEY = "shuffle"
internal const val MORE_KEY = "more"
private const val NAME_ROW_KEY = "name"
private const val DELETE_ROW_KEY = "delete"

/**
 * One of the profile's own playlists (`b3-library` playlist, plempty, plmenu, plrename, pldelete)
 * on [SavedPlaylistViewModel]: the header (Play all, Shuffle, ⋯) beside its videos in saved order,
 * each with where the profile left it. ⋯ opens the playlist's sheet (its name edited in place,
 * Delete playlist asking first on a screen covering the shell); a playlist deleted or no longer
 * there leaves the page ([onGone]). Closing the sheet returns focus to ⋯. Holding OK on a row
 * opens its menu on [menu] (its play rows play on through the playlist, as the row does).
 */
@Composable
internal fun SavedPlaylistScreen(
    viewModel: SavedPlaylistViewModel,
    menu: VideoMenuViewModel,
    time: ShellTime,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onGone: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlist = state.playlist
    val sheet = state.sheet
    val gone = playlist == SavedPlaylistState.Gone
    val currentOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(gone) { if (gone) currentOnGone() }
    val videoMenu by menu.menu.collectAsStateWithLifecycle()
    BackHandler(enabled = sheet != null, onBack = viewModel::onSheetBack)
    BackHandler(enabled = videoMenu != null, onBack = menu::onBack)
    val more = remember { FocusRequester() }
    val page = remember { FocusRequester() }
    FocusOnClose(open = sheet != null, target = more)
    FocusOnClose(open = menuHoldsFocus(menu, videoMenu), target = page)
    val from = rememberCameFrom(sheet) { it?.let(::sheetIdentity) }
    val menuFrom = rememberMenuCameFrom(videoMenu)
    val ready = playlist as? SavedPlaylistState.Ready
    val now = remember(playlist) { time.clock.now() }
    val playVideo: (VideoItem, Boolean) -> Unit = { video, startOver -> viewModel.playFrom(video, startOver)?.let(onPlay) }

    Overlays(
        sheet = layerOf(sheet?.takeIf { it != PlaylistSheet.ConfirmDelete && ready != null }, videoMenu.asSheet()),
        cover = layerOf(ready?.summary?.takeIf { sheet == PlaylistSheet.ConfirmDelete }, videoMenu.asCover()),
        onRailShade = onRailShade,
        modifier = modifier,
        sheetKey = { shown -> shown.key(::sheetIdentity) },
        coverKey = { shown -> shown.key { PlaylistSheet.ConfirmDelete } },
        sheetContent = { shown ->
            when (shown) {
                is Layer.Own -> ready?.let { PlaylistOptions(it.summary, shown.value, from, now, time, viewModel) }
                is Layer.Menu -> VideoMenuSheet(shown.menu, menuFrom, menu, playVideo, onOpenChannel)
            }
        },
        coverContent = { shown ->
            when (shown) {
                is Layer.Own -> ConfirmDelete(shown.value, onKeep = viewModel::onKeepPlaylist, onDelete = viewModel::onConfirmDelete)
                is Layer.Menu -> VideoMenuCover(shown.menu, menu)
            }
        },
    ) {
        WithMenuNotice(menu) {
            ScreenFocus(
                onNothingToFocus = onNothingToFocus,
                modifier = Modifier.focusRequester(page),
                initialFocusKey = when {
                    playlist == SavedPlaylistState.Failed -> RETRY_KEY
                    ready?.videos?.isEmpty() == true -> MORE_KEY
                    else -> PlayAllKey
                },
                // Focus waits for the first read; an emptied or filled playlist enters again.
                entryKey = when (playlist) {
                    SavedPlaylistState.Loading, SavedPlaylistState.Gone -> null
                    SavedPlaylistState.Failed -> SavedPlaylistState.Failed
                    is SavedPlaylistState.Ready -> playlist.videos.isEmpty()
                },
                focusHeldElsewhere = focusHeldElsewhere,
            ) {
                when (playlist) {
                    SavedPlaylistState.Loading, SavedPlaylistState.Gone -> Box(Modifier.fillMaxSize().restingRoomLight())
                    SavedPlaylistState.Failed -> Box(Modifier.fillMaxSize().restingRoomLight()) {
                        Statement(
                            kicker = Kicker(R.drawable.ic_alert, "Couldn’t read this Shield"),
                            title = "This playlist couldn’t load",
                            body = "Its videos are still on this Shield; Ayn couldn’t read them just now. Try again.",
                            modifier = Modifier.padding(start = KeLayout.ContentStart, top = LibraryStatementTop),
                        ) {
                            PillButton(R.drawable.ic_retry, "Try again", RETRY_KEY, viewModel::retry)
                        }
                    }
                    is SavedPlaylistState.Ready -> PlaylistPage(
                        ready = playlist,
                        now = now,
                        time = time,
                        viewModel = viewModel,
                        onPlay = onPlay,
                        onOpenMenu = { video -> menu.open(video, VideoSource.Playlist(playlist.summary.id)) },
                        moreModifier = Modifier.focusRequester(more),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistPage(
    ready: SavedPlaylistState.Ready,
    now: Instant,
    time: ShellTime,
    viewModel: SavedPlaylistViewModel,
    onPlay: (PlayerRoute) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    moreModifier: Modifier,
) {
    val summary = ready.summary
    val cover = summary.coverThumbnailUrl
    // The backdrop asks for the cover once it has landed, so the two share one download.
    var backdropUrl by remember { mutableStateOf<String?>(null) }
    val rowLight = remember { FocusedCardLight() }
    var rowsFocused by remember { mutableStateOf(false) }
    val play = { route: PlayerRoute? -> route?.let(onPlay) }

    Box(Modifier.fillMaxSize()) {
        PlaylistBackdrop(backdropUrl.takeIf { it == cover })
        Box(Modifier.fillMaxSize().restingRoomLight()) {
            CardRoomLight(rowLight, lit = rowsFocused)
        }
        PlaylistHeader(
            title = summary.name,
            coverUrl = cover,
            onCoverLoaded = { backdropUrl = cover },
            modifier = Modifier.padding(start = KeLayout.ContentStart, top = PageTop),
            meta = { place -> SavedMeta(summary, now, time, place) },
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(ActionGap)) {
                    if (ready.videos.isNotEmpty()) {
                        PillButton(R.drawable.ic_play, "Play all", PlayAllKey, { play(viewModel.playAll()) })
                        RoundButton(R.drawable.ic_shuffle, "Shuffle", SHUFFLE_KEY, { play(viewModel.shuffle()) })
                    }
                    RoundButton(R.drawable.ic_more, "More", MORE_KEY, viewModel::onOptions, moreModifier)
                }
            },
        )
        if (ready.videos.isEmpty()) {
            Statement(
                kicker = Kicker(R.drawable.ic_list, "Nothing saved here yet"),
                title = "Hold OK on any video to save it here",
                body = "Its menu has Save to playlist. Videos go in the order you save them; Play all and Shuffle show up once there’s one.",
                compact = true,
                modifier = Modifier.padding(start = ListStart, top = EmptyTop).widthIn(max = EmptyWidth),
            ) {}
        } else {
            PlaylistRows(
                videos = ready.videos,
                onPlay = { video -> play(viewModel.playFrom(video)) },
                onRowFocused = rowLight::onCardFocused,
                modifier = Modifier.onFocusChanged { rowsFocused = it.hasFocus },
                line = { video -> savedRowLine(video, now) },
                progress = { video -> ready.marks[video.id].barFraction() },
                onOpenMenu = onOpenMenu,
            )
        }
    }
}

/** "8 videos · 3 h 39 min · Updated today" (the count in bold; no running time while empty). */
@Composable
private fun SavedMeta(summary: PlaylistSummary, now: Instant, time: ShellTime, modifier: Modifier) {
    val rest = playlistMeta(summary.totalDurationSeconds, summary.updatedAt, now, time.zone)
    Text(
        text = buildAnnotatedString {
            withStyle(weightSpan(600, KeColors.Ink)) { append(playlistCountText(summary.videoCount)) }
            withStyle(SpanStyle(color = KeColors.Ink3)) { append(MetaSeparator) }
            append(rest)
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = KeColors.Ink2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The playlist's own sheet (`plmenu`, `plrename`): Name, edited in place with the keyboard up
 * (Done saves, BACK cancels), and Delete playlist. It opens on Name, or on Delete coming back from
 * the confirm ([from]); focus returns to Name when an edit ends.
 */
@Composable
private fun PlaylistOptions(
    summary: PlaylistSummary,
    sheet: PlaylistSheet,
    from: PlaylistSheet?,
    now: Instant,
    time: ShellTime,
    viewModel: SavedPlaylistViewModel,
) {
    val focus = rememberSheetFocus(if (from == PlaylistSheet.ConfirmDelete) DELETE_ROW_KEY else NAME_ROW_KEY)
    val nameRow = remember { FocusRequester() }
    val renaming = (sheet as? PlaylistSheet.Renaming)?.naming
    FocusOnClose(open = renaming != null, target = nameRow)
    SettingsSheet(
        kicker = "Playlist",
        title = { SheetTitle(summary.name) },
        sub = metaLine(playlistCountText(summary.videoCount), updatedText(summary.updatedAt, now, time.zone)),
    ) {
        if (renaming != null) {
            SettingEditRow(
                label = "Name",
                value = renaming.name,
                onValueChange = viewModel::onRenameChange,
                onDone = viewModel::onRenameDone,
                readOnly = renaming.saving,
                minHeight = SheetRowMinHeight,
                capitalization = KeyboardCapitalization.Sentences,
                leadIcon = R.drawable.ic_edit,
            )
        } else {
            SettingRow(
                label = "Name",
                value = summary.name,
                minHeight = SheetRowMinHeight,
                onClick = viewModel::onRename,
                modifier = focus.on(NAME_ROW_KEY).focusRequester(nameRow),
                lead = { RowIcon(R.drawable.ic_edit, it) },
            )
        }
        SettingRow(
            label = "Delete playlist",
            danger = true,
            minHeight = SheetRowMinHeight,
            onClick = viewModel::onDelete,
            modifier = focus.on(DELETE_ROW_KEY),
            lead = { DangerRowIcon(R.drawable.ic_trash, it) },
        )
    }
}

/** Delete “name”? (`pldelete`): what goes and what stays; Keep playlist is focused first. */
@Composable
private fun ConfirmDelete(summary: PlaylistSummary, onKeep: () -> Unit, onDelete: () -> Unit) {
    val keep = rememberInitialFocus()
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow("Playlist", Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_trash) }
                GuidedTitle("Delete “${summary.name}”?")
                GuidedBody(deletePlaylistText(summary.videoCount))
            },
            actions = {
                GuidedButton(R.drawable.ic_back, "Keep playlist", ControlStyle.Secondary, onKeep, Modifier.focusRequester(keep))
                GuidedButton(R.drawable.ic_trash, "Delete playlist", ControlStyle.Danger, onDelete)
            },
        )
    }
}

/** Renaming keeps the sheet it started in. */
private fun sheetIdentity(sheet: PlaylistSheet): Any = if (sheet is PlaylistSheet.Renaming) PlaylistSheet.Options else sheet
