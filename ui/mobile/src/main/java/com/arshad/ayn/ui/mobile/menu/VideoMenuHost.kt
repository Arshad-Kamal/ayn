package com.arshad.ayn.ui.mobile.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.MAX_PLAYLIST_NAME
import com.arshad.ayn.feature.home.ForYouHide
import com.arshad.ayn.feature.library.MenuStage
import com.arshad.ayn.feature.library.PlaylistNaming
import com.arshad.ayn.feature.library.VideoMenu
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.LastShown
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.common.onUserScroll
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.common.rememberFaceArrival
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/**
 * A page with the card menu on [menu] (`m6-home-and-menus` states 9–19, M6-D11). [page] calls
 * `openMenu` on a long press or ⋮; the menu is a Material 3 bottom sheet that a swipe or a scrim
 * tap closes and system Back steps back a stage; naming a playlist and the owner's lock cover the
 * shell. The menu's notice is a snackbar at the page's foot ([NoticeSnackbar]), which its time,
 * Undo, opening another menu or the next scroll ends. A For you pick's menu offers Not interested and Don't recommend this
 * channel when the page carries them out ([onForYouHide]). A Short's Play goes to [onPlayShort] where the page plays Shorts.
 */
@Composable
internal fun VideoMenuHost(
    menu: VideoMenuViewModel,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: ((channelUrl: String) -> Unit)?,
    modifier: Modifier = Modifier,
    onForYouHide: ((VideoItem, ForYouHide) -> Unit)? = null,
    onPlayShort: ((VideoItem) -> Unit)? = null,
    page: @Composable (openMenu: (VideoItem, VideoSource) -> Unit) -> Unit,
) {
    val shown by menu.menu.collectAsStateWithLifecycle()
    val notice by menu.notice.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().onUserScroll { if (menu.notice.value != null) menu.onNoticeClosed() }) {
        page { video, source ->
            menu.onNoticeClosed()
            menu.open(video, source)
        }
        NoticeSnackbar(notice, ::menuNoticeLine, menu::onUndoNotice, menu::onNoticeClosed, Modifier.align(Alignment.BottomCenter))
    }
    StageSheet(shown?.takeIf { !it.stage.covers }, onDismiss = menu::close, onBack = menu::onBack, stageKey = { it.stage::class }) { sheet ->
        MenuSheetContent(sheet, menu, onPlay, onOpenChannel, onForYouHide, onPlayShort)
    }
    shown?.takeIf { it.stage.covers }?.let { MenuCover(it, menu) }
}

/** A Short's menu (no resume, no approving on its own), the open notice going first. */
internal fun VideoMenuViewModel.openShort(short: VideoItem) {
    onNoticeClosed()
    open(short, VideoSource.Elsewhere, short = true)
}

/** Naming a playlist and the owner's lock are pages over the shell; every other stage is a sheet. */
private val MenuStage.covers: Boolean get() = when (this) {
    is MenuStage.Naming, is MenuStage.BlockGate -> true
    MenuStage.Options, MenuStage.SaveTo, is MenuStage.Block, is MenuStage.Approve -> false
}

/**
 * A Material 3 bottom sheet while [sheet] is set (the card menu, a playlist's ⋯, a confirm);
 * its stages ([stageKey]) cross-fade inside it. Set to `null` by its owner (a row that leaves,
 * Back from the first stage, a cover taking over), it slides away before leaving; a swipe or scrim
 * tap slides it away, then calls [onDismiss]; system Back calls [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Any> StageSheet(sheet: T?, onDismiss: () -> Unit, onBack: () -> Unit, stageKey: (T) -> Any, content: @Composable (T) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val last = remember { LastShown<T>() }
    if (sheet != null) last.value = sheet
    val open = sheet != null
    LaunchedEffect(open) {
        // Closed by the menu: slide away. Reopened mid-slide: come back (the sheet's own show ran at its entry).
        if (!open && state.isVisible) state.hide()
        if (open && state.isVisible && state.targetValue == SheetValue.Hidden) state.show()
    }
    val shown = sheet ?: last.value?.takeIf { state.isVisible } ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AynColors.Surface1,
        contentColor = AynColors.Ink,
        scrimColor = AynColors.Scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(width = 32.dp, height = 4.dp).background(AynColors.Ink3, RoundedCornerShape(2.dp))) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler(enabled = open, onBack = onBack)
        AnimatedContent(
            targetState = shown,
            contentKey = stageKey,
            transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) },
            label = "sheet stage",
        ) { stage -> content(stage) }
    }
}

/** The menu's pages over the shell: a new playlist named for its video, the owner's lock. */
@Composable
private fun MenuCover(menu: VideoMenu, viewModel: VideoMenuViewModel) {
    FullCover {
        when (val stage = menu.stage) {
            is MenuStage.Naming -> NamePlaylistPage(
                naming = stage.naming,
                confirm = "Create and save",
                onNameChange = viewModel::onNamingChange,
                onDone = viewModel::onNamingDone,
                onCancel = viewModel::onBack,
                goesInFirst = menu.video,
            )
            is MenuStage.BlockGate -> BlockGatePage(menu, stage, viewModel)
            MenuStage.Options, MenuStage.SaveTo, is MenuStage.Block, is MenuStage.Approve -> Unit
        }
    }
}

/** A page over the whole shell, the navigation bar included (its own window); Back is the content's. */
@Composable
internal fun FullCover(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(AynColors.Ground)) { content() }
    }
}

/**
 * Naming a new playlist (`m6-home-and-menus` state 19, from Save to playlist with [goesInFirst];
 * the Library's New playlist without one): the field opens with the keyboard up; its Done key or
 * [confirm] calls [onDone] (nothing while the name is blank or a save is under way). Cancel or
 * Back calls [onCancel].
 */
@Composable
internal fun NamePlaylistPage(
    naming: PlaylistNaming,
    confirm: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    goesInFirst: VideoItem? = null,
) {
    BackHandler(onBack = onCancel)
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        BackBar(onCancel, label = "New playlist", labelIcon = R.drawable.ic_plus)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)
                .risingIn(),
        ) {
            Text("Name the playlist", style = MaterialTheme.typography.headlineLarge)
            TextField(
                value = naming.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                colors = aynFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp).focusRequester(field),
            )
            goesInFirst?.let { GoesInFirst(it) }
            Text(
                "Up to $MAX_PLAYLIST_NAME characters. Names can repeat.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = AynColors.Ink3,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", onCancel, kind = ButtonKind.Tonal)
                PillButton(confirm, onDone, icon = R.drawable.ic_check)
            }
        }
    }
}

/** "<title> goes in first." beside the video's picture (`.vline`), both its face. */
@Composable
private fun GoesInFirst(video: VideoItem) {
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(8.dp)).background(AynColors.Surface2)) {
            Picture(face?.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
        }
        Text(
            face?.let { "${it.title} goes in first." }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = AynColors.Ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = arrival.value },
        )
    }
}

/** The owner's lock before a block reaches other profiles (state 15, set 1 state 6's pad with the
 *  block as its label): Cancel or Back returns to the Block sheet as chosen. */
@Composable
private fun BlockGatePage(menu: VideoMenu, gate: MenuStage.BlockGate, viewModel: VideoMenuViewModel) {
    val owner = menu.blockers?.owner ?: return
    val actions = remember(viewModel) {
        EntryActions(
            onPress = viewModel::onGatePress,
            onSubmitSequence = viewModel::onGateSubmitSequence,
            onDigit = viewModel::onGateDigit,
            onErase = viewModel::onGateErase,
            onBack = viewModel::onGateLeave,
            onRejectionShown = viewModel::onGateRejectionShown,
        )
    }
    val copy = EntryCopy(label = blockKicker(gate.target), line = gateLine(owner.name, gate.lockKind), hint = null, waitTail = ", or tap Cancel.")
    LockEntryPage(owner, gate.lockKind, gate.entry, copy, actions, onCancel = viewModel::onGateLeave)
}
