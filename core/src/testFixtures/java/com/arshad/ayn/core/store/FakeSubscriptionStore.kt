package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.ChannelItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The store in memory, subscribed to [channelUrls] ("Channel 1", "Channel 2" …). */
class FakeSubscriptionStore(vararg channelUrls: String) : SubscriptionStore {
    private val rows = MutableStateFlow(
        channelUrls.mapIndexed { i, url -> Subscription(url, name = "Channel ${i + 1}", avatarUrl = null, 0L, 0L) },
    )

    override fun subscriptions(): Flow<List<Subscription>> = rows
    // Keyed as the real store keys (M5-D19): any URL form of a channel finds its one row.
    override fun subscription(channelUrl: String): Flow<Subscription?> = rows.map { list -> list.firstOrNull { it.isFor(channelUrl) } }

    override suspend fun subscribe(channel: ChannelItem) {
        rows.update { list ->
            list.filterNot { it.isFor(channel.url) } + Subscription(canonicalChannelUrl(channel.url), channel.name, channel.avatarUrl, 0L, 0L)
        }
    }

    override suspend fun unsubscribe(channelUrl: String) {
        rows.update { list -> list.filterNot { it.isFor(channelUrl) } }
    }

    fun isSubscribed(channelUrl: String): Boolean = rows.value.any { it.isFor(channelUrl) }

    private fun Subscription.isFor(url: String): Boolean = canonicalChannelUrl(channelUrl) == canonicalChannelUrl(url)
}
