package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.kids.lockHeldHint
import com.arshad.ayn.feature.kids.lockHeldNote
import com.arshad.ayn.feature.settings.LockChange
import com.arshad.ayn.feature.settings.ProfileRow
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.contentsOf
import com.arshad.ayn.feature.settings.removeText
import com.arshad.ayn.feature.settings.rowOf
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.menu.ConfirmSheetContent
import com.arshad.ayn.ui.mobile.menu.RowRadio
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.SmallTile
import com.arshad.ayn.ui.mobile.profiles.color
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight

/**
 * The sheet over Settings › Profiles while one shows (`m6-settings` 26–29, 33, 36; your lock's
 * menu): a swipe closes it to the page, Back steps back one level. [onRestrictions] opens another
 * profile's restrictions, [onKidsProfile] a kids profile's add flow; while that flow [covered] the
 * page, the kind sheet waits closed and comes back when it closes.
 */
@Composable
internal fun ProfilesSheets(
    state: ProfilesUiState,
    me: Profile,
    profiles: ProfilesViewModel,
    onRestrictions: (Long) -> Unit,
    onKidsProfile: () -> Unit,
    covered: Boolean,
) {
    val sheet = state.screen.takeIf { it.isSheet(me) && !covered }
    // Renaming happens in the owner's sheet, which stays put as the name changes.
    StageSheet(sheet, onDismiss = profiles::onDismiss, onBack = profiles::onBack, stageKey = { if (it is ProfilesScreen.Rename) ProfilesScreen.OwnSheet else it }) { shown ->
        when (shown) {
            ProfilesScreen.ProfileKind -> ProfileKindSheet(state.rows.size + 1, profiles, onKidsProfile)
            ProfilesScreen.OwnSheet, is ProfilesScreen.Rename -> OwnSheet(me, shown as? ProfilesScreen.Rename, state, profiles)
            is ProfilesScreen.ProfileSheet -> state.rowOf(shown.profileId)?.let { ProfileSheet(it, me, profiles, onRestrictions) }
            is ProfilesScreen.ConfirmRemove -> state.rowOf(shown.profileId)?.let { row ->
                val name = row.profile.name
                ConfirmSheetContent(
                    kicker = "Profiles",
                    title = "Remove $name?",
                    body = removeText(row),
                    keep = "Keep $name",
                    confirm = "Remove $name",
                    onKeep = profiles::onBack,
                    onConfirm = profiles::onConfirmRemove,
                    kickerLead = { ProfileTile(name, row.profile.tint, ScopeTile) },
                )
            }
            ProfilesScreen.Colour -> ColourSheet(me, profiles)
            ProfilesScreen.ChooseKind -> ChooseLockSheet("Lock your profile", marked = me.lock ?: LockKind.Sequence, onKind = profiles::onLockKind)
            ProfilesScreen.LockMenu -> LockMenuSheet(me, state.kidsNames, profiles)
            ProfilesScreen.Page, is ProfilesScreen.EntryScreen, is ProfilesScreen.AddForm -> Unit
        }
    }
}

/** The screens that show as a sheet; a non-owner renames on the page. */
private fun ProfilesScreen.isSheet(me: Profile): Boolean = when (this) {
    ProfilesScreen.ProfileKind, ProfilesScreen.OwnSheet, is ProfilesScreen.ProfileSheet, is ProfilesScreen.ConfirmRemove,
    ProfilesScreen.Colour, ProfilesScreen.ChooseKind, ProfilesScreen.LockMenu,
    -> true
    is ProfilesScreen.Rename -> me.isOwner
    ProfilesScreen.Page, is ProfilesScreen.EntryScreen, is ProfilesScreen.AddForm -> false
}

/** The owner's own sheet (state 27): Name, Colour, Lock; the owner can't be removed. A lock just
 *  saved or removed says so at its foot, and the notice goes with the sheet. */
@Composable
private fun OwnSheet(me: Profile, rename: ProfilesScreen.Rename?, state: ProfilesUiState, profiles: ProfilesViewModel) {
    DisposableEffect(profiles) { onDispose { profiles.onToastShown() } }
    Box {
        Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState()).imePadding()) {
            SettingsSheetKicker("Your profile · owner")
            SheetHead(me.name, me.tint, profileHint(me.lock, state.contentsOf(me.id)))
            OwnRows(me, rename, profiles, onPage = false)
            SettingsSheetNote("The owner’s profile can’t be removed. Only a restore makes someone else the owner.")
            SheetFoot()
        }
        NoticeSnackbar(state.toast, ::profilesToastLine, onClose = profiles::onToastShown, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Another profile's sheet (state 26): its restrictions, reset its lock (when it has one), remove
 *  it; the note says what asks for the owner's ([me]'s) lock. */
@Composable
private fun ProfileSheet(row: ProfileRow, me: Profile, profiles: ProfilesViewModel, onRestrictions: (Long) -> Unit) {
    val profile = row.profile
    val name = profile.name
    Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetKicker("Profile")
        SheetHead(profile.name, profile.tint, rowHint(row))
        SheetRow("Restrictions", tap { onRestrictions(profile.id) }, hint = "Filters $name can’t turn off", icon = R.drawable.ic_funnel, chevron = true)
        if (profile.lock != null) {
            SheetRow("Reset $name’s lock", tap { profiles.onResetLock(profile.id) }, hint = "Removes it; $name sets a new one", icon = R.drawable.ic_lock)
        }
        SheetRow(
            "Remove profile",
            tap { profiles.onRemoveProfile(profile.id) },
            hint = "Deletes everything $name keeps here",
            icon = R.drawable.ic_trash,
            danger = true,
        )
        me.lock?.let { SettingsSheetNote(ownerAsksNote(it)) }
        SheetFoot()
    }
}

/** Add a profile (state 33): a kids profile or a standard one, the [number]th of five. */
@Composable
private fun ProfileKindSheet(number: Int, profiles: ProfilesViewModel, onKidsProfile: () -> Unit) {
    SheetColumn(
        kicker = "Add a profile · $number of $MAX_PROFILES",
        title = "What kind of profile?",
        sub = null,
        note = "A kids profile is left only with your lock, so Ayn asks you to have one first.",
    ) {
        ChoiceRow(
            "Kids profile",
            on = false,
            onClick = onKidsProfile,
            hint = "Shows only channels and videos you approve, with daily time limits",
        )
        ChoiceRow("Standard profile", on = false, onClick = profiles::onStandardProfile, hint = "Its own subscriptions, history and playlists. Its user can lock it.")
    }
}

/** Your colour (state 36): the five tints, yours chosen; a tap saves it. */
@Composable
private fun ColourSheet(me: Profile, profiles: ProfilesViewModel) {
    Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetKicker("Your colour")
        SheetHead(me.name, me.tint, "Marks your profile in the picker and on the avatar.")
        Column(Modifier.selectableGroup()) {
            ProfileTint.entries.forEach { tint ->
                val on = tint == me.tint
                ChoiceRow(tint.name, on, onClick = { profiles.onTintPicked(tint) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RowRadio(on)
                        Box(Modifier.size(12.dp).background(tint.color, CircleShape))
                    }
                }
            }
        }
        SheetFoot()
    }
}

/** Choose a lock (state 29): a sequence tapped on the pad or a PIN, each saying what it shows;
 *  [marked] is chosen. */
@Composable
internal fun ChooseLockSheet(kicker: String, marked: LockKind, onKind: (LockKind) -> Unit) {
    SheetColumn(kicker = kicker, title = "Choose a lock", sub = "You’ll enter it each time you open your profile, and after the screen goes off.") {
        ChoiceRow(
            "Sequence",
            marked == LockKind.Sequence,
            onClick = { onKind(LockKind.Sequence) },
            hint = "6 to 8 taps on an on-screen pad, then OK. The screen never shows which arrows you tap.",
        )
        ChoiceRow("4-digit PIN", marked == LockKind.Pin, onClick = { onKind(LockKind.Pin) }, hint = "Entered on a number pad: anyone watching can see each digit.")
    }
}

/** Your lock: change it or remove it, each after the current one. While [kids] have kids profiles
 *  the owner's Remove lock is held, saying why (KP-D14). */
@Composable
private fun LockMenuSheet(me: Profile, kids: List<String>, profiles: ProfilesViewModel) {
    val kind = me.lock ?: return
    val held = me.isOwner && kids.isNotEmpty()
    SheetColumn(kicker = "Your lock", title = lockWord(kind), sub = lockMenuSub(kind, held), choices = false, note = if (held) lockHeldNote(kids) else null) {
        SheetRow("Change lock", tap { profiles.onChangeLock(LockChange.Change) }, hint = changeLockHint(kind), icon = R.drawable.ic_edit)
        SheetRow(
            "Remove lock",
            if (held) Modifier else tap { profiles.onChangeLock(LockChange.Remove) },
            hint = if (held) lockHeldHint(kids) else "Your profile opens without asking",
            icon = R.drawable.ic_lock,
            dimmed = held,
        )
    }
}

/** Whom a sheet is about (`.shd2`): the tile, the [name] over its [line], above a hairline. */
@Composable
internal fun SheetHead(name: String, tint: ProfileTint, line: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileTile(name, tint, SmallTile)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineMedium.atWeight(720).copy(fontSize = 22.sp, lineHeight = 26.sp))
                Text(line, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = AynColors.Ink3)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AynColors.Line))
    }
}
