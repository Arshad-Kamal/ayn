package com.arshad.ayn.ui.tv.subscriptions

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.subscriptions.FeedBody
import com.arshad.ayn.feature.subscriptions.SubscriptionsLayout

internal const val AllKey = "strip:all"
internal const val RetryKey = "retry"
internal const val RetryFailedKey = "retry-failed"
internal const val RetryChannelKey = "retry-channel"
internal const val FindKey = "find-channel"
internal const val OpenChannelKey = "open-channel"

/** A channel avatar's focus key in the strip. */
internal fun avatarKey(channelUrl: String): String = "strip:$channelUrl"

/** A feed card's focus key. */
internal fun videoKey(video: VideoItem): String = "video:${video.id.value}"

/** A shelf Short's focus key. */
internal fun shortKey(short: VideoId): String = "short:${short.value}"

/** [com.arshad.ayn.ui.tv.focus.ScreenFocus]'s entry key: each kind of content takes focus as it
 *  lands; `null` while loading (focus waits for content). */
internal val SubscriptionsLayout.entryKey: String?
    get() = when (this) {
        is SubscriptionsLayout.Loading -> null
        SubscriptionsLayout.Blank -> "blank"
        is SubscriptionsLayout.Failed -> "failed"
        is SubscriptionsLayout.Feed -> "feed"
    }

internal val SubscriptionsLayout.initialFocusKey: String?
    get() = when (this) {
        is SubscriptionsLayout.Loading -> null
        SubscriptionsLayout.Blank -> FindKey
        is SubscriptionsLayout.Failed -> RetryKey
        is SubscriptionsLayout.Feed -> {
            val picked = selected
            val days = body as? FeedBody.Days
            when {
                picked != null -> avatarKey(picked.channelUrl)
                failedNames.isNotEmpty() -> RetryFailedKey
                days != null && days.groups.isNotEmpty() -> videoKey(days.groups.first().videos.first())
                days != null && days.shorts.isNotEmpty() -> shortKey(days.shorts.first().id)
                else -> AllKey
            }
        }
    }
