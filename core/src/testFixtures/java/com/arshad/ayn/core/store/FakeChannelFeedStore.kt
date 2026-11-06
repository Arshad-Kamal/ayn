package com.arshad.ayn.core.store

/**
 * In-memory [ChannelFeedStore] for the layers above the store: [recorded] holds every [record]
 * call in order, and [feeds] answers from [kept] ((profile, channel URL) to the Restricted Mode and
 * feed), which [record] fills as the real store does (one feed per channel, replaced only by a
 * later fetch) and a test may set. [profileNow] is the session profile; [failReads] and
 * [failWrites] make the calls throw.
 */
class FakeChannelFeedStore(override var profileNow: Long? = 1L) : ChannelFeedStore {
    data class Record(val profileId: Long, val restrictedMode: RestrictedMode, val feeds: Map<String, StoredChannelFeed>)

    val recorded: MutableList<Record> = mutableListOf()
    val kept: MutableMap<Pair<Long, String>, Pair<RestrictedMode, StoredChannelFeed>> = mutableMapOf()
    var failReads: Boolean = false
    var failWrites: Boolean = false

    override suspend fun feeds(profileId: Long, restrictedMode: RestrictedMode): Map<String, StoredChannelFeed> {
        if (failReads) throw IllegalStateException("database unreadable")
        return kept.filter { (key, value) -> key.first == profileId && value.first == restrictedMode }
            .map { (key, value) -> key.second to value.second }
            .toMap()
    }

    override suspend fun record(profileId: Long, restrictedMode: RestrictedMode, feeds: Map<String, StoredChannelFeed>) {
        if (failWrites) throw IllegalStateException("disk full")
        recorded += Record(profileId, restrictedMode, feeds)
        feeds.forEach { (url, feed) ->
            val old = kept[profileId to url]?.second
            if (old == null || old.fetchedAt < feed.fetchedAt) kept[profileId to url] = restrictedMode to feed
        }
    }

    /** Keeps [feed] as [channelUrl]'s for [profileId] under [restrictedMode]. */
    fun keep(channelUrl: String, feed: StoredChannelFeed, profileId: Long = 1L, restrictedMode: RestrictedMode = RestrictedMode.Off) {
        kept[profileId to channelUrl] = restrictedMode to feed
    }
}
