package com.arshad.ayn.ui.tv.channel

import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.feature.channel.ChannelLayout
import com.arshad.ayn.feature.channel.TabBody
import com.arshad.ayn.feature.listingKey

internal const val SubscribeKey = "subscribe"
internal const val RetryKey = "retry"
internal const val BackKey = "back"
internal const val ShortsOnKey = "shorts-on"

/** A tab's focus key. */
internal fun tabKey(tab: ChannelTab): String = "tab:${tab.name}"

/** A tab card's focus key. */
internal fun cardKey(item: ListingItem): String = "card:${item.listingKey}"

/** A video's tab card focus key (a video's listing key is its id). */
internal fun cardKey(video: VideoId): String = "card:${video.value}"

/** [com.arshad.ayn.ui.tv.focus.ScreenFocus]'s entry key: each kind of content takes focus as it
 *  lands; `null` while loading (focus waits for the page). Turn on Shorts takes its button away: a
 *  new entry puts focus back on the page. */
internal val ChannelLayout.entryKey: String?
    get() = when (this) {
        ChannelLayout.Loading -> null
        is ChannelLayout.Failed -> "failed"
        is ChannelLayout.Page -> if (body == TabBody.ShortsOnly) "shorts-only" else "page"
    }

internal val ChannelLayout.initialFocusKey: String?
    get() = when (this) {
        ChannelLayout.Loading -> null
        is ChannelLayout.Failed -> RetryKey
        is ChannelLayout.Page -> SubscribeKey
    }
