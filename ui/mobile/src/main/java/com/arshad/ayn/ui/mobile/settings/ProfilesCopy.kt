package com.arshad.ayn.ui.mobile.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.feature.kids.kidsHint
import com.arshad.ayn.feature.settings.LockChange
import com.arshad.ayn.feature.settings.NameProblem
import com.arshad.ayn.feature.settings.OwnerAction
import com.arshad.ayn.feature.settings.ProfileRow
import com.arshad.ayn.feature.settings.ProfilesToast
import com.arshad.ayn.feature.settings.countsText
import com.arshad.ayn.ui.mobile.common.NoticeLine

// The phone's Settings › Profiles copy (`m6-settings` 25–36); text both UIs show: feature/settings/ProfilesText.kt.

internal const val OWNER_LEDE = "Each profile keeps its own subscriptions, history, playlists and Watch later. Quality and " +
    "background audio are shared by everyone on this phone."

/** A page that isn't the owner's: what the profile keeps, and who adds and removes profiles. */
internal fun guestLede(ownerName: String?): String =
    "Your subscriptions, history, playlists and Watch later. " +
        (ownerName?.let { "$it, the owner, adds and removes profiles." } ?: "The owner adds and removes profiles.")

/** How a profile is locked (a row's line, a sheet's head). */
internal fun lockWord(kind: LockKind?): String = when (kind) {
    LockKind.Sequence -> "Locked with a sequence"
    LockKind.Pin -> "Locked with a PIN"
    null -> "No lock"
}

/** The Lock row's line. */
internal fun lockValue(kind: LockKind?): String = when (kind) {
    LockKind.Sequence -> "Sequence"
    LockKind.Pin -> "PIN"
    null -> "Off"
}

/** A profile's line: its lock, then what it keeps. */
internal fun profileHint(kind: LockKind?, contents: ProfileContents): String = "${lockWord(kind)} · ${countsText(contents)}"

/** A row's line: a kids profile's allowances, anyone else's lock and counts. */
internal fun rowHint(row: ProfileRow): String =
    if (row.profile.isKids) kidsHint(row.contents, row.kid) else profileHint(row.profile.lock, row.contents)

private fun lockNoun(kind: LockKind): String = if (kind == LockKind.Pin) "PIN" else "sequence"

/** The ⌫ key's part of a lock page's hint. */
internal fun erasesHint(kind: LockKind): String = if (kind == LockKind.Pin) "⌫ erases a digit" else "⌫ erases a tap"

/** Asking for [name]'s lock (the owner's gate, state 32). */
internal fun enterLockLine(name: String, kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "Enter $name’s sequence, then OK."
    LockKind.Pin -> "Enter $name’s PIN."
}

/** The owner's gate's label: what [action] is about to do ([name] names its profile). */
internal fun ownerGateLabel(action: OwnerAction, name: (Long) -> String?): String = when (action) {
    OwnerAction.Add -> "Adding a profile"
    is OwnerAction.Remove -> "Removing ${name(action.profileId) ?: "a profile"}"
    is OwnerAction.ResetLock -> name(action.profileId)?.let { "Resetting $it’s lock" } ?: "Resetting a lock"
}

internal fun ownerGateHint(action: OwnerAction, kind: LockKind): String {
    val only = when (action) {
        OwnerAction.Add -> "Only the owner adds profiles"
        is OwnerAction.Remove -> "Only the owner removes profiles"
        is OwnerAction.ResetLock -> "Only the owner resets locks"
    }
    return "$only · ${erasesHint(kind)} · Back cancels"
}

/** What on another profile's sheet asks for the owner's [kind] lock (state 26). */
internal fun ownerAsksNote(kind: LockKind): String = "Restrictions and resets ask for your ${lockNoun(kind)} first."

internal fun currentLockLabel(change: LockChange): String = when (change) {
    LockChange.Change -> "Changing your lock"
    LockChange.Remove -> "Removing your lock"
}

internal fun currentLockLine(kind: LockKind): String = when (kind) {
    LockKind.Sequence -> "Tap your current sequence first, then OK."
    LockKind.Pin -> "Enter your current PIN first."
}

internal fun currentLockHint(kind: LockKind): String = "${erasesHint(kind)} · Back cancels"

/** A new lock's step (states 30–31): step 2 ([confirming]) asks for the same again. */
internal fun newLockLabel(confirming: Boolean): String = "New lock · step ${if (confirming) 2 else 1} of 2"

internal fun newLockLine(kind: LockKind, confirming: Boolean): String = when (kind) {
    LockKind.Sequence -> if (confirming) "Tap the same sequence again, then OK." else "Tap the arrows 6 to 8 times, then OK."
    LockKind.Pin -> if (confirming) "Enter the same PIN again." else "Choose 4 digits."
}

internal fun newLockHint(kind: LockKind, confirming: Boolean): String = when {
    confirming -> "If the two don’t match, nothing is saved and you start again."
    kind == LockKind.Sequence -> "The screen never shows which arrows you tap · ${erasesHint(kind)}"
    else -> "Anyone watching can see each digit as you choose it · ${erasesHint(kind)}"
}

/** Where Back goes from a lock's wait over Settings. */
internal const val GO_BACK_WAIT_TAIL = ", or go back."

/** Your lock's sheet: what changing (and, unless [held], removing) it asks first. */
internal fun lockMenuSub(kind: LockKind, held: Boolean): String =
    if (held) "Changing it asks for your current ${lockNoun(kind)} first." else "Changing or removing it asks for your current ${lockNoun(kind)} first."

internal fun changeLockHint(kind: LockKind): String =
    if (kind == LockKind.Pin) "Then choose a sequence or a new PIN" else "Then choose a new sequence or a PIN"

internal const val NEW_PROFILE_LINE = "Up to $MAX_PROFILE_NAME characters, and not a name another profile has. The profile " +
    "starts empty and unlocked; its user can lock it in Settings › Profiles."

internal fun newProfileLabel(number: Int): String = "New profile · $number of $MAX_PROFILES"

/** Why a new profile's name wasn't taken (state 35). */
internal fun nameProblemText(problem: NameProblem, name: String): String = when (problem) {
    NameProblem.Missing -> "A profile needs a name: up to $MAX_PROFILE_NAME characters."
    NameProblem.Taken -> "${name.trim()} already has a profile here. Choose another name."
    NameProblem.Full -> "This phone already has its $MAX_PROFILES profiles."
}

/** Why your new name wasn't taken, under its field. */
internal fun renameProblemText(problem: NameProblem, name: String): String = when (problem) {
    NameProblem.Taken -> "${name.trim()} already has a profile here."
    NameProblem.Missing, NameProblem.Full -> "Your profile needs a name: up to $MAX_PROFILE_NAME characters."
}

/** A kids profile just added (state 26), without the mockup's "her": Ayn doesn't know a child's
 *  pronouns. */
internal fun kidAddedLine(name: String): NoticeLine = NoticeLine("$name added", "the profile opens from Who’s watching?", action = null)

internal fun profilesToastLine(toast: ProfilesToast): NoticeLine = when (toast) {
    ProfilesToast.LockSaved -> NoticeLine("Lock saved", "your profile asks for it from now on", action = null)
    ProfilesToast.LockRemoved -> NoticeLine("Lock removed", "your profile opens without asking", action = null)
}
