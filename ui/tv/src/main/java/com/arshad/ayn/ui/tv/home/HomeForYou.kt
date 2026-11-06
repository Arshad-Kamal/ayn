package com.arshad.ayn.ui.tv.home

import com.arshad.ayn.feature.home.HomeRow
import com.arshad.ayn.feature.home.HomeTop
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.foryou.ForYouPick
import com.arshad.ayn.feature.home.ForYouHidden
import com.arshad.ayn.feature.home.SHOWN_AFTER_MS
import com.arshad.ayn.feature.home.ForYouHide
import com.arshad.ayn.ui.tv.common.CardRow
import com.arshad.ayn.ui.tv.common.NoticeLine
import com.arshad.ayn.ui.tv.common.UndoNotice
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.ui.tv.common.VideoCardWidth
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.focus.FocusSlot
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlinx.coroutines.delay

/**
 * The For you row (`b3-home` fy-rest, fy-row): its picks as cards; OK plays, holding OK opens the
 * pick's menu with its reason ([onOpenMenu]). A hidden card fades out while the rest slide into its
 * place, and an undone one fades back (FY-D11, D8). [onShowing] hears the picks wholly on screen.
 */
@Composable
internal fun ForYouRow(
    row: HomeRow.ForYou,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (ForYouPick) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    onShowing: (List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardRow(
        title = "For you",
        itemWidth = VideoCardWidth,
        itemSpacing = CardSpacing,
        modifier = modifier,
        onShowing = { keys -> onShowing(row.picks.filter { it.video.id.value in keys }.map { it.video }) },
    ) { rowFocused ->
        itemsIndexed(row.picks, key = { _, pick -> pick.video.id.value }) { index, pick ->
            VideoCard(
                video = pick.video,
                meta = metaLine(pick.video.uploaderName, pick.video.ageText(row.now)),
                focusKey = forYouKey(pick.video.id),
                onClick = { onPlay(pick.video) },
                modifier = Modifier.animateItem(fadeInSpec = KeMotion.move(), placementSpec = KeMotion.move(), fadeOutSpec = KeMotion.move()),
                rowFocused = rowFocused,
                onFocused = onCardFocused,
                onLongClick = { onOpenMenu(pick) },
                focusSlot = FocusSlot(row.key, index),
            )
        }
    }
}

/**
 * For you's part of Home besides its row: the Hidden notice (FY-D11) and where focus lands as it
 * goes (the card that took the hidden one's place, the hero's Play, or the undone card; failing
 * those, Home entered again as on arrival), and the shown timers of [inView] (FY-D3), started over
 * each [visit]: `null` while Home is stopped, so nothing counts as shown off screen.
 */
@Composable
internal fun BoxScope.ForYouOnHome(
    hidden: ForYouHidden?,
    heroTop: HomeTop.HeroTop?,
    inView: List<VideoItem>,
    visit: Int?,
    onUndoHide: () -> Unit,
    onHiddenNoticeClosed: () -> Unit,
    onForYouShown: (VideoItem) -> Unit,
) {
    var landing by remember { mutableStateOf<FocusLanding?>(null) }
    HiddenNotice(
        hidden = hidden,
        onUndo = {
            hidden?.let { landing = FocusLanding(undoneKey(it, heroTop)) }
            onUndoHide()
        },
        onClose = {
            landing = FocusLanding(hidden?.takesPlace?.let { landingKey(it, heroTop) } ?: PlayKey.takeIf { heroTop != null })
            onHiddenNoticeClosed()
        },
        modifier = Modifier.align(Alignment.BottomEnd),
    )
    if (visit != null) key(visit) { ShownTimers(inView, onForYouShown) }
    val memory = LocalFocusMemory.current
    LaunchedEffect(landing) {
        val to = landing ?: return@LaunchedEffect
        // Once the row has laid out without the hidden card (or with the undone one back).
        withFrameNanos { }
        if (memory?.focus(to.key) != true) memory?.enter()
        landing = null
    }
}

/** Where focus goes when a notice closes: For you's hero shown above is reached by its Play. */
private fun landingKey(id: VideoId, heroTop: HomeTop.HeroTop?): String =
    if (heroTop?.reason != null && heroTop.hero.video.id == id) PlayKey else forYouKey(id)

/** Where focus goes on Undo: back on the hidden card, which a hidden hero is again. */
private fun undoneKey(hidden: ForYouHidden, heroTop: HomeTop.HeroTop?): String =
    if (heroTop != null && hidden.before.hero.video.id == hidden.video.id) PlayKey else forYouKey(hidden.video.id)

/** One focus move after the notice: a new instance each time, so the same key lands again; a
 *  `null` [key] falls back to Home's own focus. */
private class FocusLanding(val key: String?)

/**
 * Counts each of [inView] as shown once it stays in view [SHOWN_AFTER_MS] ([onShown]); a video that
 * leaves view before then starts over. Draws nothing.
 */
@Composable
internal fun ShownTimers(inView: List<VideoItem>, onShown: (VideoItem) -> Unit) {
    val currentOnShown by rememberUpdatedState(onShown)
    inView.forEach { video ->
        key(video.id.value) {
            LaunchedEffect(Unit) {
                delay(SHOWN_AFTER_MS)
                currentOnShown(video)
            }
        }
    }
}

/**
 * The Hidden notice (`b3-home` fy-hidden, fy-hidden-channel): "Hidden" and what it hides, Undo
 * focused (the screen's return key while it shows), as [UndoNotice] keeps it: OK on Undo calls
 * [onUndo]; any other key, BACK, or its time going by calls [onClose], and focus goes to the card
 * that took the hidden one's place.
 */
@Composable
internal fun HiddenNotice(hidden: ForYouHidden?, onUndo: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    UndoNotice(
        notice = hidden,
        line = { NoticeLine("Hidden", hiddenText(it)) },
        onUndo = onUndo,
        onClose = onClose,
        modifier = modifier,
        undoModifier = Modifier.focusMemory(UndoKey),
    )
}

/** What the notice says the hide does. */
internal fun hiddenText(hidden: ForYouHidden): String = when (hidden.hide) {
    ForYouHide.Video -> "You won’t see this video in For you"
    ForYouHide.Channel -> "${hidden.video.uploaderName ?: "This channel"} won’t be recommended in For you"
}
