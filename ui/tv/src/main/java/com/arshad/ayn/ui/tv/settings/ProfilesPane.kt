package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.kids.kidsHint
import com.arshad.ayn.feature.settings.ProfileRow
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.profilesCountText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.label
import com.arshad.ayn.ui.tv.theme.KeColors

// Focus keys of the Profiles page's rows (ScreenFocus memory).
internal const val LOCK_YOURS_KEY = "lock-yours"
internal const val ADD_KEY = "add-profile"
internal const val NAME_KEY = "own-name"
internal const val COLOUR_KEY = "own-colour"
internal const val LOCK_KEY = "own-lock"

internal fun profileKey(id: Long): String = "profile-$id"

private const val OWNER_SUB = "Each profile keeps its own subscriptions, history, playlists and Watch later. Quality, " +
    "codec and the other playback settings are shared by everyone on this Shield."

/**
 * Settings › Profiles (`b3-profiles` manage, warn, yuri, rename): the owner's page lists every
 * profile (theirs marked Owner and "You") and Add a profile while there is room, under a warning
 * while the owner has no lock; anyone else's page holds their own rows. [onLockYours] marks that
 * the warning's row opened the owner's sheet (it starts on Lock).
 */
@Composable
internal fun ProfilesPane(state: ProfilesUiState, profiles: ProfilesViewModel, onLockYours: () -> Unit) {
    val me = state.me ?: return
    SettingsPane("Profiles", if (me.isOwner) OWNER_SUB else guestSub(state.ownerName)) {
        if (me.isOwner) {
            OwnersRows(state, me, profiles, onLockYours)
        } else {
            OwnRows(me, state.screen as? ProfilesScreen.Rename, profiles, inSheet = false)
        }
    }
}

private fun guestSub(ownerName: String?): String =
    "Your subscriptions, history, playlists and Watch later. " +
        (ownerName?.let { "$it, the owner, adds and removes profiles." } ?: "The owner adds and removes profiles.")

@Composable
private fun OwnersRows(state: ProfilesUiState, me: Profile, profiles: ProfilesViewModel, onLockYours: () -> Unit) {
    if (state.ownerHasNoLock) {
        Notice(
            lead = "Your profile isn’t locked.",
            text = "Anyone can lock you out, or reset or remove other profiles.",
            modifier = Modifier.padding(start = RowInset, top = 2.dp, bottom = 4.dp),
        )
        SettingRow(
            label = "Lock your profile",
            hint = "A D-pad sequence or a PIN",
            chevron = true,
            onClick = {
                onLockYours()
                profiles.onOpenProfile(me.id)
            },
            modifier = Modifier.focusMemory(LOCK_YOURS_KEY),
            lead = { RowIcon(R.drawable.ic_lock, it) },
        )
    }
    state.rows.forEach { row ->
        val profile = row.profile
        SettingRow(
            label = profile.name,
            hint = rowHint(row),
            value = if (profile.id == me.id) "You" else "",
            onClick = { profiles.onOpenProfile(profile.id) },
            modifier = Modifier.focusMemory(profileKey(profile.id)),
            lead = { ProfilePlate(profile.name, profile.tint, RowPlate) },
            labelTrail = { tones -> ProfileTags(profile.isOwner, profile.isKids, tones) },
        )
    }
    if (state.canAdd) {
        SettingRow(
            label = "Add a profile",
            hint = profilesCountText(state.rows.size),
            chevron = true,
            onClick = profiles::onAddProfile,
            modifier = Modifier.focusMemory(ADD_KEY),
            lead = { RowIcon(R.drawable.ic_plus, it) },
        )
    }
}

/**
 * Your own rows (a non-owner's page, the owner's sheet): Name, edited in place with the system
 * keyboard ([rename]); Colour; Lock. Focus returns to Name when an edit ends.
 */
@Composable
internal fun OwnRows(
    me: Profile,
    rename: ProfilesScreen.Rename?,
    profiles: ProfilesViewModel,
    inSheet: Boolean,
    focus: SheetFocus? = null,
) {
    val minHeight = if (inSheet) SheetRowMinHeight else RowMinHeight
    val nameRow = remember { FocusRequester() }
    FocusOnClose(open = rename != null, target = nameRow)
    if (rename != null) {
        RenameRow(rename, profiles, minHeight)
    } else {
        SettingRow(
            label = "Name",
            value = me.name,
            minHeight = minHeight,
            onClick = profiles::onRename,
            modifier = Modifier.focusRequester(nameRow).keyed(focus, NAME_KEY, inSheet),
        )
    }
    SettingRow(
        label = "Colour",
        value = me.tint.label,
        minHeight = minHeight,
        onClick = profiles::onColour,
        modifier = Modifier.keyed(focus, COLOUR_KEY, inSheet),
        valueLead = { TintDot(me.tint, Modifier.padding(end = 2.dp)) },
    )
    SettingRow(
        label = "Lock",
        hint = if (inSheet) null else "Keeps your profile to yourself on this Shield",
        value = lockValue(me.lock),
        minHeight = minHeight,
        onClick = profiles::onLock,
        modifier = Modifier.keyed(focus, LOCK_KEY, inSheet),
        lead = { RowIcon(R.drawable.ic_lock, it) },
    )
}

/** On the page a row keeps its place in the screen's focus memory; in a sheet the sheet focuses it. */
private fun Modifier.keyed(focus: SheetFocus?, key: String, inSheet: Boolean): Modifier =
    if (inSheet) then(focus?.on(key) ?: Modifier) else focusMemory(key)

/**
 * Your name being edited (`b3-profiles` rename): the Name row turns into a field and the system
 * keyboard's Done key saves; BACK cancels. A refused name says why under the row.
 */
@Composable
private fun RenameRow(rename: ProfilesScreen.Rename, profiles: ProfilesViewModel, minHeight: Dp) {
    Column {
        SettingEditRow(
            label = "Name",
            value = rename.name,
            onValueChange = profiles::onRenameChange,
            onDone = profiles::onRenameDone,
            readOnly = rename.saving,
            minHeight = minHeight,
        )
        rename.problem?.let { FieldProblem(renameProblemText(it, rename.name), Modifier.padding(start = RowInset, top = 6.dp)) }
    }
}

/** Why a name was refused (`.fmsg`): a warning mark and the line. */
@Composable
internal fun FieldProblem(text: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = KeColors.Warn, modifier = Modifier.padding(top = 2.dp, end = 5.dp).size(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.38.em), color = KeColors.Ink2)
    }
}

/** A profile row's hint: a kids profile's allowances (`kp-kids` profiles), anyone else's lock and counts. */
internal fun rowHint(row: ProfileRow): String =
    if (row.profile.isKids) kidsHint(row.contents, row.kid) else profileHint(row.profile.lock, row.contents)

