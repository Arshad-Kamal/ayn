package com.arshad.ayn.ui.tv

import com.arshad.ayn.core.access.LiveChat
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.session.SessionToken
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.kids.KidsStores
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.ui.tv.shell.ParentModels
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.home.kidsPicks
import com.arshad.ayn.ui.tv.shell.KidsShell
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.ProfilePlayback
import com.arshad.ayn.feature.search.ApprovedSearch
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.core.sync.DriveSignIn
import com.arshad.ayn.core.sync.SyncControl
import com.arshad.ayn.core.sync.SyncSignIn
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.tv.common.LocalDeviceSettings
import com.arshad.ayn.ui.tv.common.ProvideDeviceSettings
import com.arshad.ayn.ui.tv.profiles.ProfileGate
import com.arshad.ayn.ui.tv.shell.SettingsModels
import com.arshad.ayn.ui.tv.shell.TvShell
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow

/**
 * TV UI root (`:ui:tv`): the Cinema theme around the profile [gate], which shows the navigation
 * shell for the open profile. The extraction seam, the shared subscriptions feed, the
 * subscription, watch and playlist stores, For you's related lists, state and request log, the playback service's session (its service lives in `:app`)
 * and its [playback] handle, and the profile, lock, session, backup, settings and filter stores Settings works on
 * come from the app container (ARCH-4/5 manual DI), as do the player's [votes], [captions] and [liveChat]
 * and the [branding] every video's title and picture go through; the gate's ViewModel is the
 * activity's, and a Settings restore hands over to it. [chooseFile] opens the activity's file picker, whose
 * picks reach the gate (M5-D20). [lockTime] runs lock waits and must count deep sleep. The TV's
 * [deviceSettings] reach every screen as [LocalDeviceSettings], first read before the gate shows.
 * [approvedSearch] answers a kids profile's Search (KP-D10). Settings › Sync sets sync up through
 * [syncControl], [syncSignIn] and [driveSignIn] (`null` hides Google Drive); its Sync now asks [requestSync] for a round, and Home tells this
 * launch's failed sync ([syncNotice], the activity's, M8-D11). Card menus block through the filter,
 * profile and lock stores (KP-D19), and [contentFilter] (the open profile's) takes what a block
 * hides off the screens already showing it. A kids profile's shell reads [kids]: its time left
 * today and its warnings, and its Parent page's time ledger, review keeps and YouTube's clock; the
 * owner's picks read from the filter and profile stores (KP-D8, D10, D11).
 */
@Composable
fun TvApp(
    gate: ProfileGateViewModel,
    extractor: Extractor,
    subscriptionsFeed: SubscriptionsFeed,
    subscriptionStore: SubscriptionStore,
    watchStore: WatchStore,
    playlistStore: PlaylistStore,
    relatedLists: RelatedListStore,
    forYouStore: ForYouStore,
    fetchAttempts: FetchAttemptLog,
    playerSession: (Context) -> SessionToken,
    profileStore: ProfileStore,
    profileLocks: ProfileLocks,
    profileSession: ProfileSession,
    backupStore: BackupStore,
    profileSettings: ProfileSettingsStore,
    filterStore: FilterStore,
    contentFilter: Flow<ContentFilter>,
    votes: VoteSource,
    captions: CaptionSource,
    liveChat: LiveChat,
    branding: CardBranding,
    deviceSettings: DeviceSettingsStore,
    playback: ProfilePlayback,
    lockTime: TimeSource,
    chooseFile: (PickRequest) -> Unit,
    syncControl: SyncControl,
    syncSignIn: SyncSignIn,
    driveSignIn: DriveSignIn?,
    requestSync: () -> Unit,
    modifier: Modifier = Modifier,
    syncNotice: SyncLaunchNotice? = null,
    approvedSearch: ApprovedSearch? = null,
    kids: KidsStores? = null,
) {
    val settingsModels = remember(
        gate, profileStore, profileLocks, profileSession, backupStore, profileSettings, filterStore, deviceSettings, playback, lockTime, chooseFile,
        subscriptionStore, extractor, syncControl, syncSignIn, driveSignIn, requestSync,
    ) {
        SettingsModels(
            profiles = { ProfilesViewModel(profileStore, profileLocks, filterStore, profileSession, lockTime) },
            backup = { profileId ->
                BackupViewModel(backupStore, profileStore, profileLocks, playback, handover = gate, picks = gate, profileId, lockTime)
            },
            preferences = { PreferencesViewModel(profileSettings, deviceSettings, profileStore) },
            filters = { FiltersViewModel(profileSettings, filterStore, profileStore, profileLocks) },
            restrictions = { RestrictionsViewModel(filterStore, profileStore, profileLocks, lockTime) },
            kidsAdd = { KidsAddViewModel(profileStore, profileLocks, filterStore, subscriptionStore, extractor, lockTime) },
            sync = { profileId -> SyncViewModel(syncControl, syncSignIn, driveSignIn, profileStore, profileLocks, requestSync, profileId, lockTime) },
            chooseFile = chooseFile,
        )
    }
    val menuBlocking = remember(filterStore, profileStore, profileLocks, lockTime) {
        MenuBlocking(filterStore, profileStore, profileLocks, lockTime)
    }
    val kidsShell = remember(kids, filterStore, profileStore, profileLocks, profileSession, watchStore, subscriptionStore, subscriptionsFeed, lockTime, contentFilter) {
        kids?.let { stores ->
            val parent = ParentModels(
                page = { kidId, leave -> ParentViewModel(kidId, profileStore, profileLocks, filterStore, leave, lockTime) },
                review = {
                    ParentReviewViewModel(
                        profileSession, filterStore, stores.time, watchStore, subscriptionStore, subscriptionsFeed, stores.reviewKeeps, stores.serverClock,
                    )
                },
            )
            KidsShell(stores.clock, parent) { kidId -> kidsPicks(kidId, filterStore, profileStore, contentFilter) }
        }
    }
    KeTheme {
        ProvideDeviceSettings(deviceSettings) {
            ProfileGate(gate, chooseFile, modifier) { opening ->
                TvShell(
                    extractor, subscriptionsFeed, subscriptionStore, watchStore, playlistStore, relatedLists, forYouStore, fetchAttempts,
                    profileSettings, votes, captions, liveChat, branding, playerSession, settingsModels, opening, menuBlocking, contentFilter,
                    approvedSearch = approvedSearch,
                    kids = kidsShell,
                    syncNotice = syncNotice,
                )
            }
        }
    }
}
