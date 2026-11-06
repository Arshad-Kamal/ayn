package com.arshad.ayn.ui.tv.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.feature.profiles.RestoreStep
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.settings.ChooseLockSheet
import com.arshad.ayn.ui.tv.settings.DeviceLine
import com.arshad.ayn.ui.tv.settings.FootButton
import com.arshad.ayn.ui.tv.settings.NO_OWNER_BODY
import com.arshad.ayn.ui.tv.settings.NO_OWNER_TITLE
import com.arshad.ayn.ui.tv.settings.ProfileTags
import com.arshad.ayn.ui.tv.settings.ResultsTable
import com.arshad.ayn.ui.tv.settings.RowCheck
import com.arshad.ayn.ui.tv.settings.RowPlate
import com.arshad.ayn.ui.tv.settings.SetupPage
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SkipsList
import com.arshad.ayn.feature.settings.backupCounts
import com.arshad.ayn.ui.tv.settings.firstPickDeviceLine
import com.arshad.ayn.ui.tv.settings.firstPickLede
import com.arshad.ayn.ui.tv.settings.firstRestoreText
import com.arshad.ayn.feature.settings.firstRestoredLede
import com.arshad.ayn.ui.tv.settings.monthDay
import com.arshad.ayn.ui.tv.settings.newLockCopy
import com.arshad.ayn.feature.settings.restoreButton
import com.arshad.ayn.ui.tv.settings.restoredDeviceLine
import com.arshad.ayn.feature.settings.restoredRows
import com.arshad.ayn.feature.settings.restoredTitle
import com.arshad.ayn.feature.settings.shownSkips
import com.arshad.ayn.ui.tv.settings.widenStart

/** First launch's restore events, as the gate's ViewModel takes them. */
internal class RestoreActions(
    val onRestore: () -> Unit,
    val onChooseProfiles: () -> Unit,
    val onToggle: (Int) -> Unit,
    val onChooseFile: () -> Unit,
    val onDone: () -> Unit,
    val onLockMyProfile: () -> Unit,
    val onLockKind: (LockKind) -> Unit,
    val onBack: () -> Unit,
    val entry: UnlockActions,
)

/**
 * First launch's restore (`kp-backup` firstrestore, firstpick, firstdone; `b3-profiles` refused,
 * failed), for a file picked in the system file picker. While the file is read only its name
 * shows; while a restore runs its screen stays up (its buttons do nothing then). An owner-less
 * backup can't start the Shield (Q18). The results' Lock my profile sets the owner's lock there
 * ([OwnerLockSteps]) before the owner opens. [profiles] are the Shield's, for the results' looks.
 */
@Composable
internal fun RestoreScreen(restore: GateScreen.Restore, profiles: List<Profile>, actions: RestoreActions) {
    BackHandler(onBack = actions.onBack)
    val fileName = restore.fileName ?: "The chosen file"
    when (val step = restore.step) {
        is RestoreStep.Choose -> ChooseProfiles(fileName, step, actions)
        is RestoreStep.Done -> {
            val owner = profiles.firstOrNull { it.id == step.result.ownerId }
            val first = rememberInitialFocus()
            OwnerLockSteps(step.lock, owner, first, actions.onLockKind, actions.entry) {
                FirstRestored(fileName, step, profiles, first, actions)
            }
        }
        else -> RestoreGuided(fileName, step, actions)
    }
}

/** The guided restore steps: reading, refused, no owner, confirm, failed. */
@Composable
private fun RestoreGuided(fileName: String, step: RestoreStep, actions: RestoreActions) {
    val first = remember { FocusRequester() }
    // Each set of actions starts on its first; a confirm keeps its focus while it restores.
    val focusKey: Any? = when (step) {
        RestoreStep.Reading -> null
        is RestoreStep.Confirm -> RestoreStep.Confirm::class
        else -> step
    }
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            withFrameNanos { }
            first.requestFocus()
        }
    }
    val problem = step is RestoreStep.Refused || step == RestoreStep.Failed || step == RestoreStep.NoOwner
    // While the file is read nothing acts, but focus waits here so nothing else takes the keys.
    val hold = if (step == RestoreStep.Reading) Modifier.focusRequester(first).focusable() else Modifier
    Box(Modifier.fillMaxSize().controlRoomLight().then(hold)) {
        ProfileGuided(
            copy = {
                KickerRow(fileName, Modifier.padding(bottom = 11.dp)) { KickerIcon(if (problem) R.drawable.ic_alert else R.drawable.ic_download) }
                when (step) {
                    is RestoreStep.Refused -> {
                        val (title, line) = refusalCopy(step.reason)
                        GuidedTitle(title)
                        GuidedBody(line)
                    }
                    RestoreStep.NoOwner -> {
                        GuidedTitle(NO_OWNER_TITLE)
                        GuidedBody(NO_OWNER_BODY)
                    }
                    RestoreStep.Failed -> {
                        GuidedTitle("The restore didn’t finish")
                        GuidedBody("Nothing on this Shield changed. Try again, or choose another file.")
                    }
                    is RestoreStep.Confirm -> {
                        GuidedTitle("Restore this backup?")
                        GuidedBody(firstRestoreText(step.profiles, step.deviceSettings))
                    }
                    else -> Unit
                }
            },
            actions = {
                val firstButton = Modifier.focusRequester(first)
                when (step) {
                    is RestoreStep.Refused, RestoreStep.NoOwner -> {
                        GuidedButton(R.drawable.ic_download, "Choose another file", ControlStyle.Primary, actions.onChooseFile, firstButton)
                        GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, actions.onBack)
                    }
                    RestoreStep.Failed -> {
                        GuidedButton(R.drawable.ic_retry, "Try again", ControlStyle.Primary, actions.onRestore, firstButton)
                        GuidedButton(R.drawable.ic_download, "Choose another file", ControlStyle.Secondary, actions.onChooseFile)
                    }
                    is RestoreStep.Confirm -> {
                        val idle = !step.restoring
                        GuidedButton(R.drawable.ic_check, "Restore everything", ControlStyle.Primary, actions.onRestore, firstButton)
                        // One profile leaves nothing to choose: the owner always comes back.
                        if (step.profiles.size > 1) {
                            GuidedButton(R.drawable.ic_list, "Choose profiles", ControlStyle.Secondary, actions.onChooseProfiles)
                        }
                        GuidedButton(R.drawable.ic_download, "Choose another file", ControlStyle.Secondary, { if (idle) actions.onChooseFile() })
                    }
                    else -> Unit
                }
            },
        )
    }
}

/** Choose profiles (`firstpick`): a checkbox per profile (OK ticks), the owner always restored. */
@Composable
private fun ChooseProfiles(fileName: String, step: RestoreStep.Choose, actions: RestoreActions) {
    val first = rememberInitialFocus()
    val firstChoosable = step.profiles.indexOfFirst { !it.owner }
    val ownerName = step.profiles.firstOrNull { it.owner }?.name.orEmpty()
    val chosen = step.chosen.size
    SetupPage(
        icon = R.drawable.ic_download,
        kicker = fileName,
        title = "Restore which profiles?",
        lede = firstPickLede(ownerName),
        foot = {
            FootButton(R.drawable.ic_download, restoreButton(chosen), actions.onRestore, style = ControlStyle.Primary)
            FootButton(R.drawable.ic_back, "Back", actions.onBack)
        },
    ) {
        Column(Modifier.padding(top = 17.dp).width(650.dp).widenStart(15.dp)) {
            step.profiles.forEachIndexed { index, profile ->
                SettingRow(
                    label = profile.name,
                    hint = backupCounts(profile),
                    value = if (profile.owner) "Always restored" else null,
                    chevron = false,
                    focusable = !profile.owner,
                    onClick = { actions.onToggle(index) },
                    modifier = if (index == firstChoosable) Modifier.focusRequester(first) else Modifier,
                    lead = { tones ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowCheck(index in step.chosen, tones)
                            ProfilePlate(profile.name, profile.tint, RowPlate, Modifier.padding(start = 11.dp))
                        }
                    },
                    labelTrail = { tones -> ProfileTags(profile.owner, profile.kids, tones) },
                )
            }
        }
        if (step.deviceSettings) {
            val leftOut = step.profiles.filterIndexed { index, _ -> index !in step.chosen }.map { it.name }
            DeviceLine(firstPickDeviceLine(leftOut))
        }
    }
}

/** The results (`firstdone`): every profile Added, unlocked; Lock my profile first, or Done. */
@Composable
private fun FirstRestored(fileName: String, done: RestoreStep.Done, profiles: List<Profile>, first: FocusRequester, actions: RestoreActions) {
    val result = done.result
    val kids = result.profiles.filter { restored -> profiles.any { it.id == restored.profileId && it.isKids } }.map { it.name }
    SetupPage(
        icon = if (result.partial) R.drawable.ic_alert else R.drawable.ic_download,
        kicker = fileName,
        title = restoredTitle(result),
        lede = firstRestoredLede(result, monthDay(done.exportedAtMillis), kids),
        foot = {
            FootButton(R.drawable.ic_lock, "Lock my profile", actions.onLockMyProfile, Modifier.focusRequester(first), ControlStyle.Primary)
            FootButton(R.drawable.ic_check, "Done", actions.onDone)
        },
    ) {
        SkipsList(shownSkips(result, profiles))
        ResultsTable(restoredRows(result, profiles, firstLaunch = true))
        DeviceLine(restoredDeviceLine(result, firstLaunch = true))
    }
}

/**
 * The owner's lock asked for from the gate (KP-D14): over [page], Choose a lock slides in at
 * [step] ChooseKind; at NewLock the [owner]'s new lock is entered twice. When the sheet closes
 * focus returns to [pageFocus].
 */
@Composable
internal fun OwnerLockSteps(
    step: OwnerLockStep?,
    owner: Profile?,
    pageFocus: FocusRequester,
    onKind: (LockKind) -> Unit,
    entry: UnlockActions,
    page: @Composable () -> Unit,
) {
    if (step is OwnerLockStep.NewLock) {
        owner?.let { LockEntryScreen(it, step.kind, step.entry, newLockCopy(step.kind, step.entry), entry) }
        return
    }
    val choosing = step == OwnerLockStep.ChooseKind
    FocusOnClose(open = choosing, target = pageFocus)
    Overlays<OwnerLockStep, Unit>(
        sheet = step.takeIf { choosing },
        cover = null,
        onRailShade = {},
        sheetContent = { ChooseLockSheet(owner?.name.orEmpty(), LockKind.Sequence, LockKind.Sequence, onKind) },
        coverContent = {},
        page = page,
    )
}

/**
 * A kids profile chosen while the owner has no lock (`kp-kids` nolock, Q19): why, then Lock my
 * profile (Choose a lock, the new lock) before [needed]'s kid opens; BACK steps back to the picker.
 */
@Composable
internal fun OwnerLockNeededScreen(needed: GateScreen.OwnerLockNeeded, owner: Profile?, actions: OwnerLockActions) {
    val kid = needed.kid.name
    OwnerLockFirstScreen(needed.step, owner, "$kid’s kids profile", "Lock your profile, then $kid’s profile opens.", actions)
}

/**
 * Lock your profile first (`kp-kids` nolock, KP-D14): why a kids profile needs the [owner]'s lock,
 * [kicker] naming what waits for it and [then] ending the body; Lock my profile goes on to
 * Choose a lock and the new lock ([step]). BACK is the caller's step back.
 */
@Composable
internal fun OwnerLockFirstScreen(step: OwnerLockStep, owner: Profile?, kicker: String, then: String, actions: OwnerLockActions) {
    BackHandler(onBack = actions.onBack)
    val first = rememberInitialFocus()
    OwnerLockSteps(step, owner, first, actions.onKind, actions.entry) {
        Box(Modifier.fillMaxSize().controlRoomLight()) {
            ProfileGuided(
                copy = {
                    KickerRow(kicker, Modifier.padding(bottom = 11.dp)) { KickerIcon(R.drawable.ic_lock) }
                    GuidedTitle("Lock your profile first")
                    GuidedBody(
                        "Only your lock takes a child out of a kids profile. Without it, anyone could leave and open the " +
                            "other profiles. $then",
                    )
                },
                actions = {
                    GuidedButton(R.drawable.ic_lock, "Lock my profile", ControlStyle.Primary, actions.onStart, Modifier.focusRequester(first))
                    GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, actions.onBack)
                },
            )
        }
    }
}

/** The owner-lock screen's events, as the gate's ViewModel takes them. */
internal class OwnerLockActions(
    val onStart: () -> Unit,
    val onKind: (LockKind) -> Unit,
    val onBack: () -> Unit,
    val entry: UnlockActions,
)
