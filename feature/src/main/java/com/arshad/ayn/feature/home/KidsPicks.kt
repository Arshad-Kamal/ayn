package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What a kids profile's Home adds (`kp-kids` kidhome, KP-D10): the videos the owner approved one
 *  by one, newest approval first, shown as picked by the owner ([ownerName]), and the channels its
 *  packs approve ([packChannels], FPK-D13), whose new videos show beside the approved channels'. */
data class KidsPicks(val ownerName: String?, val videos: List<VideoItem>, val packChannels: List<Subscription> = emptyList()) {
    companion object {
        val None: KidsPicks = KidsPicks(ownerName = null, videos = emptyList())
    }
}

/** Kids profile [kidId]'s picks as they change: its approved videos that [filter] (the session
 *  profile's) passes, so a block still wins over an approval, and [filter]'s pack channels. */
fun kidsPicks(kidId: Long, filters: FilterStore, profiles: ProfileStore, filter: Flow<ContentFilter>): Flow<KidsPicks> =
    combine(filters.rules(kidId), profiles.profiles(), filter) { rules, all, current ->
        val videos = rules.approvedVideos.mapNotNull { it.toVideoItem() }.filter { current.allows(it) }
        KidsPicks(all.firstOrNull { it.isOwner }?.name, videos, packSubscriptions(current))
    }

/** The channels [filter]'s packs approve for a kids profile (FPK-D13) that it doesn't block, as
 *  subscriptions named from the pack's list; none for any other profile. */
fun packSubscriptions(filter: ContentFilter): List<Subscription> =
    filter.packApprovedChannels
        .filter { filter.allowsChannel(it.url, it.name) }
        .map { Subscription(it.url, it.name, avatarUrl = null, subscribedAtMillis = 0, updatedAtMillis = 0) }

/** A kids profile's [approved] channels, then the [packs] channels it hasn't approved itself. */
fun withPackChannels(approved: List<Subscription>, packs: List<Subscription>): List<Subscription> {
    val own = approved.mapTo(HashSet()) { it.channelUrl }
    return approved + packs.filter { it.channelUrl !in own }
}
