package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.access.ChannelItem
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * [SubscriptionStore] over the Room DAO, for the [session]'s profile: a profile switch re-emits
 * every flow with the new profile's rows. Every channel URL in is keyed by [canonicalChannelUrl]
 * (M5-D19), so any URL form of a channel finds its one row. A kids profile's subscriptions are
 * its approved channels, which only the owner writes ([FilterStore.approveChannel], KP-D10), so
 * its own subscribe and unsubscribe change nothing: a channel page reached through an approved
 * video can't approve its channel. An unsubscribe drops the channel's kept feed (PERF P9c) and
 * records the removal for sync (M8-D6). Room's own suspend DAO calls are main-safe, so there is no
 * dispatcher to inject (KT-CONC-01); the clock is injected for deterministic timestamps
 * (KT-IDIOM-06).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val clock: Clock = Clock.System,
) : SubscriptionStore {
    private val dao: SubscriptionDao = database.subscriptionDao()

    override fun subscriptions(): Flow<List<Subscription>> =
        session.selectedId.flatMapLatest { profileId ->
            dao.subscriptions(profileId).map { rows -> rows.map(SubscriptionEntity::toSubscription) }
        }

    override fun subscription(channelUrl: String): Flow<Subscription?> =
        session.selectedId.flatMapLatest { profileId ->
            dao.subscription(profileId, canonicalChannelUrl(channelUrl)).map { it?.toSubscription() }
        }

    override suspend fun subscribe(channel: ChannelItem) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (!database.profileDao().isKids(profileId)) {
                dao.upsert(channel.toSubscriptionEntity(profileId, clock.now().toEpochMilliseconds()))
            }
        }
    }

    override suspend fun unsubscribe(channelUrl: String) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            if (!database.profileDao().isKids(profileId)) {
                val key = canonicalChannelUrl(channelUrl)
                database.removalDao().subscription(profileId, key, clock.now().toEpochMilliseconds())
                dao.delete(profileId, key)
                database.channelFeedDao().prune(profileId)
            }
        }
    }
}

/** [this] channel as [profileId]'s subscription, subscribed now: a fresh subscribe. */
internal fun ChannelItem.toSubscriptionEntity(profileId: Long, nowMillis: Long): SubscriptionEntity = SubscriptionEntity(
    profileId = profileId,
    channelUrl = canonicalChannelUrl(url),
    name = name,
    avatarUrl = avatarUrl,
    subscribedAtMillis = nowMillis,
    updatedAtMillis = nowMillis,
)

internal fun SubscriptionEntity.toSubscription(): Subscription = Subscription(
    channelUrl = channelUrl,
    name = name,
    avatarUrl = avatarUrl,
    subscribedAtMillis = subscribedAtMillis,
    updatedAtMillis = updatedAtMillis,
)
