package com.arshad.ayn.ui.mobile.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.settings.DEVICES_HEADING
import com.arshad.ayn.feature.settings.KEEP_SYNCING
import com.arshad.ayn.feature.settings.LINK_AGAIN
import com.arshad.ayn.feature.settings.LINK_AGAIN_HINT
import com.arshad.ayn.feature.settings.NEXTCLOUD
import com.arshad.ayn.feature.settings.NEXTCLOUD_SERVICE_HINT
import com.arshad.ayn.feature.settings.SERVICES_KICKER
import com.arshad.ayn.feature.settings.SERVICES_TITLE
import com.arshad.ayn.feature.settings.SIGN_IN_AGAIN
import com.arshad.ayn.feature.settings.SYNC_NOW
import com.arshad.ayn.feature.settings.SYNC_NOW_HINT
import com.arshad.ayn.feature.settings.SYNC_OWNER_ONLY
import com.arshad.ayn.feature.settings.SyncFix
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncUiState
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.settings.TURN_OFF_HINT
import com.arshad.ayn.feature.settings.TURN_ON_BUTTON
import com.arshad.ayn.feature.settings.WEBDAV_SERVICE
import com.arshad.ayn.feature.settings.WEBDAV_SERVICE_HINT
import com.arshad.ayn.feature.settings.accountHint
import com.arshad.ayn.feature.settings.accountTitle
import com.arshad.ayn.feature.settings.deviceHint
import com.arshad.ayn.feature.settings.servicesNote
import com.arshad.ayn.feature.settings.signInAgainHint
import com.arshad.ayn.feature.settings.soloNote
import com.arshad.ayn.feature.settings.syncFailureNotice
import com.arshad.ayn.feature.settings.syncFix
import com.arshad.ayn.feature.settings.syncKeptNote
import com.arshad.ayn.feature.settings.syncOffLede
import com.arshad.ayn.feature.settings.syncStatusLine
import com.arshad.ayn.feature.settings.syncingHint
import com.arshad.ayn.feature.settings.turnOffQuestion
import com.arshad.ayn.feature.settings.turnOffSub
import com.arshad.ayn.feature.settings.title
import com.arshad.ayn.feature.settings.turnOffTitle
import com.arshad.ayn.feature.settings.turnOnHint
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.NoticeSnackbar
import com.arshad.ayn.ui.mobile.menu.ConfirmSheetContent
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.player.rememberMinuteNow
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

/**
 * Settings › Sync on the phone (`m8-sync-phone` 2, 6–9; M8-D9, M8-D11): off, Turn on sync with what
 * stays on each device; on, the status line, a failure's notice and its fix (Sign in again, Link
 * again) first, Sync now, the service, the devices and Turn off. Only the owner ([me]) gets the rows
 * that act: anyone else reads the state and who sets sync up. The services and Turn off sheets slide
 * over the page; the setup steps cover the shell ([SyncCover]); a toast shows as the snackbar. Times
 * are said by [time]'s clock and zone.
 */
@Composable
internal fun SyncPage(me: Profile, sync: SyncViewModel, time: ShellTime, onBack: () -> Unit) {
    val state by sync.uiState.collectAsStateWithLifecycle()
    val syncTime = SyncTime(rememberMinuteNow(time.clock), time.zone, LocalConfiguration.current.locales[0])
    Box(Modifier.fillMaxSize()) {
        SettingsFrame(SettingsPage.Sync.title(owner = state.isOwner), onBack) {
            if (state.off) offRows(state, sync) else onRows(state, sync, syncTime)
        }
        NoticeSnackbar(state.toast, ::syncToastLine, onClose = sync::onToastShown, modifier = Modifier.align(Alignment.BottomCenter))
    }
    val sheet = state.screen.takeIf { it == SyncScreen.Services || it is SyncScreen.TurnOff }
    StageSheet(sheet, onDismiss = sync::onDismiss, onBack = sync::onBack, stageKey = { it::class }) { shown ->
        when (shown) {
            is SyncScreen.TurnOff -> TurnOffSheet(shown, state.status, sync)
            else -> ServicesSheet(state.drive, sync)
        }
    }
    SyncCover(state, me, sync, syncTime)
}

private fun LazyListScope.offRows(state: SyncUiState, sync: SyncViewModel) {
    item(key = "lede") { PageLede(syncOffLede(PhoneSyncWords)) }
    if (state.isOwner) {
        item(key = "turn-on") { SettingRow(TURN_ON_BUTTON, sync::onTurnOn, hint = turnOnHint(state.drive), lead = { RowIcon(R.drawable.ic_cloud) }) }
    }
    item(key = "kept") { PageFoot(syncKeptNote(PhoneSyncWords, PHONE_SETTINGS)) }
}

private fun LazyListScope.onRows(state: SyncUiState, sync: SyncViewModel, time: SyncTime) {
    val status = state.status
    val words = PhoneSyncWords
    item(key = "status") { StatusLine(status, time) }
    status.failure?.let { failure ->
        item(key = "notice") {
            val (lead, text) = syncFailureNotice(failure, status.account, words)
            SettingsNotice(lead, text)
        }
    }
    if (state.isOwner) {
        syncFix(status.failure)?.let { fix -> item(key = "fix") { FixRow(fix, status.account, sync) } }
        item(key = "sync-now") {
            SettingRow(
                SYNC_NOW,
                sync::onSyncNow,
                hint = if (status.syncing) syncingHint(words, status.devices) else SYNC_NOW_HINT,
                chevron = false,
                lead = { RowIcon(R.drawable.ic_retry) },
            )
        }
    }
    status.account?.let { account ->
        item(key = "account") {
            SettingRow(accountTitle(account), null, hint = accountHint(account, status.folderBytes), chevron = false, lead = { RowIcon(account.service.icon) })
        }
    }
    item(key = "devices") { SubHeading(DEVICES_HEADING) }
    item(key = "this") { SettingRow(words.hereStart, null, hint = thisPhoneHint(status, time), chevron = false, lead = { RowIcon(R.drawable.ic_phone) }) }
    items(status.devices, key = { "device-${it.id}" }) { device ->
        SettingRow(device.name, null, hint = deviceHint(device, time), chevron = false, lead = { RowIcon(R.drawable.ic_tv) })
    }
    if (status.devices.isEmpty()) item(key = "solo") { DeviceNote(soloNote(words, status.account)) }
    if (state.isOwner) {
        item(key = "turn-off") {
            Spacer(Modifier.height(20.dp))
            SettingRow(turnOffTitle(words), sync::onTurnOff, hint = TURN_OFF_HINT, chevron = false, lead = { RowIcon(R.drawable.ic_cloud_off) })
        }
    } else {
        item(key = "owner-only") {
            Spacer(Modifier.height(16.dp))
            OwnerOnlyLine(SYNC_OWNER_ONLY)
        }
    }
}

/** What the status line shows: the three looks it changes between (fading, D8). */
private enum class StatusLook { Synced, Syncing, Failed }

/** The status line (`.sstat`): synced with a tick, syncing with a turning ring, failed in amber. It
 *  fades only when its look changes, not as its age ticks over each minute. */
@Composable
private fun StatusLine(status: SyncStatus, time: SyncTime) {
    val look = when {
        status.syncing -> StatusLook.Syncing
        status.failure != null -> StatusLook.Failed
        else -> StatusLook.Synced
    }
    val line = syncStatusLine(status, time)
    AnimatedContent(
        targetState = look,
        transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.press()) },
        label = "syncStatus",
    ) { shown ->
        val color = if (shown == StatusLook.Failed) AynColors.Warn else AynColors.Ink
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (shown) {
                StatusLook.Syncing -> CircularProgressIndicator(Modifier.size(16.dp), color = AynColors.Ink2, strokeWidth = 2.dp)
                StatusLook.Failed, StatusLook.Synced -> Icon(
                    painterResource(if (shown == StatusLook.Failed) R.drawable.ic_alert else R.drawable.ic_check),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(line, style = MaterialTheme.typography.labelLarge.atWeight(620).copy(fontSize = 15.sp), color = color)
        }
    }
}

/** A failure's fix, the page's first row: Sign in again, or Link again after the link was lost. */
@Composable
private fun FixRow(fix: SyncFix, account: SyncAccount?, sync: SyncViewModel) {
    when (fix) {
        SyncFix.SignInAgain -> account?.let {
            SettingRow(SIGN_IN_AGAIN, sync::onSignInAgain, hint = signInAgainHint(it), lead = { RowIcon(it.service.icon) })
        }
        SyncFix.LinkAgain -> SettingRow(LINK_AGAIN, sync::onLinkAgain, hint = LINK_AGAIN_HINT, lead = { RowIcon(R.drawable.ic_cloud) })
    }
}

/** Under This phone while no other device has joined (`.dnote`), in line with the rows' text. */
@Composable
private fun DeviceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
        color = AynColors.Ink3,
        modifier = Modifier.padding(start = 58.dp, end = 20.dp, bottom = 4.dp),
    )
}

/** "Where should Ayn keep your sync files?" (`m8-sync-phone` 3): Google Drive (when this build can
 *  sign in to it, [drive]), Nextcloud, or another WebDAV server. */
@Composable
private fun ServicesSheet(drive: Boolean, sync: SyncViewModel) {
    SheetColumn(kicker = SERVICES_KICKER, title = SERVICES_TITLE, sub = null, choices = false, note = servicesNote(PhoneSyncWords)) {
        if (drive) SheetRow(SyncService.GOOGLE_DRIVE.title, tap(sync::onGoogleDrive), hint = PHONE_DRIVE_HINT, icon = R.drawable.ic_gdrive)
        SheetRow(NEXTCLOUD, tap(sync::onNextcloud), hint = NEXTCLOUD_SERVICE_HINT, icon = R.drawable.ic_cloud)
        SheetRow(WEBDAV_SERVICE, tap(sync::onWebDav), hint = WEBDAV_SERVICE_HINT, icon = R.drawable.ic_server)
    }
}

/** "Turn off sync on this phone?" (`m8-sync-phone` 8): Keep syncing, or Turn off; both dim while
 *  the turn off runs. */
@Composable
private fun TurnOffSheet(shown: SyncScreen.TurnOff, status: SyncStatus, sync: SyncViewModel) {
    ConfirmSheetContent(
        kicker = "Sync",
        title = turnOffQuestion(PhoneSyncWords),
        body = turnOffSub(PhoneSyncWords, status.devices, status.account?.service),
        keep = KEEP_SYNCING,
        confirm = TURN_OFF_SHORT,
        onKeep = sync::onCancel,
        onConfirm = sync::onConfirmTurnOff,
        kickerIcon = R.drawable.ic_cloud,
        confirmIcon = R.drawable.ic_cloud_off,
        enabled = !shown.running,
    )
}
