package com.arshad.ayn.ui.mobile.profiles

import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.PIN_DIGITS
import com.arshad.ayn.core.lock.RelockReason
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.SEQUENCE_PRESSES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.UnlockMessage
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlin.time.Duration
import kotlinx.coroutines.launch

/** The entry's events, as the gate's and Settings' ViewModels take them. */
internal class EntryActions(
    val onPress: (Press) -> Unit,
    val onSubmitSequence: () -> Unit,
    val onDigit: (Char) -> Unit,
    val onErase: () -> Unit,
    val onBack: () -> Unit,
    val onRejectionShown: () -> Unit,
)

/** What an entry page says: [label] beside the back arrow (what the entry is for, after
 *  [labelIcon]), [line] under the name and [hint] under that; a wait's message ends with
 *  [waitTail] (where Back goes). */
internal class EntryCopy(val label: String?, val line: String, val hint: String?, val waitTail: String, @param:DrawableRes val labelIcon: Int? = null)

/**
 * Unlocking a profile (`m6-shell-and-profiles` picker 2–4, locks 5): Back (the arrow or the
 * system's) returns to the profiles even with presses entered (they are dropped); the pads' own
 * erase key erases.
 * Leaving a kids profile it is the owner's lock, and Back returns to the kid's Home.
 */
@Composable
internal fun UnlockPage(unlock: GateScreen.Unlock, actions: EntryActions, footer: @Composable () -> Unit = {}) {
    val kind = unlock.profile.lock ?: return
    val leaving = unlock.leaving
    val erases = if (kind == LockKind.Pin) "⌫ erases a digit" else "⌫ erases a press"
    val relock = relockHint(unlock)
    val copy = when {
        leaving != null -> EntryCopy(
            label = "Leaving ${leaving.name}’s profile",
            line = if (kind == LockKind.Pin) "Enter ${unlock.profile.name}’s PIN." else "Enter ${unlock.profile.name}’s sequence, then tap OK.",
            hint = "$erases · Back returns to ${leaving.name}’s Home",
            waitTail = ", or go back to ${leaving.name}’s Home.",
        )
        kind == LockKind.Pin -> EntryCopy(null, "Enter your PIN.", relock ?: "It opens on the fourth digit · $erases · Back returns to profiles", GateWaitTail)
        else -> EntryCopy(null, "Tap your sequence, then OK.", relock ?: "$erases · Back returns to profiles", GateWaitTail)
    }
    LockEntryPage(unlock.profile, kind, unlock.entry, copy, actions, footer = footer)
}

/** Why a phone re-lock brought the profile back (state 7, Q42). */
private fun relockHint(unlock: GateScreen.Unlock): String? = when (unlock.relockReason) {
    RelockReason.ScreenOff -> "${unlock.profile.name}’s profile locked when the screen went off"
    RelockReason.Away -> "${unlock.profile.name}’s profile locked after 5 minutes away"
    null -> null
}

private const val GateWaitTail = ", or go back to choose another profile. Other profiles still open while this one waits."

/**
 * A lock's entry for [profile]: who it is for at the top, under [copy], and the [kind] lock's pad
 * below. A sequence is tapped on an on-screen D-pad (dots, never the presses), a PIN on a number
 * pad that submits on its fourth digit. A wrong try or a mismatched new lock shakes the dots
 * away; during a wait the pad dims, takes no taps and counts down. An owner gate (set 1 state 6)
 * ends on Cancel ([onCancel]); [footer] sits at the foot (an unlock's kept video).
 */
@Composable
internal fun LockEntryPage(
    profile: Profile,
    kind: LockKind,
    entry: UnlockEntry,
    copy: EntryCopy,
    actions: EntryActions,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    BackHandler(onBack = actions.onBack)
    val waiting = entry.wait != null
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(actions.onBack, label = copy.label, labelIcon = copy.labelIcon)
        Column(Modifier.fillMaxSize().risingIn()) {
            Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp)) {
                ProfileTile(profile.name, profile.tint, MediumTile)
                Text(profile.name, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 14.dp))
                Text(copy.line, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.4.em), modifier = Modifier.padding(top = 10.dp))
                if (!waiting && copy.hint != null) {
                    Text(copy.hint, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (kind) {
                    LockKind.Sequence -> {
                        SequenceRow(entry, actions)
                        Spacer(Modifier.height(26.dp))
                        SequencePad(entry, actions)
                    }
                    LockKind.Pin -> {
                        PinSlots(entry, actions.onRejectionShown)
                        Spacer(Modifier.height(26.dp))
                        NumberPad(entry, actions)
                    }
                }
                EntryMessage(entry, kind, copy.waitTail)
            }
            Spacer(Modifier.weight(1f))
            if (onCancel != null) {
                WideButton("Cancel", onCancel, kind = ButtonKind.Tonal, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp))
            }
            footer()
        }
    }
}

private val RowWidth = 300.dp
private val RowHeight = 44.dp

/** Over the D-pad: the dots and the erase key, or during a wait the countdown. */
@Composable
private fun SequenceRow(entry: UnlockEntry, actions: EntryActions) {
    Box(Modifier.width(RowWidth).height(RowHeight), contentAlignment = Alignment.Center) {
        val wait = entry.wait
        if (wait != null) {
            // The countdown takes the dots' place: a wrong try that starts the wait isn't shaken.
            LaunchedEffect(entry.rejections) { if (entry.rejectedDots > 0) actions.onRejectionShown() }
            Countdown(wait)
            return@Box
        }
        Dots(entry, actions.onRejectionShown)
        if (entry.entered > 0 && !entry.checking) {
            IconButton(onClick = actions.onErase, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 6.dp)) {
                Icon(painterResource(R.drawable.ic_bksp), contentDescription = "Erase", tint = AynColors.Ink2)
            }
        }
    }
}

@Composable
private fun Countdown(left: Duration) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(waitText(left), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
        Text("Locked for now", style = MaterialTheme.typography.bodyMedium, color = AynColors.Ink3, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** A wait's time left, rounded up to the second (it never shows 0:00 while still waiting). */
internal fun waitText(left: Duration): String {
    val seconds = (left.inWholeMilliseconds + 999) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private val PadSize = 280.dp
private val ArrowKey = 76.dp
private val ArrowInset = 8.dp
private val OkKey = 96.dp
private const val WaitAlpha = 0.3f

/**
 * The sequence pad (`.dpad`): four arrows around OK, tapped like the remote's D-pad. A tapped key
 * fills ivory; during a wait or a check the pad takes no taps, and it dims for the wait.
 */
@Composable
private fun SequencePad(entry: UnlockEntry, actions: EntryActions) {
    val accepting = entry.wait == null && !entry.checking
    val dim by animateFloatAsState(if (entry.wait != null) WaitAlpha else 1f, AynMotion.base(), label = "padDim")
    Box(
        Modifier
            .size(PadSize)
            .graphicsLayer { alpha = dim }
            .drawBehind {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(0f to AynColors.PadLight, 1f to AynColors.Surface1, center = Offset(r, r * 0.8f), radius = r * 1.1f), r)
                drawCircle(Color.White.copy(alpha = 0.1f), r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            },
    ) {
        PadArrow(Press.Up, -90f, "Up", accepting, actions, Modifier.align(Alignment.TopCenter).offset(y = ArrowInset))
        PadArrow(Press.Down, 90f, "Down", accepting, actions, Modifier.align(Alignment.BottomCenter).offset(y = -ArrowInset))
        PadArrow(Press.Left, 180f, "Left", accepting, actions, Modifier.align(Alignment.CenterStart).offset(x = ArrowInset))
        PadArrow(Press.Right, 0f, "Right", accepting, actions, Modifier.align(Alignment.CenterEnd).offset(x = -ArrowInset))
        PadKey(
            onClick = actions.onSubmitSequence,
            enabled = accepting,
            shape = CircleShape,
            rest = AynColors.Tonal.copy(alpha = 0.12f),
            label = "OK",
            modifier = Modifier.align(Alignment.Center).size(OkKey),
        ) { ink ->
            Text("OK", style = MaterialTheme.typography.labelLarge.atWeight(720).copy(fontSize = 18.sp, letterSpacing = 0.04.em), color = ink)
        }
    }
}

/** An arrow key; ic_arrow points right, turned [degrees] clockwise. */
@Composable
private fun PadArrow(press: Press, degrees: Float, label: String, enabled: Boolean, actions: EntryActions, modifier: Modifier) {
    PadKey(onClick = { actions.onPress(press) }, enabled = enabled, shape = CircleShape, rest = Color.Transparent, label = label, modifier = modifier.size(ArrowKey)) { ink ->
        Icon(painterResource(R.drawable.ic_arrow), contentDescription = null, tint = ink, modifier = Modifier.size(34.dp).rotate(degrees))
    }
}

/**
 * A pad key: it fills ivory while pressed and for [AynMotion.PRESS_MS] after a tap, so a quick
 * tap still shows (the mockups' 120 ms), its content turning dark on the fill.
 */
@Composable
private fun PadKey(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    rest: Color,
    label: String,
    modifier: Modifier = Modifier,
    restInk: Color = AynColors.Ink2,
    content: @Composable BoxScope.(ink: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val held by animateFloatAsState(if (pressed) 1f else 0f, AynMotion.press(), label = "keyHeld")
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fill = maxOf(held, flash.value)
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .drawBehind {
                drawOutline(shape.createOutline(size, layoutDirection, this), lerp(rest, AynColors.Ink, fill))
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                scope.launch {
                    flash.snapTo(1f)
                    flash.animateTo(0f, tween(AynMotion.PRESS_MS, delayMillis = AynMotion.PRESS_MS, easing = AynMotion.Ease))
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content(lerp(restInk, AynColors.OnInk, fill))
    }
}

private val DotSize = 14.dp
private val DotGap = 14.dp
private const val RejectedAlpha = 0.55f

/** One dot per press (never the presses themselves); a wrong try's dots show red, dimmed, shaking. */
@Composable
private fun Dots(entry: UnlockEntry, onRejectionShown: () -> Unit) {
    val rejected = entry.rejectedDots > 0
    val count = if (rejected) entry.rejectedDots else entry.entered
    Row(
        modifier = Modifier
            .height(DotSize)
            .semantics { contentDescription = "$count entered" }
            .shake(entry, onRejectionShown),
        horizontalArrangement = Arrangement.spacedBy(DotGap),
    ) {
        repeat(count) { Dot(filled = true, rejected = rejected) }
    }
}

/** The PIN's four slots (its length is public): filled as digits go in; a wrong try shakes them. */
@Composable
private fun PinSlots(entry: UnlockEntry, onRejectionShown: () -> Unit) {
    val rejected = entry.rejectedDots > 0
    val filled = if (rejected) entry.rejectedDots else entry.entered
    Row(
        modifier = Modifier
            .height(DotSize)
            .semantics { contentDescription = "$filled entered" }
            .shake(entry, onRejectionShown),
        horizontalArrangement = Arrangement.spacedBy(DotGap),
    ) {
        repeat(PIN_DIGITS) { index -> Dot(filled = index < filled, rejected = rejected) }
    }
}

private const val ShakeMs = 260

/** Shakes (three swings, transform only) while a wrong try's dots show, then reports them shown. */
@Composable
private fun Modifier.shake(entry: UnlockEntry, onRejectionShown: () -> Unit): Modifier {
    val offset = remember { Animatable(0f) }
    val shown by rememberUpdatedState(onRejectionShown)
    val rejected = entry.rejectedDots > 0
    LaunchedEffect(entry.rejections, rejected) {
        if (!rejected) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = ShakeMs
                -1f at ShakeMs / 6
                1f at ShakeMs / 2
                -1f at ShakeMs * 5 / 6
            },
        )
        shown()
    }
    return graphicsLayer { translationX = offset.value * 8.dp.toPx() }
}

/** A dot pops in (scale 0 → 1); an empty one is a ring. */
@Composable
private fun Dot(filled: Boolean, rejected: Boolean) {
    val pop = remember(filled) { Animatable(if (filled) 0f else 1f) }
    LaunchedEffect(filled) { if (filled) pop.animateTo(1f, AynMotion.press()) }
    val color = if (rejected) AynColors.Rejected.copy(alpha = RejectedAlpha) else AynColors.Ink
    Box(
        Modifier
            .size(DotSize)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .drawBehind {
                val r = size.minDimension / 2
                if (filled) drawCircle(color, r) else drawCircle(AynColors.Ink3, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            },
    )
}

private val KeyWidth = 96.dp
private val KeyHeight = 64.dp
private val KeyShape = RoundedCornerShape(22.dp)
private val KeyRows = listOf("123", "456", "789")

/** The number pad (`.keys`): the phone's layout, 0 under 8 and erase beside it; dims for a wait. */
@Composable
private fun NumberPad(entry: UnlockEntry, actions: EntryActions) {
    val accepting = entry.wait == null && !entry.checking
    val dim by animateFloatAsState(if (entry.wait != null) WaitAlpha else 1f, AynMotion.base(), label = "keysDim")
    Column(Modifier.graphicsLayer { alpha = dim }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KeyRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { digit -> DigitKey(digit, accepting, actions.onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.width(KeyWidth))
            DigitKey('0', accepting, actions.onDigit)
            PadKey(
                onClick = actions.onErase,
                enabled = accepting,
                shape = KeyShape,
                rest = Color.Transparent,
                label = "Erase",
                modifier = Modifier.size(KeyWidth, KeyHeight),
            ) { ink ->
                Icon(painterResource(R.drawable.ic_bksp), contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onDigit: (Char) -> Unit) {
    PadKey(
        onClick = { onDigit(digit) },
        enabled = enabled,
        shape = KeyShape,
        rest = AynColors.Surface2,
        label = digit.toString(),
        restInk = AynColors.Ink,
        modifier = Modifier.size(KeyWidth, KeyHeight),
    ) { ink ->
        Text(digit.toString(), style = MaterialTheme.typography.titleMedium.atWeight(640).copy(fontSize = 26.sp, fontFeatureSettings = "tnum"), color = ink)
    }
}

private val MessageMinHeight = 64.dp

/** Under the pad: a wrong try's warning, a new lock's mismatch, the too-short hint, or the wait's message. */
@Composable
private fun EntryMessage(entry: UnlockEntry, kind: LockKind, waitTail: String) {
    val wait = entry.wait
    val message = entry.message
    val warning = wait == null && (message is UnlockMessage.Wrong || message == UnlockMessage.Mismatch)
    val text = when {
        wait != null -> "Too many tries. Try again in ${waitText(wait)}$waitTail"
        message is UnlockMessage.Wrong -> wrongTryText(message.triesBeforeWait)
        message == UnlockMessage.Mismatch -> mismatchText(kind)
        message == UnlockMessage.TooShort -> "Tap at least ${SEQUENCE_PRESSES.first} presses, then OK."
        else -> null
    }
    Box(Modifier.fillMaxWidth().heightIn(min = MessageMinHeight).padding(start = 28.dp, end = 28.dp, top = 18.dp), contentAlignment = Alignment.TopCenter) {
        if (text != null) {
            Row(Modifier.risingIn(), verticalAlignment = Alignment.Top) {
                if (warning) {
                    Icon(
                        painterResource(R.drawable.ic_alert),
                        contentDescription = null,
                        tint = AynColors.ErrorInk,
                        modifier = Modifier.padding(top = 1.dp, end = 8.dp).size(18.dp),
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    color = if (warning) AynColors.ErrorInk else AynColors.Ink2,
                )
            }
        }
    }
}

/** A wrong try warns once the wait is two tries away (M5-D16). */
internal fun wrongTryText(triesBeforeWait: Int): String = when (triesBeforeWait) {
    2 -> "That’s not it. Two more tries, then a 30-second wait."
    1 -> "That’s not it. One more try, then a 30-second wait."
    else -> "That’s not it."
}

/** A new lock's second entry didn't match its first. */
internal fun mismatchText(kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "The two didn’t match, so nothing was saved. Tap a new sequence."
    LockKind.Pin -> "The two didn’t match, so nothing was saved. Enter a new PIN."
}
