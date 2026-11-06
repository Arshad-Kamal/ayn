package com.arshad.ayn.ui.tv.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.MORE_TIME_MINUTES
import com.arshad.ayn.feature.profiles.StoppedStep
import com.arshad.ayn.feature.profiles.StoppedAsk
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.player.BlurredPicture
import com.arshad.ayn.ui.tv.player.PlayerShade
import com.arshad.ayn.feature.kids.moreTimeLabel
import com.arshad.ayn.feature.kids.moreTimePickBody
import com.arshad.ayn.feature.kids.stoppedCopy
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout

/** Time's up's events (More time, Switch profile), as the gate's ViewModel takes them; [onClose]
 *  closes Ayn. */
internal class TimeStoppedActions(
    val onClose: () -> Unit,
    val onMoreTime: () -> Unit,
    val onSwitchProfile: () -> Unit,
    val onPick: (minutes: Int) -> Unit,
    val onBack: () -> Unit,
    val entry: UnlockActions,
)

/** The owner's unlock on Time's up (`kp-kids` moretime, switchunlock): [owner]'s lock for [ask],
 *  asked from [kid]; BACK returns to [stopped]. */
internal fun stoppedAskCopy(ask: StoppedAsk, kid: String, owner: String, kind: LockKind, stopped: KidsTime): EntryCopy {
    val back = if (stopped is KidsTime.Bedtime) "the bedtime screen" else "Time’s up"
    val (kicker, only) = when (ask) {
        StoppedAsk.MoreTime -> EntryKicker(R.drawable.ic_later, "More time for $kid") to "Only $owner adds time."
        StoppedAsk.SwitchProfile -> EntryKicker(R.drawable.ic_channel, "Switch profile") to "Only $owner switches profiles."
    }
    return EntryCopy(
        kicker = kicker,
        line = when (kind) {
            LockKind.Sequence -> "$only Enter $owner’s sequence, then press OK."
            LockKind.Pin -> "$only Enter $owner’s PIN."
        },
        hint = "BACK returns to $back",
        waitTail = ", or press BACK to return to $back.",
    )
}

/**
 * A kids profile's time stopped (`kp-kids` timesup, bedtime, moretime, moretimepick, switchunlock;
 * KP-D8, D9, D26): the guided terminal over the stopped video's [picture] (none when no video
 * played), the kid's plate heading it; OK and BACK close Ayn, More time asks for [owner]'s lock and
 * then the amount, Switch profile asks for it and then the gate shows the picker.
 */
@Composable
internal fun TimeStoppedScreen(stopped: GateScreen.TimeStopped, owner: Profile?, picture: String?, actions: TimeStoppedActions) {
    // Back from an ask, focus returns to the action that asked.
    var lastAsk by remember { mutableStateOf<StoppedAsk?>(null) }
    val ask = (stopped.step as? StoppedStep.Unlock)?.ask
    LaunchedEffect(ask) { if (ask != null) lastAsk = ask }
    when (val step = stopped.step) {
        StoppedStep.Statement -> StoppedStatement(stopped, owner, picture, focusOn = lastAsk, actions)
        is StoppedStep.Unlock -> {
            val kind = step.owner.lock ?: return // no lock: the pass comes free and the next step follows
            val copy = stoppedAskCopy(step.ask, stopped.kid.name, step.owner.name, kind, stopped.time)
            LockEntryScreen(step.owner, kind, step.entry, copy, actions.entry)
        }
        is StoppedStep.Pick -> MoreTimePick(stopped.kid, step, actions)
    }
}

// `.terminal .guided` (px ÷ 2): the copy from the safe line, a 280 dp action column.
private val TerminalActionsWidth = 280.dp
private const val KidLightAlpha = 0.2f
private const val KidLightX = 0.24f

@Composable
private fun StoppedStatement(
    stopped: GateScreen.TimeStopped,
    owner: Profile?,
    picture: String?,
    focusOn: StoppedAsk?,
    actions: TimeStoppedActions,
) {
    BackHandler(onBack = actions.onClose)
    val first = rememberInitialFocus()
    val focused = Modifier.focusRequester(first)
    val kid = stopped.kid
    val copy = stoppedCopy(kid.name, stopped.time, videoSaved = picture != null)
    Box(Modifier.fillMaxSize()) {
        BlurredPicture(picture, brightness = 0.42f)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to PlayerShade.copy(alpha = 0.82f),
                        0.6f to PlayerShade.copy(alpha = 0.5f),
                        1f to PlayerShade.copy(alpha = 0.35f),
                    ),
                )
                .roomLight({ kid.tint.color }, alpha = { KidLightAlpha }, x = { KidLightX }),
        )
        ProfileGuided(
            start = KeLayout.SafeHorizontal,
            actionsWidth = TerminalActionsWidth,
            copy = {
                ProfilePlate(kid.name, kid.tint, MediumPlate)
                Spacer(Modifier.height(15.dp))
                GuidedTitle(copy.title)
                GuidedBody(copy.body)
                owner?.let { Hint("More time and Switch profile need ${it.name}’s lock") }
            },
            actions = {
                GuidedButton(R.drawable.ic_check, "OK", ControlStyle.Primary, actions.onClose, if (focusOn == null) focused else Modifier)
                GuidedButton(
                    R.drawable.ic_later,
                    "More time",
                    ControlStyle.Secondary,
                    actions.onMoreTime,
                    if (focusOn == StoppedAsk.MoreTime) focused else Modifier,
                    trailing = R.drawable.ic_lock,
                )
                GuidedButton(
                    R.drawable.ic_channel,
                    "Switch profile",
                    ControlStyle.Secondary,
                    actions.onSwitchProfile,
                    if (focusOn == StoppedAsk.SwitchProfile) focused else Modifier,
                    trailing = R.drawable.ic_lock,
                )
            },
        )
    }
}

@Composable
private fun MoreTimePick(kid: Profile, pick: StoppedStep.Pick, actions: TimeStoppedActions) {
    BackHandler(onBack = actions.onBack)
    val first = rememberInitialFocus()
    Box(Modifier.fillMaxSize().roomLight({ kid.tint.color }, alpha = { KidLightAlpha }, x = { KidLightX })) {
        ProfileGuided(
            copy = {
                KickerRow("More time for ${kid.name}", Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_later) }
                ProfilePlate(kid.name, kid.tint, MediumPlate)
                Spacer(Modifier.height(15.dp))
                GuidedTitle("How much more time?")
                GuidedBody(moreTimePickBody(kid.name, pick.limitTomorrow, pick.failed), color = if (pick.failed) KeColors.Warn else KeColors.Ink2)
            },
            actions = {
                MORE_TIME_MINUTES.forEachIndexed { index, minutes ->
                    GuidedButton(
                        icon = R.drawable.ic_plus,
                        label = moreTimeLabel(minutes),
                        style = if (index == 0) ControlStyle.Primary else ControlStyle.Secondary,
                        onClick = { actions.onPick(minutes) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            },
        )
    }
}

/** A guided surface's small last line (`.guided .gt .hint`). */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
        color = KeColors.Ink3,
        modifier = Modifier.padding(top = 9.dp).widthIn(max = 430.dp),
    )
}
