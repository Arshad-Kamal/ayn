package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.moreTimeLabel
import com.arshad.ayn.feature.kids.moreTimePickBody
import com.arshad.ayn.feature.kids.stoppedCopy
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.MORE_TIME_MINUTES
import com.arshad.ayn.feature.profiles.StoppedAsk
import com.arshad.ayn.feature.profiles.StoppedStep
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Kicker
import com.arshad.ayn.ui.mobile.common.KickerIcon
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.floorGlow
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.player.BlurredPicture
import com.arshad.ayn.ui.mobile.theme.AynColors

/** Time's up's events, as the gate's ViewModel takes them; [onClose] closes Ayn. */
internal class TimeStoppedActions(
    val onClose: () -> Unit,
    val onMoreTime: () -> Unit,
    val onSwitchProfile: () -> Unit,
    val onPick: (minutes: Int) -> Unit,
    val onBack: () -> Unit,
    val entry: EntryActions,
)

/** The owner's unlock on Time's up (`m6-kids` 11): [owner]'s lock for [ask], asked from [kid];
 *  Back returns to [stopped]'s screen even with presses entered. */
internal fun stoppedAskCopy(ask: StoppedAsk, kid: String, owner: String, kind: LockKind, stopped: KidsTime): EntryCopy {
    val back = if (stopped is KidsTime.Bedtime) "the bedtime screen" else "Time’s up"
    val (label, icon, only) = when (ask) {
        StoppedAsk.MoreTime -> Triple("More time for $kid", R.drawable.ic_later, "Only $owner adds time.")
        StoppedAsk.SwitchProfile -> Triple("Switch profile", R.drawable.ic_user, "Only $owner switches profiles.")
    }
    val erases = if (kind == LockKind.Pin) "⌫ erases a digit" else "⌫ erases a press"
    return EntryCopy(
        label = label,
        line = if (kind == LockKind.Pin) "$only Enter $owner’s PIN." else "$only Enter $owner’s sequence, then tap OK.",
        hint = "$erases · Back returns to $back",
        waitTail = ", or go back to $back.",
        labelIcon = icon,
    )
}

/**
 * A kids profile's time stopped (`m6-kids` 9–12; KP-D8, D26): the statement over the stopped
 * video's [picture] (blurred; none when no video played), OK and Back closing Ayn; More time asks
 * for the [owner]'s lock and then the amount; Switch profile asks for it and then the gate shows
 * the picker.
 */
@Composable
internal fun TimeStoppedPages(stopped: GateScreen.TimeStopped, owner: Profile?, picture: String?, actions: TimeStoppedActions) {
    when (val step = stopped.step) {
        StoppedStep.Statement -> StoppedStatement(stopped, owner, picture, actions)
        is StoppedStep.Unlock -> {
            val kind = step.owner.lock ?: return // no lock: the pass comes free and the next step follows
            LockEntryPage(step.owner, kind, step.entry, stoppedAskCopy(step.ask, stopped.kid.name, step.owner.name, kind, stopped.time), actions.entry)
        }
        is StoppedStep.Pick -> MoreTimePick(stopped.kid, step, picture, actions)
    }
}

// `.stopped`: the picture darkened from the foot up, the kid's colour lighting the floor.
private val StoppedShade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.7f))

@Composable
private fun StoppedGround(kid: Profile, picture: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AynColors.Ground)) {
        BlurredPicture(picture)
        Box(Modifier.matchParentSize().background(StoppedShade).floorGlow { kid.tint.color })
        content()
    }
}

@Composable
private fun StoppedStatement(stopped: GateScreen.TimeStopped, owner: Profile?, picture: String?, actions: TimeStoppedActions) {
    BackHandler(onBack = actions.onClose)
    val kid = stopped.kid
    val copy = stoppedCopy(kid.name, stopped.time, videoSaved = picture != null)
    StoppedGround(kid, picture) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 120.dp).risingIn()) {
                ProfileTile(kid.name, kid.tint, MediumTile)
                Headline(copy.title)
                Body(copy.body)
            }
            FootButtons {
                if (owner != null) {
                    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(14.dp))
                        Text("More time and Switch profile need ${owner.name}’s lock", style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3)
                    }
                }
                WideButton("OK", actions.onClose, icon = R.drawable.ic_check)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WideButton("More time", actions.onMoreTime, Modifier.weight(1f), kind = ButtonKind.Tonal, icon = R.drawable.ic_later, locked = true)
                    WideButton("Switch profile", actions.onSwitchProfile, Modifier.weight(1f), kind = ButtonKind.Tonal, icon = R.drawable.ic_user, locked = true)
                }
            }
        }
    }
}

@Composable
private fun MoreTimePick(kid: Profile, pick: StoppedStep.Pick, picture: String?, actions: TimeStoppedActions) {
    BackHandler(onBack = actions.onBack)
    StoppedGround(kid, picture) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 120.dp).risingIn()) {
                Kicker("More time for ${kid.name}", Modifier.padding(bottom = 12.dp)) { KickerIcon(R.drawable.ic_later) }
                ProfileTile(kid.name, kid.tint, MediumTile)
                Headline("How much more time?")
                Text(
                    moreTimePickBody(kid.name, pick.limitTomorrow, pick.failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (pick.failed) AynColors.Warn else AynColors.Ink2,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            FootButtons {
                MORE_TIME_MINUTES.forEachIndexed { index, minutes ->
                    WideButton(
                        moreTimeLabel(minutes),
                        { actions.onPick(minutes) },
                        kind = if (index == 0) ButtonKind.Primary else ButtonKind.Tonal,
                        enabled = !pick.adding,
                    )
                }
            }
        }
    }
}
