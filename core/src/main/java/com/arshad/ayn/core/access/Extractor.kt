package com.arshad.ayn.core.access

import java.io.IOException
import java.net.MalformedURLException

/**
 * App-facing extraction seam (spec D7). Feature/UI code programs against this interface and
 * never against `org.schabi.newpipe.*` types, so the vendored extractor can be rebased or
 * swapped without touching app code (spec R4/D7).
 *
 * Implementations are main-safe (KT-CONC-01): blocking extraction runs on their own injected
 * dispatcher. Failures surface as [AccessException], never as vendored exception types.
 */
interface Extractor {
    suspend fun video(id: VideoId): VideoMetadata

    /** Search results for [query]; [page] `null` for the first page. */
    suspend fun search(query: String, page: PageToken? = null): ItemsPage

    /** YouTube's search completions for a partial [query], in YouTube's order (may be empty). */
    suspend fun suggestions(query: String): List<String>

    /** A kiosk listing (Home's Live now row). Classic Trending is gone from 2026 YouTube;
     *  the working surfaces are the four trending_* categories and Live. */
    suspend fun browse(kiosk: BrowseKiosk, page: PageToken? = null): ItemsPage

    /** A channel's identity block and tabs — the channel page header. */
    suspend fun channel(channelUrl: String): ChannelItem

    /** One tab of a channel; [page] `null` for the first page. */
    suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken? = null): ItemsPage

    /** A channel's RSS feed (latest ~15 uploads; no continuation — [ItemsPage.next] is null).
     *  Items carry an exact [VideoItem.uploadDate] and no [VideoItem.textualUploadDate]. */
    suspend fun channelFeed(channelUrl: String): ItemsPage

    /** A YouTube playlist's details and its first page of videos, in playlist order. */
    suspend fun playlist(playlistUrl: String): PlaylistPage

    /** A later page of a playlist's videos; [page] comes from [PlaylistPage.videos] or a previous call. */
    suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage

    /** A video's or Short's comments (CMT-D1): [page] `null` for the first page, a
     *  [CommentsPage.next] for a later one, or a [Comment.repliesToken] for that comment's replies. */
    suspend fun comments(videoId: VideoId, page: PageToken? = null): CommentsPage

    /** YouTube's related videos of [videoId] from one request (For you's fetch, FY-D5), mapped as
     *  [VideoMetadata.relatedVideos]; never empty: an answer with no list, or none usable, fails as
     *  [AccessFailure.UNREADABLE], a rate-limit answer as [AccessFailure.RATE_LIMITED]. */
    suspend fun related(videoId: VideoId): List<VideoItem>
}

/**
 * Wraps extractor/transport failures so callers never see vendored exception types. The
 * adapter passes YouTube's refusal ([AccessFailure.UNAVAILABLE]) itself, for the playlist and
 * video calls only, and its rate limit ([AccessFailure.RATE_LIMITED]) for the related call only
 * (only it can name the vendored types); by
 * default a transport failure ([IOException] anywhere in the cause chain, however the vendored
 * tree wrapped it) means YouTube was never reached, and anything else means it answered and the
 * answer couldn't be used. [MalformedURLException] is an [IOException] the vendored parsers
 * raise on URLs read from YouTube's reply, so it reads as unreadable.
 */
class AccessException(
    message: String,
    cause: Throwable,
    val failure: AccessFailure = transportOrUnreadable(cause),
    /** What an [AccessFailure.UNAVAILABLE] video refusal was, when YouTube's answer names it. */
    val refusal: VideoRefusal? = null,
) : Exception(message, cause)

/** The video refusals the player names on top of [AccessFailure.UNAVAILABLE] ([Extractor.video] only). */
sealed interface VideoRefusal {
    /** YouTube refuses made-for-kids videos to the VISIONOS client (spec 12 KP-D5). */
    data object MadeForKids : VideoRefusal

    /** A premiere or scheduled stream that hasn't begun; [message] is YouTube's own sentence
     *  ("This live event will begin in 44 minutes."), one line, length-capped; null when it gave none. */
    data class NotStarted(val message: String?) : VideoRefusal
}

/** What a failed seam call means for the user (RATE_LIMITED: for For you's fetch budget).
 *  OFFLINE and UNREADABLE are the two error statements of the `b3-*` mockups. */
enum class AccessFailure {
    /** YouTube couldn't be reached (no connection, DNS, timeout). The live chat also reads its 5xx
     *  and 429 answers so: all retry alike (LIVE-D6). */
    OFFLINE,

    /** YouTube answered, but its reply couldn't be read (usually extractor drift: needs a KE update). */
    UNREADABLE,

    /** YouTube answered that the playlist or video isn't available: missing, private, removed,
     *  age-restricted, members-only or region-blocked ([Extractor.playlist],
     *  [Extractor.playlistVideos] and [Extractor.video] only; every other call keeps the two
     *  kinds above). */
    UNAVAILABLE,

    /** YouTube answered that this device is over its request limit (HTTP 429; [Extractor.related]
     *  only, which For you's budget reads: every other call keeps the kinds above). */
    RATE_LIMITED,
}

private fun transportOrUnreadable(cause: Throwable): AccessFailure =
    if (causeChain(cause).any { it is IOException && it !is MalformedURLException }) {
        AccessFailure.OFFLINE
    } else {
        AccessFailure.UNREADABLE
    }

/** Bounded: a cause chain the vendored tree built into a loop can't spin forever. */
internal fun causeChain(first: Throwable): Sequence<Throwable> =
    generateSequence(first) { it.cause }.take(CAUSE_DEPTH_LIMIT)

private const val CAUSE_DEPTH_LIMIT = 8
