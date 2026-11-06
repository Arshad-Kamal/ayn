package com.arshad.ayn

import android.app.Application
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.SegmentSource
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.access.captions.TimedTextCaptions
import com.arshad.ayn.core.access.dearrow.DeArrowBranding
import com.arshad.ayn.core.access.defaultHttpClient
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.OkHttpDownloader
import com.arshad.ayn.core.access.potoken.EjsNChallengeSolver
import com.arshad.ayn.core.access.potoken.OkHttpBotguardService
import com.arshad.ayn.core.access.potoken.WebViewPoTokenSource
import com.arshad.ayn.core.access.ryd.ReturnYouTubeDislikeVotes
import com.arshad.ayn.core.access.sponsorblock.SponsorBlockSegments
import com.arshad.ayn.core.backup.BackupRepository
import com.arshad.ayn.core.backup.BackupStore
import com.arshad.ayn.core.backup.DownloadsBackupFiles
import com.arshad.ayn.core.filter.ContentFilters
import com.arshad.ayn.core.filter.FilteredPlaylistStore
import com.arshad.ayn.core.filter.FilteredRelatedListStore
import com.arshad.ayn.core.filter.FilteredSubscriptionStore
import com.arshad.ayn.core.filter.FilteredWatchStore
import com.arshad.ayn.core.filter.FilteringExtractor
import com.arshad.ayn.core.filter.FilteringLiveChat
import com.arshad.ayn.core.kids.KidsTimer
import com.arshad.ayn.core.lock.AwayRecords
import com.arshad.ayn.core.lock.LockRepository
import com.arshad.ayn.core.lock.PreferencesAwayRecords
import com.arshad.ayn.core.lock.ProfileLocks
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.core.lock.sessionLockOf
import com.arshad.ayn.core.store.ChannelFeedRepository
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.FetchAttemptRepository
import com.arshad.ayn.core.store.FilterRepository
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ForYouRepository
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.KidsPin
import com.arshad.ayn.core.store.KidsReviewKeeps
import com.arshad.ayn.core.store.KidsTimeRepository
import com.arshad.ayn.core.store.KidsTimeStore
import com.arshad.ayn.core.store.LastUsedProfile
import com.arshad.ayn.core.store.PlaylistRepository
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.PreferencesDeviceSettings
import com.arshad.ayn.core.store.PreferencesKidsPin
import com.arshad.ayn.core.store.PreferencesKidsReviewKeeps
import com.arshad.ayn.core.store.PreferencesLastUsedProfile
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsRepository
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.RelatedListRepository
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionRepository
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchRepository
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.core.sync.DriveSignIn
import com.arshad.ayn.core.sync.GoogleClient
import com.arshad.ayn.core.sync.GoogleDriveSignIn
import com.arshad.ayn.core.sync.NextcloudSignIn
import com.arshad.ayn.core.sync.SYNCED_TABLES
import com.arshad.ayn.core.sync.SyncRunner
import com.arshad.ayn.core.sync.SyncSignIn
import com.arshad.ayn.core.sync.SyncTriggers
import com.arshad.ayn.core.sync.googleClientOrNull
import com.arshad.ayn.core.sync.syncRunner
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.search.ApprovedSearch
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.playback.PlaybackHandle
import com.arshad.ayn.playback.PositionWriter
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * App-scoped dependencies (ARCH-5's manual container; Hilt not adopted, android-arch.md D3).
 * The extractor is app-scoped so its caches survive activity recreation; the Room database is
 * the D4 store (ARCH-6/8 — committed schema, no destructive migration). One `NewPipeAccess` per process (its init
 * installs process-global transport) carries the WebView PO-token bridge for both listings
 * and playback (listings never mint; the bridge only fires on stream extraction).
 * The subscriptions feed is app-scoped so Home and Subscriptions share its fetch cache; it keeps
 * each profile's last fetched channel feeds in the database for the next start (PERF P9c).
 * One OkHttp client serves extraction, BotGuard, SABR, SponsorBlock, DeArrow and Return YouTube
 * Dislike, so they share a connection pool.
 */
class KeApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // App scope: the TV going to sleep, and the screensaver's start, can land while KE is
        // stopped (M5-D9).
        val relockSignals = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> container.relockMonitor.onScreenOff()
                    Intent.ACTION_DREAMING_STARTED -> container.relockMonitor.onDreamingStarted()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_DREAMING_STARTED) }
        ContextCompat.registerReceiver(this, relockSignals, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        container.syncTriggers.start()
    }
}

class AppContainer(context: Context) {

    /** A TV gets the TV UI and its rules, anything else the phone's (M6-D7): the one form-factor flag. */
    val isTv: Boolean = context.getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /** A profile's settings before it writes any: a phone shows Shorts, a TV hides them (M6-D6). */
    private val profileDefaults = ProfileSettings(showShorts = !isTv)

    val httpClient: OkHttpClient by lazy { defaultHttpClient() }

    val poTokenSource: WebViewPoTokenSource by lazy {
        WebViewPoTokenSource(context, OkHttpBotguardService(httpClient))
    }

    /** Solves a made-for-kids video's MWEB stream challenge (spec 12 KP-D30); its WebView starts on first use. */
    val nChallengeSolver: EjsNChallengeSolver by lazy { EjsNChallengeSolver(context) }

    /** Filtered for the session profile (KP-D1): every listing, and the resolve gate in `video`. */
    val extractor: Extractor by lazy {
        FilteringExtractor(NewPipeAccess(poTokenSource, nChallengeSolver, youTubeDownloader), contentFilters::now)
    }

    /** The vendored extractor's transport, shared by the page seam and the live chat. */
    private val youTubeDownloader: OkHttpDownloader by lazy { OkHttpDownloader(httpClient, serverClock) }

    /** A live stream's chat, the session profile's keywords applied (LIVE-D17). */
    val liveChat: LiveChat by lazy { FilteringLiveChat(NewPipeLiveChat(youTubeDownloader), contentFilters::now) }

    /** Time from YouTube's InnerTube answers, which a kids profile's day is judged by (KP-D22). */
    val serverClock: ServerClock by lazy { ServerClock(elapsedRealtime) }

    /** The session's kids profile time (KP-D8): counted while MainActivity is started. */
    val kidsTimer: KidsTimer by lazy {
        KidsTimer(
            session = profileSession,
            filters = filterStore,
            ledger = kidsTimeStore,
            clock = serverClock,
            elapsed = { SystemClock.elapsedRealtime().milliseconds },
            endPlayback = playbackHandle::end,
        )
    }

    val kidsTimeStore: KidsTimeStore by lazy { KidsTimeRepository(database) }

    val filterStore: FilterStore by lazy { FilterRepository(database) }

    /** The session profile's content filter (KP-D20). */
    val contentFilters: ContentFilters by lazy { ContentFilters(profileSession, profileSettingsStore, filterStore) }

    /** Process-lifetime work (KT-CONC-02: the owning scope, not GlobalScope). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: KeDatabase by lazy { KeDatabase.open(context) }

    val profileStore: ProfileStore by lazy { ProfileRepository(database) }

    /** No profile is selected until the profile gate opens one (M5-D16). */
    val profileSession: ProfileSession by lazy { ProfileSession() }

    /** Time since boot, deep sleep included: lock waits on screen (the gate's, Settings') keep
     *  running while the TV sleeps, as the stored waits do. */
    val elapsedRealtime: TimeSource = ElapsedRealtime

    val lastUsedProfile: LastUsedProfile by lazy { PreferencesLastUsedProfile(context) }

    /** Where KE was left, for a restart after its process died (M5-D21). */
    val awayRecords: AwayRecords by lazy { PreferencesAwayRecords(context) }

    /** The kids profile Ayn reopens into until the owner's lock leaves it (KP-D9). */
    val kidsPin: KidsPin by lazy { PreferencesKidsPin(context) }

    /** The uploads the owner kept in each kids profile's parent review (Q17). */
    val kidsReviewKeeps: KidsReviewKeeps by lazy { PreferencesKidsReviewKeeps(context) }

    val backupStore: BackupStore by lazy {
        BackupRepository(
            database, profileSession, DownloadsBackupFiles(context), deviceSettingsStore,
            profileDefaults = profileDefaults, syncLinked = syncRunner::isLinked,
        )
    }

    /** The build's Google client; `null` without one, which hides Google Drive (M8-D1). */
    private val googleClient: GoogleClient? = googleClientOrNull(BuildConfig.GOOGLE_CLIENT_ID, BuildConfig.GOOGLE_CLIENT_SECRET)

    /** Cloud sync (M8): inert until the owner links a folder. Before each round the player saves
     *  its place and the queued saves land, so the upload carries it (M8-D8). */
    val syncRunner: SyncRunner by lazy {
        syncRunner(
            context, database, httpClient, profileDefaults, deviceName = Build.MODEL, google = googleClient,
            openProfileId = { profileSession.currentIdOrNull },
            beforeSnapshot = {
                withContext(Dispatchers.Main) { playbackHandle.saveMovedPlace() }
                positionWriter.drain()
            },
        )
    }

    /** Settings › Sync's Nextcloud sign-in (Login Flow v2) on the app's client. */
    val syncSignIn: SyncSignIn by lazy { NextcloudSignIn(httpClient) }

    /** Settings › Sync's Google Drive sign-in (device flow); `null` hides Drive. */
    val driveSignIn: DriveSignIn? by lazy { googleClient?.let { GoogleDriveSignIn(httpClient, it) } }

    /** Whether MainActivity is started; with a held video, Ayn counts as open (M8-D8). */
    @Volatile var screenStarted: Boolean = false

    val syncTriggers: SyncTriggers by lazy {
        SyncTriggers(
            sync = syncRunner::sync,
            scope = appScope,
            localChanges = database.invalidationTracker.createFlow(*SYNCED_TABLES, emitInitialState = false),
            profileSwitches = profileSession.selectedId,
            isOpen = { screenStarted || withContext(Dispatchers.Main) { playbackHandle.holdsItem() } },
        )
    }

    /** The running playback service's session, which a re-lock or profile switch ends (M5-D3). */
    val playbackHandle: PlaybackHandle by lazy { PlaybackHandle() }

    val subscriptionStore: SubscriptionStore by lazy {
        FilteredSubscriptionStore(SubscriptionRepository(database, profileSession), contentFilters)
    }

    val watchStore: WatchStore by lazy { FilteredWatchStore(WatchRepository(database, profileSession), contentFilters) }

    val playlistStore: PlaylistStore by lazy { FilteredPlaylistStore(PlaylistRepository(database, profileSession), contentFilters) }

    val relatedListStore: RelatedListStore by lazy {
        FilteredRelatedListStore(
            RelatedListRepository(database, profileSession, restrictedMode = { contentFilters.now().restrictedMode }),
            contentFilters,
        )
    }

    val forYouStore: ForYouStore by lazy { ForYouRepository(database, profileSession) }

    /** Device-wide: YouTube's limit is per IP (FY-D5). */
    val fetchAttemptLog: FetchAttemptLog by lazy { FetchAttemptRepository(database) }

    val profileSettingsStore: ProfileSettingsStore by lazy { ProfileSettingsRepository(database, profileSession, defaults = profileDefaults) }

    val deviceSettingsStore: DeviceSettingsStore by lazy { PreferencesDeviceSettings(context) }

    val segmentSource: SegmentSource by lazy { SponsorBlockSegments(httpClient) }

    val voteSource: VoteSource by lazy { ReturnYouTubeDislikeVotes(httpClient) }

    val captionSource: CaptionSource by lazy { TimedTextCaptions(httpClient) }

    /** App-scoped: DeArrow's answers are cached for the process, and a lookup outlives the card
     *  that asked (M7-D11). */
    val cardBranding: CardBranding by lazy { CardBranding(DeArrowBranding(httpClient), profileSettingsStore, profileSession.selectedId, appScope) }

    val profileLocks: ProfileLocks by lazy { LockRepository(database, profileSession) }

    /** Read synchronously by the re-lock rules; `null` until the first read lands. Collected
     *  from the moment [relockMonitor] is built. */
    private val profiles: StateFlow<List<Profile>?> by lazy {
        profileStore.profiles().stateIn(appScope, SharingStarted.Eagerly, initialValue = null)
    }

    val relockMonitor: RelockMonitor by lazy {
        val profiles = profiles
        RelockMonitor(elapsed = { SystemClock.elapsedRealtime().milliseconds }) {
            sessionLockOf(profiles.value, profileSession.currentIdOrNull)
        }
    }

    /** Whether the open profile is a kids profile; `false` while the profiles are unknown. */
    fun kidsProfileOpen(): Boolean = sessionLockOf(profiles.value, profileSession.currentIdOrNull)?.pinnedKids == true

    /** Whether the open profile is a kids profile, once the profiles are known (waits for the first
     *  read): for a gate that must not let a kids session through before they load. */
    suspend fun kidsProfileOpenNow(): Boolean =
        sessionLockOf(profiles.filterNotNull().first(), profileSession.currentIdOrNull)?.pinnedKids == true

    /** App-scoped so the playback service's last save outlives the service. */
    val positionWriter: PositionWriter by lazy { PositionWriter(watchStore, appScope) }

    val subscriptionsFeed: SubscriptionsFeed by lazy {
        SubscriptionsFeed(
            extractor,
            currentFilter = contentFilters::now,
            channelFeeds = ChannelFeedRepository(database, profileSession),
            writeScope = appScope,
        )
    }

    /** A kids profile's Search (KP-D10). */
    val approvedSearch: ApprovedSearch by lazy {
        ApprovedSearch(subscriptionsFeed, subscriptionStore, contentFilters::now) {
            filterStore.rules(profileSession.currentId()).first().approvedVideos
        }
    }
}

private object ElapsedRealtime : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}
