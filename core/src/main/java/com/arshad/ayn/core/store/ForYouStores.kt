package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * For you's cache of YouTube's related lists (FY-D4), for the current profile ([ProfileSession]).
 * Every path that holds a video's related list records it: the playback resolve, the Home hero's
 * page, For you's own fetch. Each profile keeps its [MAX_LISTS] newest; Clear history
 * ([WatchStore.clear]) empties them, and a restore starts them empty.
 */
interface RelatedListStore {
    /** [items] as [videoId]'s related list, fetched now; an empty list records nothing. */
    suspend fun record(videoId: VideoId, items: List<VideoItem>)

    /** For you's fetch of [videoId]'s list failed now; a list already kept stays. */
    suspend fun recordFailure(videoId: VideoId)

    /** What is kept for those of [videoIds] that have a row: a list, a failed fetch, or both
     *  ([RelatedList]); a failure-only entry has no items and no [RelatedList.fetchedAt]. */
    suspend fun lists(videoIds: Collection<VideoId>): Map<VideoId, RelatedList>

    companion object {
        const val MAX_LISTS = 300

        /** A list this old is fetched again: a new fetch brings new, relevant videos (FY ledger). */
        val REUSE: Duration = 3.days
    }
}

/** A kept related list: [items] in YouTube's order (empty when only a failure is known); upload
 *  dates are kept to the millisecond. */
data class RelatedList(
    val items: List<VideoItem>,
    /** When the list was stored; null when no fetch has succeeded. */
    val fetchedAt: Instant?,
    /** The last failed fetch since then, or null. */
    val failedAt: Instant?,
) {
    /** Young enough to use without fetching again ([RelatedListStore.REUSE]). */
    fun isFresh(now: Instant): Boolean = fetchedAt != null && now - fetchedAt < RelatedListStore.REUSE
}

/**
 * Every For you request to YouTube, device-wide (FY-D5): the budget and the stop count from it,
 * so failures count and a restart forgets nothing. Attempts [KEPT] old are dropped.
 */
interface FetchAttemptLog {
    suspend fun record(outcome: FetchOutcome)

    /** The attempts made at or after [start], oldest first. */
    suspend fun since(start: Instant): List<FetchAttempt>

    companion object {
        val KEPT: Duration = 1.days
    }
}

data class FetchAttempt(val at: Instant, val outcome: FetchOutcome)

/** How a request ended; [key] is the stored value. */
enum class FetchOutcome(val key: String) {
    Fetched("fetched"),

    /** YouTube answered over its limit (HTTP 429). */
    RateLimited("rate_limited"),

    /** Any other failure. */
    Failed("failed"),
}

/**
 * A profile's For you state (FY-D6), for the current profile ([ProfileSession]). Feedback is the
 * user's own decision and goes in backups: Not interested (videos) and Don't recommend this
 * channel (channels, by [canonicalChannelUrl]); each is undone by its `unhide` (FY-D11).
 * Impressions (how each For you card fared) drive rotation and boredom; they stay on the device
 * and are dropped [IMPRESSIONS_KEPT] after the card was last shown or opened.
 */
interface ForYouStore {
    fun hiddenVideos(): Flow<Set<VideoId>>

    /** Canonical channel URLs ([canonicalChannelUrl]). */
    fun hiddenChannels(): Flow<Set<String>>

    suspend fun hideVideo(videoId: VideoId)

    suspend fun unhideVideo(videoId: VideoId)

    /** Stored by [canonicalChannelUrl]. */
    suspend fun hideChannel(channelUrl: String)

    suspend fun unhideChannel(channelUrl: String)

    /** [cards] were shown on one Home visit (FY-D3: held focus or sat in view for 2 s); each
     *  card's count goes up by one. */
    suspend fun recordShown(cards: List<VideoItem>)

    /** [card] was opened from For you. */
    suspend fun recordOpened(card: VideoItem)

    /** The impressions shown or opened within [IMPRESSIONS_KEPT]. */
    suspend fun impressions(): List<Impression>

    companion object {
        val IMPRESSIONS_KEPT: Duration = 30.days
    }
}

/** How one For you card fared with the profile. */
data class Impression(
    val videoId: VideoId,
    /** The card's channel by [canonicalChannelUrl]; null when the card had none. */
    val channelUrl: String?,
    /** Home visits it was shown on; 0 when it was opened before a show was recorded. */
    val shownCount: Int,
    /** Null when no show was recorded. */
    val lastShownAt: Instant?,
    val openedAt: Instant?,
)

/**
 * FY-D3's consecutive skips: For you cards passed over since the profile's last real watch (an
 * entry played to [WatchThresholds.MIN_RESUME_MS] or watched). A card shown [SKIP_SHOWS] times
 * and never opened is a skip at its last show; a card opened whose watch entry is still under
 * [WatchThresholds.MIN_RESUME_MS] and unwatched is a skip at its opening (a bounce). An opening
 * whose watch entry is gone (taken out of history, or it never played) can't tell a bounce from a watch, so it isn't
 * counted. Not interested and Don't recommend are feedback, not skips: a card in [hiddenVideos] or
 * of a channel in [hiddenChannels] ([canonicalChannelUrl]) doesn't count, and an Undo brings its
 * skip back.
 */
fun consecutiveSkips(
    impressions: List<Impression>,
    history: List<WatchEntry>,
    hiddenVideos: Set<VideoId>,
    hiddenChannels: Set<String>,
): Int {
    val lastRealWatch = history.filter { it.isRealWatch() }.maxOfOrNull { it.lastWatchedAtMillis } ?: Long.MIN_VALUE
    val entries = history.associateBy { it.videoId }
    return impressions
        .filterNot { it.videoId in hiddenVideos || it.channelUrl in hiddenChannels }
        .mapNotNull { it.skippedAtMillis(entries[it.videoId]) }
        .count { it > lastRealWatch }
}

/** Shows of a never-opened card that make it a skip. */
const val SKIP_SHOWS = 3

/** FY-D3's real watch: played to [WatchThresholds.MIN_RESUME_MS] or marked watched (a rewatch
 *  of a watched video included). */
fun WatchEntry.isRealWatch(): Boolean = watched || positionMs >= WatchThresholds.MIN_RESUME_MS

private fun Impression.skippedAtMillis(entry: WatchEntry?): Long? {
    val opened = openedAt
    return when {
        opened == null -> lastShownAt?.toEpochMilliseconds()?.takeIf { shownCount >= SKIP_SHOWS }
        entry != null && !entry.isRealWatch() -> opened.toEpochMilliseconds()
        else -> null
    }
}
