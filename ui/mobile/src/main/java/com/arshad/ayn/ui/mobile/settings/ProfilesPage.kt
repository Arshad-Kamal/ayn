package com.arshad.ayn.ui.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.KidsAddScreen
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.ProfileRow
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.profilesCountText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.SmallTile
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/**
 * Settings › Profiles (`m6-settings` 25–36): the owner's list (every profile, theirs marked Owner
 * and You, Add a profile while there is room, under a warning while the owner has no lock); anyone
 * else's page holds their own Name, Colour and Lock. Each profile's sheet, the lock pages and the
 * new profile's page open over it. [onRestrictions] opens another profile's restrictions;
 * [kidsAdd] adds a kids profile over the page, which then says so and lights its row.
 */
@Composable
internal fun ProfilesPage(profiles: ProfilesViewModel, kidsAdd: KidsAddViewModel, onRestrictions: (Long) -> Unit, onBack: () -> Unit) {
    val state by profiles.uiState.collectAsStateWithLifecycle()
    val kidsState by kidsAdd.uiState.collectAsStateWithLifecycle()
    val me = state.me
    val screen = state.screen
    val renaming = screen as? ProfilesScreen.Rename
    var added by remember { mutableStateOf<AddedKid?>(null) }
    LaunchedEffect(kidsState.added) {
        val id = kidsState.added ?: return@LaunchedEffect
        added = AddedKid(id, kidsState.draft.name.trim())
        profiles.onKidAdded()
        kidsAdd.onAddedShown()
    }
    // A name edited on the page: Back keeps the old one before it leaves.
    BackHandler(enabled = me?.isOwner == false && renaming != null, onBack = profiles::onBack)
    Box(Modifier.fillMaxSize()) {
        SettingsFrame(SettingsPage.Profiles.title(owner = me?.isOwner == true), onBack) {
            if (me == null) return@SettingsFrame
            if (me.isOwner) {
                ownersRows(state, me, profiles, lit = added?.id)
            } else {
                item(key = "lede") { PageLede(guestLede(state.ownerName)) }
                item(key = "own") { Column { OwnRows(me, renaming, profiles, onPage = true) } }
            }
        }
        // A lock saved or removed lands on your own rows: the owner's are in their sheet, which
        // shows the notice instead.
        NoticeSnackbar(
            state.toast?.takeIf { me?.isOwner == false },
            ::profilesToastLine,
            onClose = profiles::onToastShown,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        NoticeSnackbar(
            added,
            { kidAddedLine(it.name) },
            onClose = { added = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (me != null) {
        ProfilesSheets(
            state,
            me,
            profiles,
            onRestrictions = { id ->
                profiles.onDismiss()
                onRestrictions(id)
            },
            onKidsProfile = kidsAdd::onStart,
            // Until the page takes the new profile, the kind sheet under the flow stays closed.
            covered = kidsState.screen != KidsAddScreen.Closed || kidsState.added != null,
        )
        ProfilesCover(state, me, profiles)
        KidsAddCover(kidsState, kidsAdd)
    }
}

/** A kids profile just added: its row is lit while the page says so. */
private class AddedKid(val id: Long, val name: String)

private fun LazyListScope.ownersRows(state: ProfilesUiState, me: Profile, profiles: ProfilesViewModel, lit: Long?) {
    item(key = "lede") { PageLede(OWNER_LEDE) }
    if (state.ownerHasNoLock) {
        item(key = "warn") {
            Column {
                SettingsNotice("Your profile isn’t locked.", "Anyone can lock you out, or reset or remove other profiles.")
                SettingRow(
                    "Lock your profile",
                    onClick = { profiles.onOpenProfile(me.id) },
                    hint = "A sequence or a PIN",
                    lead = { RowIcon(R.drawable.ic_lock) },
                )
            }
        }
    }
    items(state.rows, key = { it.profile.id }) { row -> ProfileListRow(row, isMe = row.profile.id == me.id, lit = row.profile.id == lit, profiles) }
    if (state.canAdd) {
        item(key = "add") {
            SettingRow("Add a profile", profiles::onAddProfile, hint = profilesCountText(state.rows.size), lead = { AddPlate() })
        }
    }
}

/** A profile in the owner's list: its tile, name and tags, its lock and what it keeps; [lit] rests
 *  on a lit fill (it fades). */
@Composable
private fun ProfileListRow(row: ProfileRow, isMe: Boolean, lit: Boolean, profiles: ProfilesViewModel) {
    val profile = row.profile
    val fill by animateColorAsState(if (lit) AynColors.RowLit else Color.Transparent, AynMotion.base(), label = "addedRow")
    val tags: (@Composable () -> Unit)? = if (profile.isOwner || profile.isKids) {
        {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (profile.isOwner) SettingsTag("Owner")
                if (profile.isKids) SettingsTag("Kids")
            }
        }
    } else {
        null
    }
    SettingRow(
        profile.name,
        onClick = { profiles.onOpenProfile(profile.id) },
        modifier = Modifier.drawBehind { drawRect(fill) },
        hint = rowHint(row),
        value = if (isMe) "You" else null,
        lead = { ProfileTile(profile.name, profile.tint, SmallTile) },
        labelTrail = tags,
    )
}

/** Add a profile's mark (`.pplus`): a plus in an outlined tile. */
@Composable
private fun AddPlate() {
    Box(Modifier.size(40.dp).border(1.5.dp, AynColors.Ink3, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(20.dp))
    }
}

/**
 * Your own rows (a non-owner's page, the owner's sheet, state 27): Name (tapped, it turns into its
 * field with the keyboard up, as a playlist's does; the ✓ key saves), Colour and Lock, dimmed
 * while the name is edited.
 */
@Composable
internal fun OwnRows(me: Profile, rename: ProfilesScreen.Rename?, profiles: ProfilesViewModel, onPage: Boolean) {
    if (rename != null) {
        RenameField(rename, profiles)
    } else {
        OwnRow("Name", me.name, R.drawable.ic_edit, chevron = false, onPage = onPage, dimmed = false, onClick = profiles::onRename)
    }
    OwnRow("Colour", me.tint.name, R.drawable.ic_interface, chevron = true, onPage = onPage, dimmed = rename != null, onClick = profiles::onColour)
    OwnRow("Lock", lockValue(me.lock), R.drawable.ic_lock, chevron = true, onPage = onPage, dimmed = rename != null, onClick = profiles::onLock)
}

@Composable
private fun OwnRow(label: String, value: String, icon: Int, chevron: Boolean, onPage: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    if (onPage) {
        Dimmed(lit = !dimmed) {
            SettingRow(label, onClick, hint = value, chevron = chevron, enabled = !dimmed, lead = { RowIcon(icon) })
        }
    } else {
        SheetRow(label, if (dimmed) Modifier else tap(onClick), hint = value, icon = icon, chevron = chevron, dimmed = dimmed)
    }
}

/** Your name being edited, the cursor at its end; a refused name says why under the field. */
@Composable
private fun RenameField(rename: ProfilesScreen.Rename, profiles: ProfilesViewModel) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    // The field keeps its own selection (the cursor starts at the end); the text is the
    // ViewModel's, which can refuse an edit (too long, a save under way).
    var value by remember { mutableStateOf(TextFieldValue(rename.name, TextRange(rename.name.length))) }
    val shown = if (value.text == rename.name) value else TextFieldValue(rename.name, TextRange(rename.name.length))
    val problem = rename.problem
    TextField(
        value = shown,
        onValueChange = { typed ->
            value = typed
            profiles.onRenameChange(typed.text)
        },
        label = { Text("Name") },
        singleLine = true,
        readOnly = rename.saving,
        isError = problem != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { profiles.onRenameDone() }),
        supportingText = {
            Text(
                problem?.let { renameProblemText(it, rename.name) }
                    ?: "Up to $MAX_PROFILE_NAME characters, and not a name another profile has. The ✓ key saves; Back keeps the old name.",
            )
        },
        colors = aynFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(field),
    )
}
