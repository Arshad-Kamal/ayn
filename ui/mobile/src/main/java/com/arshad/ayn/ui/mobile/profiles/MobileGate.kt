package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.media3.common.Player
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.RestoreStep
import com.arshad.ayn.ui.mobile.settings.CHOSEN_FILE
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynColors

/**
 * The root gate on the phone (M5-D16, `m6-shell-and-profiles`): first launch and its restore, the
 * picker, an unlock, or the open profile's [shell]. The shell is composed only while a profile is
 * open. Each opening keeps its saved state (tab stacks) under one key and its ViewModels in the
 * gate's [ProfileGateViewModel.shellViewModels]: reopening the same profile restores them,
 * opening another drops them, as on TV. [chooseFile] opens the system file picker, whose pick
 * reaches the gate's ViewModel. Restore's Choose profiles and results draw with Settings' backup
 * kit. A kids profile's gate screens are the owner's lock first (`m6-kids` 21) and Time's up
 * (9–12), over the picture of the video the shell last reported. [kept] is the phone's controller: while it holds
 * the video [GateUiState.keptFor] names, the picker and that profile's unlock show it (M6-D8).
 */
@Composable
internal fun MobileGate(
    gate: ProfileGateViewModel,
    chooseFile: (PickRequest) -> Unit,
    modifier: Modifier = Modifier,
    kept: Player? = null,
    shell: @Composable (ShellOpening) -> Unit,
) {
    val state by gate.uiState.collectAsStateWithLifecycle()
    val settingsPick by gate.settingsPick.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    // Every opening's key this activity has used, kept across process death so an old opening's
    // saved state can't outlive it.
    val usedShellKeys = rememberSaveable { arrayListOf<String>() }
    val activity = LocalActivity.current
    val entry = remember(gate) { gate.entryActions(gate::onUnlockLeave) }
    val restore = remember(gate, chooseFile) {
        RestoreActions(
            onRestore = gate::onRestore,
            onChooseProfiles = gate::onChooseProfiles,
            onToggle = gate::onToggleRestoreProfile,
            onChooseFile = { chooseFile(PickRequest(PickKind.Restore, profileId = null)) },
            onDone = gate::onRestoreDone,
            onBack = gate::onFirstLaunchBack,
        )
    }
    val restoreLock = remember(gate) {
        OwnerLockActions(
            onStart = gate::onLockMyProfile,
            onKind = gate::onOwnerLockKind,
            onBack = gate::onFirstLaunchBack,
            onDismiss = gate::onFirstLaunchBack,
            entry = gate.entryActions(gate::onRestoreLockLeave),
        )
    }

    val stoppedActions = remember(gate, activity) {
        TimeStoppedActions(
            onClose = { activity?.finish() },
            onMoreTime = gate::onMoreTime,
            onSwitchProfile = gate::onSwitchProfile,
            onPick = gate::onMoreTimePick,
            onBack = gate::onStoppedBack,
            entry = gate.entryActions(gate::onStoppedLeave),
        )
    }
    val kidLock = remember(gate) {
        OwnerLockActions(
            onStart = gate::onOwnerLockStart,
            onKind = gate::onOwnerLockKind,
            onBack = gate::onOwnerLockBack,
            onDismiss = gate::onOwnerLockBack,
            entry = gate.entryActions(gate::onOwnerLockLeave),
        )
    }
    val picture = rememberSaveable(saver = ShellPicture.Saver) { ShellPicture() }
    val deviceOwner = state.profiles.firstOrNull { it.isOwner }

    val held = kept?.let { rememberHeld(it) }
    Box(modifier.fillMaxSize().background(AynColors.Ground)) {
        when (val screen = state.screen) {
            GateScreen.Welcome -> WelcomePage(onCreateProfile = gate::onCreateProfile, onRestoreBackup = restore.onChooseFile)
            is GateScreen.NameOwner -> NameOwnerPage(
                naming = screen,
                onNameChange = gate::onNameChange,
                onTintChange = gate::onTintChange,
                onContinue = gate::onCreateOwner,
                onBack = gate::onFirstLaunchBack,
            )
            is GateScreen.Restore -> when (val step = screen.step) {
                is RestoreStep.Choose -> RestoreChoosePage(screen.fileName ?: CHOSEN_FILE, step, restore)
                is RestoreStep.Done -> FirstRestoredPage(screen.fileName ?: CHOSEN_FILE, step, state.profiles, restore, restoreLock)
                RestoreStep.Reading, is RestoreStep.Confirm, is RestoreStep.Refused, RestoreStep.NoOwner, RestoreStep.Failed ->
                    RestorePage(screen, restore)
            }
            is GateScreen.Picker -> key(screen) {
                PickerPage(screen, state.profiles, onPick = gate::onPick, onBack = { gate.onPickerBack() }) {
                    val keptFor = state.keptFor
                    if (kept != null && keptFor != null && held != null) {
                        PickerKeptNote(kept, held, keptFor.name, locked = keptFor.lock != null, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp))
                    }
                }
            }
            is GateScreen.Unlock -> key(screen.profile.id) {
                UnlockPage(screen, entry) {
                    if (kept != null && held != null && state.keptFor?.id == screen.profile.id) {
                        KeptStrip(kept, held, "Still playing · background audio", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp))
                    }
                }
            }
            is GateScreen.Open -> {
                // The profile is part of the key: after the process is recreated the count
                // restarts, and a restored key must never hand one profile another's stacks.
                val shellKey = "${screen.profile.id}:${state.shellKey}"
                LaunchedEffect(shellKey) {
                    usedShellKeys.filter { it != shellKey }.forEach(saved::removeState)
                    usedShellKeys.clear()
                    usedShellKeys += shellKey
                }
                saved.SaveableStateProvider(shellKey) {
                    val owner = remember(shellKey) {
                        object : ViewModelStoreOwner {
                            override val viewModelStore: ViewModelStore = gate.shellViewModels
                        }
                    }
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        shell(
                            ShellOpening(
                                screen.profile,
                                onSwitchProfile = gate::onRailProfile,
                                restored = state.restored,
                                settingsPickWaiting = settingsPick != null,
                                onRestoredShown = gate::onRestoredShown,
                                owner = deviceOwner,
                                onLeaveKids = gate::onLeaveKids,
                                onPicture = { picture.report(screen.profile.id, it) },
                            ),
                        )
                    }
                }
            }
            is GateScreen.TimeStopped -> TimeStoppedPages(screen, deviceOwner, picture.of(screen.kid.id), stoppedActions)
            is GateScreen.OwnerLockNeeded -> {
                if (deviceOwner != null) {
                    val kid = screen.kid.name
                    OwnerLockFirstPages(
                        step = screen.step,
                        owner = deviceOwner,
                        kicker = "$kid’s kids profile",
                        then = "Lock your profile, then $kid’s profile opens.",
                        tint = screen.kid.tint.color,
                        actions = kidLock,
                    )
                }
            }
            GateScreen.Loading -> Unit
        }
    }
}

/** The picture the shell last reported and whose shell reported it, kept after the shell closes
 *  (and across recreation) for that kid's Time's up (KP-D26). Read when Time's up composes, which
 *  only a gate change brings; nothing observes it. */
private class ShellPicture(private var profileId: Long? = null, private var url: String? = null) {
    fun report(profileId: Long, url: String?) {
        this.profileId = profileId
        this.url = url
    }

    /** [kidId]'s picture; none when another profile's shell reported last. */
    fun of(kidId: Long): String? = url.takeIf { profileId == kidId }

    companion object {
        val Saver: Saver<ShellPicture, Any> = listSaver(save = { listOf(it.profileId, it.url) }, restore = { ShellPicture(it[0] as Long?, it[1] as String?) })
    }
}

/** The pads' events on the gate; only where Back goes differs. */
private fun ProfileGateViewModel.entryActions(onBack: () -> Unit): EntryActions = EntryActions(
    onPress = ::onPress,
    onSubmitSequence = ::onSubmitSequence,
    onDigit = ::onDigit,
    onErase = ::onErase,
    onBack = onBack,
    onRejectionShown = ::onRejectionShown,
)
