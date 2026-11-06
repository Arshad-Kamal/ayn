package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.kids.lockHeldHint
import com.arshad.ayn.feature.kids.lockHeldNote
import com.arshad.ayn.feature.settings.LockChange
import com.arshad.ayn.feature.settings.ProfileRow
import com.arshad.ayn.feature.settings.contentsOf
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.label

private const val RESTRICTIONS_KEY = "restrictions"
private const val RESET_KEY = "reset"
private const val REMOVE_KEY = "remove"
private const val STANDARD_KEY = "standard"
private const val KIDS_KEY = "kids"

/**
 * The sheet [shown] over Settings › Profiles (`b3-profiles` own, psheet, colour, kind,
 * lockmenu; `kp-filters` psheet; `kp-kids` kind, ownlockheld); [from] is the screen before it,
 * which picks the row it opens on. [onRestrictions] opens another profile's restrictions,
 * [onKidsProfile] the kids profile's add flow.
 */
@Composable
internal fun ProfilesSheet(
    shown: ProfilesScreen,
    from: ProfilesScreen?,
    state: ProfilesUiState,
    profiles: ProfilesViewModel,
    ownSheetEntry: OwnSheetEntry,
    onRestrictions: (Long) -> Unit,
    onKidsProfile: () -> Unit,
) {
    val me = state.me ?: return
    when (shown) {
        ProfilesScreen.ProfileKind -> ProfileKindSheet(state.rows.size + 1, profiles, onKidsProfile)
        ProfilesScreen.OwnSheet, is ProfilesScreen.Rename -> OwnSheet(me, shown as? ProfilesScreen.Rename, from, state, profiles, ownSheetEntry)
        is ProfilesScreen.ProfileSheet -> state.rows.firstOrNull { it.profile.id == shown.profileId }?.let { row ->
            ProfileSheet(row, me, from, profiles, onRestrictions)
        }
        ProfilesScreen.Colour -> ColourSheet(me, profiles)
        ProfilesScreen.ChooseKind -> ChooseKindSheet(me, from, profiles)
        ProfilesScreen.LockMenu -> LockMenuSheet(me, state.kidsNames, from, profiles)
        // Not sheets (SettingsScreen's layerOf).
        ProfilesScreen.Page, is ProfilesScreen.ConfirmRemove, is ProfilesScreen.OwnerGate, is ProfilesScreen.CurrentLock,
        is ProfilesScreen.NewLock, is ProfilesScreen.AddForm,
        -> Unit
    }
}

/** The owner's own row (`own`): name, colour, lock; the owner can't be removed. */
@Composable
private fun OwnSheet(
    me: Profile,
    rename: ProfilesScreen.Rename?,
    from: ProfilesScreen?,
    state: ProfilesUiState,
    profiles: ProfilesViewModel,
    entry: OwnSheetEntry,
) {
    val initial = remember {
        val lock = entry.lock
        entry.lock = false
        when (from) {
            ProfilesScreen.Colour -> COLOUR_KEY
            ProfilesScreen.ChooseKind, ProfilesScreen.LockMenu, is ProfilesScreen.NewLock, is ProfilesScreen.CurrentLock -> LOCK_KEY
            else -> if (lock) LOCK_KEY else NAME_KEY
        }
    }
    val focus = rememberSheetFocus(initial)
    SettingsSheet(
        kicker = "Your profile · owner",
        title = { PlateTitle(me.name, me.tint) },
        sub = profileHint(me.lock, state.contentsOf(me.id)),
        note = "The owner’s profile can’t be removed. Only a restore makes someone else the owner.",
    ) {
        OwnRows(me, rename, profiles, inSheet = true, focus = focus)
    }
}

/**
 * Add a profile (`kp-kids` kind, 1): a standard profile or a kids profile, the [number]th of five.
 * The focused kind's radio is the one OK takes; it opens on Kids, as the mockup does.
 */
@Composable
private fun ProfileKindSheet(number: Int, profiles: ProfilesViewModel, onKidsProfile: () -> Unit) {
    val focus = rememberSheetFocus(KIDS_KEY)
    var focused by remember { mutableStateOf(KIDS_KEY) }
    SettingsSheet(
        kicker = "Add a profile · $number of $MAX_PROFILES",
        title = { SheetTitle("What kind of profile?") },
        sub = null,
        note = "A kids profile is left only with your lock, so Ayn asks you to have one first.",
    ) {
        SettingRow(
            label = "Standard profile",
            hint = "Its own subscriptions, history and playlists. Its user can lock it.",
            chevron = false,
            minHeight = SheetRowMinHeight,
            onClick = profiles::onStandardProfile,
            modifier = focus.on(STANDARD_KEY).onFocusChanged { if (it.isFocused) focused = STANDARD_KEY },
            lead = { RowRadio(on = focused == STANDARD_KEY, tones = it) },
        )
        SettingRow(
            label = "Kids profile",
            hint = "Shows only channels and videos you approve, with daily time limits",
            chevron = false,
            minHeight = SheetRowMinHeight,
            onClick = onKidsProfile,
            modifier = focus.on(KIDS_KEY).onFocusChanged { if (it.isFocused) focused = KIDS_KEY },
            lead = { RowRadio(on = focused == KIDS_KEY, tones = it) },
        )
    }
}

/**
 * The owner's sheet for another profile (`psheet`, `kp-filters` psheet): its restrictions, reset
 * its lock (when it has one; a kids profile has none), remove it; the note says what asks for the
 * owner's ([me]'s) lock.
 */
@Composable
private fun ProfileSheet(
    row: ProfileRow,
    me: Profile,
    from: ProfilesScreen?,
    profiles: ProfilesViewModel,
    onRestrictions: (Long) -> Unit,
) {
    val profile = row.profile
    val locked = profile.lock != null
    val focus = rememberSheetFocus(if (from is ProfilesScreen.ConfirmRemove) REMOVE_KEY else RESTRICTIONS_KEY)
    SettingsSheet(
        kicker = "Profile",
        title = { PlateTitle(profile.name, profile.tint) },
        sub = rowHint(row),
        note = me.lock?.let(::restrictionsGateNote),
    ) {
        SettingRow(
            label = "Restrictions",
            hint = "Filters ${profile.name} can’t turn off",
            chevron = true,
            minHeight = SheetRowMinHeight,
            onClick = { onRestrictions(profile.id) },
            modifier = focus.on(RESTRICTIONS_KEY),
            lead = { RowIcon(R.drawable.ic_funnel, it) },
        )
        if (locked) {
            SettingRow(
                label = "Reset ${profile.name}’s lock",
                hint = "Removes it; ${profile.name} sets a new one",
                minHeight = SheetRowMinHeight,
                onClick = { profiles.onResetLock(profile.id) },
                modifier = focus.on(RESET_KEY),
                lead = { RowIcon(R.drawable.ic_lock, it) },
            )
        }
        SettingRow(
            label = "Remove profile",
            hint = "Deletes everything ${profile.name} keeps here",
            danger = true,
            minHeight = SheetRowMinHeight,
            onClick = { profiles.onRemoveProfile(profile.id) },
            modifier = focus.on(REMOVE_KEY),
            lead = { RowIcon(R.drawable.ic_trash, it) },
        )
    }
}

/** Your colour (`colour`): the tints, yours marked; the title's plate shows the focused one. */
@Composable
private fun ColourSheet(me: Profile, profiles: ProfilesViewModel) {
    val focus = rememberSheetFocus(me.tint.name)
    var shownTint by remember { mutableStateOf(me.tint) }
    SettingsSheet(
        kicker = "Your colour",
        title = { PlateTitle(me.name, shownTint) },
        sub = "Marks your profile in the picker and the rail.",
    ) {
        ProfileTint.entries.forEach { tint ->
            SettingRow(
                label = tint.label,
                minHeight = SheetRowMinHeight,
                onClick = { profiles.onTintPicked(tint) },
                modifier = focus.on(tint.name).onFocusChanged { if (it.isFocused) shownTint = tint },
                lead = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowRadio(on = tint == me.tint, tones = it)
                        Spacer(Modifier.width(11.dp))
                        TintDot(tint)
                    }
                },
            )
        }
    }
}

@Composable
private fun ChooseKindSheet(me: Profile, from: ProfilesScreen?, profiles: ProfilesViewModel) {
    val marked = me.lock ?: LockKind.Sequence
    ChooseLockSheet(me.name, marked, (from as? ProfilesScreen.NewLock)?.lockKind ?: marked, profiles::onLockKind)
}

/** Choose a lock (`kind`) for [name]'s profile: a D-pad sequence or a PIN, each saying what it
 *  shows; [marked] is chosen, [initial] focused as it opens. Also the gate's, for the owner's
 *  lock a kids profile or a first-launch restore asks for (KP-D14). */
@Composable
internal fun ChooseLockSheet(name: String, marked: LockKind, initial: LockKind, onKind: (LockKind) -> Unit) {
    val focus = rememberSheetFocus(initial.name)
    SettingsSheet(
        kicker = "Lock $name’s profile",
        title = { SheetTitle("Choose a lock") },
        sub = "You’ll enter it each time you open your profile, and after the TV sleeps.",
    ) {
        SettingRow(
            label = "D-pad sequence",
            hint = "6 to 8 presses on the remote’s arrows, then OK. The screen never shows which arrows you press.",
            minHeight = SheetRowMinHeight,
            onClick = { onKind(LockKind.Sequence) },
            modifier = focus.on(LockKind.Sequence.name),
            lead = { RowRadio(on = marked == LockKind.Sequence, tones = it) },
        )
        SettingRow(
            label = "4-digit PIN",
            hint = "Entered on an on-screen keypad: anyone watching can see each digit.",
            minHeight = SheetRowMinHeight,
            onClick = { onKind(LockKind.Pin) },
            modifier = focus.on(LockKind.Pin.name),
            lead = { RowRadio(on = marked == LockKind.Pin, tones = it) },
        )
    }
}

/**
 * Your lock (`lockmenu`): change it or remove it, each after the current one. While [kids] have
 * kids profiles the owner's Remove lock is held, saying why (`kp-kids` ownlockheld, KP-D14).
 */
@Composable
private fun LockMenuSheet(me: Profile, kids: List<String>, from: ProfilesScreen?, profiles: ProfilesViewModel) {
    val kind = me.lock ?: return
    val held = me.isOwner && kids.isNotEmpty()
    val focus = rememberSheetFocus(((from as? ProfilesScreen.CurrentLock)?.change ?: LockChange.Change).name)
    val current = if (kind == LockKind.Pin) "PIN" else "sequence"
    SettingsSheet(
        kicker = "Your lock",
        title = { SheetTitle(lockWord(kind)) },
        sub = if (held) "Changing it asks for your current $current first." else "Changing or removing it asks for your current $current first.",
        note = if (held) lockHeldNote(kids) else null,
    ) {
        SettingRow(
            label = "Change lock",
            hint = if (kind == LockKind.Pin) "Then choose a D-pad sequence or a new PIN" else "Then choose a new D-pad sequence or a PIN",
            minHeight = SheetRowMinHeight,
            onClick = { profiles.onChangeLock(LockChange.Change) },
            modifier = focus.on(LockChange.Change.name),
            lead = { RowIcon(R.drawable.ic_edit, it) },
        )
        SettingRow(
            label = "Remove lock",
            hint = if (held) lockHeldHint(kids) else "Your profile opens without asking",
            minHeight = SheetRowMinHeight,
            onClick = { profiles.onChangeLock(LockChange.Remove) },
            focusable = !held,
            modifier = focus.on(LockChange.Remove.name).then(if (held) Modifier.alpha(HeldAlpha) else Modifier),
            lead = { RowIcon(R.drawable.ic_lock, it) },
        )
    }
}

/** A row that can't be chosen now, still read (`.srow.held`). */
private const val HeldAlpha = 0.5f

/** A sheet title naming a profile: its plate, then its name (`.sh-who`). */
@Composable
internal fun PlateTitle(name: String, tint: ProfileTint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProfilePlate(name, tint, SheetTitlePlate)
        Spacer(Modifier.width(9.dp))
        SheetTitle(name)
    }
}
