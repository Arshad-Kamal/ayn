package com.arshad.ayn.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.ChatPlace
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.rememberFadeTint
import com.arshad.ayn.ui.tv.settings.PlacementMini
import com.arshad.ayn.ui.tv.settings.RowTones
import com.arshad.ayn.ui.tv.settings.SheetFrame
import com.arshad.ayn.ui.tv.settings.SheetHead
import com.arshad.ayn.ui.tv.settings.SheetTitle
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight

/** What the Chat sheet asks of the player (LIVE-D21, D22, D26). */
internal class ChatSheetCalls(
    val chooseView: (live: Boolean) -> Unit,
    val choosePlacement: (ChatPlacement) -> Unit,
    /** Scroll chat; `null` while chat isn't over the picture (Beside scrolls already). */
    val scroll: (() -> Unit)?,
    val hide: () -> Unit,
)

// `.sheet.chatsheet` (px ÷ 2): 380 dp wide; `.sh3` 15 dp above, 7 below; `.vgrid` 6 dp gaps,
// reaching 9 dp past the copy each side; `.acts` 14 dp under the grid, 8 dp apart; `.vopt.on::after`
// a 13 dp dot 9 dp from the card's top and 8 from its end, a faint 3.5 dp ring inside it.
private val DotRing = Color.White.copy(alpha = 0.1f)
private val ChatSheetWidth = 380.dp
private val GridGap = 6.dp
private val GridReach = 9.dp
private const val GRID_COLUMNS = 3

private val CardShape = RoundedCornerShape(10.dp)
private val CardRest = Color.White.copy(alpha = 0.05f)
private val CardOn = Color.White.copy(alpha = 0.1f)
private val CardStyle = ControlStyle(restFill = CardRest, restContent = KeColors.Ink2, focusedScale = 1.04f, ring = false)
private val DotSize = 13.dp

/**
 * The Chat sheet (`live-tv-player` chat-sheet, chat-sheet-scroll): [kicker] (the channel and its
 * watchers), Show (Top chat / Live chat, once YouTube's answer names both), View (the six, each
 * drawn small; OK moves chat there for this stream and keeps the sheet up), then Scroll chat
 * (while chat is over the picture) and Hide chat. It opens on the view showing.
 */
@Composable
internal fun ChatSheet(kicker: String, chat: PlayerChat?, place: ChatPlace, calls: ChatSheetCalls) {
    val focus = rememberSheetFocus(place.placement.key)
    SheetFrame(width = ChatSheetWidth) {
        SheetHead(kicker = kicker, title = { SheetTitle("Chat") })
        AnimatedVisibility(chat?.views != null, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
            Column {
                SectionHeading("Show")
                ChatSwitch(live = chat?.liveView == true, onPick = calls.chooseView)
            }
        }
        SectionHeading("View")
        Column(Modifier.widen(GridReach), verticalArrangement = Arrangement.spacedBy(GridGap)) {
            ChatPlacement.entries.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    row.forEach { placement ->
                        ViewCard(
                            placement = placement,
                            on = placement == place.placement,
                            onClick = { calls.choosePlacement(placement) },
                            modifier = Modifier.weight(1f).then(focus.on(placement.key)),
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(top = 14.dp)) {
            // Held while it fades out, so a pick that takes chat off the picture doesn't drop it mid-fade.
            val scroll = remember { HeldScroll() }
            calls.scroll?.let { scroll.call = it }
            AnimatedVisibility(calls.scroll != null, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
                PillButton(
                    R.drawable.ic_list,
                    "Scroll chat",
                    focusKey = "scroll",
                    onClick = { scroll.call() },
                    style = ControlStyle.Secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            PillButton(R.drawable.ic_chat_off, "Hide chat", focusKey = "hide", onClick = calls.hide, style = ControlStyle.Secondary)
        }
    }
}

/** The last Scroll chat call shown: a cache written by [ChatSheet]'s composition, not state. */
private class HeldScroll {
    var call: () -> Unit = {}
}

/** Lays its content [by] wider on each side (`.vgrid`'s negative margin), centred where it was. */
private fun Modifier.widen(by: Dp): Modifier = layout { measurable, constraints ->
    val reach = by.roundToPx()
    val width = constraints.maxWidth + reach * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-reach, 0) }
}

/** A heading in the sheet (`.sh3`). */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.atWeight(620).copy(fontSize = 12.sp),
        color = KeColors.Ink3,
        modifier = Modifier.padding(top = 15.dp, bottom = 7.dp),
    )
}

/** One view (`.vopt`): [placement] drawn small over its name; the view showing ([on]) is lit and
 *  dotted; focus fills it with ink. */
@Composable
private fun ViewCard(placement: ChatPlacement, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "viewCard")
    val tones = remember(progress) { RowTones(progress, danger = false) }
    val lit = animateFloatAsState(if (on) 1f else 0f, KeMotion.fast(), label = "viewOn")
    ControlSurface(
        onClick = onClick,
        style = CardStyle,
        shape = CardShape,
        restContent = rememberFadeTint(KeColors.Ink2, KeColors.Ink, lit),
        modifier = modifier
            .semantics { selected = on }
            .onFocusChanged { focused = it.isFocused }
            .drawBehind { drawRoundRect(CardOn, cornerRadius = CornerRadius(10.dp.toPx()), alpha = lit.value * (1f - progress.value)) },
    ) {
        Column(Modifier.padding(start = 9.dp, end = 9.dp, top = 9.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            PlacementMini(placement, tones, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            TintedText(
                placement.label,
                style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 10.5.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 8.dp)
                .size(DotSize)
                .drawBehind {
                    drawCircle(lerp(KeColors.Ink, KeColors.Ground, progress.value), alpha = lit.value)
                    val ring = 3.5.dp.toPx()
                    drawCircle(DotRing, radius = size.minDimension / 2 - ring / 2, style = Stroke(ring), alpha = lit.value)
                },
        )
    }
}
