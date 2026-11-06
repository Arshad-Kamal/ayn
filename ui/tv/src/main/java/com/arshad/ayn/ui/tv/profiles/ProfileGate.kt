package com.arshad.ayn.ui.tv.profiles

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.ui.tv.focus.releasesStayWith
import com.arshad.ayn.ui.tv.theme.KeColors

/**
 * The root gate (M5-D16): first launch and its restore, the picker, an unlock screen, the owner's
 * lock a kids profile waits for (KP-D14), Time's up (KP-D8), or the open profile's [shell]. The shell is composed only while a profile is open, so nothing of it draws or takes
 * focus under the gate. Each opening keeps its saved state (back stack, focus) under one key and
 * its ViewModels in the gate's [ProfileGateViewModel.shellViewModels]: reopening the same profile
 * restores them; opening another profile drops them (Home, cleared back stack), the ViewModels
 * before the gate selects that profile. Screens switch without a cross-fade, so a
 * re-lock never shows the last screen fading out; each gate screen rises in on its own (D8).
 * A key's release belongs to the screen its press reached: the OK that submits an unlock or a
 * pick must not click what the next screen focuses, so a release whose press came before the
 * screen changed is dropped. A shell opened by a Settings restore gets what was restored once
 * ([ShellOpening.restored]) and reports it shown. [chooseFile] opens the file picker,
 * whose pick reaches the gate's ViewModel. A kids profile whose time stopped shows Time's up over
 * the picture its player last showed, whose OK closes Ayn (KP-D26).
 */
@Composable
internal fun ProfileGate(
    gate: ProfileGateViewModel,
    chooseFile: (PickRequest) -> Unit,
    modifier: Modifier = Modifier,
    shell: @Composable (opening: ShellOpening) -> Unit,
) {
    val state by gate.uiState.collectAsStateWithLifecycle()
    val settingsPick by gate.settingsPick.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    val chooseRestore = { chooseFile(PickRequest(PickKind.Restore, profileId = null)) }
    // Time's up's steps are one screen class: the OK that submits More time's lock must not pick an amount.
    val screenIdentity = state.screen.let { screen ->
        Triple(screen::class, (screen as? GateScreen.Open)?.profile?.id, (screen as? GateScreen.TimeStopped)?.step?.let { it::class })
    }
    // Every opening's key this activity has used, kept across process death so an old opening's
    // saved state can't outlive it.
    val usedShellKeys = rememberSaveable { arrayListOf<String>() }
    var restoreChosen by rememberSaveable { mutableStateOf(false) }
    val shellPicture = remember { ShellPicture() }
    val activity = LocalActivity.current

    Box(
        modifier
            .fillMaxSize()
            .background(KeColors.Ground)
            .releasesStayWith(screenIdentity),
    ) {
        when (val screen = state.screen) {
            GateScreen.Loading -> Unit
            GateScreen.Welcome -> WelcomeScreen(
                focusRestore = restoreChosen,
                onCreateProfile = {
                    restoreChosen = false
                    gate.onCreateProfile()
                },
                onRestoreBackup = {
                    restoreChosen = true
                    chooseRestore()
                },
            )
            is GateScreen.NameOwner -> NameOwnerScreen(
                naming = screen,
                onNameChange = gate::onNameChange,
                onTintChange = gate::onTintChange,
                onContinue = gate::onCreateOwner,
                onBack = gate::onFirstLaunchBack,
            )
            is GateScreen.Restore -> RestoreScreen(
                restore = screen,
                profiles = state.profiles,
                actions = remember(gate) {
                    RestoreActions(
                        onRestore = gate::onRestore,
                        onChooseProfiles = gate::onChooseProfiles,
                        onToggle = gate::onToggleRestoreProfile,
                        onChooseFile = chooseRestore,
                        onDone = gate::onRestoreDone,
                        onLockMyProfile = gate::onLockMyProfile,
                        onLockKind = gate::onOwnerLockKind,
                        onBack = gate::onFirstLaunchBack,
                        entry = entryActions(gate, onBack = gate::onFirstLaunchBack),
                    )
                },
            )
            is GateScreen.OwnerLockNeeded -> OwnerLockNeededScreen(
                needed = screen,
                owner = state.profiles.firstOrNull { it.isOwner },
                actions = remember(gate) {
                    OwnerLockActions(
                        onStart = gate::onOwnerLockStart,
                        onKind = gate::onOwnerLockKind,
                        onBack = gate::onOwnerLockBack,
                        entry = entryActions(gate, onBack = gate::onOwnerLockBack),
                    )
                },
            )
            is GateScreen.Picker -> key(screen) {
                PickerScreen(screen, state.profiles, onPick = gate::onPick, onBack = { gate.onPickerBack() })
            }
            is GateScreen.Unlock -> key(screen.profile.id) {
                UnlockScreen(unlock = screen, actions = remember(gate) { entryActions(gate, onBack = gate::onUnlockBack) })
            }
            is GateScreen.TimeStopped -> TimeStoppedScreen(
                stopped = screen,
                owner = state.profiles.firstOrNull { it.isOwner },
                picture = shellPicture.url,
                actions = remember(gate, activity) {
                    TimeStoppedActions(
                        onClose = { activity?.finish() },
                        onMoreTime = gate::onMoreTime,
                        onSwitchProfile = gate::onSwitchProfile,
                        onPick = gate::onMoreTimePick,
                        onBack = gate::onStoppedBack,
                        entry = entryActions(gate, onBack = gate::onStoppedBack),
                    )
                },
            )
            is GateScreen.Open -> {
                // The profile is part of the key: after the process is recreated the count
                // restarts, and a restored key must never hand one profile another's back stack.
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
                                profile = screen.profile,
                                restored = state.restored,
                                settingsPickWaiting = settingsPick != null,
                                onRestoredShown = gate::onRestoredShown,
                                onRailProfile = gate::onRailProfile,
                                onLeaveKids = gate::onLeaveKids,
                                onPicture = { shellPicture.url = it },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** The gate's lock entry, whose BACK goes to [onBack]. */
private fun entryActions(gate: ProfileGateViewModel, onBack: () -> Unit): UnlockActions = UnlockActions(
    onPress = gate::onPress,
    onSubmitSequence = gate::onSubmitSequence,
    onDigit = gate::onDigit,
    onErase = gate::onErase,
    onBack = onBack,
    onRejectionShown = gate::onRejectionShown,
)

/** The open [profile]'s shell: [restored] is set when a Settings restore opened it (the shell
 *  starts on Settings › Backup & restore with its results, then calls [onRestoredShown], Q9);
 *  [settingsPickWaiting] while a file picked for its Settings waits there (M5-D20); the rail's
 *  profile slot calls [onRailProfile]; a kids profile's Parent page leaves it through
 *  [onLeaveKids] with the owner's pass it holds; [onPicture] gets the player's picture as it
 *  changes (`null` off the player), for Time's up. */
internal class ShellOpening(
    val profile: Profile,
    val restored: RestoredBackup?,
    val settingsPickWaiting: Boolean,
    val onRestoredShown: () -> Unit,
    val onRailProfile: () -> Unit,
    val onLeaveKids: (OwnerPass) -> Unit = {},
    val onPicture: (String?) -> Unit = {},
)

/** The picture the shell last reported, kept after it closes for Time's up (KP-D26). Read when
 *  Time's up composes, which only a gate change brings; nothing observes it. */
private class ShellPicture {
    var url: String? = null
}
