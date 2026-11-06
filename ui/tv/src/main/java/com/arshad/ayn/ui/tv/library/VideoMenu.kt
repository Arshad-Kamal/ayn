package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.feature.library.playlistCountText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.core.store.SavedIn
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.home.ForYouHide
import com.arshad.ayn.feature.library.BlockTarget
import com.arshad.ayn.feature.library.MenuResume
import com.arshad.ayn.feature.library.MenuStage
import com.arshad.ayn.feature.library.VideoMenu
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.UndoNotice
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberCameFrom
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.focus.FocusHeld
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.LocalOverlayHoldsFocus
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.RowInset
import com.arshad.ayn.ui.tv.settings.RowSwitch
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SheetFrame
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetRows
import com.arshad.ayn.ui.tv.settings.SheetRowsTop
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// `b3-library` menu, addto (px ÷ 2): the video heads the sheet, a 96 × 54 dp picture 11 dp from its
// title and channel line, a hairline 14 dp under it; the menu's rows start 13 dp below that, the save
// sheet's title 17 dp below (22 sp), its rows 17 dp under the title.
private val HeadThumbWidth = 96.dp
private val HeadThumbShape = RoundedCornerShape(5.dp)
private val HeadGap = 11.dp
private val HeadBottom = 14.dp
private val HeadLine = 0.5.dp
private val OptionsTop = 13.dp
private val SaveTitleTop = 17.dp
private val SaveTitleSize = 22.sp

// `b3-home` fy-menu (px ÷ 2): the reason sits 11 dp under the head, its rows 9 dp under it.
private val ReasonTop = 11.dp
private val ReasonRowsTop = 9.dp
private val ReasonIcon = 14.dp

private const val PLAY_ROW_KEY = "play"
private const val SAVE_ROW_KEY = "save"
private const val LATER_ROW_KEY = "later"
private const val NEW_ROW_KEY = "new"
private const val BLOCK_CHANNEL_KEY = "block-channel"
private const val BLOCK_VIDEO_KEY = "block-video"

// `kp-filters` cardmenu (`.sheet .opts hr`, px ÷ 2): a hairline 6 dp from the rows around it.
private val RowsHairlineGap = 3.dp

/** A screen's overlay: one of its own ([Own]) or the card menu's ([Menu]). */
internal sealed interface Layer<out T : Any> {
    data class Own<out T : Any>(val value: T) : Layer<T>

    data class Menu(val menu: VideoMenu) : Layer<Nothing>

    /** What tells this overlay apart for `Overlays`: [own]'s key for the screen's, the stage for the menu's. */
    fun key(own: (T) -> Any): Any = when (this) {
        is Own -> own(value)
        is Menu -> menuIdentity(menu)
    }
}

/** The screen's own overlay if it shows one, else the card menu's. */
internal fun <T : Any> layerOf(own: T?, menu: VideoMenu?): Layer<T>? = own?.let { Layer.Own(it) } ?: menu?.let { Layer.Menu(it) }

/** The card menu's sheet (the menu, Save to playlist, Block) while it shows one. */
internal fun VideoMenu?.asSheet(): VideoMenu? = this?.takeIf { it.stage !is MenuStage.Naming && it.stage !is MenuStage.BlockGate }

/** The card menu's covering screen (naming a playlist for its video, the owner's lock for a
 *  block) while it shows one. */
internal fun VideoMenu?.asCover(): VideoMenu? = this?.takeIf { it.stage is MenuStage.Naming || it.stage is MenuStage.BlockGate }

/** Whether the card menu holds focus: a sheet, a cover, or its notice's Undo. Once nothing
 *  does, focus returns to the page. */
@Composable
internal fun menuHoldsFocus(menu: VideoMenuViewModel, shown: VideoMenu?): Boolean {
    val notice by menu.notice.collectAsStateWithLifecycle()
    return shown != null || notice?.done == true
}

/** [content] with the card menu's notice at its bottom end (`kp-filters` blocked, `kp-kids`
 *  approved): Undo takes focus as it shows. */
@Composable
internal fun WithMenuNotice(menu: VideoMenuViewModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val notice by menu.notice.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        content()
        UndoNotice(
            notice = notice,
            line = ::menuNoticeLine,
            onUndo = menu::onUndoNotice,
            onClose = menu::onNoticeClosed,
            modifier = Modifier.align(Alignment.BottomEnd),
            focusOnShow = true,
        )
    }
}

/** One menu stage apart from another: a read landing, a switch flipping or a name typed keeps it. */
internal fun menuIdentity(menu: VideoMenu): Any = menu.stage::class

/** The menu as it was before its stage last changed, for [VideoMenuSheet]'s `from`. */
@Composable
internal fun rememberMenuCameFrom(menu: VideoMenu?): VideoMenu? = rememberCameFrom(menu) { it?.let(::menuIdentity) }

/**
 * A page outside Library whose only overlay is the card menu on [menu] (its sheets, and naming a
 * playlist or the owner's lock for a block covering the shell; the rail dims or hides,
 * [onRailShade]), with its blocked notice at the bottom end. [page] applies its
 * `pageModifier` to its `ScreenFocus` and calls `onOpenMenu` when OK is held on a video
 * ([VideoSource.Elsewhere]: no remove row). BACK steps the menu back; once it and the notice
 * close, focus returns to the page, which puts it on what held it, or on the card now in its
 * place when a block took that one out ([LocalOverlayHoldsFocus]). A screen listing Shorts opens
 * a Short's menu itself (`menu.open(…, short = true)`); its Play leaves through [onPlayShort]. A
 * screen with BACK handling of its own registers it before this, so the menu's wins while it shows, or
 * (handlers inside [page]) gates it off while the menu shows. A For you pick's menu
 * ([VideoSource.ForYou]) offers Not interested and Don't recommend this channel when the host
 * carries them out ([onForYouHide]).
 */
@Composable
internal fun VideoMenuHost(
    menu: VideoMenuViewModel,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: ((channelUrl: String) -> Unit)?,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    onPlayShort: ((VideoItem) -> Unit)? = null,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)? = null,
    page: @Composable (pageModifier: Modifier, onOpenMenu: (VideoItem) -> Unit) -> Unit,
) {
    val videoMenu by menu.menu.collectAsStateWithLifecycle()
    val menuFrom = rememberMenuCameFrom(videoMenu)
    BackHandler(enabled = videoMenu != null, onBack = menu::onBack)
    val pageFocus = remember { FocusRequester() }
    val pageHeld = remember { FocusHeld() }
    val holdsFocus = menuHoldsFocus(menu, videoMenu)
    // A page already holding focus keeps it: requested again, it would enter on its first item.
    FocusOnClose(open = holdsFocus) { if (!pageHeld.now) pageFocus.requestFocus() }
    Overlays(
        sheet = videoMenu.asSheet(),
        cover = videoMenu.asCover(),
        onRailShade = onRailShade,
        modifier = modifier,
        sheetKey = ::menuIdentity,
        coverKey = ::menuIdentity,
        sheetContent = { shown -> VideoMenuSheet(shown, menuFrom, menu, onPlay, onOpenChannel, onPlayShort, onForYouHide) },
        coverContent = { shown -> VideoMenuCover(shown, menu) },
    ) {
        WithMenuNotice(menu) {
            CompositionLocalProvider(LocalOverlayHoldsFocus provides holdsFocus) {
                page(Modifier.focusRequester(pageFocus).onFocusChanged { pageHeld.now = it.hasFocus }) { video ->
                    menu.open(video, VideoSource.Elsewhere)
                }
            }
        }
    }
}

/**
 * The card menu opened by holding OK on a video (`b3-library` menu, menulater, menupl) and its
 * Save to playlist sheet (addto) on [viewModel], headed by the video. The play rows and Go to
 * channel close the menu and leave through the screen's [onPlay] ([startOver]: Play from the
 * start) and [onOpenChannel] (a Short's Play through [onPlayShort]); a video with no channel link
 * has no channel row, nor has a host without [onOpenChannel] (a channel's own page). Each sheet
 * opens on its first row, or on the row
 * that leads back to where the person came from ([from]). A For you pick's menu opens with why it
 * was picked (FY-D8) and ends with [onForYouHide]'s two rows. In the owner's profile each kids
 * profile gets Approve for (its sheet: [ApproveSheet]) and Block for (KP-D10). Outside a kids
 * profile every menu then offers Block (KP-D19), whose sheet asks in which profiles ([BlockSheet]).
 */
@Composable
internal fun VideoMenuSheet(
    menu: VideoMenu,
    from: VideoMenu?,
    viewModel: VideoMenuViewModel,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: ((channelUrl: String) -> Unit)?,
    onPlayShort: ((VideoItem) -> Unit)? = null,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)? = null,
) {
    when (val stage = menu.stage) {
        is MenuStage.Block -> BlockSheet(menu, stage, from, viewModel)
        is MenuStage.Approve -> ApproveSheet(menu, stage, viewModel)
        MenuStage.Options -> SheetFrame {
            VideoHead(menu.video, menu.short)
            MenuOptions(menu, from, viewModel, onPlay, onOpenChannel, onPlayShort, onForYouHide)
        }
        MenuStage.SaveTo -> SheetFrame {
            VideoHead(menu.video, menu.short)
            SaveTo(menu, from, viewModel)
        }
        // These cover the shell instead (VideoMenuCover).
        is MenuStage.Naming, is MenuStage.BlockGate -> Unit
    }
}

/** The card menu's covering screens: a new playlist named for its video (`b3-library`
 *  newplsave: Create and save puts it in first), and the owner's lock before a block. */
@Composable
internal fun VideoMenuCover(menu: VideoMenu, viewModel: VideoMenuViewModel) {
    when (val stage = menu.stage) {
        is MenuStage.Naming -> NamePlaylistScreen(
            naming = stage.naming,
            confirm = "Create and save",
            onNameChange = viewModel::onNamingChange,
            onDone = viewModel::onNamingDone,
            onCancel = viewModel::onBack,
            goesInFirst = menu.video,
        )
        is MenuStage.BlockGate -> BlockGateCover(menu, stage, viewModel)
        MenuStage.Options, MenuStage.SaveTo, is MenuStage.Block, is MenuStage.Approve -> Unit
    }
}

@Composable
private fun ColumnScope.MenuOptions(
    menu: VideoMenu,
    from: VideoMenu?,
    viewModel: VideoMenuViewModel,
    onPlay: (VideoItem, Boolean) -> Unit,
    onOpenChannel: ((String) -> Unit)?,
    onPlayShort: ((VideoItem) -> Unit)?,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)?,
) {
    val focus = rememberSheetFocus(
        when (val came = from?.stage) {
            MenuStage.SaveTo -> SAVE_ROW_KEY
            is MenuStage.Block -> if (came.target is BlockTarget.Channel) BLOCK_CHANNEL_KEY else BLOCK_VIDEO_KEY
            is MenuStage.Approve -> approveRowKey(came.kid.id)
            else -> PLAY_ROW_KEY
        },
    )
    val reason = (menu.source as? VideoSource.ForYou)?.reason
    reason?.let { ReasonLine(it) }
    val video = menu.video
    // A block keeps the title the card showed; a Short's card shows YouTube's.
    val shownTitle = if (menu.short) null else rememberCardFace(video)?.title
    val leave = { go: () -> Unit ->
        viewModel.close()
        go()
    }
    // Until the resume point is read the row offers Play, which resumes anyway.
    val resume = menu.resume as? MenuResume.At
    // A long menu (a For you pick's, with Block) scrolls under the head; focus brings its row into view.
    SheetRows(
        top = if (reason != null) ReasonRowsTop else OptionsTop,
        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
    ) {
        SettingRow(
            label = resume?.let { resumeText(it.positionMs) } ?: "Play",
            onClick = { leave { if (menu.short && onPlayShort != null) onPlayShort(video) else onPlay(video, false) } },
            minHeight = SheetRowMinHeight,
            modifier = focus.on(PLAY_ROW_KEY),
            lead = { RowIcon(R.drawable.ic_play, it) },
        )
        if (resume != null) {
            SettingRow(
                label = "Play from the start",
                onClick = { leave { onPlay(video, true) } },
                minHeight = SheetRowMinHeight,
                lead = { RowIcon(R.drawable.ic_restart, it) },
            )
        }
        SettingRow(
            label = "Save to playlist",
            onClick = viewModel::onSaveTo,
            chevron = true,
            minHeight = SheetRowMinHeight,
            modifier = focus.on(SAVE_ROW_KEY),
            lead = { RowIcon(R.drawable.ic_list, it) },
        )
        val channelUrl = video.uploaderUrl
        if (onOpenChannel != null && channelUrl != null) {
            SettingRow(
                label = "Go to channel",
                onClick = { leave { onOpenChannel(channelUrl) } },
                chevron = true,
                minHeight = SheetRowMinHeight,
                lead = { RowIcon(R.drawable.ic_channel, it) },
            )
        }
        // A kids profile never plays a Short (KP-D11), so a Short's menu approves nothing.
        menu.blockers?.kids?.takeIf { !menu.short }?.forEach { kid ->
            key(kid.id) {
                SettingRow(
                    label = "Approve for ${kid.name}",
                    onClick = { viewModel.onApprove(kid) },
                    hint = approveRowHint(video),
                    chevron = true,
                    minHeight = SheetRowMinHeight,
                    modifier = focus.on(approveRowKey(kid.id)),
                    lead = { RowIcon(R.drawable.ic_shield, it) },
                )
                SettingRow(
                    label = "Block for ${kid.name}",
                    onClick = { viewModel.onBlockForKid(kid, shownTitle) },
                    hint = "Hidden from ${kid.name}, even from a channel you approved",
                    minHeight = SheetRowMinHeight,
                    lead = { RowIcon(R.drawable.ic_block, it) },
                )
            }
        }
        if (reason != null && onForYouHide != null) {
            SettingRow(
                label = "Not interested",
                onClick = { leave { onForYouHide(video, ForYouHide.Video) } },
                minHeight = SheetRowMinHeight,
                lead = { RowIcon(R.drawable.ic_trash, it) },
            )
            if (channelUrl != null) {
                SettingRow(
                    label = "Don’t recommend this channel",
                    onClick = { leave { onForYouHide(video, ForYouHide.Channel) } },
                    minHeight = SheetRowMinHeight,
                    lead = { RowIcon(R.drawable.ic_channel, it) },
                )
            }
        }
        removeText(menu.source)?.let { label ->
            SettingRow(
                label = label,
                onClick = viewModel::onRemove,
                minHeight = SheetRowMinHeight,
                lead = { RowIcon(R.drawable.ic_trash, it) },
            )
        }
        if (menu.blockers != null) {
            RowsHairline()
            if (channelUrl != null) {
                val channel = BlockTarget.Channel(channelUrl, video.uploaderName)
                SettingRow(
                    label = blockKicker(channel),
                    onClick = { viewModel.onBlock(channel) },
                    hint = "Hides ${channel.name ?: "it"} everywhere, in the profiles you choose",
                    chevron = true,
                    minHeight = SheetRowMinHeight,
                    modifier = focus.on(BLOCK_CHANNEL_KEY),
                    lead = { RowIcon(R.drawable.ic_block, it) },
                )
            }
            SettingRow(
                label = "Block this video",
                onClick = { viewModel.onBlock(BlockTarget.Video(video, shownTitle)) },
                hint = "Hides it everywhere, in the profiles you choose",
                chevron = true,
                minHeight = SheetRowMinHeight,
                modifier = focus.on(BLOCK_VIDEO_KEY),
                lead = { RowIcon(R.drawable.ic_block, it) },
            )
        }
    }
}

/** A hairline between a sheet's rows (`.sheet .opts hr`), as wide as their text. */
@Composable
private fun RowsHairline() {
    Box(
        Modifier
            .padding(horizontal = RowInset, vertical = RowsHairlineGap)
            .fillMaxWidth()
            .height(HeadLine)
            .background(KeColors.Line),
    )
}

/** Save to playlist: Watch later and each playlist as a switch, then New playlist; rows wait for the read. */
@Composable
private fun ColumnScope.SaveTo(menu: VideoMenu, from: VideoMenu?, viewModel: VideoMenuViewModel) {
    Text(
        text = "Save to playlist",
        style = MaterialTheme.typography.headlineSmall.copy(fontSize = SaveTitleSize),
        modifier = Modifier.padding(top = SaveTitleTop),
    )
    val savedIn = menu.savedIn
    val playlists = menu.playlists
    if (savedIn != null && playlists != null) {
        SaveToRows(savedIn, playlists, from, viewModel, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun SaveToRows(
    savedIn: SavedIn,
    playlists: List<PlaylistSummary>,
    from: VideoMenu?,
    viewModel: VideoMenuViewModel,
    modifier: Modifier,
) {
    val focus = rememberSheetFocus(if (from?.stage is MenuStage.Naming) NEW_ROW_KEY else LATER_ROW_KEY)
    // A long list scrolls under the head; focus brings its row into view.
    SheetRows(top = SheetRowsTop, modifier = modifier.verticalScroll(rememberScrollState())) {
        SettingRow(
            label = "Watch later",
            onClick = viewModel::onToggleWatchLater,
            minHeight = SheetRowMinHeight,
            modifier = focus.on(LATER_ROW_KEY),
            lead = { RowIcon(R.drawable.ic_later, it) },
            control = { RowSwitch(savedIn.watchLater, it) },
        )
        playlists.forEach { playlist ->
            key(playlist.id) {
                SettingRow(
                    label = playlist.name,
                    onClick = { viewModel.onTogglePlaylist(playlist.id) },
                    hint = playlistCountText(playlist.videoCount),
                    minHeight = SheetRowMinHeight,
                    lead = { RowIcon(R.drawable.ic_list, it) },
                    control = { RowSwitch(playlist.id in savedIn.playlistIds, it) },
                )
            }
        }
        SettingRow(
            label = "New playlist",
            onClick = viewModel::onNewPlaylist,
            chevron = true,
            minHeight = SheetRowMinHeight,
            modifier = focus.on(NEW_ROW_KEY),
            lead = { RowIcon(R.drawable.ic_plus, it) },
        )
    }
}

/** Why For you picked the video (`b3-home` fy-menu `.why`): a line under the head, not an action. */
@Composable
private fun ReasonLine(reason: ForYouReason) {
    Row(modifier = Modifier.padding(top = ReasonTop), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(ReasonIcon))
        Text(text = reasonText(reason), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp), color = KeColors.Ink2)
    }
}

/** FY-D8's wording by the seed's kind, its subject in the ink weight. */
internal fun reasonText(reason: ForYouReason): AnnotatedString = buildAnnotatedString {
    append(
        when (reason.kind) {
            SeedKind.Watched -> "Because you watched "
            SeedKind.Saved -> "Because you saved "
            SeedKind.Followed -> "Because you follow "
        },
    )
    withStyle(weightSpan(600, KeColors.Ink)) { append(reason.subject) }
}

private fun approveRowKey(kidId: Long): String = "approve-$kidId"

/** What Approve for offers: the video, or all of its channel when it names one. */
internal fun approveRowHint(video: VideoItem): String =
    video.uploaderName?.takeIf { video.uploaderUrl != null }?.let { "This video, or all of $it" } ?: "This video"

/** The video a sheet acts on (`.vhead`): its picture, title (its face, as the card showed it; a
 *  [short]'s is YouTube's) and "channel · length", over a hairline. */
@Composable
internal fun VideoHead(video: VideoItem, short: Boolean, modifier: Modifier = Modifier) {
    val face = if (short) CardFace.original(video) else rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val line = HeadLine.toPx()
                drawRect(KeColors.Line, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .padding(bottom = HeadBottom),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HeadGap),
    ) {
        Box(Modifier.size(HeadThumbWidth, HeadThumbWidth / ThumbAspect).clip(HeadThumbShape).background(KeColors.Surface2)) {
            Picture(face?.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
        }
        Column {
            Text(
                text = face?.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall.atWeight(640),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = arrival.value },
            )
            metaLine(video.uploaderName, lengthText(video.durationSeconds)).takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = KeColors.Ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
