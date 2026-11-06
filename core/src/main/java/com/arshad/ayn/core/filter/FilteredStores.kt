package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.core.store.SavedVideo
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.WatchStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

/*
 * The content filters on store reads (KP-D1, D20): History, Continue watching, Library and For
 * you's kept related lists drop what the session profile's filter hides as they emit, so a rule
 * added or removed shows at once; a playlist's cover is its first video the filter passes. History
 * and Continue watching also drop entries played as Shorts while the profile doesn't show Shorts
 * (PAF-D6). Writes and every other read pass through unchanged (playlist counts include hidden
 * videos: they are still saved).
 */

class FilteredWatchStore(private val delegate: WatchStore, private val filters: ContentFilters) : WatchStore by delegate {
    override fun history(): Flow<List<WatchEntry>> = delegate.history().keptUnder(filters)

    override fun resumePoints(): Flow<List<WatchEntry>> = delegate.resumePoints().keptUnder(filters)
}

private fun Flow<List<WatchEntry>>.keptUnder(filters: ContentFilters): Flow<List<WatchEntry>> =
    combine(filters.snapshot) { entries, (filter, shortsShown) ->
        entries.filter { (shortsShown || !it.isShort) && filter.allows(it) }
    }

class FilteredPlaylistStore(private val delegate: PlaylistStore, private val filters: ContentFilters) : PlaylistStore by delegate {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun playlists(): Flow<List<PlaylistSummary>> =
        delegate.playlists().combine(filters.current) { summaries, filter -> summaries to filter }
            .mapLatest { (summaries, filter) -> summaries.map { it.coveredUnder(filter) } }

    private suspend fun PlaylistSummary.coveredUnder(filter: ContentFilter): PlaylistSummary {
        if (coverThumbnailUrl == null) return this
        val cover = delegate.videos(id).first().firstOrNull { filter.allows(it.video) }
        return copy(coverThumbnailUrl = cover?.video?.thumbnailUrl)
    }

    override fun videos(playlistId: Long): Flow<List<SavedVideo>> = delegate.videos(playlistId).keptBy(filters) { allows(it.video) }

    override fun playlistVideos(): Flow<List<SavedVideo>> = delegate.playlistVideos().keptBy(filters) { allows(it.video) }

    override fun watchLater(): Flow<List<SavedVideo>> = delegate.watchLater().keptBy(filters) { allows(it.video) }
}

class FilteredRelatedListStore(private val delegate: RelatedListStore, private val filters: ContentFilters) : RelatedListStore by delegate {
    override suspend fun lists(videoIds: Collection<VideoId>): Map<VideoId, RelatedList> {
        val filter = filters.now()
        return delegate.lists(videoIds).mapValues { (_, list) -> list.copy(items = list.items.filter { filter.allows(it) }) }
    }
}

/** A blocked channel leaves the subscriptions list too ("nothing from it shows anywhere"). */
class FilteredSubscriptionStore(private val delegate: SubscriptionStore, private val filters: ContentFilters) : SubscriptionStore by delegate {
    override fun subscriptions(): Flow<List<Subscription>> = delegate.subscriptions().keptBy(filters) { allowsChannel(it.channelUrl, it.name) }
}

private fun ContentFilter.allows(entry: WatchEntry): Boolean = allows(
    videoId = entry.videoId,
    title = entry.title,
    channelUrl = entry.uploaderUrl,
    durationSeconds = entry.durationMs?.let { it / MILLIS_PER_SECOND },
    channelName = entry.uploaderName,
)

private fun <T> Flow<List<T>>.keptBy(filters: ContentFilters, keep: ContentFilter.(T) -> Boolean): Flow<List<T>> =
    combine(filters.current) { items, filter -> items.filter { filter.keep(it) } }

private const val MILLIS_PER_SECOND = 1000L
