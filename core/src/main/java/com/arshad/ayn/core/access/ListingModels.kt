package com.arshad.ayn.core.access

import kotlin.time.Instant

/**
 * Listing models — browse/search/channel/subscriptions items as the UI sees them.
 *
 * Contract (charter D5, decided at the seam): Shorts never appear in [ItemsPage.items];
 * the one adapter that produces pages moves them to [ItemsPage.shorts] before anything
 * downstream runs, so a consumer that never reads that list keeps the full block (M7-D12). Playlist
 * pages are the exception: YouTube marks no Shorts there, so they play as ordinary videos. Live
 * streams are the opposite (spec D11): they stay visible and carry [VideoItem.isLive].
 *
 * Model discipline (spec M4 D5, CMP-7 honesty): val-only data classes; constructors take
 * snapshot collections (adapters pass `toList()`-frozen lists) and the package is marked
 * stable via the root compose stability configuration (`gradle/compose-stability.conf`).
 */

/** One video as it appears in a listing. Counts use the extractor's -1-means-unknown. */
data class VideoItem(
    val id: VideoId,
    val title: String,
    val uploaderName: String?,
    /** Channel URL — the key the seam's channel methods take (the extractor's own identity). */
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    /** YouTube's display text ("3 days ago"); null where the source has none (RSS feeds). */
    val textualUploadDate: String?,
    /** Publication instant: exact on feed items, approximate (parsed from the display text) on
     *  InnerTube listings, null when unknown. Order and age by this, never by the text. */
    val uploadDate: Instant?,
    val thumbnailUrl: String?,
    /** D11: live streams carry a LIVE badge; only a profile's Hide live streams filter hides them (KP). */
    val isLive: Boolean,
    /** YouTube's members-only badge on the listing; RSS feeds, history and playlist cards don't
     *  carry it (kept related lists and channel feeds do). */
    val membersOnly: Boolean = false,
    /** Scheduled and not started: a premiere or a scheduled stream (a listing doesn't say which).
     *  Its [uploadDate] is the start when the listing gives one; RSS feeds never mark it. */
    val upcoming: Boolean = false,
) : ListingItem

/** One channel as it appears in a listing (search results) or as a page header. */
data class ChannelItem(
    val name: String,
    /** Channel URL — the key the seam's channel methods take. */
    val url: String,
    val avatarUrl: String?,
    /** Page-header banner art: uncropped (16:9) at 1920 px wide when YouTube serves its yt3
     *  banner, else the largest desktop crop as served; listings never carry one (search items
     *  have no banners). */
    val bannerUrl: String? = null,
    val subscriberCount: Long,
    val description: String?,
    val verified: Boolean,
    /** Page-header tabs in YouTube's order; listings never carry them. */
    val tabs: List<ChannelTab> = emptyList(),
) : ListingItem

/** One playlist as it appears in a listing (a channel's Playlists, Releases, Podcasts or Courses
 *  tab). [videoCount] is -1 when unknown. */
data class PlaylistItem(
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val videoCount: Long,
    val uploaderName: String?,
) : ListingItem

/** A playlist page's header (ui-rebuild B-D10): what YouTube reports for the playlist itself.
 *  [videoCount] is -1 when unknown. */
data class PlaylistDetails(
    val title: String,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val uploaderAvatarUrl: String?,
    val videoCount: Long,
    val thumbnailUrl: String?,
)

/** A playlist's details and its first page of videos ([ItemsPage.next] continues the list). */
data class PlaylistPage(
    val details: PlaylistDetails,
    val videos: ItemsPage,
)

/** What a listing page can hold. Exhaustive on purpose: UI renders each kind explicitly. */
sealed interface ListingItem

/**
 * A channel page tab the extractor can list (ui-rebuild B-D7). A [SHORTS] page has its videos in
 * [ItemsPage.shorts] and nothing in [ItemsPage.items] (charter D5); the UI decides whether to
 * show the tab.
 */
enum class ChannelTab(internal val tabName: String) {
    VIDEOS("videos"),
    SHORTS("shorts"),
    LIVE("livestreams"),
    RELEASES("albums"),
    PODCASTS("podcasts"),
    COURSES("courses"),
    PLAYLISTS("playlists"),
}

/**
 * The kiosk surfaces that work on 2026 YouTube (classic Trending is gone — the fork's
 * `Trending` kiosk fails at fetch, and the service's default kiosk is Live, so the id is
 * always explicit at the seam, never defaulted).
 */
enum class BrowseKiosk(internal val kioskId: String) {
    TRENDING_MUSIC("trending_music"),
    TRENDING_GAMING("trending_gaming"),
    TRENDING_MOVIES_AND_SHOWS("trending_movies_and_shows"),
    TRENDING_PODCASTS("trending_podcasts_episodes"),
    LIVE("live"),
}

/**
 * Continuation state for paged listings: exactly what the vendored extractor's page token
 * carries, in Bundle-safe primitives (page state survives process death). Opaque to
 * callers — construct nothing but `null` (first page) or a previously returned token.
 */
data class PageToken(
    val url: String? = null,
    val id: String? = null,
    val ids: List<String> = emptyList(),
    val cookies: Map<String, String> = emptyMap(),
    val bodyBase64: String? = null,
)

/** One page of listing results; [next] is `null` when the listing is exhausted. */
data class ItemsPage(
    val items: List<ListingItem>,
    val next: PageToken?,
    /** The page's Shorts (M7-D12), each once per page, in page order, with a vertical picture.
     *  Every page fills it; YouTube marks Shorts in search shelves, channel feeds and a channel's
     *  Shorts tab, and none on playlist pages. */
    val shorts: List<VideoItem> = emptyList(),
)
