package com.arshad.ayn.ui.tv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.SessionToken
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.dearrow.RecordedBranding
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.home.KidsPicks
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.settings.SyncLaunchNotice
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.tv.common.ProvideDeviceSettings
import com.arshad.ayn.ui.tv.profiles.ShellOpening
import com.arshad.ayn.ui.tv.shell.KidsShell
import com.arshad.ayn.ui.tv.shell.ParentModels
import com.arshad.ayn.ui.tv.shell.SettingsModels
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.shell.TvShell
import com.arshad.ayn.ui.tv.theme.KeTheme
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf

/** The zone shell tests date things in, so day names and times don't follow the machine's. */
internal val TestZone: ZoneId = ZoneId.of("America/Toronto")

/** Saturday, October 3 2026, 9 pm in [TestZone]: "now" for shell tests. */
internal val TestNow: LocalDateTime = LocalDateTime.of(2026, 10, 3, 21, 0)

internal val TestTime = ShellTime(FixedClock(TestNow.inTestZone()), TestZone)

/** The rail's right edge at mdpi (1 dp = 1 px): page content lies right of it. */
internal const val RailEdgeDp = 100f

/** Return YouTube Dislike with no data for any video. */
internal val NoVotes = VoteSource { VoteLookup.None }

/** DeArrow through [source] (its recorded buckets by default) for profile 1, both switches on;
 *  lookups run in [scope], which the test cancels. */
internal fun testBranding(scope: CoroutineScope, source: BrandingSource = RecordedBranding.source()): CardBranding =
    FakeProfileSettingsStore(selectedId = 1).let { CardBranding(source, it, it.selected.filterNotNull(), scope) }

/** Zainab's kids profile, as a kids shell opens on it. */
internal val TestKidOpening = ShellOpening(
    TestProfile.copy(id = 4, name = "Zainab", isOwner = false, isKids = true),
    restored = null,
    settingsPickWaiting = false,
    onRestoredShown = {},
    onRailProfile = {},
)

/** A kids profile's shell on [clock] (no limit by default) with no picks; opening its Parent page fails the test. */
internal fun testKidsShell(clock: KidsClock = FakeKidsClock()): KidsShell = KidsShell(
    clock,
    ParentModels(page = { _, _ -> error("no Parent page in this test") }, review = { error("no review in this test") }),
) { flowOf(KidsPicks.None) }

/** What the player was opened with, while the shell shows it. */
internal fun NavHostController.playerRoute(): PlayerRoute? =
    currentBackStackEntry?.takeIf { it.destination.hasRoute(PlayerRoute::class) }?.toRoute<PlayerRoute>()

/** The player's ViewModel, while the shell shows the player (it is never created here). */
internal fun NavHostController.playerViewModel(): PlayerViewModel =
    ViewModelProvider.create(checkNotNull(currentBackStackEntry).viewModelStore, object : ViewModelProvider.Factory {})[PlayerViewModel::class]

/** [this] local time in [TestZone]. */
internal fun LocalDateTime.inTestZone(): Instant = atZone(TestZone).toInstant().toKotlinInstant()

/**
 * The real shell in the Cinema theme for a screen test: every seam a fake unless the test passes
 * its own (the defaults are remembered, so a recomposition keeps them), Home on its blank slate,
 * [TestOpening]'s profile, Settings' [TestSettings], [TestTime], and YouTube's titles and pictures
 * unless a test passes a [branding], and the TV's [deviceSettings] (through the app's provider).
 * Card menus block through [menuBlocking] (fakes with no profiles: no Block rows), and no filter
 * hides anything on screen unless a test passes a [contentFilter]. A kids profile's shell needs
 * [kids] and an [opening] of a kids profile. Home tells a [syncNotice]'s failed sync.
 */
@Composable
internal fun TestShell(
    extractor: Extractor = remember { HomeExtractor() },
    subscriptionsFeed: SubscriptionsFeed = remember(extractor) { SubscriptionsFeed(extractor) },
    subscriptionStore: SubscriptionStore = remember { FakeSubscriptionStore() },
    watchStore: WatchStore = remember { FakeWatchStore() },
    playlistStore: PlaylistStore = remember { FakePlaylistStore() },
    relatedLists: RelatedListStore = remember { FakeRelatedListStore() },
    forYouStore: ForYouStore = remember { FakeForYouStore() },
    fetchAttempts: FetchAttemptLog = remember { FakeFetchAttemptLog(Clock.System) },
    profileSettings: ProfileSettingsStore = remember { FakeProfileSettingsStore(selectedId = 1) },
    votes: VoteSource = NoVotes,
    captions: CaptionSource = remember { RecordedCaptions.source() },
    liveChat: LiveChat = remember { FakeLiveChat() },
    branding: CardBranding? = null,
    playerSession: (Context) -> SessionToken = NoPlayerSession,
    settingsModels: SettingsModels = TestSettings,
    opening: ShellOpening = TestOpening,
    time: ShellTime = TestTime,
    navController: NavHostController = rememberNavController(),
    deviceSettings: DeviceSettings = DeviceSettings(),
    menuBlocking: MenuBlocking = remember { MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic) },
    contentFilter: Flow<ContentFilter> = remember { flowOf(ContentFilter.of(ProfileSettings(), ProfileRules())) },
    kids: KidsShell? = null,
    syncNotice: SyncLaunchNotice? = null,
) {
    KeTheme {
        ProvideDeviceSettings(remember(deviceSettings) { FakeDeviceSettingsStore(deviceSettings) }) {
            TvShell(
                extractor = extractor,
                subscriptionsFeed = subscriptionsFeed,
                subscriptionStore = subscriptionStore,
                watchStore = watchStore,
                playlistStore = playlistStore,
                relatedLists = relatedLists,
                forYouStore = forYouStore,
                fetchAttempts = fetchAttempts,
                profileSettings = profileSettings,
                votes = votes,
                captions = captions,
                liveChat = liveChat,
                branding = branding,
                playerSession = playerSession,
                settingsModels = settingsModels,
                opening = opening,
                menuBlocking = menuBlocking,
                contentFilter = contentFilter,
                time = time,
                // The watch store's readers map on the main looper the test idles.
                compute = Dispatchers.Main,
                navController = navController,
                kids = kids,
                syncNotice = syncNotice,
            )
        }
    }
}
