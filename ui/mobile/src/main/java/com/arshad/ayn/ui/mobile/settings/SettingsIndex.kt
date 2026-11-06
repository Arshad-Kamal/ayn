package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.syncCategoryHint
import com.arshad.ayn.ui.mobile.player.rememberMinuteNow
import com.arshad.ayn.ui.mobile.shell.ShellTime
import kotlinx.coroutines.flow.StateFlow
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesUiState
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.AynMark
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.SmallTile
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight

/**
 * Settings (`m6-settings` state 1, `m8-sync-phone` 1): who is open, then every category in the TV's
 * order with what it holds now ([backups]: how many backups this phone keeps, `null` until listed;
 * [syncStatus]: sync's state, its times said by [time]); a tap opens its page ([onOpen]). Backup &
 * restore is Import from YouTube for anyone but the owner, who alone sets up Sync (its row dimmed
 * for anyone else).
 */
@Composable
internal fun SettingsIndex(
    me: Profile,
    profiles: ProfilesViewModel,
    filters: FiltersViewModel,
    preferences: PreferencesViewModel,
    backups: suspend () -> Int,
    syncStatus: StateFlow<SyncStatus>,
    time: ShellTime,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    val people by profiles.uiState.collectAsStateWithLifecycle()
    val filtered by filters.uiState.collectAsStateWithLifecycle()
    val prefs by preferences.uiState.collectAsStateWithLifecycle()
    val saved by produceState<Int?>(null, backups) { value = backups() }
    val sync by syncStatus.collectAsStateWithLifecycle()
    val syncTime = SyncTime(rememberMinuteNow(time.clock), time.zone, LocalConfiguration.current.locales[0])
    val version = rememberVersionName()
    val settings = prefs.profile?.settings
    val device = prefs.device
    SettingsFrame("Settings", onBack) {
        item(key = "who") { Who(me, version) }
        item(key = "rows") {
            Column {
                Category(R.drawable.ic_user, SettingsPage.Profiles, people.profilesLine(me), me.isOwner, onOpen)
                Category(R.drawable.ic_funnel, SettingsPage.Filters, filtered.profile?.settings?.let { filtersSummary(it, filtered.ownKeywords.size) }, me.isOwner, onOpen)
                Category(R.drawable.ic_play, SettingsPage.Playback, if (settings != null && device != null) playbackSummary(settings, device) else null, me.isOwner, onOpen)
                Category(R.drawable.ic_shield_plain, SettingsPage.SponsorBlock, settings?.let(::sponsorBlockSummary), me.isOwner, onOpen)
                Category(R.drawable.ic_edit, SettingsPage.DeArrow, settings?.let(::deArrowSummary), me.isOwner, onOpen)
                Category(R.drawable.ic_thumb_down, SettingsPage.Dislikes, settings?.let(::dislikesSummary), me.isOwner, onOpen)
                Category(R.drawable.ic_interface, SettingsPage.Interface, if (settings != null && device != null) interfaceSummary(settings, device) else null, me.isOwner, onOpen)
                Dimmed(lit = me.isOwner) {
                    Category(R.drawable.ic_cloud, SettingsPage.Sync, syncCategoryHint(me.isOwner, sync, PhoneSyncWords, syncTime), me.isOwner, onOpen)
                }
                Category(
                    if (me.isOwner) R.drawable.ic_upload else R.drawable.ic_nav_subscriptions,
                    SettingsPage.Backup,
                    if (me.isOwner) saved?.let(::backupSummary) else null,
                    me.isOwner,
                    onOpen,
                )
                Category(R.drawable.ic_info, SettingsPage.About, aboutSummary(version), me.isOwner, onOpen)
            }
        }
    }
}

/** `null` until the profiles load. */
private fun ProfilesUiState.profilesLine(me: Profile): String? =
    if (rows.isEmpty()) null else profilesSummary(owner = me.isOwner, profiles = rows.size, ownerName = ownerName)

@Composable
private fun Category(@DrawableRes icon: Int, page: SettingsPage, line: String?, owner: Boolean, onOpen: (SettingsPage) -> Unit) {
    SettingRow(label = page.title(owner), onClick = { onOpen(page) }, hint = line, lead = { RowIcon(icon) })
}

/** The open profile at the head (`.swho`): its tile, its name and Owner, the version. */
@Composable
private fun Who(me: Profile, version: String?) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileTile(me.name, me.tint, SmallTile)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(me.name, style = MaterialTheme.typography.titleLarge)
                    if (me.isOwner) {
                        Text(
                            "Owner",
                            style = MaterialTheme.typography.labelSmall.atWeight(680).copy(fontSize = 11.sp),
                            color = AynColors.Ink2,
                            modifier = Modifier.background(AynColors.Tonal, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(versionLine(version), style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3)
            }
        }
        GroupLine()
    }
}

/** The installed version's name ("0.4"), `null` when the package manager doesn't say. */
@Composable
internal fun rememberVersionName(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
}

/** The About row's mark (`.aynmark`). */
@Composable
internal fun AboutMark() {
    AynMark(size = 34.dp)
}
