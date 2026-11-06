package com.arshad.ayn.feature.search

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.titleQuery
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.feature.home.packSubscriptions
import com.arshad.ayn.feature.home.withPackChannels
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import kotlinx.coroutines.flow.first

/**
 * A kids profile's Search (KP-D10): a local match over what the owner approved, the approved
 * channels' fetched uploads (the subscriptions feed: the kid's subscriptions are its approved
 * channels) with the channels its packs approve (FPK-D13), and the approved videos; no request
 * goes to YouTube's search. Approved videos come first, then uploads newest first; the session
 * profile's filter has the last word on each.
 */
class ApprovedSearch(
    private val feed: SubscriptionsFeed,
    private val subscriptions: SubscriptionStore,
    private val currentFilter: suspend () -> ContentFilter,
    /** The session profile's approved videos. */
    private val approvedVideos: suspend () -> List<ApprovedVideo>,
) {
    /** The approved videos matching [query] ([titleMatchesQuery]); `null` when the session
     *  profile searches YouTube instead (it has no allow-list). */
    suspend fun search(query: String): List<VideoItem>? {
        val filter = currentFilter()
        if (filter.allowsDiscovery) return null
        val uploads = feed.load(withPackChannels(subscriptions.subscriptions().first(), packSubscriptions(filter))).entries
        val approved = approvedVideos().mapNotNull { it.toVideoItem() }
        val matches = titleQuery(query)
        return (approved + uploads)
            .distinctBy { it.id }
            .filter { filter.allows(it) && matches(it.title) }
    }
}
