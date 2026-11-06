package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.RestoreStep
import com.arshad.ayn.feature.settings.spokenList
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.AynMark
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Kicker
import com.arshad.ayn.ui.mobile.common.KickerIcon
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.common.floorGlow
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.settings.CHOSEN_FILE
import com.arshad.ayn.ui.mobile.settings.RESTORE_FAILED_LINE
import com.arshad.ayn.ui.mobile.settings.RESTORE_FAILED_TITLE
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/** First launch (`m6-shell-and-profiles` first 12): create the owner, or restore an Ayn backup. */
@Composable
internal fun WelcomePage(onCreateProfile: () -> Unit, onRestoreBackup: () -> Unit) {
    val amber = ProfileTint.Amber.color
    Column(Modifier.fillMaxSize().floorGlow { amber }.safeDrawingPadding().risingIn()) {
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 28.dp)) {
            Kicker("Welcome") { AynMark(size = 22.dp) }
            Text("Everything Ayn keeps stays on this phone", style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 10.dp))
            Body("Start with your own profile. It becomes the owner, who adds and removes profiles and can reset their locks.")
        }
        Spacer(Modifier.weight(1f))
        FootButtons {
            WideButton("Create my profile", onCreateProfile, icon = R.drawable.ic_user)
            WideButton("Restore an Ayn backup", onRestoreBackup, kind = ButtonKind.Tonal, icon = R.drawable.ic_download)
        }
    }
}

/**
 * Naming the owner (`first` 13): the field opens with the keyboard up and its Done key continues;
 * a tapped colour retints the tile.
 */
@Composable
internal fun NameOwnerPage(
    naming: GateScreen.NameOwner,
    onNameChange: (String) -> Unit,
    onTintChange: (ProfileTint) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    NameProfilePage(
        label = "Your profile · owner",
        name = naming.name,
        tint = naming.tint,
        line = if (naming.nameMissing) {
            "Your profile needs a name first: up to $MAX_PROFILE_NAME characters."
        } else {
            "Up to $MAX_PROFILE_NAME characters. You can lock the profile once it exists."
        },
        refused = naming.nameMissing,
        confirm = "Continue",
        confirmEnabled = !naming.saving,
        actions = NameActions(onNameChange, onTintChange, onContinue, onBack),
    )
}

/** What a profile's name page does: the field's edits, a tapped colour, the button (and the
 *  keyboard's Done key), Back. */
internal class NameActions(
    val onNameChange: (String) -> Unit,
    val onTintChange: (ProfileTint) -> Unit,
    val onConfirm: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * A profile's name page (`first` 13, `m6-settings` 34–35): [label] by the back arrow, the tile,
 * the name's field (opening with the keyboard up) over [line], which marks a [refused] name, the
 * colours, then [confirm]. [barEnd] sits at the bar's far side (a step count).
 */
@Composable
internal fun NameProfilePage(
    label: String,
    name: String,
    tint: ProfileTint,
    line: String,
    refused: Boolean,
    confirm: String,
    confirmEnabled: Boolean,
    actions: NameActions,
    barEnd: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = actions.onBack)
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        BackBar(actions.onBack, label = label, end = barEnd)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)
                .risingIn(),
        ) {
            ProfileTile(name, tint, LargeTile)
            TextField(
                value = name,
                onValueChange = actions.onNameChange,
                label = { Text("Name") },
                supportingText = { Text(line) },
                isError = refused,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { actions.onConfirm() }),
                colors = aynFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp).focusRequester(field),
            )
            TintRow(tint, actions.onTintChange, Modifier.padding(top = 14.dp))
            WideButton(confirm, actions.onConfirm, enabled = confirmEnabled, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

private val SwatchSize = 36.dp

/** The five tints (`.sw5`); the chosen one stands in an ink ring, its name after the row. */
@Composable
private fun TintRow(selected: ProfileTint, onSelect: (ProfileTint) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileTint.entries.forEach { tint -> Swatch(tint, tint == selected) { onSelect(tint) } }
        Text(selected.name, style = MaterialTheme.typography.labelMedium, color = AynColors.Ink2, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Swatch(tint: ProfileTint, selected: Boolean, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, AynMotion.base(), label = "swatch")
    val color = tint.color
    Box(
        Modifier
            .size(SwatchSize)
            .semantics {
                contentDescription = tint.name
                this.selected = selected
            }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .drawBehind {
                val r = size.minDimension / 2
                drawCircle(color, r)
                if (ring > 0f) drawCircle(AynColors.Ink.copy(alpha = ring), r + 4.dp.toPx(), style = Stroke(2.dp.toPx()))
            },
    )
}

/**
 * First launch's restore (`first` 14, 15) for a file picked in the system file picker: while it
 * is read only its name shows; "Restore this backup?" with its profiles, and Choose profiles when
 * there is more than one (Q57); a refusal, an owner-less backup (it can't start this phone) or a
 * failed restore says why. Restore shows its progress on the button and the page takes nothing
 * else meanwhile.
 */
@Composable
internal fun RestorePage(restore: GateScreen.Restore, actions: RestoreActions) {
    val step = restore.step
    FilePage(
        fileName = restore.fileName ?: CHOSEN_FILE,
        onBack = actions.onBack,
        foot = {
            when (step) {
                is RestoreStep.Confirm -> {
                    WideButton("Restore", actions.onRestore, busy = step.restoring)
                    if (step.profiles.size > 1) {
                        WideButton("Choose profiles", actions.onChooseProfiles, kind = ButtonKind.Tonal, enabled = !step.restoring)
                    }
                    WideButton("Choose another file", actions.onChooseFile, kind = ButtonKind.Tonal, enabled = !step.restoring)
                }
                is RestoreStep.Refused, RestoreStep.NoOwner -> {
                    WideButton("Choose another file", actions.onChooseFile)
                    WideButton("Back", actions.onBack, kind = ButtonKind.Tonal)
                }
                RestoreStep.Failed -> {
                    WideButton("Try again", actions.onRestore)
                    WideButton("Choose another file", actions.onChooseFile, kind = ButtonKind.Tonal)
                }
                RestoreStep.Reading, is RestoreStep.Choose, is RestoreStep.Done -> Unit
            }
        },
    ) {
        when (step) {
            is RestoreStep.Confirm -> ConfirmCopy(step.profiles, step.deviceSettings)
            is RestoreStep.Refused -> refusalCopy(step.reason).let { (title, line) -> Statement(title, line) }
            RestoreStep.NoOwner -> Statement("This backup has no owner profile", "Create your profile, then restore it from Settings.")
            RestoreStep.Failed -> Statement(RESTORE_FAILED_TITLE, RESTORE_FAILED_LINE)
            RestoreStep.Reading, is RestoreStep.Choose, is RestoreStep.Done -> Unit
        }
    }
}

/** First launch's restore events, as the gate's ViewModel takes them. */
internal class RestoreActions(
    val onRestore: () -> Unit,
    val onChooseProfiles: () -> Unit,
    val onToggle: (Int) -> Unit,
    val onChooseFile: () -> Unit,
    val onDone: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * A page about one picked file (`first` 14–15, `m6-settings` 45): Back, the file's name, then
 * [content] (what it holds or what's wrong with it), scrolling over [foot]'s stacked buttons.
 */
@Composable
internal fun FilePage(fileName: String, onBack: () -> Unit, foot: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        ) {
            Kicker(fileName) { KickerIcon(R.drawable.ic_file) }
            content()
        }
        FootButtons(foot)
    }
}

@Composable
private fun ConfirmCopy(profiles: List<BackupProfileSummary>, deviceSettings: Boolean) {
    Column(Modifier.risingIn()) {
        Headline("Restore this backup?")
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.forEach { ProfileTile(it.name, it.tint, SmallTile) }
        }
        Body(confirmText(profiles, deviceSettings))
        Row(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .drawBehind { drawRoundRect(AynColors.Surface1, cornerRadius = CornerRadius(14.dp.toPx())) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(lockNote(profiles.size), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodySmall.lineHeight), color = AynColors.Ink2)
        }
    }
}

/** What the backup brings back, by name. */
internal fun confirmText(profiles: List<BackupProfileSummary>, deviceSettings: Boolean): String {
    val brings = if (profiles.size == 1) {
        "It brings back ${profiles.first().name}’s profile with its subscriptions, playlists and history"
    } else {
        "It brings back ${profiles.size} profiles (${spokenList(profiles.map { it.name })}) with their subscriptions, playlists and history"
    }
    return if (deviceSettings) "$brings, and the settings of the device it came from." else "$brings."
}

private fun lockNote(count: Int): String =
    if (count == 1) {
        "Locks aren’t kept in backups: the profile arrives unlocked, so set its lock again."
    } else {
        "Locks aren’t kept in backups: every profile arrives unlocked, so set them again."
    }

/** Why a picked file can't be restored: its heading, then what to do. */
internal fun refusalCopy(reason: BackupRefusal): Pair<String, String> = when (reason) {
    BackupRefusal.NotABackup -> "This file isn’t an Ayn backup" to
        "Ayn restores its own backups, named like Ayn-backup-2026-10-03.json, and KE-backup files from before the rename. " +
        "A YouTube subscriptions file from Google Takeout is imported in Settings once your profile exists."
    BackupRefusal.NewerVersion -> "This backup is from a newer Ayn" to "Update Ayn on this phone, then restore it again."
    BackupRefusal.Damaged -> "This backup is damaged" to
        "It’s an Ayn backup, but parts of it don’t hold together, so nothing was restored. Try another copy."
    BackupRefusal.TooLarge -> "This file is too large to be an Ayn backup" to
        "Ayn backups stay well under 32 MB. Choose a backup file Ayn (or KE) saved."
    BackupRefusal.Unreadable -> "Ayn couldn’t open this file" to "Check that it’s still there, or choose another."
}

/** What's wrong with a file, or how a step ended: [title] over [line]. */
@Composable
internal fun Statement(title: String, line: String) {
    Column(Modifier.risingIn()) {
        Headline(title)
        Body(line)
    }
}

@Composable
internal fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
}

@Composable
internal fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = AynColors.Ink2, modifier = Modifier.padding(top = 14.dp))
}

/** A page's actions at its foot, stacked, the first the strongest. */
@Composable
internal fun FootButtons(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
