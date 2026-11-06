package com.arshad.ayn.feature.foryou

import android.util.Log
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.ForYouStore
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.forYouOn
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The session profile's For you, ranked from what its stores hold now: seeds ([pickSeeds]) from
 * its history, Watch later, every playlist and the subscription uploads it is handed, their kept
 * related lists, and the profile's subscriptions, impressions and hidden videos and channels
 * ([rankForYou]). It reads the stores only; fetching missing lists is [ForYouRefresher]'s. The
 * reads and the ranking run on [compute]: they grow with the profile's history.
 */
class ForYouBuilder(
    private val watchStore: WatchStore,
    private val playlistStore: PlaylistStore,
    private val subscriptionStore: SubscriptionStore,
    private val relatedLists: RelatedListStore,
    private val forYouStore: ForYouStore,
    private val settings: ProfileSettingsStore,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * The ranking now, keeping [pinned] as the hero while it holds (FY-D7); `null` when the
     * profile's For you is off (FY-D10) or its settings can't be read. A store that can't be read
     * throws. Its duration is logged: a device pass reads how long Home waits for For you.
     */
    suspend fun rank(pinned: PinnedPick?, subscriptionUploads: List<VideoItem>, refused: Set<VideoId> = emptySet()): ForYouRanking? {
        if (!settings.forYouOn()) return null
        val started = TimeSource.Monotonic.markNow()
        val ranking = withContext(compute) { rankNow(pinned, subscriptionUploads, refused) }
        Log.i(FOR_YOU_LOG_TAG, "ranked ${ranking.picks.size} picks from ${ranking.seeds.size} seeds in ${started.elapsedNow().inWholeMilliseconds} ms")
        return ranking
    }

    /** [refused] (videos whose page the profile's filter refused) are left out as hidden ones are. */
    private suspend fun rankNow(pinned: PinnedPick?, subscriptionUploads: List<VideoItem>, refused: Set<VideoId>): ForYouRanking {
        val now = clock.now()
        val history = watchStore.history().first()
        val sources = SeedSources(history, playlistStore.watchLater().first(), playlistStore.playlistVideos().first(), subscriptionUploads)
        val seeds = pickSeeds(sources, now, zone)
        val inputs = ForYouInputs(
            seeds = seeds,
            lists = relatedLists.lists(seeds.map { it.videoId }),
            history = history,
            subscribedChannels = subscriptionStore.subscriptions().first().mapTo(HashSet()) { it.channelUrl },
            impressions = forYouStore.impressions(),
            hiddenVideos = forYouStore.hiddenVideos().first() + refused,
            hiddenChannels = forYouStore.hiddenChannels().first(),
            pinned = pinned,
        )
        return rankForYou(inputs, now)
    }
}
