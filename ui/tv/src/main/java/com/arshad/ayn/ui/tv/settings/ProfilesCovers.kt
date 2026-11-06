package com.arshad.ayn.ui.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.settings.OwnerAction
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.removeText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.GuidedBody
import com.arshad.ayn.ui.tv.profiles.GuidedTitle
import com.arshad.ayn.ui.tv.profiles.KickerIcon
import com.arshad.ayn.ui.tv.profiles.KickerRow
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.MediumPlate
import com.arshad.ayn.ui.tv.profiles.GuidedField
import com.arshad.ayn.ui.tv.profiles.ProfileGuided
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.TintRow
import com.arshad.ayn.ui.tv.profiles.UnlockActions
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.ui.tv.profiles.controlRoomLight
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus

/**
 * A screen covering the shell over Settings › Profiles (`b3-profiles` remove, gate, setseq1,
 * setseq, mismatch, setpin1/2, current, add, addtaken).
 */
@Composable
internal fun ProfilesCover(shown: ProfilesScreen, state: ProfilesUiState, profiles: ProfilesViewModel) {
    val me = state.me ?: return
    val actions = remember(profiles) {
        UnlockActions(
            onPress = profiles::onPress,
            onSubmitSequence = profiles::onSubmitSequence,
            onDigit = profiles::onDigit,
            onErase = profiles::onErase,
            onBack = profiles::onBack,
            onRejectionShown = profiles::onRejectionShown,
        )
    }
    when (shown) {
        is ProfilesScreen.ConfirmRemove -> state.rows.firstOrNull { it.profile.id == shown.profileId }?.let { row ->
            ConfirmRemove(row.profile, removeText(row), profiles)
        }
        is ProfilesScreen.OwnerGate -> {
            val name: (Long) -> String? = { id -> state.rows.firstOrNull { it.profile.id == id }?.profile?.name }
            val copy = EntryCopy(
                kicker = EntryKicker(ownerGateIcon(shown.action), ownerGateKicker(shown.action, name)),
                line = ownerGateLine(shown.action, me.name, shown.lockKind),
                hint = cancelHint(shown.lockKind),
                waitTail = SettingsWaitTail,
            )
            LockEntryScreen(me, shown.lockKind, shown.entry, copy, actions)
        }
        is ProfilesScreen.CurrentLock -> {
            val copy = EntryCopy(
                kicker = EntryKicker(R.drawable.ic_lock, currentLockKicker(shown.change)),
                line = currentLockLine(shown.lockKind),
                hint = cancelHint(shown.lockKind),
                waitTail = SettingsWaitTail,
            )
            LockEntryScreen(me, shown.lockKind, shown.entry, copy, actions)
        }
        is ProfilesScreen.NewLock -> LockEntryScreen(me, shown.lockKind, shown.entry, newLockCopy(shown.lockKind, shown.entry), actions)
        is ProfilesScreen.AddForm -> AddProfileScreen(shown, state.rows.size + 1, profiles)
        // Not covering screens (SettingsScreen's layerOf).
        ProfilesScreen.Page, ProfilesScreen.OwnSheet, is ProfilesScreen.ProfileSheet, is ProfilesScreen.Rename,
        ProfilesScreen.Colour, ProfilesScreen.ChooseKind, ProfilesScreen.LockMenu, ProfilesScreen.ProfileKind,
        -> Unit
    }
}

private fun ownerGateIcon(action: OwnerAction): Int = when (action) {
    OwnerAction.Add -> R.drawable.ic_plus
    is OwnerAction.Remove -> R.drawable.ic_trash
    is OwnerAction.ResetLock -> R.drawable.ic_lock
}

/** Setting a new lock, step 1 or 2; also the gate's owner lock (KP-D14). */
internal fun newLockCopy(kind: LockKind, entry: UnlockEntry): EntryCopy = EntryCopy(
    kicker = EntryKicker(R.drawable.ic_lock, "New lock · step ${if (entry.confirming) 2 else 1} of 2"),
    line = newLockLine(kind, entry.confirming),
    hint = newLockHint(kind, entry.confirming),
    waitTail = SettingsWaitTail,
)

/** Remove a profile? (`remove`): what it deletes, named with its counts; Keep is focused first. */
@Composable
private fun ConfirmRemove(profile: Profile, deletes: String, profiles: ProfilesViewModel) {
    val keep = rememberInitialFocus()
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow("Profiles", Modifier.padding(bottom = 11.dp)) { ProfilePlate(profile.name, profile.tint, RowPlate) }
                GuidedTitle("Remove ${profile.name}?")
                GuidedBody(deletes)
            },
            actions = {
                GuidedButton(R.drawable.ic_back, "Keep ${profile.name}", ControlStyle.Secondary, profiles::onBack, Modifier.focusRequester(keep))
                GuidedButton(R.drawable.ic_trash, "Remove ${profile.name}", ControlStyle.Danger, profiles::onConfirmRemove)
            },
        )
    }
}

/**
 * A new profile (`add`, `addtaken`): its name with the system keyboard (the field starts focused
 * while empty, its Done key moves on to Add profile) and its colour with the arrows; a refused
 * name says why under the field.
 */
@Composable
private fun AddProfileScreen(form: ProfilesScreen.AddForm, number: Int, profiles: ProfilesViewModel) {
    val field = remember { FocusRequester() }
    val add = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (if (form.name.isEmpty()) field else add).requestFocus()
    }
    // A refused name puts focus back in the field to fix it.
    LaunchedEffect(form.problem) {
        if (form.problem != null) field.requestFocus()
    }
    BackHandler(onBack = profiles::onBack)
    val tint = form.tint.color
    Box(Modifier.fillMaxSize().roomLight({ tint }, alpha = { 0.24f }, x = { 0.3f })) {
        ProfileGuided(
            copy = {
                KickerRow("New profile · $number of $MAX_PROFILES") { KickerIcon(R.drawable.ic_plus) }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                ) {
                    ProfilePlate(form.name, form.tint, MediumPlate)
                    GuidedField(
                        value = form.name,
                        onValueChange = profiles::onAddNameChange,
                        onDone = { add.requestFocus() },
                        onDown = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.focusRequester(field),
                    )
                }
                form.problem?.let { FieldProblem(nameProblemText(it, form.name), Modifier.padding(top = 10.dp, start = 83.dp)) }
                TintRow(form.tint, profiles::onAddTintChange, Modifier.padding(top = if (form.problem != null) 11.dp else 17.dp, start = 83.dp))
                GuidedBody(
                    "Up to $MAX_PROFILE_NAME characters, and not a name another profile has. The profile starts empty " +
                        "and unlocked; its user can lock it in Settings › Profiles.",
                )
            },
            actions = {
                GuidedButton(R.drawable.ic_check, "Add profile", ControlStyle.Primary, profiles::onAddConfirm, Modifier.focusRequester(add))
                GuidedButton(R.drawable.ic_back, "Cancel", ControlStyle.Secondary, profiles::onBack)
            },
        )
    }
}
