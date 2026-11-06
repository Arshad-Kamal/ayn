package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Kicker
import com.arshad.ayn.ui.mobile.common.KickerIcon
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.common.floorGlow
import com.arshad.ayn.ui.mobile.common.risingIn
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.settings.ChooseLockSheet
import com.arshad.ayn.ui.mobile.settings.newLockCopy

/** What the owner-lock pages do: Lock my profile, a lock's kind picked, Back (the page's), a
 *  swiped Choose a lock, and the new lock's pad. */
internal class OwnerLockActions(
    val onStart: () -> Unit,
    val onKind: (LockKind) -> Unit,
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
    val entry: EntryActions,
)

/**
 * Lock your profile first (`m6-kids` 21, KP-D14) while the [owner] has no lock: why a kids profile
 * needs it, [kicker] naming what waits for it and [then] ending the body, over a floor lit [tint].
 * Lock my profile opens Choose a lock as a sheet over it, then the new lock is entered twice
 * ([step]).
 */
@Composable
internal fun OwnerLockFirstPages(step: OwnerLockStep, owner: Profile, kicker: String, then: String, tint: Color, actions: OwnerLockActions) {
    OwnerLockSteps(step, owner, actions) { OwnerLockFirstPage(kicker, then, tint, actions) }
}

/**
 * Setting the [owner]'s lock over [page] ([step] `null`: the page alone): Choose a lock as a sheet
 * over it, then the new lock entered twice in its place.
 */
@Composable
internal fun OwnerLockSteps(step: OwnerLockStep?, owner: Profile, actions: OwnerLockActions, page: @Composable () -> Unit) {
    if (step is OwnerLockStep.NewLock) {
        LockEntryPage(owner, step.kind, step.entry, newLockCopy(step.kind, step.entry), actions.entry)
    } else {
        page()
    }
    StageSheet(step.takeIf { it == OwnerLockStep.ChooseKind }, onDismiss = actions.onDismiss, onBack = actions.onBack, stageKey = { it }) {
        ChooseLockSheet("Lock your profile", marked = LockKind.Sequence, onKind = actions.onKind)
    }
}

@Composable
private fun OwnerLockFirstPage(kicker: String, then: String, tint: Color, actions: OwnerLockActions) {
    BackHandler(onBack = actions.onBack)
    Column(Modifier.fillMaxSize().floorGlow { tint }.safeDrawingPadding()) {
        BackBar(actions.onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 24.dp)
                .risingIn(),
        ) {
            Kicker(kicker) { KickerIcon(R.drawable.ic_lock) }
            Headline("Lock your profile first")
            Body("Only your lock takes a child out of a kids profile. Without it, anyone could leave and open the other profiles. $then")
        }
        FootButtons {
            WideButton("Lock my profile", actions.onStart, icon = R.drawable.ic_lock)
            WideButton("Back", actions.onBack, kind = ButtonKind.Tonal)
        }
    }
}
