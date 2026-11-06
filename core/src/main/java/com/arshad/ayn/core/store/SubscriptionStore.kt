package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.ChannelItem
import kotlinx.coroutines.flow.Flow

/**
 * Subscriptions seam (spec D4): feature/UI code programs against this interface,
 * never against Room types (ARCH-4). The Room-backed [SubscriptionRepository] lives behind it.
 * The store is the single source of truth for subscriptions (ARCH-6), and every call means the
 * current profile's subscriptions ([ProfileSession]).
 */
interface SubscriptionStore {
    /** All subscriptions, most recent subscribe first (the DAO's ordering; stable tiebreak on URL). */
    fun subscriptions(): Flow<List<Subscription>>

    /** The subscription for [channelUrl] in any of its URL forms (its row's [Subscription.channelUrl]
     *  may differ), or `null` when not subscribed (the subscribe button's state). */
    fun subscription(channelUrl: String): Flow<Subscription?>

    /** Subscribe to a channel. Subscribing an already-stored channel re-stamps it as a fresh
     *  subscribe: fields refresh and the subscribe date moves to now. */
    suspend fun subscribe(channel: ChannelItem)

    suspend fun unsubscribe(channelUrl: String)
}

/**
 * A subscription as consumers see it. [updatedAtMillis] is written on every change and is the
 * D4 last-write-wins key (ARCH-7).
 */
data class Subscription(
    /** The channel's canonical URL ([canonicalChannelUrl]), which the
     *  [com.arshad.ayn.core.access.Extractor] methods take too. */
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAtMillis: Long,
    val updatedAtMillis: Long,
)
