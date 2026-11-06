package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.settings.DEVICES_HEADING
import com.arshad.ayn.feature.settings.LINK_AGAIN
import com.arshad.ayn.feature.settings.LINK_AGAIN_HINT
import com.arshad.ayn.feature.settings.SIGN_IN_AGAIN
import com.arshad.ayn.feature.settings.SYNC_NOW
import com.arshad.ayn.feature.settings.SYNC_NOW_HINT
import com.arshad.ayn.feature.settings.SYNC_OWNER_ONLY
import com.arshad.ayn.feature.settings.SyncFix
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncUiState
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.settings.TURN_OFF_HINT
import com.arshad.ayn.feature.settings.TURN_ON_BUTTON
import com.arshad.ayn.feature.settings.accountHint
import com.arshad.ayn.feature.settings.accountTitle
import com.arshad.ayn.feature.settings.deviceHint
import com.arshad.ayn.feature.settings.soloNote
import com.arshad.ayn.feature.settings.syncFailureNotice
import com.arshad.ayn.feature.settings.syncFix
import com.arshad.ayn.feature.settings.syncKeptNote
import com.arshad.ayn.feature.settings.syncOffLede
import com.arshad.ayn.feature.settings.syncStatusLine
import com.arshad.ayn.feature.settings.syncingHint
import com.arshad.ayn.feature.settings.thisDeviceHint
import com.arshad.ayn.feature.settings.turnOffTitle
import com.arshad.ayn.feature.settings.turnOnHint
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.player.Spinner
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.atWeight

// Focus keys of Settings › Sync's rows (ScreenFocus memory).
private const val SYNC_TURN_ON_KEY = "sync-turn-on"
private const val FIX_KEY = "sync-fix"
private const val SYNC_NOW_KEY = "sync-now"
private const val TURN_OFF_KEY = "sync-turn-off"

/**
 * Settings › Sync (`m8-sync-tv` off, synced, syncing, error): off, Turn on sync with what stays on
 * each device; on, the status line, a failure's notice and its fix (Sign in again, Link again)
 * first, Sync now, the service, the devices and Turn off. Only the owner gets the rows that act
 * (M8-D9): anyone else reads the state and who sets sync up (M8-D11).
 */
@Composable
internal fun SyncPane(state: SyncUiState, sync: SyncViewModel, time: SyncTime) {
    val words = TvSyncWords
    val status = state.status
    if (state.off) {
        SettingsPane("Sync", syncOffLede(words)) {
            if (state.isOwner) {
                SettingRow(
                    label = TURN_ON_BUTTON,
                    hint = turnOnHint(state.drive),
                    chevron = true,
                    onClick = sync::onTurnOn,
                    modifier = Modifier.focusMemory(SYNC_TURN_ON_KEY),
                    lead = { RowIcon(R.drawable.ic_cloud, it) },
                )
            }
            PaneNote(syncKeptNote(words, DEVICE_SETTINGS))
        }
        return
    }
    SettingsPane("Sync", sub = null) {
        StatusLine(status, time)
        status.failure?.let { failure ->
            val (lead, text) = syncFailureNotice(failure, status.account, words)
            Notice(lead, text, Modifier.padding(start = RowInset, top = 4.dp, bottom = 8.dp))
        }
        if (state.isOwner) {
            FixRow(syncFix(status.failure), status.account, sync)
            SettingRow(
                label = SYNC_NOW,
                hint = if (status.syncing) syncingHint(words, status.devices) else SYNC_NOW_HINT,
                onClick = sync::onSyncNow,
                modifier = Modifier.focusMemory(SYNC_NOW_KEY),
                lead = { RowIcon(R.drawable.ic_retry, it) },
            )
        }
        status.account?.let { account ->
            SettingRow(
                label = accountTitle(account),
                hint = accountHint(account, status.folderBytes),
                focusable = false,
                onClick = {},
                lead = { RowIcon(account.service.icon, it) },
            )
        }
        PaneSubHeading(DEVICES_HEADING)
        SettingRow(
            label = words.hereStart,
            hint = thisDeviceHint(status, time),
            focusable = false,
            onClick = {},
            lead = { RowIcon(R.drawable.ic_tv, it) },
        )
        status.devices.forEach { device ->
            key(device.id) {
                SettingRow(
                    label = device.name,
                    hint = deviceHint(device, time),
                    focusable = false,
                    onClick = {},
                    lead = { RowIcon(R.drawable.ic_phone, it) },
                )
            }
        }
        if (status.devices.isEmpty()) PaneNote(soloNote(words, status.account), top = 2.dp)
        if (state.isOwner) {
            Spacer(Modifier.height(14.dp))
            SettingRow(
                label = turnOffTitle(words),
                hint = TURN_OFF_HINT,
                onClick = sync::onTurnOff,
                modifier = Modifier.focusMemory(TURN_OFF_KEY),
                lead = { RowIcon(R.drawable.ic_cloud_off, it) },
            )
        } else {
            PaneNote(SYNC_OWNER_ONLY, top = 14.dp)
        }
    }
}

/** The status line (`.syncstat`): synced with a tick, syncing with a turning ring, failed in amber. */
@Composable
private fun StatusLine(status: SyncStatus, time: SyncTime) {
    val failed = status.failure != null && !status.syncing
    val color = if (failed) KeColors.Warn else KeColors.Ink
    Row(
        modifier = Modifier.padding(start = RowInset, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            status.syncing -> Spinner(15.dp, 2.dp)
            else -> Icon(
                painterResource(if (failed) R.drawable.ic_alert else R.drawable.ic_check),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(syncStatusLine(status, time), style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 15.sp), color = color)
    }
}

/** A failure's fix, the page's first row: Sign in again, or Link again after the link was lost. */
@Composable
private fun FixRow(fix: SyncFix?, account: SyncAccount?, sync: SyncViewModel) {
    when (fix) {
        null -> Unit
        SyncFix.SignInAgain -> account?.let {
            SettingRow(
                label = SIGN_IN_AGAIN,
                hint = tvSignInAgainHint(it),
                chevron = true,
                onClick = sync::onSignInAgain,
                modifier = Modifier.focusMemory(FIX_KEY),
                lead = { tones -> RowIcon(it.service.icon, tones) },
            )
        }
        SyncFix.LinkAgain -> SettingRow(
            label = LINK_AGAIN,
            hint = LINK_AGAIN_HINT,
            chevron = true,
            onClick = sync::onLinkAgain,
            modifier = Modifier.focusMemory(FIX_KEY),
            lead = { RowIcon(R.drawable.ic_cloud, it) },
        )
    }
}
