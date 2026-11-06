package com.arshad.ayn.ui.tv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.feature.settings.DONT_ADD
import com.arshad.ayn.feature.settings.KEEP_APART
import com.arshad.ayn.feature.settings.KEEP_APART_HINT
import com.arshad.ayn.feature.settings.KEEP_SYNCING
import com.arshad.ayn.feature.settings.MATCH_TITLE
import com.arshad.ayn.feature.settings.NEXTCLOUD
import com.arshad.ayn.feature.settings.SERVICES_KICKER
import com.arshad.ayn.feature.settings.SERVICES_TITLE
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncUiState
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.settings.TURN_OFF_AGAIN_HINT
import com.arshad.ayn.feature.settings.TURN_OFF_BUTTON
import com.arshad.ayn.feature.settings.WEBDAV_SERVICE
import com.arshad.ayn.feature.settings.WEBDAV_SERVICE_HINT
import com.arshad.ayn.feature.settings.addHere
import com.arshad.ayn.feature.settings.dontAddHint
import com.arshad.ayn.feature.settings.servicesNote
import com.arshad.ayn.feature.settings.thereHint
import com.arshad.ayn.feature.settings.title
import com.arshad.ayn.feature.settings.turnOffQuestion
import com.arshad.ayn.feature.settings.turnOffSub
import com.arshad.ayn.feature.settings.withOption
import com.arshad.ayn.ui.tv.R

// A sheet's title that asks a question takes up to three lines (`m8-sync-tv` choose, turnoff).
private const val QuestionLines = 3

/** "Where should Ayn keep your sync files?" (`choose`): Google Drive (focused as it opens, when
 *  this build can sign in to it), Nextcloud, or another WebDAV server. */
@Composable
internal fun SyncServicesSheet(state: SyncUiState, sync: SyncViewModel) {
    val focus = rememberSheetFocus(if (state.drive) DRIVE_KEY else NEXTCLOUD_KEY)
    SettingsSheet(
        kicker = SERVICES_KICKER,
        title = { SheetTitle(SERVICES_TITLE, maxLines = QuestionLines) },
        sub = null,
        note = servicesNote(TvSyncWords),
    ) {
        if (state.drive) {
            SettingRow(
                label = SyncService.GOOGLE_DRIVE.title,
                hint = TV_DRIVE_HINT,
                minHeight = SheetRowMinHeight,
                onClick = sync::onGoogleDrive,
                modifier = focus.on(DRIVE_KEY),
                lead = { RowIcon(R.drawable.ic_gdrive, it) },
            )
        }
        SettingRow(
            label = NEXTCLOUD,
            hint = TV_NEXTCLOUD_HINT,
            minHeight = SheetRowMinHeight,
            onClick = sync::onNextcloud,
            modifier = focus.on(NEXTCLOUD_KEY),
            lead = { RowIcon(R.drawable.ic_cloud, it) },
        )
        SettingRow(
            label = WEBDAV_SERVICE,
            hint = WEBDAV_SERVICE_HINT,
            minHeight = SheetRowMinHeight,
            onClick = sync::onWebDav,
            lead = { RowIcon(R.drawable.ic_server, it) },
        )
    }
}

private const val DRIVE_KEY = "google-drive"
private const val NEXTCLOUD_KEY = "nextcloud"

/** "Turn off sync on this Shield?" (`turnoff`): Keep syncing (focused as it opens) or Turn off sync. */
@Composable
internal fun SyncTurnOffSheet(state: SyncUiState, sync: SyncViewModel) {
    val focus = rememberSheetFocus(KEEP_SYNCING_KEY)
    SettingsSheet(
        kicker = "Sync",
        title = { SheetTitle(turnOffQuestion(TvSyncWords), maxLines = QuestionLines) },
        sub = turnOffSub(TvSyncWords, state.status.devices, state.status.account?.service),
    ) {
        SettingRow(
            label = KEEP_SYNCING,
            minHeight = SheetRowMinHeight,
            onClick = sync::onCancel,
            modifier = focus.on(KEEP_SYNCING_KEY),
            lead = { RowIcon(R.drawable.ic_back, it) },
        )
        SettingRow(
            label = TURN_OFF_BUTTON,
            hint = TURN_OFF_AGAIN_HINT,
            minHeight = SheetRowMinHeight,
            onClick = sync::onConfirmTurnOff,
            lead = { RowIcon(R.drawable.ic_cloud_off, it) },
        )
    }
}

private const val KEEP_SYNCING_KEY = "keep-syncing"

/** A profile here's choices: With one of its candidates on the other devices, or Keep apart. */
@Composable
internal fun MatchHereSheet(shown: SyncScreen.Match, syncId: String, sync: SyncViewModel) {
    val match = shown.match
    val profile = match.preview.local.firstOrNull { it.syncId == syncId } ?: return
    val with = match.with[syncId]
    val focus = rememberSheetFocus(with ?: KEEP_APART_KEY)
    SettingsSheet(kicker = MATCH_TITLE, title = { PlateTitle(profile.name, ProfileTint.of(profile.tint)) }, sub = null, scroll = true) {
        match.candidates(syncId).forEach { remote ->
            SettingRow(
                label = withOption(remote),
                minHeight = SheetRowMinHeight,
                labelMaxLines = 2,
                onClick = { sync.onMatchWith(remote.syncId) },
                modifier = focus.on(remote.syncId),
                lead = { RowRadio(on = with == remote.syncId, tones = it) },
            )
        }
        SettingRow(
            label = KEEP_APART,
            hint = KEEP_APART_HINT,
            minHeight = SheetRowMinHeight,
            onClick = sync::onKeepApart,
            modifier = focus.on(KEEP_APART_KEY),
            lead = { RowRadio(on = with == null, tones = it) },
        )
    }
}

private const val KEEP_APART_KEY = "keep-apart"

/** A profile only on the other devices: Add to this Shield (while there is room) or Don't add. */
@Composable
internal fun MatchThereSheet(shown: SyncScreen.Match, syncId: String, sync: SyncViewModel) {
    val match = shown.match
    val profile = match.remote(syncId) ?: return
    val skipped = syncId in match.skipped
    val room = !skipped || match.countHere < MAX_PROFILES
    val focus = rememberSheetFocus(if (skipped) DONT_ADD_KEY else ADD_HERE_KEY)
    SettingsSheet(kicker = MATCH_TITLE, title = { PlateTitle(profile.name, ProfileTint.of(profile.tint)) }, sub = null) {
        SettingRow(
            label = addHere(TvSyncWords),
            hint = thereHint(match, profile, TvSyncWords),
            minHeight = SheetRowMinHeight,
            focusable = room,
            onClick = sync::onAddHere,
            modifier = focus.on(ADD_HERE_KEY).alpha(if (room) 1f else UnavailableAlpha),
            lead = { RowRadio(on = !skipped, tones = it) },
        )
        SettingRow(
            label = DONT_ADD,
            hint = dontAddHint(profile),
            minHeight = SheetRowMinHeight,
            onClick = sync::onDontAdd,
            modifier = focus.on(DONT_ADD_KEY),
            lead = { RowRadio(on = skipped, tones = it) },
        )
    }
}

private const val ADD_HERE_KEY = "add-here"
private const val DONT_ADD_KEY = "dont-add"
