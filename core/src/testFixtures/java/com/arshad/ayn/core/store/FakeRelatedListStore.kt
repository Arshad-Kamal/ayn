package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem

/**
 * In-memory [RelatedListStore] for the layers above the store: [recorded] holds every [record]
 * call in order (empty lists included, which the real store ignores) and [failures] every
 * [recordFailure]; [lists] answers from [kept], which the test sets. [failWrites] makes writes throw.
 */
class FakeRelatedListStore : RelatedListStore {
    val recorded: MutableList<Pair<VideoId, List<VideoItem>>> = mutableListOf()
    val failures: MutableList<VideoId> = mutableListOf()
    val kept: MutableMap<VideoId, RelatedList> = mutableMapOf()
    var failWrites: Boolean = false

    override suspend fun record(videoId: VideoId, items: List<VideoItem>) {
        if (failWrites) throw IllegalStateException("disk full")
        recorded += videoId to items
    }

    override suspend fun recordFailure(videoId: VideoId) {
        if (failWrites) throw IllegalStateException("disk full")
        failures += videoId
    }

    override suspend fun lists(videoIds: Collection<VideoId>): Map<VideoId, RelatedList> = kept.filterKeys { it in videoIds }
}
