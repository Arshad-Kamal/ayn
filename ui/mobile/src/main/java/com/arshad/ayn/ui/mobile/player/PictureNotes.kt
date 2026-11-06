package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.arshad.ayn.core.access.CaptionCue
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.player.CaptionCues
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.player.RetryLine
import com.arshad.ayn.feature.player.SkipNote
import com.arshad.ayn.feature.player.SkipPrompt
import com.arshad.ayn.feature.player.followCues
import com.arshad.ayn.feature.player.followSkips
import com.arshad.ayn.feature.player.promptLeft
import com.arshad.ayn.feature.player.skippedLengthText
import com.arshad.ayn.feature.player.skippedText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlinx.coroutines.delay

/** Where the cues rest over the picture's foot (`m6-player` `.cues`; `.cues.l` sideways, [large]). */
internal fun cuesRest(large: Boolean): Dp = if (large) 24.dp else 12.dp

/** How far the cues rise while the upright chrome shows (`.cues.up`). */
internal val OverChrome = 32.dp

/** The gap the cues keep above a note or the skip prompt. */
internal val CuesOverCorner = 6.dp

private const val MAX_CUE_LINES = 2
private val CueWell = Color(0xFF020202).copy(alpha = 0.78f)

/**
 * The chosen track's cue at [player]'s position (`m6-player` 17), centred near the picture's foot,
 * one well per line, at most two (a longer cue's later lines share the second), risen by [lift]
 * (the caller animates it: transform only); [large] on a sideways screen (`m6-player` 30). Nothing
 * shows while [cues] aren't [CaptionCues.Shown] or between cues; a cue comes and goes with no fade,
 * keeping to the speech.
 */
@Composable
internal fun BoxScope.CaptionCuesLayer(player: Player, cues: CaptionCues?, lift: () -> Dp, large: Boolean = false) {
    val shown = (cues as? CaptionCues.Shown)?.cues
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Ticks only while the app shows: a backgrounded page wakes nothing.
    val cue by produceState<CaptionCue?>(null, player, shown, lifecycle) {
        value = null
        if (shown != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { player.followCues(shown) { value = it } }
    }
    val wells = cue?.let { cueWells(it.text) } ?: return
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .graphicsLayer { translationY = -lift().toPx() }
            .padding(start = 10.dp, end = 10.dp, bottom = cuesRest(large)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        wells.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.atWeight(520).copy(fontSize = if (large) 19.sp else 13.sp, lineHeight = 1.32.em),
                color = AynColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(CueWell)
                    .then(if (large) Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 3.dp) else Modifier.padding(start = 7.dp, end = 7.dp, top = 1.dp, bottom = 2.dp)),
            )
        }
    }
}

/** A cue's wells: its lines, at most [MAX_CUE_LINES], a longer cue's later lines sharing the last. */
internal fun cueWells(text: String): List<String> {
    val lines = text.lines().filter { it.isNotBlank() }
    return if (lines.size <= MAX_CUE_LINES) lines else lines.take(MAX_CUE_LINES - 1) + lines.drop(MAX_CUE_LINES - 1).joinToString(" ")
}

/** What holds the picture's bottom corner while every sheet and the chrome are away (a screen
 *  reader's chrome aside, Q34; [cornerOf]). */
internal sealed interface Corner {
    data class Retrying(val line: RetryLine) : Corner

    data class Resumed(val atMs: Long) : Corner

    data class Skipped(val note: SkipNote) : Corner

    data class Prompt(val prompt: SkipPrompt) : Corner

    data class CaptionsFailed(val languageName: String, val failure: CaptionCues.Failed) : Corner

    /** Held in the bottom-right corner ([PictureCorner] puts the skip prompt there), else the bottom left. */
    val atEnd: Boolean
        get() = when (this) {
            is Prompt -> true
            is Retrying, is Resumed, is Skipped, is CaptionsFailed -> false
        }
}

/**
 * Who holds the corner, in this order of precedence: a retry under way, the resumed note, the skip
 * note, the skip prompt (M7-D10), then the note for [failedLanguage]'s captions that didn't load;
 * nobody while [hidden] (the chrome, a screen reader's aside (Q34), a sheet or the end card shows).
 */
internal fun cornerOf(
    hidden: Boolean,
    retry: RetryLine?,
    resumedAtMs: Long?,
    skipNote: SkipNote?,
    skipPrompt: SkipPrompt?,
    failure: CaptionCues.Failed?,
    failedLanguage: String?,
): Corner? = when {
    hidden -> null
    retry != null -> Corner.Retrying(retry)
    resumedAtMs != null -> Corner.Resumed(resumedAtMs)
    skipNote != null -> Corner.Skipped(skipNote)
    skipPrompt != null -> Corner.Prompt(skipPrompt)
    failure != null && failedLanguage != null -> Corner.CaptionsFailed(failedLanguage, failure)
    else -> null
}

/** Whether the skip slot (the prompt, or the note that takes its place) has the corner, or would
 *  were one up: the corner isn't [hidden] and nothing above the skip note holds it. */
internal fun promptInView(hidden: Boolean, corner: Corner?): Boolean =
    !hidden && (corner == null || corner is Corner.Skipped || corner is Corner.Prompt || corner is Corner.CaptionsFailed)

/**
 * The corner's time-keeping: SponsorBlock's skip tick for [turn]'s [segments] while the app shows
 * ([followSkips]), and whether the skip prompt is in view ([promptInView]; its time runs only
 * then, so not while the app is away); the skip note, and the note for captions that didn't load, each go [noteFor]
 * after they hold the corner, counted afresh each time one comes back (the chrome or a sheet took
 * it meanwhile), so neither runs out unseen.
 */
@Composable
internal fun CornerEffects(
    player: Player,
    viewModel: PlayerViewModel,
    turn: Int,
    segments: SegmentLookup?,
    corner: Corner?,
    promptInView: Boolean,
    noteFor: Long,
    onCaptionsNoteGone: (CaptionCues.Failed) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, viewModel, turn, segments, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            player.followSkips { positionMs -> viewModel.segmentTick(turn, segments, positionMs) }
        }
    }
    LifecycleStartEffect(player, viewModel, promptInView) {
        viewModel.skipPromptInView(promptInView, player.currentPosition)
        onStopOrDispose { viewModel.skipPromptInView(false, player.currentPosition) }
    }
    val skipped = (corner as? Corner.Skipped)?.note
    LaunchedEffect(viewModel, skipped) {
        if (skipped != null) {
            delay(noteFor)
            viewModel.dismissSkipNote()
        }
    }
    val failed = (corner as? Corner.CaptionsFailed)?.failure
    val currentOnGone by rememberUpdatedState(onCaptionsNoteGone)
    LaunchedEffect(failed) {
        if (failed != null) {
            delay(noteFor)
            currentOnGone(failed)
        }
    }
}

/** One holder's place in the corner: a retry stays one note as its count moves on. */
internal fun Corner.key(): Any = when (this) {
    is Corner.Retrying -> "retrying"
    is Corner.Resumed -> "resumed"
    is Corner.Skipped -> note.serial
    is Corner.Prompt -> prompt.span
    is Corner.CaptionsFailed -> failure
}

/** What the corner's actions do: Start over, Undo, Skip and Try again. */
internal class CornerCalls(
    val startOver: () -> Unit,
    val undo: () -> Unit,
    val skip: () -> Unit,
    val retryCaptions: () -> Unit,
)

/**
 * The picture's bottom corner (`m6-player` 18–20, `.pn8`, `.skipp`): [corner]'s note at the
 * bottom left, or the skip prompt at the bottom right; each rises in and fades away, raised by
 * [lift] (over a screen reader's sideways chrome, Q34; transform only). Each holder's top, from the
 * picture's foot, goes to [onHeight] under its [key] as it is laid out, for the cues (a holder
 * coming back mid-fade keeps its composition and reports nothing new).
 */
@Composable
internal fun BoxScope.PictureCorner(corner: Corner?, lift: () -> Dp, positionMs: () -> Long, calls: CornerCalls, onHeight: (key: Any, px: Int) -> Unit) {
    val risePx = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedContent(
        targetState = corner,
        modifier = Modifier.matchParentSize().graphicsLayer { translationY = -lift().toPx() },
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) +
                    slideInVertically(tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) { risePx },
                initialContentExit = fadeOut(AynMotion.base()),
                sizeTransform = null,
            )
        },
        contentKey = { it?.key() },
        label = "corner",
    ) { shown ->
        Box(Modifier.fillMaxSize()) {
            if (shown == null) return@Box
            val key = shown.key()
            // Measured with its offset from the foot: the height is where its top sits.
            val note = Modifier.align(Alignment.BottomStart).onSizeChanged { onHeight(key, it.height) }.padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
            when (shown) {
                is Corner.Prompt -> SkipPromptCard(
                    shown.prompt,
                    positionMs,
                    calls.skip,
                    Modifier.align(Alignment.BottomEnd).onSizeChanged { onHeight(key, it.height) }.padding(end = 12.dp, bottom = 14.dp),
                )
                is Corner.Retrying -> PictureNote(shown.line.text + " " + shown.line.count, note, spinner = true)
                is Corner.Resumed -> PictureNote("Resumed at ${timeText(shown.atMs)}", note, "Start over", calls.startOver)
                is Corner.Skipped -> PictureNote(
                    skippedText(shown.note.span.categories) + MetaSeparator + skippedLengthText(shown.note.span.endMs - shown.note.span.startMs),
                    note,
                    "Undo",
                    calls.undo,
                )
                is Corner.CaptionsFailed -> PictureNote(
                    "${shown.languageName} captions didn’t load${MetaSeparator}the video plays on",
                    note,
                    "Try again",
                    calls.retryCaptions,
                )
            }
        }
    }
}

/**
 * A note in the picture's corner (`.pn8`): its words, then one [action] ([spinner]: a small one
 * before the words instead, for a note that only waits).
 */
@Composable
internal fun PictureNote(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}, spinner: Boolean = false) {
    Row(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 36.dp)
            .background(AynColors.Surface2.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinner) Spinner(16.dp)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (action != null) {
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(AynColors.Tonal)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(700))
            }
        }
    }
}

/**
 * The skip prompt (`m6-player` 18, `.skipp`): "Skip to 24:40", and under it the span's categories
 * over the line that runs out with the prompt ([promptLeft], eased between position ticks; a pause
 * holds it). A tap skips; left alone it goes at its time and the video plays through.
 */
@Composable
private fun SkipPromptCard(prompt: SkipPrompt, positionMs: () -> Long, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val left by animateFloatAsState(promptLeft(prompt, positionMs()), tween(PlayerChromePolicy.POSITION_TICK_MS.toInt(), easing = LinearEasing), label = "lifetime")
    val colour = prompt.span.categories.first().color
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }.width(IntrinsicSize.Max), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton("Skip to ${timeText(prompt.span.endMs)}", onSkip, Modifier.height(36.dp), icon = R.drawable.ic_skip)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                prompt.span.categories.joinToString(MetaSeparator) { it.text.name },
                style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 11.5.sp),
                color = AynColors.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(AynColors.Ink.copy(alpha = 0.25f))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = left
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .background(colour),
                )
            }
        }
    }
}
