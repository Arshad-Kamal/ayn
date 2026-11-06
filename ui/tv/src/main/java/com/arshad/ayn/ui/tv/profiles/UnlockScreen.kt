package com.arshad.ayn.ui.tv.profiles

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.PIN_DIGITS
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.SEQUENCE_PRESSES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.UnlockMessage
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import kotlin.time.Duration

/** The input's events, as the gate's and Settings' ViewModels take them. */
internal class UnlockActions(
    val onPress: (Press) -> Unit,
    val onSubmitSequence: () -> Unit,
    val onDigit: (Char) -> Unit,
    val onErase: () -> Unit,
    val onBack: () -> Unit,
    val onRejectionShown: () -> Unit,
)

// `.guided.unlock` (px ÷ 2): the input column is 300 dp wide.
private val InputWidth = 300.dp

/** What a lock-entry screen says: [kicker] above the plate (what the entry is for), [line] under the
 *  name and [hint] under that; a wait's message ends with [waitTail] (where BACK goes). */
internal class EntryCopy(val kicker: EntryKicker?, val line: String, val hint: String, val waitTail: String)

internal class EntryKicker(@param:DrawableRes val icon: Int, val text: String)

/**
 * Unlocking a profile (`b3-profiles` seq, wrong, wait, pin): the profile at the left, its lock's
 * input at the right. BACK erases, then returns to the profiles; leaving a kids profile (`kp-kids`
 * leave) it is the owner's lock, and BACK returns to the kid's Home.
 */
@Composable
internal fun UnlockScreen(unlock: GateScreen.Unlock, actions: UnlockActions) {
    val kind = unlock.profile.lock ?: return
    val leaving = unlock.leaving
    val copy = when {
        leaving != null -> leaveCopy(leaving.name, unlock.profile.name, kind)
        unlock.entry.wait != null -> EntryCopy(null, "Locked for now.", "Other profiles still open while this one waits", GateWaitTail)
        kind == LockKind.Pin -> EntryCopy(
            kicker = null,
            line = "Enter your PIN.",
            hint = "BACK erases a digit · BACK with nothing entered returns to profiles · number keys work too",
            waitTail = GateWaitTail,
        )
        else -> EntryCopy(null, "Enter your D-pad sequence, then press OK.", "BACK erases a press · BACK with nothing entered returns to profiles", GateWaitTail)
    }
    LockEntryScreen(unlock.profile, kind, unlock.entry, copy, actions)
}

private const val GateWaitTail = ", or press BACK to choose another profile."

/** Leaving [kid]'s kids profile: [owner]'s lock, BACK back to the kid (KP-D9). */
internal fun leaveCopy(kid: String, owner: String, kind: LockKind): EntryCopy = EntryCopy(
    kicker = EntryKicker(R.drawable.ic_lock, "Leaving $kid’s profile"),
    line = when (kind) {
        LockKind.Sequence -> "Enter $owner’s sequence, then press OK."
        LockKind.Pin -> "Enter $owner’s PIN."
    },
    hint = "BACK returns to $kid’s Home",
    waitTail = ", or press BACK to return to $kid’s Home.",
)

/**
 * A lock's entry (`b3-profiles` seq, wrong, wait, pin; gate, setseq1/setseq, setpin1/2, mismatch,
 * current): who it is for at the left, under [copy], and the [kind] lock's input at the right. A
 * sequence lock reads the D-pad on the pad (dots, never the presses); a PIN lock has the keypad
 * (number keys work too). A wrong try or a mismatched new lock shakes the dots away; during a
 * wait the input is off and counts down. BACK goes to [actions].
 */
@Composable
internal fun LockEntryScreen(profile: Profile, kind: LockKind, entry: UnlockEntry, copy: EntryCopy, actions: UnlockActions) {
    BackHandler(onBack = actions.onBack)
    val tint = profile.tint.color
    val digits = Modifier.onPreviewKeyEvent { event ->
        val digit = event.digit()
        if (kind != LockKind.Pin || digit == null) return@onPreviewKeyEvent false
        // A held number key enters its digit once.
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) actions.onDigit(digit)
        true
    }
    Box(Modifier.fillMaxSize().roomLight({ tint }, alpha = { 0.22f }, x = { 0.3f }).then(digits)) {
        ProfileGuided(
            actionsWidth = InputWidth,
            actionsAlignment = Alignment.CenterHorizontally,
            actionsSpacing = if (kind == LockKind.Pin) 20.dp else 17.dp,
            copy = {
                copy.kicker?.let { kicker ->
                    KickerRow(kicker.text, Modifier.padding(bottom = 11.dp)) { KickerIcon(kicker.icon) }
                }
                ProfilePlate(profile.name, profile.tint, MediumPlate)
                Spacer(Modifier.height(15.dp))
                GuidedTitle(profile.name)
                GuidedBody(copy.line)
                Text(
                    text = copy.hint,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
                    color = KeColors.Ink3,
                    modifier = Modifier.padding(top = 9.dp).widthIn(max = 430.dp),
                )
            },
            actions = {
                when (kind) {
                    LockKind.Sequence -> {
                        SequencePad(entry, actions)
                        EntryDots(entry, actions.onRejectionShown)
                    }
                    LockKind.Pin -> {
                        PinSlots(entry, actions.onRejectionShown)
                        Keypad(entry, actions)
                    }
                }
                EntryMessage(entry, kind, copy.waitTail)
            },
        )
    }
}

private fun KeyEvent.digit(): Char? = when (val code = key.nativeKeyCode) {
    in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 -> '0' + (code - NativeKeyEvent.KEYCODE_0)
    in NativeKeyEvent.KEYCODE_NUMPAD_0..NativeKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - NativeKeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}

private fun KeyEvent.press(): Press? = when (key) {
    Key.DirectionUp -> Press.Up
    Key.DirectionDown -> Press.Down
    Key.DirectionLeft -> Press.Left
    Key.DirectionRight -> Press.Right
    else -> null
}

private fun KeyEvent.isOk(): Boolean = key in EnterKeys

// `.pad` (px ÷ 2): a 150 dp well; chevrons in 30 dp boxes 7 dp in; OK 55 dp across.
private val PadSize = 150.dp
private val ChevronBox = 30.dp
private val ChevronInset = 7.dp
private val OkSize = 55.dp
private val RingGap = 2.dp

// `b3-profiles` v8: the dots sit 20 dp further below the pad, the PIN slots 16 dp further above the keypad.
private val DotsClearance = 20.dp
private val SlotsClearance = 16.dp
private val RingOuter = 3.5.dp
private val WaitRingInset = 6.dp
private val WaitRingWidth = 2.5.dp
private const val WaitPadAlpha = 0.42f

/**
 * The sequence pad: while focused it takes every D-pad key (focus never leaves it); a direction's
 * first key-down is a press (a held one counts once) and pulses the ring, OK submits. It reads raw
 * key-downs, which a tv-material click (acting on key-up) can't give, so it is a plain focusable.
 * During a wait it dims inside a ring that drains with the countdown.
 */
@Composable
private fun SequencePad(entry: UnlockEntry, actions: UnlockActions) {
    val focus = rememberInitialFocus()
    var focused by remember { mutableStateOf(false) }
    val accepting = entry.wait == null && !entry.checking
    val currentAccepting by rememberUpdatedState(accepting)
    var pulses by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulses) {
        if (pulses == 0) return@LaunchedEffect
        pulse.snapTo(0f)
        pulse.animateTo(1f, tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut))
    }
    val ring = animateFloatAsState(if (focused && entry.wait == null) 1f else 0f, KeMotion.fast(), label = "padRing")
    val dim = animateFloatAsState(if (entry.wait != null) WaitPadAlpha else 1f, KeMotion.base(), label = "padDim")
    val drained = entry.wait?.let { left -> entry.waitShown?.let { shown -> fractionLeft(left, shown) } } ?: 0f
    // Each wait starts full and moves on with the countdown's one-second steps, so it drains smoothly.
    val drain = remember(entry.waitShown) { Animatable(drained) }
    LaunchedEffect(drain, drained) { drain.animateTo(drained, tween(1000, easing = LinearEasing)) }

    Box(
        modifier = Modifier
            .size(PadSize)
            .semantics { contentDescription = "D-pad sequence" }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val press = event.press()
                if (press == null && !event.isOk()) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && currentAccepting) {
                    if (press != null) {
                        actions.onPress(press)
                        pulses++
                    } else {
                        actions.onSubmitSequence()
                    }
                }
                true
            }
            .focusable()
            .drawBehind {
                val r = size.minDimension / 2
                if (ring.value > 0f) {
                    drawCircle(KeColors.Ink, r + RingOuter.toPx(), alpha = ring.value)
                    drawCircle(KeColors.Ground, r + RingGap.toPx(), alpha = ring.value)
                }
                if (entry.wait != null) {
                    val inset = WaitRingInset.toPx()
                    val stroke = WaitRingWidth.toPx()
                    val arcSize = Size(size.width + 2 * inset - stroke, size.height + 2 * inset - stroke)
                    val topLeft = Offset(-inset + stroke / 2, -inset + stroke / 2)
                    drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(KeColors.Ink, -90f, 360f * drain.value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (pulse.value < 1f) {
                    val scale = 0.94f + 0.14f * pulse.value
                    drawCircle(KeColors.Ink, (r + 1.dp.toPx()) * scale, alpha = 0.8f * (1f - pulse.value), style = Stroke(1.dp.toPx()))
                }
            },
    ) {
        PadFace(Modifier.graphicsLayer { alpha = dim.value })
        entry.wait?.let { left ->
            Text(
                text = waitText(left),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 32.sp, fontFeatureSettings = "tnum"),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** A wait's time left, rounded up to the second (it never shows 0:00 while still waiting). */
internal fun waitText(left: Duration): String = timeText((left.inWholeMilliseconds + 999) / 1000 * 1000)

private fun fractionLeft(left: Duration, shown: Duration): Float =
    if (shown <= Duration.ZERO) 0f else (left / shown).toFloat().coerceIn(0f, 1f)

/** The pad's well: four chevrons and OK. */
@Composable
private fun PadFace(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(KeColors.Well)
                drawCircle(Color.White.copy(alpha = 0.14f), size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            },
    ) {
        Chevron(0f, Modifier.align(Alignment.CenterEnd).offset(x = -ChevronInset))
        Chevron(90f, Modifier.align(Alignment.BottomCenter).offset(y = -ChevronInset))
        Chevron(180f, Modifier.align(Alignment.CenterStart).offset(x = ChevronInset))
        Chevron(-90f, Modifier.align(Alignment.TopCenter).offset(y = ChevronInset))
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(OkSize)
                .drawBehind { drawCircle(Color.White.copy(alpha = 0.1f)) },
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", style = MaterialTheme.typography.labelLarge.atWeight(700).copy(fontSize = 13.sp, letterSpacing = 0.04.em), color = KeColors.Ink2)
        }
    }
}

@Composable
private fun Chevron(degrees: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(ChevronBox), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = KeColors.Ink3,
            modifier = Modifier.size(17.dp).rotate(degrees),
        )
    }
}

// `.dots` / `.slots` (px ÷ 2): 15 dp dots, 10 dp apart (slots 13 dp apart).
private val DotSize = 15.dp
private const val RejectedAlpha = 0.55f
private val ShakeBy = 7.dp
private const val ShakeMs = 200

/**
 * The sequence's dots: one per press, none for presses not made, at most [SEQUENCE_PRESSES]' last. A wrong try leaves
 * its dots showing dimmed; they shake twice, then [onRejectionShown] clears them.
 */
@Composable
private fun EntryDots(entry: UnlockEntry, onRejectionShown: () -> Unit) {
    val rejected = entry.rejectedDots > 0
    val count = if (rejected) entry.rejectedDots else entry.entered
    Row(
        modifier = Modifier.padding(top = DotsClearance).height(DotSize).shake(entry, onRejectionShown),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) { Dot(filled = true, dimmed = rejected) }
    }
}

/** The PIN's four slots (its length is public): filled as digits go in; a wrong try shakes them. */
@Composable
private fun PinSlots(entry: UnlockEntry, onRejectionShown: () -> Unit) {
    val rejected = entry.rejectedDots > 0
    val filled = if (rejected) entry.rejectedDots else entry.entered
    Row(
        modifier = Modifier.padding(bottom = SlotsClearance).height(DotSize).shake(entry, onRejectionShown),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        repeat(PIN_DIGITS) { index -> Dot(filled = index < filled, dimmed = rejected) }
    }
}

/** Shakes (transform only) while a wrong try's dots show, then reports them shown. */
@Composable
private fun Modifier.shake(entry: UnlockEntry, onRejectionShown: () -> Unit): Modifier {
    val offset = remember { Animatable(0f) }
    val shown by rememberUpdatedState(onRejectionShown)
    val rejected = entry.rejectedDots > 0
    LaunchedEffect(entry.rejections, rejected) {
        if (!rejected) {
            // A press during the shake clears the dots; the row returns to its place.
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = ShakeMs * 2
                -1f at ShakeMs / 4
                1f at ShakeMs * 3 / 4
                -1f at ShakeMs + ShakeMs / 4
                1f at ShakeMs + ShakeMs * 3 / 4
            },
        )
        shown()
    }
    return graphicsLayer { translationX = offset.value * ShakeBy.toPx() }
}

/** A dot pops in (D8); an empty one is a ring. */
@Composable
private fun Dot(filled: Boolean, dimmed: Boolean) {
    val pop = remember(filled) { Animatable(if (filled) 0f else 1f) }
    LaunchedEffect(filled) { if (filled) pop.animateTo(1f, tween(KeMotion.FAST_MS, easing = KeMotion.EaseOut)) }
    Box(
        Modifier
            .size(DotSize)
            .graphicsLayer {
                val scale = 0.4f + 0.6f * pop.value
                scaleX = scale
                scaleY = scale
                alpha = pop.value * if (dimmed) RejectedAlpha else 1f
                // Per draw, not an offscreen layer, which would clip the glow to the dot's box.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind {
                val r = size.minDimension / 2
                if (filled) {
                    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.35f), 1f to Color.Transparent, radius = r * 2.2f), r * 2.2f)
                    drawCircle(KeColors.Ink, r)
                } else {
                    drawCircle(KeColors.Ink3, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                }
            },
    )
}

// `.keypad` (px ÷ 2): three 60 dp columns, keys 46 dp tall, 8 dp apart, in the remote's layout.
private val KeyWidth = 60.dp
private val KeyHeight = 46.dp
private val KeyGap = 8.dp
private val KeyShape = RoundedCornerShape(12.dp)
private val KeyStyle = ControlStyle(restFill = KeColors.Well, restContent = KeColors.Ink, focusedScale = 1.08f, ring = false)
private val EraseKeyStyle = KeyStyle.copy(restContent = KeColors.Ink2)
private val KeyRows = listOf("123", "456", "789")

/** The PIN keypad; focus starts on 5, the middle. Dimmed while a wait runs (the store refuses tries then). */
@Composable
private fun Keypad(entry: UnlockEntry, actions: UnlockActions) {
    val five = rememberInitialFocus()
    val dim = animateFloatAsState(if (entry.wait != null) WaitPadAlpha else 1f, KeMotion.base(), label = "keypadDim")
    Column(Modifier.graphicsLayer { alpha = dim.value }, verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        KeyRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEach { digit ->
                    DigitKey(digit, actions.onDigit, if (digit == '5') Modifier.focusRequester(five) else Modifier)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            Spacer(Modifier.width(KeyWidth))
            DigitKey('0', actions.onDigit)
            ControlSurface(onClick = actions.onErase, style = EraseKeyStyle, shape = KeyShape, modifier = Modifier.size(KeyWidth, KeyHeight)) {
                TintedIcon(
                    painter = painterResource(R.drawable.ic_bksp),
                    contentDescription = "Erase",
                    modifier = Modifier.size(18.dp).align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, onDigit: (Char) -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(onClick = { onDigit(digit) }, style = KeyStyle, shape = KeyShape, modifier = modifier.size(KeyWidth, KeyHeight)) {
        TintedText(
            text = digit.toString(),
            style = MaterialTheme.typography.titleLarge.atWeight(620).copy(fontSize = 20.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// `.msg` (px ÷ 2): a reserved two-line slot, so the input above never jumps.
private val MessageMinHeight = 35.dp

/** Under the input: a wrong try's warning, a new lock's mismatch, the too-short hint, or the wait's countdown. */
@Composable
private fun EntryMessage(entry: UnlockEntry, kind: LockKind, waitTail: String) {
    val wait = entry.wait
    val message = entry.message
    Box(Modifier.heightIn(min = MessageMinHeight).widthIn(max = 280.dp), contentAlignment = Alignment.TopCenter) {
        val text = when {
            wait != null -> "Too many tries. Try again in ${waitText(wait)}$waitTail"
            message is UnlockMessage.Wrong -> wrongTryText(message.triesBeforeWait)
            message == UnlockMessage.Mismatch -> mismatchText(kind)
            message == UnlockMessage.TooShort -> "Enter at least ${SEQUENCE_PRESSES.first} presses, then press OK."
            else -> null
        }
        if (text != null) {
            Row(verticalAlignment = Alignment.Top) {
                if (wait == null && (message is UnlockMessage.Wrong || message == UnlockMessage.Mismatch)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_alert),
                        contentDescription = null,
                        tint = KeColors.Warn,
                        modifier = Modifier.padding(top = 2.dp, end = 5.dp).size(14.dp),
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.38.em, textAlign = TextAlign.Center),
                    color = KeColors.Ink2,
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

/** A new lock's second entry didn't match its first (`b3-profiles` mismatch). */
internal fun mismatchText(kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "The two didn’t match, so nothing was saved. Enter a new sequence."
    LockKind.Pin -> "The two didn’t match, so nothing was saved. Enter a new PIN."
}
