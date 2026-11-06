package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ChannelFeedStore] over Room. Feeds are encoded and decoded on [compute]: a profile with
 *  hundreds of subscriptions keeps about a megabyte of JSON. */
class ChannelFeedRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) : ChannelFeedStore {
    private val dao: ChannelFeedDao = database.channelFeedDao()

    override val profileNow: Long? get() = session.currentIdOrNull

    override suspend fun feeds(profileId: Long, restrictedMode: RestrictedMode): Map<String, StoredChannelFeed> {
        val rows = dao.feeds(profileId, restrictedMode.key)
        return withContext(compute) {
            rows.mapNotNull { row ->
                val videos = decodeRelatedItems(row.videos) ?: return@mapNotNull null
                val shorts = decodeRelatedItems(row.shorts) ?: return@mapNotNull null
                row.channelUrl to StoredChannelFeed(videos, shorts, Instant.fromEpochMilliseconds(row.fetchedAtMillis))
            }.toMap()
        }
    }

    override suspend fun record(profileId: Long, restrictedMode: RestrictedMode, feeds: Map<String, StoredChannelFeed>) {
        if (feeds.isEmpty()) return
        val rows = withContext(compute) {
            feeds.map { (channelUrl, feed) ->
                ChannelFeedEntity(
                    profileId = profileId,
                    channelUrl = channelUrl,
                    videos = encodeRelatedItems(feed.videos),
                    shorts = encodeRelatedItems(feed.shorts),
                    fetchedAtMillis = feed.fetchedAt.toEpochMilliseconds(),
                    restrictedMode = restrictedMode.key,
                )
            }
        }
        database.withWriteTransaction {
            if (session.currentIdOrNull != profileId || database.profileDao().profile(profileId) == null) return@withWriteTransaction
            val kept = dao.stamps(profileId).associate { it.channelUrl to it.fetchedAtMillis }
            val nowMillis = clock.now().toEpochMilliseconds()
            // A kept feed stamped in the future (the wall clock stepped back) is always replaced;
            // one already kept from the same fetch is skipped.
            dao.upsert(rows.filter { row -> kept[row.channelUrl]?.let { it < row.fetchedAtMillis || it > nowMillis } ?: true })
            dao.prune(profileId)
        }
    }
}
