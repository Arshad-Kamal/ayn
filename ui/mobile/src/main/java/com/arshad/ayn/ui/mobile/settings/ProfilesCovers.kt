package com.arshad.ayn.ui.mobile.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.settings.OwnerAction
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.rowOf
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.profiles.NameActions
import com.arshad.ayn.ui.mobile.profiles.NameProfilePage

/**
 * The pages over the shell from Settings › Profiles (`m6-settings` 30–32, 34–35): the owner's lock
 * before an owner-only change, your current lock before changing or removing it, a new lock entered
 * twice, and a new profile's name and colour. Touch Back leaves a lock's page even with taps
 * entered; the pads' ⌫ erases.
 */
@Composable
internal fun ProfilesCover(state: ProfilesUiState, me: Profile, profiles: ProfilesViewModel) {
    val screen = state.screen
    if (screen !is ProfilesScreen.EntryScreen && screen !is ProfilesScreen.AddForm) return
    val actions = remember(profiles) {
        EntryActions(
            onPress = profiles::onPress,
            onSubmitSequence = profiles::onSubmitSequence,
            onDigit = profiles::onDigit,
            onErase = profiles::onErase,
            onBack = profiles::onEntryLeave,
            onRejectionShown = profiles::onRejectionShown,
        )
    }
    val nameActions = remember(profiles) {
        NameActions(profiles::onAddNameChange, profiles::onAddTintChange, profiles::onAddConfirm, profiles::onBack)
    }
    FullCover {
        when (screen) {
            is ProfilesScreen.OwnerGate -> {
                val nameOf = { id: Long -> state.rowOf(id)?.profile?.name }
                val copy = EntryCopy(
                    label = ownerGateLabel(screen.action, nameOf),
                    line = enterLockLine(me.name, screen.lockKind),
                    hint = ownerGateHint(screen.action, screen.lockKind),
                    waitTail = GO_BACK_WAIT_TAIL,
                    labelIcon = ownerGateIcon(screen.action),
                )
                LockEntryPage(me, screen.lockKind, screen.entry, copy, actions)
            }
            is ProfilesScreen.CurrentLock -> {
                val copy = EntryCopy(
                    label = currentLockLabel(screen.change),
                    line = currentLockLine(screen.lockKind),
                    hint = currentLockHint(screen.lockKind),
                    waitTail = GO_BACK_WAIT_TAIL,
                    labelIcon = R.drawable.ic_lock,
                )
                LockEntryPage(me, screen.lockKind, screen.entry, copy, actions)
            }
            is ProfilesScreen.NewLock -> LockEntryPage(me, screen.lockKind, screen.entry, newLockCopy(screen.lockKind, screen.entry), actions)
            is ProfilesScreen.AddForm -> {
                val problem = screen.problem
                NameProfilePage(
                    label = newProfileLabel(state.rows.size + 1),
                    name = screen.name,
                    tint = screen.tint,
                    line = problem?.let { nameProblemText(it, screen.name) } ?: NEW_PROFILE_LINE,
                    refused = problem != null,
                    confirm = "Add profile",
                    confirmEnabled = !screen.saving && problem == null,
                    actions = nameActions,
                )
            }
            else -> Unit
        }
    }
}

private fun ownerGateIcon(action: OwnerAction): Int = when (action) {
    OwnerAction.Add -> R.drawable.ic_plus
    is OwnerAction.Remove -> R.drawable.ic_trash
    is OwnerAction.ResetLock -> R.drawable.ic_lock
}

/** A new lock's step 1 or 2 (states 30–31). */
internal fun newLockCopy(kind: LockKind, entry: UnlockEntry): EntryCopy = EntryCopy(
    label = newLockLabel(entry.confirming),
    line = newLockLine(kind, entry.confirming),
    hint = newLockHint(kind, entry.confirming),
    waitTail = GO_BACK_WAIT_TAIL,
)
