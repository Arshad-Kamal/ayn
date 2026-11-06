package com.arshad.ayn.ui.tv.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.store.MAX_PROFILE_NAME
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.PrivateTextInput
import com.arshad.ayn.ui.tv.common.roomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.NeutralSurface
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight

/**
 * First launch (`b3-profiles` welcome): create the owner, or restore a KE backup first. Coming
 * back from a restore ([focusRestore]) lands on Restore a KE backup again.
 */
@Composable
internal fun WelcomeScreen(focusRestore: Boolean, onCreateProfile: () -> Unit, onRestoreBackup: () -> Unit) {
    val first = rememberInitialFocus()
    Box(Modifier.fillMaxSize().controlRoomLight()) {
        ProfileGuided(
            copy = {
                KickerRow("Welcome", Modifier.padding(bottom = 11.dp)) { KMark() }
                GuidedTitle("Everything Ayn keeps stays on this Shield", maxWidth = 450.dp)
                GuidedBody("Start with your own profile. It becomes the owner, who adds and removes profiles and can reset their locks.")
            },
            actions = {
                val create = if (focusRestore) Modifier else Modifier.focusRequester(first)
                val restore = if (focusRestore) Modifier.focusRequester(first) else Modifier
                GuidedButton(R.drawable.ic_channel, "Create my profile", ControlStyle.Primary, onCreateProfile, create)
                GuidedButton(R.drawable.ic_download, "Restore an Ayn backup", ControlStyle.Secondary, onRestoreBackup, restore)
            },
        )
    }
}

/**
 * Naming the owner (`b3-profiles` name): the system keyboard types the name (the field starts
 * focused while it is empty; its Done key moves on to Continue), the arrows pick a tint.
 */
@Composable
internal fun NameOwnerScreen(
    naming: GateScreen.NameOwner,
    onNameChange: (String) -> Unit,
    onTintChange: (ProfileTint) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val field = remember { FocusRequester() }
    val proceed = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val startsOnField = remember { naming.name.isEmpty() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        (if (startsOnField) field else proceed).requestFocus()
    }
    BackHandler(onBack = onBack)
    val tint = naming.tint.color
    Box(Modifier.fillMaxSize().roomLight({ tint }, alpha = { 0.24f }, x = { 0.3f })) {
        ProfileGuided(
            copy = {
                KickerRow("Your profile · owner") { KickerIcon(R.drawable.ic_channel) }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                ) {
                    ProfilePlate(naming.name, naming.tint, MediumPlate)
                    GuidedField(
                        value = naming.name,
                        onValueChange = onNameChange,
                        onDone = { proceed.requestFocus() },
                        onDown = { focusManager.moveFocus(FocusDirection.Down) },
                        modifier = Modifier.focusRequester(field),
                    )
                }
                TintRow(naming.tint, onTintChange, Modifier.padding(top = 17.dp, start = 83.dp))
                GuidedBody(
                    if (naming.nameMissing) {
                        "Your profile needs a name first: up to $MAX_PROFILE_NAME characters."
                    } else {
                        "Up to $MAX_PROFILE_NAME characters. Choose a colour with the arrows; you can lock the profile once it exists."
                    },
                    color = if (naming.nameMissing) KeColors.Ink else KeColors.Ink2,
                )
            },
            actions = {
                GuidedButton(R.drawable.ic_check, "Continue", ControlStyle.Primary, onContinue, Modifier.focusRequester(proceed))
                GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, onBack)
            },
        )
    }
}

/** Why a picked file can't be restored (`b3-profiles` refused): its heading, then what to do. */
internal fun refusalCopy(reason: BackupRefusal): Pair<String, String> = when (reason) {
    BackupRefusal.NotABackup -> "This file isn’t an Ayn backup" to
        "Ayn restores its own backups, named like Ayn-backup-2026-10-03.json, and KE-backup files from before the " +
        "rename. A YouTube subscriptions " +
        "file from Google Takeout is imported in Settings once your profile exists."
    BackupRefusal.NewerVersion -> "This backup is from a newer Ayn" to "Update Ayn on this Shield, then restore it again."
    BackupRefusal.Damaged -> "This backup is damaged" to
        "It’s an Ayn backup, but parts of it don’t hold together, so nothing was restored. Try another copy."
    BackupRefusal.TooLarge -> "This file is too large to be an Ayn backup" to
        "Ayn backups stay well under 32 MB. Choose a backup file Ayn (or KE) saved."
    BackupRefusal.Unreadable -> "Ayn couldn’t open this file" to "Check that it’s still there, or choose another."
}

/** The room under a guided screen whose actions hold focus: the controls' warm white, low under the action column. */
internal fun Modifier.controlRoomLight(): Modifier =
    roomLight({ KeColors.ControlLight }, alpha = { ControlLightAlpha }, x = { ActionsLightX })

private const val ControlLightAlpha = 0.12f
private const val ActionsLightX = 0.8f

private val FieldShape = RoundedCornerShape(13.dp)

/** A guided screen's field (`.field`, 600 × 104 px; [width] wide: a name, an address, a passphrase): a well that rims in ink and grows a little
 *  when focused, its rim [warn] amber when what it holds was refused (`.field.bad`). The keyboard
 *  neither corrects nor learns what is typed; a [secret] (a passphrase) shows as dots and asks
 *  for the keyboard's password layout, [keyboardType] another layout (an address). */
@Composable
internal fun GuidedField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    warn: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "field")
    val warning = animateFloatAsState(if (warn) 1f else 0f, KeMotion.fast(), label = "fieldWarn")
    val style = MaterialTheme.typography.titleLarge.atWeight(640).copy(fontSize = 26.sp, color = KeColors.Ink)
    PrivateTextInput {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .width(width)
                .height(52.dp)
                .graphicsLayer {
                    val scale = 1f + 0.02f * progress.value
                    scaleX = scale
                    scaleY = scale
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) keyboard?.show()
                }
                // A field holding text keeps DOWN for its cursor: DOWN leaves it.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        onDown()
                        true
                    } else {
                        false
                    }
                },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(KeColors.Ink),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                autoCorrectEnabled = false,
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onDone()
            }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawInflated(FieldShape, 0.dp, KeColors.Well, 1f) }
                        .border(
                            width = 1.dp + 0.5.dp * maxOf(progress.value, warning.value),
                            color = lerp(lerp(KeColors.Ink.copy(alpha = 0.22f), KeColors.Ink, progress.value), KeColors.Warn, warning.value),
                            shape = FieldShape,
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
    }
}

private val SwatchSize = 26.dp
private val SwatchShape = RoundedCornerShape(7.5.dp)

/**
 * The tints (`.tints`): focus moving along the row picks the tint (the arrows choose), and
 * entering the row lands on the one picked. The picked tint keeps a soft ring; the focused one
 * grows inside an ink ring. Its name follows the row.
 */
@Composable
internal fun TintRow(selected: ProfileTint, onSelect: (ProfileTint) -> Unit, modifier: Modifier = Modifier) {
    val requesters = remember { ProfileTint.entries.associateWith { FocusRequester() } }
    Row(
        modifier = modifier.focusProperties { onEnter = { requesters.getValue(selected).requestFocus() } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileTint.entries.forEach { tint ->
            TintSwatch(
                tint = tint,
                selected = tint == selected,
                onPick = { onSelect(tint) },
                modifier = Modifier.focusRequester(requesters.getValue(tint)),
            )
        }
        Text(
            text = selected.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp),
            color = KeColors.Ink2,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun TintSwatch(tint: ProfileTint, selected: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "swatch")
    val ring = animateFloatAsState(if (selected || focused) 1f else 0f, KeMotion.fast(), label = "picked")
    val color = tint.color
    NeutralSurface(
        onClick = onPick,
        shape = SwatchShape,
        modifier = modifier
            .size(SwatchSize)
            .semantics { contentDescription = tint.label }
            .graphicsLayer {
                val scale = 1f + 0.14f * progress.value
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                drawInflated(SwatchShape, 3.dp, lerp(KeColors.Ink2, KeColors.Ink, progress.value), ring.value)
                drawInflated(SwatchShape, 1.5.dp, KeColors.Ground, ring.value)
                drawInflated(SwatchShape, 0.dp, color, 1f)
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onPick()
            },
    ) {}
}
