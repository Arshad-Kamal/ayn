package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Instant

/**
 * Each subscribed channel's last fetched feed, per profile (PERF P9c): the subscriptions feed
 * shows them at a start while it fetches the fresh one.
 */
interface ChannelFeedStore {
    /** The session profile now; `null` before one opens. A load captures it at its start. */
    val profileNow: Long?

    /** [profileId]'s kept feeds fetched under [restrictedMode], by channel URL. A feed fetched
     *  under another level, or one that can't be read back, is absent. */
    suspend fun feeds(profileId: Long, restrictedMode: RestrictedMode): Map<String, StoredChannelFeed>

    /**
     * Keeps [feeds] (by channel URL) for [profileId], fetched under [restrictedMode], in one
     * transaction. Nothing is written once the session has moved to another profile or [profileId]
     * is gone; a feed fetched no later than the one kept never replaces it, unless that one is
     * stamped in the future; feeds of channels the profile no longer subscribes to are dropped.
     */
    suspend fun record(profileId: Long, restrictedMode: RestrictedMode, feeds: Map<String, StoredChannelFeed>)
}

/** One channel's [videos] and [shorts], newest first, as fetched at [fetchedAt]. */
data class StoredChannelFeed(val videos: List<VideoItem>, val shorts: List<VideoItem>, val fetchedAt: Instant)
