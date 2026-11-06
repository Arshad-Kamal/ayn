package com.arshad.ayn.ui.tv.shell

import com.arshad.ayn.core.access.LiveChat
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.SessionToken
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.home.homeViewModel
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.playlist.PlaylistViewModel
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.search.ApprovedSearch
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.LAUNCH_NOTICE_MS
import com.arshad.ayn.feature.settings.LaunchNotice
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.ParentRoute
import com.arshad.ayn.ui.tv.kids.ParentPage
import com.arshad.ayn.feature.library.HistoryViewModel
import com.arshad.ayn.feature.library.LibraryViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.SavedPlaylistViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.nav.HistoryRoute
import com.arshad.ayn.feature.nav.LibraryRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import com.arshad.ayn.ui.tv.library.HistoryScreen
import com.arshad.ayn.ui.tv.library.LibraryScreen
import com.arshad.ayn.ui.tv.library.SavedPlaylistScreen
import java.time.ZoneId
import kotlin.time.Clock
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.feature.nav.SearchRoute
import com.arshad.ayn.feature.nav.SettingsRoute
import com.arshad.ayn.feature.nav.SubscriptionsRoute
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.channel.ChannelScreen
import com.arshad.ayn.ui.tv.common.LocalCardBranding
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.TOAST_MS
import com.arshad.ayn.ui.tv.common.Toast
import com.arshad.ayn.ui.tv.settings.launchNoticeText
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.home.HomeScreen
import com.arshad.ayn.ui.tv.player.PlayerScreen
import com.arshad.ayn.ui.tv.playlist.PlaylistScreen
import com.arshad.ayn.ui.tv.profiles.ShellOpening
import com.arshad.ayn.ui.tv.search.SearchScreen
import com.arshad.ayn.ui.tv.settings.SettingsScreen
import com.arshad.ayn.ui.tv.subscriptions.SubscriptionsScreen
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.ui.tv.theme.KeLayout

/** Built top-level destinations only, so no rail item leads to a dead end. */
internal val railDestinations: List<RailDestination> = listOf(
    RailDestination("Home", R.drawable.ic_nav_home, HomeRoute),
    RailDestination("Search", R.drawable.ic_nav_search, SearchRoute),
    RailDestination("Subscriptions", R.drawable.ic_nav_subscriptions, SubscriptionsRoute),
    RailDestination("Library", R.drawable.ic_nav_library, LibraryRoute),
    RailDestination("Settings", R.drawable.ic_nav_settings, SettingsRoute),
)

/** A kids profile's rail (`kp-kids` kidrail): Home, Search and Parent behind the owner's lock (KP-D11). */
internal val kidsRailDestinations: List<RailDestination> =
    railDestinations.filter { it.route == HomeRoute || it.route == SearchRoute } + RailDestination("Parent", R.drawable.ic_lock, ParentRoute)

// Mockup: while the rail holds focus the page recedes to 30% and slides 60 px right. The 30% is a
// Ground veil over the page (drawn on the shell's opaque Ground), the same pixels as fading the
// page without rendering it offscreen each frame.
private const val RecededAlpha = 0.3f
private val RecededShift = 30.dp

/**
 * TV shell: pages draw full-bleed under the rail (the player can cover it), the rail floats
 * at the left. BACK unwinds linearly (CMP-UX-10): a top-level switch keeps Home underneath,
 * so BACK from Search or Subscriptions returns to Home and BACK on Home leaves the app;
 * every destination restores its own focus on return ([ScreenFocus]). The player covers
 * everything and hides the rail; [playerSession] names the playback service's session, and the
 * player skips segments by [profileSettings], asks [votes] for Details' dislikes, [captions]
 * for a track's cues and [liveChat] for a live stream's chat. Home's For you reads [forYouStore] and spends the budget kept in
 * [fetchAttempts] (FY-D5). Every video's title and picture are its [branding] face (M7-D11; `null`
 * shows YouTube's).
 * [opening] is the open profile; its rail slot calls [ShellOpening.onRailProfile], and a shell
 * opened by a Settings restore starts on Settings › Backup & restore with its results (Q9,
 * KP-D25), and one whose Settings has a
 * file pick waiting goes there (M5-D20). Settings dims or hides the rail while a sheet or a
 * covering screen shows, as every screen with a card menu does under it. [settingsModels] builds
 * Settings' ViewModels and opens its file picker; [time] dates the Library's history and saves.
 * The watch store's readers (Home, the player, the Library's pages) map on [compute].
 * A Short opened on Search, Subscriptions or a channel plays in a run through the list the screen
 * hands over (M7-D13); the list lives in this composition, so after a process death the player
 * gets none and plays the Short alone. A kids profile's Search is [approvedSearch]'s local match (KP-D10).
 * Every card menu blocks through [menuBlocking] (KP-D19), and the open profile's [contentFilter]
 * reaches the screens as [LocalContentFilter], so a block takes cards already shown out at once.
 * A kids profile's shell ([kids], `kp-kids` kidhome) has Home, Search and the Parent page, Home
 * with the owner's picks, its time left today at the top end on every page but the player, and its
 * time warnings at the bottom end on every page (warn5, warn1); its channel pages and player have
 * no Subscribe, and no Turn on Shorts (KP-D11, D12). The player's picture goes to
 * [ShellOpening.onPicture], for Time's up to draw over (KP-D26). Home and Subscriptions learn
 * whether a visit is an arrival from the rail or a return to the foreground ([Arrivals]), which
 * lands a fresh feed they held (PERF P9c). On Home, this launch's failed sync shows as a toast for
 * [LAUNCH_NOTICE_MS] that takes no focus ([syncNotice], M8-D11).
 */
@Composable
internal fun TvShell(
    extractor: Extractor,
    subscriptionsFeed: SubscriptionsFeed,
    subscriptionStore: SubscriptionStore,
    watchStore: WatchStore,
    playlistStore: PlaylistStore,
    relatedLists: RelatedListStore,
    forYouStore: ForYouStore,
    fetchAttempts: FetchAttemptLog,
    profileSettings: ProfileSettingsStore,
    votes: VoteSource,
    captions: CaptionSource,
    liveChat: LiveChat,
    branding: CardBranding?,
    playerSession: (Context) -> SessionToken,
    settingsModels: SettingsModels,
    opening: ShellOpening,
    menuBlocking: MenuBlocking,
    contentFilter: Flow<ContentFilter>,
    modifier: Modifier = Modifier,
    // Remembered: a fresh one each recomposition would stop the screens taking it from skipping.
    time: ShellTime = remember { ShellTime() },
    compute: CoroutineDispatcher = Dispatchers.Default,
    navController: NavHostController = rememberNavController(),
    approvedSearch: ApprovedSearch? = null,
    kids: KidsShell? = null,
    syncNotice: SyncLaunchNotice? = null,
) {
    val kidsShell = kids?.takeIf { opening.profile.isKids }
    val destinations = if (kidsShell != null) kidsRailDestinations else railDestinations
    val backStackEntry by navController.currentBackStackEntryAsState()
    // The stack is Home, at most one other top-level destination (switches pop to Home), then
    // pages: a Channel or Playlist page sits inside that destination, which is the rail's current
    // item there too, so LEFT reaches the rail on those pages. getBackStackEntry throws for a
    // route not on the stack (NavController.currentBackStack is library-restricted).
    val current = remember(backStackEntry, destinations) {
        // No entry yet: the graph isn't set, and the start destination is Home.
        destinations.firstOrNull { destination ->
            backStackEntry != null && destination.route != HomeRoute && navController.holds(destination.route)
        } ?: destinations.first { it.route == HomeRoute }
    }
    val onTopLevel = current.isShowing(backStackEntry?.destination)
    val onPlayer = NavDestination.Companion.run { backStackEntry?.destination?.hasRoute(PlayerRoute::class) == true }
    val railCurrentItem = remember { FocusRequester() }
    // RIGHT from the rail: the page under it is full-bleed, so its left edge is under the rail
    // and spatial search never counts it as right of a rail item. Asked directly, the page's
    // ScreenFocus restores the item it last held.
    val page = remember { FocusRequester() }
    var railOpen by remember { mutableStateOf(false) }
    val recede = animateFloatAsState(if (railOpen) 1f else 0f, KeMotion.move(), label = "recede")
    val focusRail: () -> Unit = { railCurrentItem.requestFocus() }
    val showTopLevel: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo<HomeRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val arrivals = remember { Arrivals() }
    LaunchedEffect(backStackEntry) { arrivals.shown(backStackEntry?.destination?.id) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { arrivals.arrive(navController.currentDestination?.id) }

    var railShade by remember { mutableStateOf(RailShade.Lit) }
    val railAlpha = animateFloatAsState(railShade.alpha, KeMotion.base(), label = "railShade")
    // Q9: the restored owner's shell lands on Settings' restore results, Home beneath it.
    val restored = opening.restored
    LaunchedEffect(restored != null) {
        if (restored != null) showTopLevel(SettingsRoute)
    }
    // A pick made in Settings before KE's process went, or before a re-lock: Settings takes it.
    val pickWaiting = opening.settingsPickWaiting
    LaunchedEffect(pickWaiting) {
        val onSettings = NavDestination.Companion.run { navController.currentDestination?.hasRoute(SettingsRoute::class) == true }
        if (pickWaiting && !onSettings) showTopLevel(SettingsRoute)
    }

    val play: (VideoItem, Boolean) -> Unit = { video, startOver -> navController.navigate(PlayerRoute(video, startOver = startOver)) }
    // Read only by the player's ViewModel factory: no composition reads it.
    val handedShorts = remember { ShortsHandOver() }
    val playShort: (VideoItem, ShortsList) -> Unit = { short, run ->
        handedShorts.list = run
        navController.navigate(PlayerRoute(short, short = true))
    }
    val playRoute: (PlayerRoute) -> Unit = { route -> navController.navigate(route) }
    val openChannel: (String) -> Unit = { url -> navController.navigate(ChannelRoute(url)) }
    val goHome: () -> Unit = { navController.popBackStack(HomeRoute, inclusive = false) }
    val setRailShade: (RailShade) -> Unit = { railShade = it }
    // One card menu per destination: viewModel {} scopes it to the back-stack entry it is called in.
    val videoMenu: @Composable () -> VideoMenuViewModel = {
        viewModel { VideoMenuViewModel(watchStore, playlistStore, profileSettings, menuBlocking) }
    }
    val filter by contentFilter.collectAsStateWithLifecycle(initialValue = null)
    val kidsTimeFlow = remember(kidsShell) { kidsShell?.clock?.time ?: flowOf(KidsTime.Unlimited) }
    val kidsTime by kidsTimeFlow.collectAsStateWithLifecycle(initialValue = KidsTime.Unlimited)
    val timeLeft = (kidsTime as? KidsTime.Left)?.left
    val warningFlow = remember(kidsShell) { kidsShell?.clock?.warning ?: flowOf(null) }
    val warning by warningFlow.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(warning, kidsShell) {
        if (warning == null) return@LaunchedEffect
        delay(TOAST_MS)
        kidsShell?.clock?.consumeWarning()
    }
    val onHome = NavDestination.Companion.run { backStackEntry?.destination?.hasRoute(HomeRoute::class) == true }
    // Time's up draws over the player's picture (KP-D26); only a recomposition here changes it.
    val playerPicture = remember(backStackEntry, onPlayer) { backStackEntry?.takeIf { onPlayer }?.toRoute<PlayerRoute>()?.thumbnailUrl }
    SideEffect { opening.onPicture(playerPicture) }

    CompositionLocalProvider(LocalCardBranding provides branding, LocalContentFilter provides filter) {
        Box(modifier.fillMaxSize().background(KeColors.Ground)) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(page)
                    .graphicsLayer { translationX = RecededShift.toPx() * recede.value },
                // D8: transform/alpha only, nothing layout-animates.
                enterTransition = { fadeIn(KeMotion.base()) },
                exitTransition = { fadeOut(KeMotion.base()) },
            ) {
                composable<HomeRoute> { entry ->
                    HomeScreen(
                        viewModel = viewModel {
                            homeViewModel(
                                extractor, subscriptionsFeed, subscriptionStore, watchStore, playlistStore, relatedLists, forYouStore, fetchAttempts, profileSettings,
                                kidsPicks = kidsShell?.picks?.invoke(opening.profile.id),
                                compute = compute,
                            )
                        },
                        menu = videoMenu(),
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = play,
                        onOpenChannel = openChannel,
                        onSearch = { showTopLevel(SearchRoute) },
                        onOpenLibrary = { showTopLevel(LibraryRoute) }.takeIf { kidsShell == null },
                        onRailShade = setRailShade,
                        consumeArrival = { arrivals.consume(entry.destination.id) },
                    )
                }
                composable<SearchRoute> {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(extractor, profileSettings, approvedSearch) },
                        menu = videoMenu(),
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = play,
                        onPlayShort = playShort,
                        onOpenChannel = openChannel,
                        onRailShade = setRailShade,
                        approvedFor = opening.profile.name.takeIf { kidsShell != null },
                    )
                }
                composable<SubscriptionsRoute> { entry ->
                    SubscriptionsScreen(
                        viewModel = viewModel { SubscriptionsViewModel(subscriptionsFeed, subscriptionStore, profileSettings) },
                        menu = videoMenu(),
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = play,
                        onPlayShort = playShort,
                        onOpenChannel = openChannel,
                        onFindChannel = { showTopLevel(SearchRoute) },
                        onRailShade = setRailShade,
                        consumeArrival = { arrivals.consume(entry.destination.id) },
                    )
                }
                composable<LibraryRoute> {
                    LibraryScreen(
                        viewModel = viewModel { LibraryViewModel(watchStore, playlistStore, compute = compute) },
                        menu = videoMenu(),
                        time = time,
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = playRoute,
                        onOpenChannel = openChannel,
                        onOpenHistory = { navController.navigate(HistoryRoute) },
                        onOpenPlaylist = { id -> navController.navigate(SavedPlaylistRoute(id)) },
                        onGoHome = goHome,
                        onRailShade = setRailShade,
                    )
                }
                composable<SavedPlaylistRoute> { entry ->
                    val playlistId = entry.toRoute<SavedPlaylistRoute>().playlistId
                    SavedPlaylistScreen(
                        viewModel = viewModel { SavedPlaylistViewModel(playlistId, playlistStore, watchStore, compute = compute) },
                        menu = videoMenu(),
                        time = time,
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = playRoute,
                        onOpenChannel = openChannel,
                        // By route, so a page leaving mid-transition can't pop what covers it.
                        onGone = { navController.popBackStack<SavedPlaylistRoute>(inclusive = true) },
                        onRailShade = setRailShade,
                    )
                }
                composable<HistoryRoute> {
                    HistoryScreen(
                        viewModel = viewModel { HistoryViewModel(watchStore, time.clock, time.zone, compute = compute) },
                        menu = videoMenu(),
                        time = time,
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = playRoute,
                        onOpenChannel = openChannel,
                        onGoHome = goHome,
                        onRailShade = setRailShade,
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        profiles = viewModel { settingsModels.profiles() },
                        backup = viewModel { settingsModels.backup(opening.profile.id) },
                        preferences = viewModel { settingsModels.preferences() },
                        filters = viewModel { settingsModels.filters() },
                        restrictions = viewModel { settingsModels.restrictions() },
                        kidsAdd = viewModel { settingsModels.kidsAdd() },
                        sync = viewModel { settingsModels.sync(opening.profile.id) },
                        restored = restored,
                        pickWaiting = pickWaiting,
                        chooseFile = { kind -> settingsModels.chooseFile(PickRequest(kind, opening.profile.id)) },
                        onRestoredShown = opening.onRestoredShown,
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onRailShade = setRailShade,
                        time = time,
                    )
                }
                composable<ParentRoute> {
                    val shell = kidsShell ?: return@composable
                    ParentPage(
                        parent = viewModel { shell.parent.page(opening.profile.id, opening.onLeaveKids) },
                        review = { viewModel { shell.parent.review() } },
                        time = time,
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onClose = goHome,
                        onRailShade = setRailShade,
                    )
                }
                composable<ChannelRoute> { entry ->
                    val channelUrl = entry.toRoute<ChannelRoute>().channelUrl
                    ChannelScreen(
                        kids = kidsShell != null,
                        viewModel = viewModel { ChannelViewModel(channelUrl, extractor, subscriptionStore, profileSettings) },
                        menu = videoMenu(),
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = play,
                        onPlayShort = playShort,
                        onOpenPlaylist = { card -> navController.navigate(PlaylistRoute(card)) },
                        onBack = { navController.popBackStack() },
                        onRailShade = setRailShade,
                    )
                }
                composable<PlaylistRoute> { entry ->
                    val card = entry.toRoute<PlaylistRoute>().card()
                    PlaylistScreen(
                        viewModel = viewModel { PlaylistViewModel(card, extractor) },
                        menu = videoMenu(),
                        onNothingToFocus = focusRail,
                        focusHeldElsewhere = { railOpen },
                        onPlay = { from, startOver -> navController.navigate(PlayerRoute(from, PlayAll.YouTube(card), startOver)) },
                        onOpenChannel = openChannel,
                        onBack = { navController.popBackStack() },
                        onRailShade = setRailShade,
                    )
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    PlayerScreen(
                        kids = kidsShell != null,
                        viewModel = viewModel {
                            // None handed over: an empty run, which the player plays the Short alone from.
                            val shorts = if (route.short) handedShorts.list ?: ShortsList(emptyList()) else null
                            PlayerViewModel(route.video(), route.playAll(), extractor, subscriptionStore, watchStore, playlistStore, profileSettings, votes, captions, liveChat, route.startOver, shorts, compute = compute)
                        },
                        menu = videoMenu(),
                        sessionToken = playerSession,
                        onOpenChannel = openChannel,
                        onBack = { navController.popBackStack() },
                        clock = time.clock,
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val veil = (1f - RecededAlpha) * recede.value
                    if (veil > 0f) drawRect(KeColors.Ground, alpha = veil)
                },
            )
            // D8: the rail fades with the player rather than popping.
            AnimatedVisibility(visible = !onPlayer, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.base())) {
                KeRail(
                    destinations = destinations,
                    current = current,
                    open = railOpen,
                    currentItem = railCurrentItem,
                    page = page,
                    profile = opening.profile,
                    inert = railShade != RailShade.Lit,
                    modifier = Modifier.graphicsLayer { alpha = railAlpha.value },
                    // Reselecting the destination under a page returns to it; restoring its saved
                    // stack would reopen the same page.
                    onSelect = { destination ->
                        if (destination === current && !onTopLevel) {
                            navController.popBackStack(destination.route, inclusive = false)
                        } else {
                            showTopLevel(destination.route)
                        }
                        arrivals.arrive(navController.currentDestination?.id)
                    },
                    onProfile = opening.onRailProfile,
                    onOpenChange = { railOpen = it },
                )
            }
            AnimatedVisibility(
                visible = !onPlayer && timeLeft != null,
                enter = fadeIn(KeMotion.base()),
                exit = fadeOut(KeMotion.base()),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = KeLayout.SafeVertical, end = KeLayout.SafeHorizontal),
            ) {
                // Kept while it fades out, when the time is no longer Left.
                val shown = remember { LastTimeLeft() }
                timeLeft?.let { shown.left = it }
                shown.left?.let { TimeLeftChip(it, opening.profile.tint.color) }
            }
            TimeWarning(warning, opening.profile.tint.color, Modifier.align(Alignment.BottomEnd))
            LaunchNoticeToast(syncNotice, onHome, opening.profile.id, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * This launch's failed sync (M8-D11) as a toast for [LAUNCH_NOTICE_MS] that takes no focus: taken
 * from [syncNotice] for [profileId] while [onHome] and Ayn is started (one taken while Ayn is away
 * would go unseen).
 */
@Composable
private fun LaunchNoticeToast(syncNotice: SyncLaunchNotice?, onHome: Boolean, profileId: Long, modifier: Modifier = Modifier) {
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    var notice by remember { mutableStateOf<LaunchNotice?>(null) }
    var up by remember { mutableStateOf(false) }
    val waiting = onHome && started
    LaunchedEffect(waiting, syncNotice, profileId) {
        if (!waiting) return@LaunchedEffect
        syncNotice?.take(profileId)?.let {
            notice = it
            up = true
        }
    }
    LaunchedEffect(up) {
        if (!up) return@LaunchedEffect
        delay(LAUNCH_NOTICE_MS)
        up = false
    }
    notice?.let {
        val (text, muted) = launchNoticeText(it)
        Toast(up, R.drawable.ic_alert, text, muted, modifier, warn = true, separator = " ")
    }
}

// Written while the chip shows so it can fade out with its last value; no composition reads it for invalidation.
private class LastTimeLeft {
    var left: Duration? = null
}

/**
 * The destination whose next visit is an arrival (PERF P9c): the one the rail just showed, or the
 * one showing when the app left the foreground. Any other destination taking the top clears it,
 * so a visit made with BACK (from the player, a channel page) is never one.
 */
private class Arrivals {
    private var destinationId: Int? = null

    fun arrive(destinationId: Int?) {
        this.destinationId = destinationId
    }

    /** The top of the stack is now [destinationId]. */
    fun shown(destinationId: Int?) {
        if (destinationId != this.destinationId) this.destinationId = null
    }

    /** Whether this visit of [destinationId] is an arrival; asked once per visit. */
    fun consume(destinationId: Int): Boolean = (destinationId == this.destinationId).also { this.destinationId = null }
}

/** The Shorts a screen last opened a Short from, for the player it opens (M7-D13); a later Short
 *  replaces it, and a player whose Short isn't in it plays the Short alone. */
private class ShortsHandOver {
    var list: ShortsList? = null
}

/** The clock and time zone the Library names days and ages by (fixed in tests). */
internal data class ShellTime(val clock: Clock = Clock.System, val zone: ZoneId = ZoneId.systemDefault())

/** Builds Settings' ViewModels in the shell's per-opening store (their dependencies are the app's;
 *  [backup] and [sync] for the opening's profile id) and opens the file picker ([chooseFile]). */
internal class SettingsModels(
    val profiles: () -> ProfilesViewModel,
    val backup: (profileId: Long) -> BackupViewModel,
    val preferences: () -> PreferencesViewModel,
    val filters: () -> FiltersViewModel,
    val restrictions: () -> RestrictionsViewModel,
    val kidsAdd: () -> KidsAddViewModel,
    val sync: (profileId: Long) -> SyncViewModel,
    val chooseFile: (PickRequest) -> Unit,
)

private fun NavHostController.holds(route: Any): Boolean =
    try {
        getBackStackEntry(route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
