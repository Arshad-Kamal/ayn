package com.arshad.ayn.ui.mobile.library

import android.content.Context
import androidx.media3.session.SessionToken
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.CommentsSeam
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.SearchExtractor
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.backup.FakeBackupStore
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
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.sync.FakeSyncControl
import com.arshad.ayn.core.sync.FakeDriveSignIn
import com.arshad.ayn.core.sync.FakeSyncSignIn
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.home.homeViewModel
import com.arshad.ayn.feature.library.HistoryViewModel
import com.arshad.ayn.feature.library.LibraryViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.SavedPlaylistViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.playlist.PlaylistViewModel
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.ProfilePlayback
import com.arshad.ayn.feature.profiles.SettingsPicks
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.ui.mobile.NoCaptions
import com.arshad.ayn.ui.mobile.NoVotes
import com.arshad.ayn.ui.mobile.ShellModels
import com.arshad.ayn.ui.mobile.kids.ParentModels
import com.arshad.ayn.ui.mobile.player.PlayerModels
import com.arshad.ayn.ui.mobile.settings.SettingsModels
import com.arshad.ayn.ui.mobile.shell.ShellTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal val TestZone: ZoneId = ZoneId.of("America/Toronto")

/** Saturday, October 3 2026, 9 pm in [TestZone]. */
internal val TestTime = ShellTime(FixedClock(LocalDateTime.of(2026, 10, 3, 21, 0).inTestZone()), TestZone)

internal fun LocalDateTime.inTestZone(): Instant = atZone(TestZone).toInstant().toKotlinInstant()

/** The stores and seams the shell's pages read in tests: Home on the blank slate, channels and playlists recorded. */
internal class TestStores(
    private val session: (Context) -> SessionToken = NoPlayerSession,
    private val captions: CaptionSource = NoCaptions,
    private val votes: VoteSource = NoVotes,
    private val playback: ProfilePlayback = ProfilePlayback {},
    private val liveChat: LiveChat = FakeLiveChat(),
) {
    val watch = FakeWatchStore()
    val playlists = FakePlaylistStore()
    val settings = FakeProfileSettingsStore(selectedId = 1)
    val channels = ChannelExtractor()
    val subscriptions = FakeSubscriptionStore()
    val device = FakeDeviceSettingsStore(DeviceSettings())
    val people = FakeProfileStore()
    val filters = FakeFilterStore()
    val backups = FakeBackupStore()
    val profileSession = ProfileSession()
    val locks = FakeProfileLocks()

    /** Settings › Sync's runner and its Nextcloud and Google sign-ins; [syncRequests] counts Sync now's rounds. */
    val sync = FakeSyncControl()
    val signIn = FakeSyncSignIn()
    val drive = FakeDriveSignIn()
    var syncRequests = 0

    /** Settings' file picks: the waiting one ([picks]), and each picker opened ([picked]). */
    val picks = TestPicks()
    val picked = mutableListOf<PickRequest>()

    /** Find a channel's searches (a kids profile's add flow). */
    val search = SearchExtractor()

    /** The player's seam: the channels' answers, comments from the recorded session. */
    val comments = CommentsSeam(channels)

    /** The pages' ViewModels; [parent] builds a kids profile's Parent page and [kidsClock] is its
     *  time (none by default); [time] dates the shell's pages and the player's ages. */
    fun models(parent: ParentModels? = null, kidsClock: KidsClock? = null, time: ShellTime = TestTime): ShellModels {
        val home = HomeExtractor()
        val blocking = MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic)
        val menu = { VideoMenuViewModel(watch, playlists, settings, blocking) }
        return ShellModels(
            home = { _ ->
                homeViewModel(home, SubscriptionsFeed(home), FakeSubscriptionStore(), watch, playlists, FakeRelatedListStore(), FakeForYouStore(), FakeFetchAttemptLog(Clock.System), settings)
            },
            menu = menu,
            search = { SearchViewModel(home, settings, null) },
            subscriptions = { SubscriptionsViewModel(SubscriptionsFeed(home), FakeSubscriptionStore(), settings) },
            channel = { url -> ChannelViewModel(url, channels, FakeSubscriptionStore(), settings) },
            library = { LibraryViewModel(watch, playlists) },
            history = { HistoryViewModel(watch, time.clock, time.zone) },
            savedPlaylist = { id -> SavedPlaylistViewModel(id, playlists, watch) },
            playlist = { card -> PlaylistViewModel(card, channels) },
            marks = watch.history().map { it.marks() },
            time = time,
            kidsClock = kidsClock,
            settings = SettingsModels(
                preferences = { PreferencesViewModel(settings, device, people) },
                filters = { FiltersViewModel(settings, filters, people, locks) },
                profiles = { ProfilesViewModel(people, locks, filters, profileSession, TimeSource.Monotonic) },
                kidsAdd = { KidsAddViewModel(people, locks, filters, subscriptions, search, TimeSource.Monotonic) },
                restrictions = { RestrictionsViewModel(filters, people, locks, TimeSource.Monotonic) },
                backup = { profileId -> BackupViewModel(backups, people, locks, playback, handover = { _, _ -> }, picks, profileId, TimeSource.Monotonic) },
                sync = { profileId -> SyncViewModel(sync, signIn, drive, people, locks, { syncRequests++ }, profileId, TimeSource.Monotonic) },
                savedBackups = { backups.savedBackups().size },
                syncStatus = sync.status,
                chooseFile = { picked += it },
            ),
            player = PlayerModels(
                player = { route, shorts ->
                    PlayerViewModel(route.video(), route.playAll(), comments, subscriptions, watch, playlists, settings, votes, captions, liveChat, route.startOver, shorts)
                },
                menu = menu,
                session = session,
                playback = playback,
            ),
            parent = parent,
        )
    }
}

/** A Settings pick waiting for the Backup page, as the gate holds one. */
internal class TestPicks : SettingsPicks {
    override val settingsPick = MutableStateFlow<PickedFile?>(null)

    override fun takeSettingsPick(pick: PickedFile): Boolean = settingsPick.compareAndSet(pick, null)
}

/** A history entry watched at [at] (the test zone), [positionMs] into a [durationMs] video. */
internal fun watched(id: String, title: String, channel: String, at: LocalDateTime, positionMs: Long, durationMs: Long, watched: Boolean = false): WatchEntry {
    val millis = at.inTestZone().toEpochMilliseconds()
    return WatchEntry(VideoId(id), title, channel, null, null, positionMs, durationMs, watched, millis, millis)
}

internal val Enigma = watched("JsBZOcqZerk", "History and operating principles of the Enigma", "Veritasium", LocalDateTime.of(2026, 10, 3, 20, 12), 2_091_000, 2_862_000)
internal val PoleLap = watched("q7iVFMM0XQA", "Max Verstappen's Pole Lap", "FORMULA 1", LocalDateTime.of(2026, 10, 3, 19, 40), 125_000, 125_000, watched = true)
internal val Civilizations = watched("QW_jlUn4gA8", "We Imagine Godlike Civilizations the Wrong Way", "Kurzgesagt", LocalDateTime.of(2026, 10, 2, 22, 5), 310_000, 852_000)
internal val Heist = watched("_L1Rim7-IPo", "Most Genius Casino Heist Ever", "Mark Rober", LocalDateTime.of(2026, 10, 1, 21, 2), 5_000, 2_063_000)

/** Most recently watched first, as the store lists them. */
internal val RecordedHistory = listOf(Enigma, PoleLap, Civilizations, Heist)

internal fun savedVideo(id: String, title: String, channel: String, seconds: Long): VideoItem =
    VideoItem(VideoId(id), title, channel, null, seconds, -1, null, null, null, false)
