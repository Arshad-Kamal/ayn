package com.arshad.ayn.ui.mobile.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.profiles.RestoreStep
import com.arshad.ayn.feature.settings.backupCounts
import com.arshad.ayn.feature.settings.firstRestoredLede
import com.arshad.ayn.feature.settings.restoreButton
import com.arshad.ayn.feature.settings.restoredRows
import com.arshad.ayn.feature.settings.restoredTitle
import com.arshad.ayn.feature.settings.shownSkips
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.BackBar
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.WideButton
import com.arshad.ayn.ui.mobile.menu.RowCheck
import com.arshad.ayn.ui.mobile.settings.FileKicker
import com.arshad.ayn.ui.mobile.settings.PhoneLine
import com.arshad.ayn.ui.mobile.settings.ProfileTags
import com.arshad.ayn.ui.mobile.settings.ResultCards
import com.arshad.ayn.ui.mobile.settings.SetupPage
import com.arshad.ayn.ui.mobile.settings.SetupSide
import com.arshad.ayn.ui.mobile.settings.SkipCards
import com.arshad.ayn.ui.mobile.settings.firstPickLede
import com.arshad.ayn.ui.mobile.settings.firstPickPhoneLine
import com.arshad.ayn.ui.mobile.settings.monthDay
import com.arshad.ayn.ui.mobile.settings.restoredPhoneLine
import com.arshad.ayn.ui.mobile.theme.AynColors

/**
 * First launch's Choose profiles (`m6-settings` 46): a tick per profile in [fileName], the backup's
 * owner always restored (its tick can't be cleared); this phone's settings come back only with
 * every profile. Restore N profiles, or Back to "Restore this backup?".
 */
@Composable
internal fun RestoreChoosePage(fileName: String, step: RestoreStep.Choose, actions: RestoreActions) {
    BackHandler(onBack = actions.onBack)
    val idle = !step.restoring
    SetupPage(
        title = "Restore which profiles?",
        lede = firstPickLede(step.profiles.firstOrNull { it.owner }?.name.orEmpty()),
        bar = { BackBar(actions.onBack, label = fileName, labelIcon = R.drawable.ic_file) },
        foot = {
            WideButton(restoreButton(step.chosen.size), actions.onRestore, busy = step.restoring)
            WideButton("Back", actions.onBack, kind = ButtonKind.Tonal, enabled = idle)
        },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            step.profiles.forEachIndexed { index, profile ->
                PickRow(profile, chosen = index in step.chosen, enabled = idle && !profile.owner) { actions.onToggle(index) }
            }
        }
        if (step.deviceSettings) {
            PhoneLine(firstPickPhoneLine(step.profiles.filterIndexed { index, _ -> index !in step.chosen }.map { it.name }))
        }
    }
}

/** A profile in the backup with its tick; the owner's says it is always restored. */
@Composable
private fun PickRow(profile: BackupProfileSummary, chosen: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(chosen, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = SetupSide, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowCheck(chosen, enabled = !profile.owner)
        ProfileTile(profile.name, profile.tint, PlateTile)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                ProfileTags(profile.owner, profile.kids)
            }
            Text(backupCounts(profile), style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 3.dp))
        }
        if (profile.owner) Text("Always restored", style = MaterialTheme.typography.labelMedium, color = AynColors.Ink2)
    }
}

/**
 * First launch's results (`m6-settings` 47): every profile Added, unlocked; Lock my profile (Choose
 * a lock as a sheet, then the new lock twice: [lockActions]) or Done, which opens the owner (Q54).
 * [profiles] are this phone's, for the cards' looks.
 */
@Composable
internal fun FirstRestoredPage(fileName: String, done: RestoreStep.Done, profiles: List<Profile>, actions: RestoreActions, lockActions: OwnerLockActions) {
    val result = done.result
    val owner = profiles.firstOrNull { it.id == result.ownerId }
    val page = @Composable {
        BackHandler(onBack = actions.onBack)
        val kids = result.profiles.filter { restored -> profiles.any { it.id == restored.profileId && it.isKids } }.map { it.name }
        SetupPage(
            title = restoredTitle(result),
            lede = firstRestoredLede(result, monthDay(done.exportedAtMillis), kids),
            bar = { FileKicker(fileName, if (result.partial) R.drawable.ic_alert else R.drawable.ic_download) },
            foot = {
                // The lock is set on the owner, who may not have loaded yet.
                WideButton("Lock my profile", lockActions.onStart, icon = R.drawable.ic_lock, enabled = owner != null)
                WideButton("Done", actions.onDone, kind = ButtonKind.Tonal)
            },
        ) {
            SkipCards(shownSkips(result, profiles))
            ResultCards(restoredRows(result, profiles, firstLaunch = true))
            PhoneLine(restoredPhoneLine(result, firstLaunch = true))
        }
    }
    if (owner == null) page() else OwnerLockSteps(done.lock, owner, lockActions, page)
}
