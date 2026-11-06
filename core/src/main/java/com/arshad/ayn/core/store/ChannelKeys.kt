package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoItem
import java.net.URI
import java.net.URISyntaxException

/**
 * The subscriptions store's key for [url] (M5-D19): `https://www.youtube.com/channel/<id>` when
 * [url] names a channel id ([channelIdOf]), so one channel reached through two URL forms (a tab,
 * `http`, `m.`) is one row; any other URL (a handle, `/c/`) as given. Idempotent.
 */
fun canonicalChannelUrl(url: String): String = channelIdOf(url)?.let { CHANNEL_URL_PREFIX + it } ?: url

/** [VideoItem.uploaderUrl] by [canonicalChannelUrl] (For you's channel key, FY-D2); null without one. */
fun VideoItem.channelKey(): String? = uploaderUrl?.let(::canonicalChannelUrl)

/**
 * The channel id an http(s) YouTube URL names in its path (`/channel/UC…`, anything after the
 * id ignored), or null. Only the first path segment counts, so an id inside a query (a redirect
 * target) is not read as this URL's channel.
 */
internal fun channelIdOf(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
    val host = uri.host?.lowercase() ?: return null
    if (host != YOUTUBE_HOST && !host.endsWith(".$YOUTUBE_HOST")) return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    if (segments.getOrNull(0) != CHANNEL_SEGMENT) return null
    return segments.getOrNull(1)?.takeIf(CHANNEL_ID::matches)
}

/**
 * Subscription rows, any number of profiles, with each (profile, channel) once (M5-D19): rows of
 * one profile whose keys canonicalize alike merge into one under the canonical key, subscribed at
 * the earliest date and updated at the latest, named by the most recently updated row with a
 * non-blank name and pictured by the most recently updated row with an avatar. Order of first
 * appearance is kept.
 */
internal fun collapseByChannel(rows: List<SubscriptionEntity>): List<SubscriptionEntity> =
    rows.groupBy { it.profileId to canonicalChannelUrl(it.channelUrl) }.map { (key, group) ->
        val newestFirst = group.sortedWith(compareByDescending<SubscriptionEntity> { it.updatedAtMillis }.thenBy { it.channelUrl })
        SubscriptionEntity(
            profileId = key.first,
            channelUrl = key.second,
            name = newestFirst.firstOrNull { it.name.isNotBlank() }?.name ?: newestFirst.first().name,
            avatarUrl = newestFirst.firstNotNullOfOrNull { it.avatarUrl },
            subscribedAtMillis = group.minOf { it.subscribedAtMillis },
            updatedAtMillis = group.maxOf { it.updatedAtMillis },
        )
    }

/** A channel id (`UC` + 22 URL-safe characters): a URL's `/channel/` segment or Takeout's first column. */
internal val CHANNEL_ID = Regex("UC[\\w-]{22}")

internal const val CHANNEL_URL_PREFIX = "https://www.youtube.com/channel/"

/** The id in a [canonicalChannelUrl]; `null` for a URL it kept as given (a handle). */
internal fun channelIdOfCanonical(canonical: String): String? =
    canonical.takeIf { it.startsWith(CHANNEL_URL_PREFIX) }?.substring(CHANNEL_URL_PREFIX.length)?.takeIf(CHANNEL_ID::matches)

private const val YOUTUBE_HOST = "youtube.com"
private const val CHANNEL_SEGMENT = "channel"
private val WEB_SCHEMES = setOf("http", "https")
