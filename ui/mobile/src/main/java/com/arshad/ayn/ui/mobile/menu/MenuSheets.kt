package com.arshad.ayn.ui.mobile.menu

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.home.ForYouHide
import com.arshad.ayn.feature.kids.approveNoteText
import com.arshad.ayn.feature.library.BlockTarget
import com.arshad.ayn.feature.library.MenuResume
import com.arshad.ayn.feature.library.MenuStage
import com.arshad.ayn.feature.library.VideoMenu
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.playlistCountText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.common.rememberFaceArrival
import com.arshad.ayn.ui.mobile.profiles.PlateTile
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

private val RowShape = RoundedCornerShape(16.dp)
private val HeadThumbShape = RoundedCornerShape(10.dp)
private val SideInset = 16.dp

/**
 * What the card menu's sheet shows for [menu]'s stage (`m6-home-and-menus` states 9–11, 13–15,
 * 17): the menu, Save to playlist, Block's which profiles, Approve for a kid. Rows that leave
 * (Play, Go to channel, Not interested) close the menu, then go through [onPlay] ([startOver]:
 * Play from the start; a Short's Play through [onPlayShort], into the Shorts player, when the page
 * has one), [onOpenChannel] (no row without it or a channel link) and [onForYouHide] (a For you
 * pick's two rows, when the page carries them out).
 */
@Composable
internal fun MenuSheetContent(
    menu: VideoMenu,
    viewModel: VideoMenuViewModel,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: ((channelUrl: String) -> Unit)?,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)?,
    onPlayShort: ((VideoItem) -> Unit)? = null,
) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 20.dp)) {
        when (val stage = menu.stage) {
            MenuStage.Options -> {
                VideoHead(menu.video)
                MenuOptions(menu, viewModel, onPlay, onOpenChannel, onForYouHide, onPlayShort)
            }
            MenuStage.SaveTo -> {
                VideoHead(menu.video)
                SaveTo(menu, viewModel)
            }
            is MenuStage.Block -> BlockSheet(menu, stage, viewModel)
            is MenuStage.Approve -> ApproveSheet(menu, stage, viewModel)
            // These cover the shell instead (MenuCover).
            is MenuStage.Naming, is MenuStage.BlockGate -> Unit
        }
    }
}

/** The menu's rows (states 9, 10): a long menu scrolls under its head. */
@Composable
private fun ColumnScope.MenuOptions(
    menu: VideoMenu,
    viewModel: VideoMenuViewModel,
    onPlay: (VideoItem, Boolean) -> Unit,
    onOpenChannel: ((String) -> Unit)?,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)?,
    onPlayShort: ((VideoItem) -> Unit)?,
) {
    val video = menu.video
    // A sheet sliding away still shows its rows: a second tap there goes nowhere.
    val leave = { go: () -> Unit ->
        if (viewModel.menu.value != null) {
            viewModel.close()
            go()
        }
    }
    val reason = (menu.source as? VideoSource.ForYou)?.reason
    // A block keeps the title the card showed; a Short's card shows YouTube's.
    val shownTitle = if (menu.short) null else rememberCardFace(video)?.title
    // Until the resume point is read the row offers Play, which resumes anyway.
    val resume = menu.resume as? MenuResume.At
    val channelUrl = video.uploaderUrl
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (reason != null) ReasonLine(reasonText(reason))
        Column(Modifier.padding(top = 6.dp)) {
            SheetRow(resume?.let { resumeText(it.positionMs) } ?: "Play", tap { leave { if (menu.short && onPlayShort != null) onPlayShort(video) else onPlay(video, false) } }, icon = R.drawable.ic_play)
            if (resume != null) SheetRow("Play from the start", tap { leave { onPlay(video, true) } }, icon = R.drawable.ic_restart)
            SheetRow("Save to playlist", tap(viewModel::onSaveTo), icon = R.drawable.ic_list, chevron = true)
            if (onOpenChannel != null && channelUrl != null) {
                SheetRow("Go to channel", tap { leave { onOpenChannel(channelUrl) } }, icon = R.drawable.ic_user, chevron = true)
            }
            // A kids profile never plays a Short (KP-D11), so a Short's menu approves nothing.
            menu.blockers?.kids?.takeIf { !menu.short }?.forEach { kid ->
                key(kid.id) {
                    SheetRow("Approve for ${kid.name}", tap { viewModel.onApprove(kid) }, hint = approveRowHint(video), icon = R.drawable.ic_shield, chevron = true)
                    SheetRow(
                        "Block for ${kid.name}",
                        tap { viewModel.onBlockForKid(kid, shownTitle) },
                        hint = "Hidden from ${kid.name}, even from a channel you approved",
                        icon = R.drawable.ic_block,
                    )
                }
            }
            if (reason != null && onForYouHide != null) {
                SheetRow("Not interested", tap { leave { onForYouHide(video, ForYouHide.Video) } }, icon = R.drawable.ic_trash)
                if (channelUrl != null) {
                    SheetRow("Don’t recommend this channel", tap { leave { onForYouHide(video, ForYouHide.Channel) } }, icon = R.drawable.ic_user)
                }
            }
            removeText(menu.source)?.let { label -> SheetRow(label, tap(viewModel::onRemove), icon = R.drawable.ic_trash) }
            if (menu.blockers != null) {
                Hairline()
                if (channelUrl != null) {
                    val channel = BlockTarget.Channel(channelUrl, video.uploaderName)
                    SheetRow(
                        blockKicker(channel),
                        tap { viewModel.onBlock(channel) },
                        hint = "Hides ${channel.name ?: "it"} everywhere, in the profiles you choose",
                        icon = R.drawable.ic_block,
                        chevron = true,
                    )
                }
                SheetRow(
                    "Block this video",
                    tap { viewModel.onBlock(BlockTarget.Video(video, shownTitle)) },
                    hint = "Hides it everywhere, in the profiles you choose",
                    icon = R.drawable.ic_block,
                    chevron = true,
                )
            }
        }
    }
}

/** Save to playlist (state 11): Watch later and each playlist as a switch that saves at once,
 *  then New playlist; the rows wait for the read. */
@Composable
private fun ColumnScope.SaveTo(menu: VideoMenu, viewModel: VideoMenuViewModel) {
    Text("Save to playlist", style = SheetTitle, modifier = Modifier.padding(start = SideInset, end = SideInset, top = 14.dp, bottom = 4.dp))
    val savedIn = menu.savedIn ?: return
    val playlists = menu.playlists ?: return
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        SheetRow(
            "Watch later",
            Modifier.toggleable(savedIn.watchLater, role = Role.Switch) { viewModel.onToggleWatchLater() },
            icon = R.drawable.ic_later,
            trail = { RowSwitch(savedIn.watchLater) },
        )
        playlists.forEach { playlist ->
            key(playlist.id) {
                val on = playlist.id in savedIn.playlistIds
                SheetRow(
                    playlist.name,
                    Modifier.toggleable(on, role = Role.Switch) { viewModel.onTogglePlaylist(playlist.id) },
                    hint = playlistCountText(playlist.videoCount),
                    icon = R.drawable.ic_list,
                    trail = { RowSwitch(on) },
                )
            }
        }
        SheetRow("New playlist", tap(viewModel::onNewPlaylist), icon = R.drawable.ic_plus, chevron = true)
    }
}

/**
 * "Block in which profiles?" (states 13–15, KP-D19): All profiles blocks at once (M6-D11; anyone
 * but the owner enters the owner's lock first), Choose profiles ticks the open profile and offers
 * Block in N profiles, which waits while none are ticked. Each profile says what the block
 * becomes there: the open profile's own filter, a restriction anywhere else.
 */
@Composable
private fun BlockSheet(menu: VideoMenu, stage: MenuStage.Block, viewModel: VideoMenuViewModel) {
    val blockers = menu.blockers ?: return
    val chosen = stage.chosen
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SheetKicker(R.drawable.ic_block, blockKicker(stage.target))
        Text("Block in which profiles?", style = SheetTitleBig, modifier = Modifier.padding(start = SideInset, end = SideInset, top = 6.dp, bottom = 8.dp))
        SheetSub(blockSub(stage.target))
        SheetRow(
            "All profiles",
            Modifier.selectable(chosen == null, role = Role.RadioButton, onClick = viewModel::onBlockAll),
            hint = allProfilesHint(blockers),
            lead = { RowRadio(chosen == null) },
        )
        SheetRow(
            "Choose profiles",
            Modifier.selectable(chosen != null, role = Role.RadioButton, onClick = viewModel::onBlockChoose),
            hint = if (chosen == null) "Only the ones you tick" else null,
            lead = { RowRadio(chosen != null) },
        )
        if (chosen != null) {
            blockers.all.forEach { profile ->
                key(profile.id) {
                    val own = profile.id == blockers.self.id
                    SheetRow(
                        profile.name,
                        Modifier.toggleable(profile.id in chosen, role = Role.Checkbox) { viewModel.onBlockToggle(profile.id) },
                        modifier = Modifier.padding(start = 12.dp),
                        hint = if (own) "Your own filter" else "A restriction",
                        lead = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RowCheck(profile.id in chosen)
                                ProfileTile(profile.name, profile.tint, PlateTile)
                            }
                        },
                        labelTrail = { if (own) RowTag("You") },
                    )
                }
            }
            WideButton(
                blockInLabel(chosen.size),
                viewModel::onBlockChosen,
                icon = R.drawable.ic_block,
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.padding(start = SideInset, end = SideInset, top = 14.dp),
            )
        }
        blockNote(blockers, choosing = chosen != null)?.let { SheetNote(it) }
    }
}

/** Approve for a kids profile (state 17, KP-D10): just this video, or its whole channel, which
 *  joins the kid's channels; a video naming no channel offers the video alone. */
@Composable
private fun ApproveSheet(menu: VideoMenu, stage: MenuStage.Approve, viewModel: VideoMenuViewModel) {
    val kid = stage.kid.name
    val channel = approvableChannel(menu.video)
    // The notice names the video as its card showed it.
    val shownTitle = rememberCardFace(menu.video)?.title
    SheetKicker(R.drawable.ic_shield, "Approve for $kid")
    Text("Just this video, or the channel?", style = SheetTitleBig, modifier = Modifier.padding(start = SideInset, end = SideInset, top = 6.dp, bottom = 8.dp))
    SheetRow(
        "Approve this video",
        tap { viewModel.onApproveVideo(shownTitle) },
        hint = "Only this one. It shows in $kid’s Home and Search.",
        icon = R.drawable.ic_play,
    )
    if (channel != null) {
        SheetRow(
            "Approve the whole channel",
            tap(viewModel::onApproveChannel),
            hint = "All of $channel’s videos, new ones too. $channel joins $kid’s channels.",
            icon = R.drawable.ic_user,
        )
    }
    SheetNote(approveNoteText(kid))
}

/** The video a sheet acts on (`.vhead`): its picture, title (its face, as the card showed it) and
 *  "channel · length", over a hairline. */
@Composable
private fun VideoHead(video: VideoItem) {
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    Column {
        Row(
            Modifier.padding(start = SideInset, end = SideInset, top = 2.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(104.dp, 58.dp).clip(HeadThumbShape).background(AynColors.Surface2)) {
                Picture(face?.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
            }
            Column {
                Text(
                    face?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.atWeight(640).copy(fontSize = 15.sp, lineHeight = 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = arrival.value },
                )
                metaLine(video.uploaderName, lengthText(video.durationSeconds)).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AynColors.Line))
    }
}

/** Why For you picked the video (`.why`): a line under the head, not an action. */
@Composable
private fun ReasonLine(text: AnnotatedString) {
    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2)
    }
}

/** A sheet row (`.mrow`): [icon] or [lead], the label (and [labelTrail]) over its [hint], then
 *  [trail] or a chevron. [action] is how it takes a tap: a button, a switch, a radio, a tick.
 *  [danger] inks the icon and label (`.mrow.warn`); [dimmed] shows it at 40 % (`.mrow.dimmed`). */
@Composable
internal fun SheetRow(
    label: String,
    action: Modifier,
    modifier: Modifier = Modifier,
    hint: String? = null,
    @DrawableRes icon: Int? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    dimmed: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    labelTrail: (@Composable () -> Unit)? = null,
    trail: (@Composable () -> Unit)? = null,
) {
    val dim by animateFloatAsState(if (dimmed) 0.4f else 1f, AynMotion.base(), label = "rowDim")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer { alpha = dim }
            .clip(RowShape)
            .then(action)
            .then(if (dimmed) Modifier.semantics { disabled() } else Modifier)
            .padding(horizontal = SideInset, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = if (danger) AynColors.DangerInk else AynColors.Ink2, modifier = Modifier.size(22.dp))
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp), color = if (danger) AynColors.DangerInk else AynColors.Ink)
                labelTrail?.invoke()
            }
            // A switch's line can say what its state does; the new line fades in.
            Crossfade(hint, animationSpec = AynMotion.base(), label = "hint") { shown ->
                if (shown != null) {
                    Text(shown, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        trail?.invoke()
        if (chevron) Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(18.dp))
    }
}

/** A row that takes a tap as a button. */
internal fun tap(onClick: () -> Unit): Modifier = Modifier.clickable(role = Role.Button, onClick = onClick)

@Composable
internal fun RowSwitch(on: Boolean) {
    Switch(
        checked = on,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = AynColors.OnInk,
            checkedTrackColor = AynColors.Ink,
            checkedBorderColor = AynColors.Ink,
            uncheckedThumbColor = AynColors.Ink3,
            uncheckedTrackColor = AynColors.Surface3,
            uncheckedBorderColor = AynColors.Ink3,
        ),
    )
}

@Composable
internal fun RowRadio(on: Boolean) {
    RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AynColors.Ink, unselectedColor = AynColors.Ink2))
}

/** A row's tick; not [enabled], it shows greyed (a tick that can't be cleared). */
@Composable
internal fun RowCheck(on: Boolean, enabled: Boolean = true) {
    Checkbox(
        checked = on,
        onCheckedChange = null,
        enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = AynColors.Ink, uncheckedColor = AynColors.Ink2, checkmarkColor = AynColors.OnInk),
    )
}

/** A small label after a row's name (`.tag`): "You". */
@Composable
private fun RowTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.atWeight(700).copy(fontSize = 11.sp),
        modifier = Modifier.background(AynColors.Indicator, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** A sheet's small line over its title (`.skick`), after its [icon] or [lead] if it has one. */
@Composable
internal fun SheetKicker(@DrawableRes icon: Int?, text: String, lead: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(start = SideInset, end = SideInset, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(18.dp))
        lead?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium.atWeight(650).copy(fontSize = 13.5.sp), color = AynColors.Ink2)
    }
}

@Composable
internal fun SheetSub(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AynColors.Ink2, modifier = Modifier.padding(start = SideInset, end = SideInset, bottom = 12.dp))
}

/** A sheet's foot (`.snote`): a hairline, then a quiet line. */
@Composable
private fun SheetNote(text: String) {
    Column(Modifier.padding(start = SideInset, end = SideInset, top = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AynColors.Line))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A hairline between a sheet's rows (`.hair`). */
@Composable
private fun Hairline() {
    Box(Modifier.padding(horizontal = SideInset, vertical = 6.dp).fillMaxWidth().height(1.dp).background(AynColors.Line))
}

internal val SheetTitle
    @Composable get() = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp, lineHeight = 24.sp)

internal val SheetTitleBig
    @Composable get() = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp)

/**
 * A confirm in the sheet (`m6-library-and-playlist` 7, 13; M6-D15): the kicker after [kickerIcon]
 * (the trash), the question, what goes and what stays, then [keep] (the safe side) and [confirm] on
 * the danger fill after [confirmIcon]. [kickerLead] (a profile's tile) takes the kicker icon's place;
 * [foot] goes under the buttons. Not [enabled] (the confirm under way), both buttons dim.
 */
@Composable
internal fun ConfirmSheetContent(
    kicker: String,
    title: String,
    body: String,
    keep: String,
    confirm: String,
    onKeep: () -> Unit,
    onConfirm: () -> Unit,
    kickerLead: (@Composable () -> Unit)? = null,
    @DrawableRes kickerIcon: Int = R.drawable.ic_trash,
    @DrawableRes confirmIcon: Int = R.drawable.ic_trash,
    enabled: Boolean = true,
    foot: @Composable () -> Unit = {},
) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 20.dp)) {
        SheetKicker(if (kickerLead == null) kickerIcon else null, kicker, kickerLead)
        Text(title, style = SheetTitleBig, modifier = Modifier.padding(start = SideInset, end = SideInset, top = 6.dp, bottom = 8.dp).semantics { heading() })
        SheetSub(body)
        Row(Modifier.fillMaxWidth().padding(horizontal = SideInset, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            PillButton(keep, onKeep, Modifier.height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_back, enabled = enabled)
            PillButton(confirm, onConfirm, Modifier.height(48.dp), kind = ButtonKind.Danger, icon = confirmIcon, enabled = enabled)
        }
        foot()
    }
}
