package com.arshad.ayn.ui.tv.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.SkipNote
import com.arshad.ayn.feature.player.SkipPrompt
import com.arshad.ayn.feature.player.followSkips
import com.arshad.ayn.feature.player.promptLeft
import com.arshad.ayn.feature.player.skippedLengthText
import com.arshad.ayn.feature.player.skippedText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.ACTION_TOAST_MS
import com.arshad.ayn.ui.tv.common.PillButtonContent
import com.arshad.ayn.ui.tv.common.PillButtonHeight
import com.arshad.ayn.ui.tv.common.RiseFrom
import com.arshad.ayn.ui.tv.common.ToastPill
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.DrawnFocusedControl
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.color
import kotlinx.coroutines.delay

/** The ViewModel's SponsorBlock skip calls (M7-D10), for the stage's position tick and keys. */
internal class SkipCalls(
    /** The position tick: where to jump, or `null` to play on. */
    val tick: (turn: Int, segments: SegmentLookup?, positionMs: Long) -> Long?,
    /** OK on the prompt: where its span ends. */
    val skip: () -> Long?,
    val dismissPrompt: () -> Unit,
    /** OK on the note: where the skipped span starts. */
    val undo: () -> Long?,
    val dismissNote: () -> Unit,
    /** The prompt's place came into view or went, at a position: its time runs only in view. */
    val promptInView: (inView: Boolean, positionMs: Long) -> Unit,
    /** The chrome's skip control (PAF-D12): where the span playback is in ends. */
    val skipFromControls: () -> Long?,
) {
    /**
     * A key while the skip slot shows the prompt or [note]: OK undoes the note or skips the prompt
     * and is spent (true); an OK that finds neither (the tick cleared it since) goes on as usual.
     * Any other key but BACK drops the note and goes on as usual; BACK is the screen's BackHandler's.
     */
    fun onKey(key: Key, note: SkipNote?, seekTo: (Long) -> Unit): Boolean {
        if (key in EnterKeys) {
            val target = if (note != null) undo() else skip()
            target?.let(seekTo)
            return target != null
        }
        if (note != null && key != Key.Back) dismissNote()
        return false
    }
}

/**
 * The skips' position tick for [turn]'s [segments] ([followSkips]), seeking [player] to each
 * answer; whether the prompt's place is in view ([inView]: nothing else holds it), so its
 * time runs only then; and the skip note's lifetime ([ACTION_TOAST_MS] from the note [noteSerial] names).
 */
@Composable
internal fun SkipEffects(player: Player, skips: SkipCalls, turn: Int, segments: SegmentLookup?, noteSerial: Int?, inView: Boolean) {
    LaunchedEffect(player, skips, turn, segments) {
        player.followSkips { positionMs -> skips.tick(turn, segments, positionMs) }
    }
    DisposableEffect(player, skips, inView) {
        skips.promptInView(inView, player.currentPosition)
        onDispose { skips.promptInView(false, player.currentPosition) }
    }
    LaunchedEffect(skips, noteSerial) {
        if (noteSerial != null) {
            delay(ACTION_TOAST_MS)
            skips.dismissNote()
        }
    }
}

// `b3-player-panels` `.sbskip` (px ÷ 2): 7 px in from the safe line, 10 px above it.
private val PromptEnd = KeLayout.SafeHorizontal + 3.5.dp
private val PromptBottom = KeLayout.SafeVertical + 5.dp

// oklch(10% 0.006 65 / .7): the chips' and the lifetime line's well over the picture.
private val SkipWell = Color(0xFF040302).copy(alpha = 0.7f)
private val LineHeight = 3.dp
private val LineHalo = 1.dp
private val SkipGrowsFromRight = ControlStyle.Secondary.copy(growFrom = TransformOrigin(1f, 0.5f))

/**
 * The bottom-right slot (`b3-player-panels` sbprompt, sbprompt2, sbskipped): the skip [note] or
 * the skip [prompt], whichever is up (the ViewModel keeps one at a time). Each rises in and fades
 * away (D8); the prompt's lifetime line runs down against [positionMs].
 */
@Composable
internal fun SkipSlot(prompt: SkipPrompt?, note: SkipNote?, positionMs: () -> Long, modifier: Modifier = Modifier) {
    val risePx = with(LocalDensity.current) { RiseFrom.roundToPx() }
    Box(modifier, contentAlignment = Alignment.BottomEnd) {
        AnimatedContent(
            targetState = note,
            transitionSpec = { riseInFadeOut(risePx) },
            contentAlignment = Alignment.BottomEnd,
            contentKey = { it?.serial },
            label = "skipNote",
        ) { shown -> shown?.let { SkipNoteToast(it) } }
        AnimatedContent(
            targetState = prompt,
            transitionSpec = { riseInFadeOut(risePx) },
            contentAlignment = Alignment.BottomEnd,
            contentKey = { it?.span },
            label = "skipPrompt",
        ) { shown -> shown?.let { SkipPromptCard(it, positionMs) } }
    }
}

/** Rises [risePx] into place as it fades in; fades out in place. No size animation (D8). */
private fun riseInFadeOut(risePx: Int): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) +
        slideInVertically(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) { risePx },
    initialContentExit = fadeOut(KeMotion.fast()),
    sizeTransform = null,
)

/** "Sponsor" (chips), "Skip to 24:40" drawn focused, and the line that runs out with the prompt. */
@Composable
private fun SkipPromptCard(prompt: SkipPrompt, positionMs: () -> Long) {
    Column(
        modifier = Modifier.padding(end = PromptEnd, bottom = PromptBottom).width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            prompt.span.categories.forEach { CategoryChip(it) }
        }
        DrawnFocusedControl(modifier = Modifier.height(PillButtonHeight), style = SkipGrowsFromRight) {
            PillButtonContent(R.drawable.ic_skip, "Skip to ${timeText(prompt.span.endMs)}")
        }
        LifetimeLine(prompt, positionMs)
    }
}

@Composable
private fun CategoryChip(category: SegmentCategory) {
    Row(
        modifier = Modifier.background(SkipWell, CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Swatch(category, 9.dp)
        Text(category.text.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp), color = KeColors.Ink)
    }
}

@Composable
private fun Swatch(category: SegmentCategory, size: Dp) {
    Box(Modifier.size(size).background(category.color, RoundedCornerShape(size / 3)))
}

/**
 * How much of the prompt's lifetime is left (`.life`, [promptLeft]): the ink shrinks toward the
 * right as playback moves on, eased between position ticks; a pause holds it.
 */
@Composable
private fun LifetimeLine(prompt: SkipPrompt, positionMs: () -> Long) {
    val shown by animateFloatAsState(promptLeft(prompt, positionMs()), tween(PlayerChromePolicy.POSITION_TICK_MS.toInt(), easing = LinearEasing), label = "lifetime")
    Box(
        Modifier
            .fillMaxWidth()
            .height(LineHeight)
            .drawBehind {
                val halo = LineHalo.toPx()
                drawRoundRect(
                    SkipWell,
                    topLeft = Offset(-halo, -halo),
                    size = Size(size.width + 2 * halo, size.height + 2 * halo),
                    cornerRadius = CornerRadius(size.height / 2 + halo),
                )
            }
            .clip(RoundedCornerShape(LineHeight / 2)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = shown
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .background(KeColors.Ink),
        )
    }
}

/** "Skipped self-promotion · 11 seconds" behind its categories' swatches, Undo drawn focused. */
@Composable
private fun SkipNoteToast(note: SkipNote) {
    ToastPill(
        text = skippedText(note.span.categories),
        muted = skippedLengthText(note.span.endMs - note.span.startMs),
        lead = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                note.span.categories.forEach { Swatch(it, 10.dp) }
            }
        },
        action = { ToastAction(R.drawable.ic_undo, "Undo") },
    )
}

/** A player toast's action, drawn focused (`.toast .btn.sm`): OK takes it while the toast shows. */
@Composable
internal fun ToastAction(@DrawableRes icon: Int, label: String) {
    DrawnFocusedControl(modifier = Modifier.height(28.dp)) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp))
        }
    }
}
