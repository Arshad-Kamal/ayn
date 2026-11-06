package com.arshad.ayn.ui.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.arshad.ayn.core.access.CaptionLookup
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.filter.ContentFilter
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
import com.arshad.ayn.core.sync.FakeSyncControl
import com.arshad.ayn.core.sync.FakeSyncSignIn
import com.arshad.ayn.feature.kids.KidsStores
import com.arshad.ayn.feature.player.NoPlayerSession
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** [MobileApp] over in-memory stores: no subscriptions and an empty Live kiosk, so Home is the blank
 *  slate. A kids profile's Home reads its picks from [filterStore] and [profileStore] through
 *  [contentFilter] when [kids] is given. */
@Composable
internal fun TestMobileApp(
    gate: ProfileGateViewModel,
    kids: KidsStores? = null,
    profileStore: FakeProfileStore = remember { FakeProfileStore() },
    filterStore: FakeFilterStore = remember { FakeFilterStore() },
    contentFilter: Flow<ContentFilter> = emptyFlow(),
    expandPlayer: Boolean = false,
    onPlayerExpanded: () -> Unit = {},
) {
    val extractor = remember { HomeExtractor() }
    val profileSettings = remember { FakeProfileSettingsStore() }
    MobileApp(
        gate = gate,
        extractor = extractor,
        subscriptionsFeed = remember(extractor) { SubscriptionsFeed(extractor) },
        subscriptionStore = remember { FakeSubscriptionStore() },
        watchStore = remember { FakeWatchStore() },
        playlistStore = remember { FakePlaylistStore() },
        relatedLists = remember { FakeRelatedListStore() },
        forYouStore = remember { FakeForYouStore() },
        fetchAttempts = remember { FakeFetchAttemptLog(Clock.System) },
        profileSettings = profileSettings,
        deviceSettings = remember { FakeDeviceSettingsStore(DeviceSettings()) },
        branding = null,
        contentFilter = contentFilter,
        filterStore = filterStore,
        profileStore = profileStore,
        profileLocks = remember { FakeProfileLocks() },
        lockTime = TimeSource.Monotonic,
        chooseFile = {},
        backupStore = remember { FakeBackupStore() },
        profileSession = remember { ProfileSession() },
        playerSession = NoPlayerSession,
        playback = {},
        votes = NoVotes,
        captions = NoCaptions,
        liveChat = remember { FakeLiveChat() },
        syncControl = remember { FakeSyncControl() },
        syncSignIn = remember { FakeSyncSignIn() },
        driveSignIn = null,
        requestSync = {},
        kids = kids,
        expandPlayer = expandPlayer,
        onPlayerExpanded = onPlayerExpanded,
    )
}

/** Pictures never touch the network in tests (TEST-DETERM-01): every request fails at once. */
internal fun installOfflineImageLoader(context: Context) {
    SingletonImageLoader.setUnsafe(
        ImageLoader.Builder(context)
            .components {
                add(
                    object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                            ErrorResult(null, chain.request, IOException("no network in tests"))
                    },
                )
            }
            .build(),
    )
}

/** Return YouTube Dislike with nothing to say. */
internal val NoVotes = VoteSource { VoteLookup.None }

/** Captions that never load (a test profile's captions are Off: none are asked for unless picked). */
internal val NoCaptions = CaptionSource { CaptionLookup.Failed }
