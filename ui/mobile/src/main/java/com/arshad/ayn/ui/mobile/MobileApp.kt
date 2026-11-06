package com.arshad.ayn.ui.mobile

import com.arshad.ayn.core.access.LiveChat
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.SessionToken
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.home.HomeViewModel
import com.arshad.ayn.feature.home.homeViewModel
import com.arshad.ayn.feature.home.kidsPicks
import com.arshad.ayn.feature.kids.KidsStores
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.library.HistoryViewModel
import com.arshad.ayn.feature.library.LibraryViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.SavedPlaylistViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.playlist.PlaylistViewModel
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.ProfilePlayback
import com.arshad.ayn.feature.search.ApprovedSearch
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.core.sync.DriveSignIn
import com.arshad.ayn.core.sync.SyncControl
import com.arshad.ayn.core.sync.SyncSignIn
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.ui.mobile.channel.ChannelPage
import com.arshad.ayn.ui.mobile.common.LocalCardBranding
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.ProvideDeviceSettings
import com.arshad.ayn.ui.mobile.home.HomePage
import com.arshad.ayn.ui.mobile.kids.ParentModels
import com.arshad.ayn.ui.mobile.kids.ParentPage
import com.arshad.ayn.ui.mobile.library.HistoryPage
import com.arshad.ayn.ui.mobile.library.LibraryPage
import com.arshad.ayn.ui.mobile.library.SavedPlaylistPage
import com.arshad.ayn.ui.mobile.player.AppPlayer
import com.arshad.ayn.ui.mobile.player.MiniDock
import com.arshad.ayn.ui.mobile.player.PlayerModels
import com.arshad.ayn.ui.mobile.player.PlayerSlot
import com.arshad.ayn.ui.mobile.player.ShortsHandOver
import com.arshad.ayn.ui.mobile.player.ShortsPage
import com.arshad.ayn.ui.mobile.player.WatchPage
import com.arshad.ayn.ui.mobile.player.rememberAppPlayer
import com.arshad.ayn.ui.mobile.player.rememberOpenPlayer
import com.arshad.ayn.ui.mobile.player.rememberPlayerSlot
import com.arshad.ayn.ui.mobile.playlist.PlaylistPage
import com.arshad.ayn.ui.mobile.profiles.MobileGate
import com.arshad.ayn.ui.mobile.search.SearchPage
import com.arshad.ayn.ui.mobile.settings.BackupPage
import com.arshad.ayn.ui.mobile.settings.FiltersPage
import com.arshad.ayn.ui.mobile.settings.PreferencesPage
import com.arshad.ayn.ui.mobile.settings.ProfilesPage
import com.arshad.ayn.ui.mobile.settings.RestrictionsPage
import com.arshad.ayn.ui.mobile.settings.SettingsIndex
import com.arshad.ayn.ui.mobile.settings.SettingsModels
import com.arshad.ayn.ui.mobile.settings.SyncPage
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.HistoryKey
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.ParentKey
import com.arshad.ayn.ui.mobile.shell.PlaylistKey
import com.arshad.ayn.ui.mobile.shell.RestrictionsKey
import com.arshad.ayn.ui.mobile.shell.SavedPlaylistKey
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.SettingsPageKey
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.subscriptions.SubscriptionsPage
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The phone UI's root (M6, charter D3): the profile gate ([gate], the activity's) and the open
 * profile's shell, portrait (Q13: only the player turns). The extraction seam, the shared
 * subscriptions feed, the stores and For you's lists, log and settings come from the app container
 * (ARCH-4/5 manual DI), as do this phone's [deviceSettings] (Hero on Home, first read before the gate
 * shows), the [branding] every video's title and picture go through, the open profile's
 * [contentFilter] (lists already on screen, KP-D19) and what the card menu's Block and Approve
 * write through (the filter and profile stores, the locks; [lockTime] counts deep sleep).
 * [chooseFile] opens the activity's file picker. [approvedSearch] answers a kids profile's
 * Search (KP-D10); a kids profile's shell reads [kids] (its time and notes, its Parent page's
 * review) and Home the owner's picks for it from the filter and profile stores (KP-D8, D11). Settings reads [backupStore] and the [profileSession];
 * Settings › Sync sets sync up through [syncControl], [syncSignIn] and [driveSignIn] (`null` hides Google Drive), and its Sync now and the pull to refresh on Home and
 * Subscriptions ask [requestSync] for a round; Home tells this launch's failed sync ([syncNotice], the activity's, M8-D11).
 * The player binds to [playerSession] for the activity's life, ends the service's
 * video through [playback], and asks [votes], [captions] and [liveChat], as the TV's. [expandPlayer]: the
 * playback notification was tapped, so the player shows its watch page once a profile's shell is
 * open (after its unlock, if any); [onPlayerExpanded] consumes it. `MainActivity` hosts it off TV
 * (M6-D7).
 */
@Composable
fun MobileApp(
    gate: ProfileGateViewModel,
    extractor: Extractor,
    subscriptionsFeed: SubscriptionsFeed,
    subscriptionStore: SubscriptionStore,
    watchStore: WatchStore,
    playlistStore: PlaylistStore,
    relatedLists: RelatedListStore,
    forYouStore: ForYouStore,
    fetchAttempts: FetchAttemptLog,
    profileSettings: ProfileSettingsStore,
    deviceSettings: DeviceSettingsStore,
    branding: CardBranding?,
    contentFilter: Flow<ContentFilter>,
    filterStore: FilterStore,
    profileStore: ProfileStore,
    profileLocks: ProfileLocks,
    lockTime: TimeSource,
    chooseFile: (PickRequest) -> Unit,
    backupStore: BackupStore,
    profileSession: ProfileSession,
    playerSession: (Context) -> SessionToken,
    playback: ProfilePlayback,
    votes: VoteSource,
    captions: CaptionSource,
    liveChat: LiveChat,
    syncControl: SyncControl,
    syncSignIn: SyncSignIn,
    driveSignIn: DriveSignIn?,
    requestSync: () -> Unit,
    modifier: Modifier = Modifier,
    syncNotice: SyncLaunchNotice? = null,
    approvedSearch: ApprovedSearch? = null,
    kids: KidsStores? = null,
    expandPlayer: Boolean = false,
    onPlayerExpanded: () -> Unit = {},
) {
    PortraitOnly()
    val models = remember(
        extractor, subscriptionsFeed, subscriptionStore, watchStore, playlistStore, relatedLists, forYouStore, fetchAttempts, profileSettings,
        filterStore, profileStore, profileLocks, lockTime, approvedSearch, playerSession, playback, votes, captions, liveChat, deviceSettings,
        backupStore, profileSession, gate, chooseFile, kids, contentFilter, syncControl, syncSignIn, driveSignIn, requestSync, syncNotice,
    ) {
        val blocking = MenuBlocking(filterStore, profileStore, profileLocks, lockTime)
        val time = ShellTime()
        val menu = { VideoMenuViewModel(watchStore, playlistStore, profileSettings, blocking) }
        ShellModels(
            home = { profile ->
                val picks = if (kids != null && profile.isKids) kidsPicks(profile.id, filterStore, profileStore, contentFilter) else null
                homeViewModel(extractor, subscriptionsFeed, subscriptionStore, watchStore, playlistStore, relatedLists, forYouStore, fetchAttempts, profileSettings, kidsPicks = picks)
            },
            menu = menu,
            search = { SearchViewModel(extractor, profileSettings, approvedSearch) },
            subscriptions = { SubscriptionsViewModel(subscriptionsFeed, subscriptionStore, profileSettings) },
            channel = { channelUrl -> ChannelViewModel(channelUrl, extractor, subscriptionStore, profileSettings) },
            library = { LibraryViewModel(watchStore, playlistStore) },
            history = { HistoryViewModel(watchStore, time.clock, time.zone) },
            savedPlaylist = { id -> SavedPlaylistViewModel(id, playlistStore, watchStore) },
            playlist = { card -> PlaylistViewModel(card, extractor) },
            marks = watchStore.history().map { it.marks() }.flowOn(Dispatchers.Default),
            time = time,
            kidsClock = kids?.clock,
            parent = kids?.let { stores ->
                ParentModels(
                    page = { kidId, leave -> ParentViewModel(kidId, profileStore, profileLocks, filterStore, leave, lockTime) },
                    review = {
                        ParentReviewViewModel(
                            profileSession, filterStore, stores.time, watchStore, subscriptionStore, subscriptionsFeed, stores.reviewKeeps, stores.serverClock,
                        )
                    },
                )
            },
            settings = SettingsModels(
                preferences = { PreferencesViewModel(profileSettings, deviceSettings, profileStore) },
                filters = { FiltersViewModel(profileSettings, filterStore, profileStore, profileLocks) },
                profiles = { ProfilesViewModel(profileStore, profileLocks, filterStore, profileSession, lockTime) },
                kidsAdd = { KidsAddViewModel(profileStore, profileLocks, filterStore, subscriptionStore, extractor, lockTime) },
                restrictions = { RestrictionsViewModel(filterStore, profileStore, profileLocks, lockTime) },
                backup = { profileId ->
                    BackupViewModel(backupStore, profileStore, profileLocks, playback, handover = gate, picks = gate, profileId, lockTime)
                },
                sync = { profileId -> SyncViewModel(syncControl, syncSignIn, driveSignIn, profileStore, profileLocks, requestSync, profileId, lockTime) },
                savedBackups = { backupStore.savedBackups().size },
                syncStatus = syncControl.status,
                chooseFile = chooseFile,
            ),
            player = PlayerModels(
                player = { route, shorts ->
                    PlayerViewModel(route.video(), route.playAll(), extractor, subscriptionStore, watchStore, playlistStore, profileSettings, votes, captions, liveChat, route.startOver, shorts)
                },
                menu = menu,
                session = playerSession,
                playback = playback,
            ),
            requestSync = requestSync,
            syncNotice = syncNotice,
        )
    }
    // Above the gate: a phone re-lock keeps the video playing under the unlock (M6-D8).
    val app = rememberAppPlayer(models.player.session)
    AynTheme {
        ProvideDeviceSettings(deviceSettings) {
            CompositionLocalProvider(LocalCardBranding provides branding) {
                MobileGate(gate, chooseFile, modifier, kept = app.connection.controller) { opening ->
                    // Read in the shell, as TV does: a shell opened for another profile starts on no filter, not the last one's.
                    val filter by contentFilter.collectAsStateWithLifecycle(initialValue = null)
                    CompositionLocalProvider(LocalContentFilter provides filter) {
                        ProfileShell(opening, models, app = app, expandPlayer = expandPlayer, onPlayerExpanded = onPlayerExpanded)
                    }
                }
            }
        }
    }
}

/** Builds the shell pages' ViewModels in each entry's store (their dependencies are the app's);
 *  each page with cards has its own card menu ([menu]); [home] is the open profile's (a kid's
 *  reads the owner's picks). [marks]: how far the profile got into each video it opened, for the
 *  watch bars of pages without their own. [time] dates the Library's days, ages and changes;
 *  [kidsClock] is a kids profile's time and [parent] its Parent page's; [settings] Settings' pages;
 *  [player] the watch page's. Home's and Subscriptions' pull to refresh calls [requestSync]; Home
 *  tells this launch's failed sync ([syncNotice]). */
internal class ShellModels(
    val home: (Profile) -> HomeViewModel,
    val menu: () -> VideoMenuViewModel,
    val search: () -> SearchViewModel,
    val subscriptions: () -> SubscriptionsViewModel,
    val channel: (channelUrl: String) -> ChannelViewModel,
    val library: () -> LibraryViewModel,
    val history: () -> HistoryViewModel,
    val savedPlaylist: (playlistId: Long) -> SavedPlaylistViewModel,
    val playlist: (card: PlaylistItem) -> PlaylistViewModel,
    val marks: Flow<Map<VideoId, WatchMark>>,
    val time: ShellTime,
    val kidsClock: KidsClock?,
    val settings: SettingsModels,
    val player: PlayerModels,
    val parent: ParentModels? = null,
    val requestSync: () -> Unit = {},
    val syncNotice: SyncLaunchNotice? = null,
)

/** The open profile's shell with its one player (M6-D16): the watch page over the pages, the mini
 *  player docked above the bar, or a Short's Shorts player (no mini player, Q45), on [app]'s
 *  controller. The controller outlives an opening, so closing the player or opening another video
 *  ends the last one first ([PlayerModels.playback]: its place saved, its stop note cleared,
 *  nothing left in the notification). [expandPlayer] expands a docked player (nothing without
 *  one) and [onPlayerExpanded] consumes it. */
@Composable
internal fun ProfileShell(
    opening: ShellOpening,
    models: ShellModels,
    modifier: Modifier = Modifier,
    slot: PlayerSlot = rememberPlayerSlot(),
    app: AppPlayer = rememberAppPlayer(models.player.session),
    handedShorts: ShortsHandOver = remember { ShortsHandOver() },
    expandPlayer: Boolean = false,
    onPlayerExpanded: () -> Unit = {},
) {
    val player = rememberOpenPlayer(slot, models.player, app, handedShorts)
    val consumeExpand by rememberUpdatedState(onPlayerExpanded)
    LaunchedEffect(expandPlayer) {
        if (expandPlayer) {
            slot.expand()
            consumeExpand()
        }
    }
    val playing by rememberUpdatedState(player)
    val playback = models.player.playback
    val close = remember(slot, playback) {
        {
            playback.end()
            slot.close()
        }
    }
    val play = remember(slot, playback) {
        { route: PlayerRoute ->
            // The video the opening plays now (Up next and Play all move it on), not the one it opened with.
            val current = playing?.viewModel?.uiState?.value?.video?.id?.value
            if (current != null && current != route.videoId) playback.end()
            slot.open(route)
        }
    }
    val playShort = remember(play, handedShorts) {
        { short: VideoItem, run: ShortsList -> handedShorts.play(short, run, play) }
    }
    val shortOpen = remember(slot) { { slot.route?.short == true } }
    // For Time's up, which shows once the shell has closed (KP-D26).
    val picture = player?.picture?.value ?: slot.route?.thumbnailUrl
    SideEffect { opening.onPicture(picture) }
    MobileShell(
        opening = opening,
        modifier = modifier,
        onPlay = play,
        onPlayShort = playShort,
        shortOpen = shortOpen,
        covered = player != null && slot.expanded,
        kidsClock = models.kidsClock.takeIf { opening.profile.isKids },
        miniPlayer = { MiniDock(player?.takeIf { !slot.expanded }, onExpand = slot::expand, onClose = close) },
        overlay = { chrome ->
            player?.let {
                key(slot.serial) { if (shortOpen()) ShortsPage(it, chrome, onClose = close) else WatchPage(it, slot, chrome, models.time.clock, onClose = close) }
            }
        },
    ) { chrome -> shellPages(chrome, models) }
}

/** Each shell key's page on [models]; Play opens the watch page ([ShellChrome.play]), a Short's
 *  the Shorts player ([ShellChrome.playShort]). */
internal fun EntryProviderScope<NavKey>.shellPages(chrome: ShellChrome, models: ShellModels) {
    val playRoute = chrome.play
    val openChannel: (String) -> Unit = { channelUrl -> chrome.nav.open(ChannelKey(ChannelRoute(channelUrl))) }
    val play: (VideoItem, Boolean) -> Unit = { video, startOver -> playRoute(PlayerRoute(video, startOver = startOver)) }
    entry<TabKey> { key ->
        when (key.tab) {
            Tab.Home -> HomePage(
                viewModel = viewModel { models.home(chrome.profile) },
                menu = viewModel { models.menu() },
                chrome = chrome,
                onPlay = play,
                onOpenChannel = openChannel,
                onPull = models.requestSync,
                syncNotice = models.syncNotice,
                onFixSync = { chrome.nav.openSettings(SettingsPage.Sync) },
            )
            Tab.Subscriptions -> SubscriptionsPage(
                viewModel = viewModel { models.subscriptions() },
                menu = viewModel { models.menu() },
                chrome = chrome,
                marks = models.marks,
                onPlay = play,
                onPlayShort = chrome.playShort,
                onOpenChannel = openChannel,
                consumeArrival = { chrome.nav.consumeArrival(key) },
                onPull = models.requestSync,
            )
            Tab.Library -> LibraryPage(
                viewModel = viewModel { models.library() },
                menu = viewModel { models.menu() },
                chrome = chrome,
                onPlay = playRoute,
                onOpenChannel = openChannel,
                time = models.time,
            )
        }
    }
    entry<HistoryKey> {
        HistoryPage(
            viewModel = viewModel { models.history() },
            menu = viewModel { models.menu() },
            chrome = chrome,
            onPlay = playRoute,
            onOpenChannel = openChannel,
            time = models.time,
        )
    }
    entry<SavedPlaylistKey> { key ->
        SavedPlaylistPage(
            viewModel = viewModel { models.savedPlaylist(key.route.playlistId) },
            menu = viewModel { models.menu() },
            chrome = chrome,
            onPlay = playRoute,
            onOpenChannel = openChannel,
            // By key, so a page leaving mid-transition can't pop what covers it.
            onGone = { chrome.nav.close(key) },
            time = models.time,
        )
    }
    entry<PlaylistKey> { key ->
        val card = key.route.card()
        PlaylistPage(
            viewModel = viewModel { models.playlist(card) },
            menu = viewModel { models.menu() },
            chrome = chrome,
            marks = models.marks,
            onPlay = { from, startOver -> playRoute(PlayerRoute(from, PlayAll.YouTube(card), startOver)) },
            onOpenChannel = openChannel,
        )
    }
    entry<SearchKey> {
        SearchPage(
            viewModel = viewModel { models.search() },
            menu = viewModel { models.menu() },
            chrome = chrome,
            marks = models.marks,
            onPlay = play,
            onPlayShort = chrome.playShort,
            onOpenChannel = openChannel,
        )
    }
    entry<ChannelKey> { key ->
        ChannelPage(
            viewModel = viewModel { models.channel(key.route.channelUrl) },
            menu = viewModel { models.menu() },
            chrome = chrome,
            marks = models.marks,
            onPlay = play,
            onPlayShort = chrome.playShort,
            onOpenPlaylist = { card -> chrome.nav.open(PlaylistKey(PlaylistRoute(card))) },
        )
    }
    entry<SettingsKey> {
        SettingsIndex(
            me = chrome.profile,
            profiles = viewModel { models.settings.profiles() },
            filters = viewModel { models.settings.filters() },
            preferences = viewModel { models.settings.preferences() },
            backups = models.settings.savedBackups,
            syncStatus = models.settings.syncStatus,
            time = models.time,
            onOpen = { page -> chrome.nav.open(SettingsPageKey(page)) },
            onBack = chrome.nav::back,
        )
    }
    entry<SettingsPageKey> { key ->
        when (key.page) {
            SettingsPage.Filters -> FiltersPage(
                me = chrome.profile,
                filters = viewModel { models.settings.filters() },
                time = models.time,
                onShowShorts = { chrome.nav.open(SettingsPageKey(SettingsPage.Interface)) },
                onBack = chrome.nav::back,
            )
            SettingsPage.Profiles -> ProfilesPage(
                profiles = viewModel { models.settings.profiles() },
                kidsAdd = viewModel { models.settings.kidsAdd() },
                onRestrictions = { profileId -> chrome.nav.open(RestrictionsKey(profileId)) },
                onBack = chrome.nav::back,
            )
            SettingsPage.Backup -> BackupPage(
                me = chrome.profile,
                backup = viewModel { models.settings.backup(chrome.profile.id) },
                chooseFile = models.settings.chooseFile,
                restored = chrome.restored,
                onRestoredShown = chrome.onRestoredShown,
                onBack = chrome.nav::back,
            )
            SettingsPage.Sync -> SyncPage(
                me = chrome.profile,
                sync = viewModel { models.settings.sync(chrome.profile.id) },
                time = models.time,
                onBack = chrome.nav::back,
            )
            else -> PreferencesPage(key.page, chrome.profile, viewModel { models.settings.preferences() }, onBack = chrome.nav::back)
        }
    }
    entry<ParentKey> { key ->
        val parent = models.parent ?: return@entry
        ParentPage(
            parent = viewModel { parent.page(chrome.profile.id, chrome.onLeaveKids) },
            review = { viewModel { parent.review() } },
            time = models.time,
            // By key, so a page leaving mid-transition can't pop what covers it.
            onClose = { chrome.nav.close(key) },
        )
    }
    entry<RestrictionsKey> { key ->
        RestrictionsPage(
            profileId = key.profileId,
            restrictions = viewModel { models.settings.restrictions() },
            time = models.time,
            // By key, so a page leaving mid-transition can't pop what covers it.
            onGone = { chrome.nav.close(key) },
        )
    }
}

/** Every phone page is portrait (Q13); the player turns on its own. */
@Composable
private fun PortraitOnly() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
