package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.settings.LockChange
import com.arshad.ayn.feature.settings.NameProblem
import com.arshad.ayn.feature.settings.OwnerAction
import com.arshad.ayn.feature.settings.countsText

// The TV's Settings › Profiles copy (`b3-profiles` v8; Q7: the history count reads "in history").

/** How a profile is locked (a row's hint, a sheet's line). */
internal fun lockWord(kind: LockKind?): String = when (kind) {
    LockKind.Sequence -> "Locked with a D-pad sequence"
    LockKind.Pin -> "Locked with a PIN"
    null -> "No lock"
}

/** The Lock row's value. */
internal fun lockValue(kind: LockKind?): String = when (kind) {
    LockKind.Sequence -> "D-pad sequence"
    LockKind.Pin -> "PIN"
    null -> "Off"
}

/** A profile row's hint: its lock, then what it keeps. */
internal fun profileHint(kind: LockKind?, contents: ProfileContents): String = "${lockWord(kind)} · ${countsText(contents)}"

/** The owner gate's kicker: what [action] is about to do ([profileName] names its profile). */
internal fun ownerGateKicker(action: OwnerAction, profileName: (Long) -> String?): String = when (action) {
    OwnerAction.Add -> "Adding a profile"
    is OwnerAction.Remove -> "Removing ${profileName(action.profileId) ?: "a profile"}"
    is OwnerAction.ResetLock -> profileName(action.profileId)?.let { "Resetting $it’s lock" } ?: "Resetting a lock"
}

/** The owner gate's line: only the owner does [action], then whose lock it asks for. */
internal fun ownerGateLine(action: OwnerAction, ownerName: String, kind: LockKind): String {
    val only = when (action) {
        OwnerAction.Add -> "Only the owner adds profiles."
        is OwnerAction.Remove -> "Only the owner removes profiles."
        is OwnerAction.ResetLock -> "Only the owner resets locks."
    }
    return "$only ${enterLock(ownerName, kind)}"
}

internal fun currentLockKicker(change: LockChange): String = when (change) {
    LockChange.Change -> "Changing your lock"
    LockChange.Remove -> "Removing your lock"
}

internal fun currentLockLine(kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "Enter your current sequence first, then press OK."
    LockKind.Pin -> "Enter your current PIN first."
}

/** Setting a lock: step 1 asks for it, step 2 ([confirming]) for the same again. */
internal fun newLockLine(kind: LockKind, confirming: Boolean): String = when (kind) {
    LockKind.Sequence -> if (confirming) "Enter the same sequence again, then press OK." else "Press the arrows 6 to 8 times, then press OK."
    LockKind.Pin -> if (confirming) "Enter the same PIN again." else "Choose 4 digits."
}

internal fun newLockHint(kind: LockKind, confirming: Boolean): String = when {
    confirming -> "If the two don’t match, nothing is saved and you start again."
    kind == LockKind.Sequence -> "The screen never shows which arrows you press · BACK erases a press"
    else -> "Anyone watching can see each digit as you choose it · BACK erases a digit"
}

/** Why a name wasn't taken, said under the field. */
internal fun nameProblemText(problem: NameProblem, name: String): String = when (problem) {
    NameProblem.Missing -> "A profile needs a name: up to $MAX_PROFILE_NAME characters."
    NameProblem.Taken -> "${name.trim()} already has a profile here. Choose another name."
    NameProblem.Full -> "This Shield already has its $MAX_PROFILES profiles."
}

/** A renamed profile's refusal, under its row (`b3-profiles` rename). */
internal fun renameProblemText(problem: NameProblem, name: String): String = when (problem) {
    NameProblem.Taken -> "${name.trim()} already has a profile here."
    NameProblem.Missing, NameProblem.Full -> "Your profile needs a name."
}
