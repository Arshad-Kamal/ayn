package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.YouTubeRestrict
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.canonicalChannelUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The content filters at the access seam (KP-D1, D20): every listing, playlist page and related
 * list leaves with the session profile's filtered items removed, and [video] refuses a filtered
 * video with [VideoFiltered]. Playback resolves through [video], so that refusal is the resolve
 * gate for every route to the player. Each call uses the [PinnedFilter] it runs under, else reads
 * [currentFilter] once, and that filter's Restricted Mode rides every request the call makes
 * (KP-D2, D15). Suggestions, comments and a page's live chat are
 * off under Restricted Mode ([ContentFilter.allowsSuggestions], [ContentFilter.allowsComments],
 * LIVE-D25);
 * a kids profile's search and browse answer empty without a request ([ContentFilter.allowsDiscovery]),
 * and its related videos are the playing video's channel's ([ContentFilter.keepsRelatedToChannel]).
 * Under Hide Shorts a channel's Shorts tab answers empty too.
 *
 * A page that filtering empties fetches the next one, at most [MAX_REFILL_PAGES] more per call
 * (the guest rate limit); the answer is the last page fetched, with every fetched page's Shorts.
 */
class FilteringExtractor(
    private val delegate: Extractor,
    private val currentFilter: suspend () -> ContentFilter,
) : Extractor {
    override suspend fun video(id: VideoId): VideoMetadata = withFilter { filter ->
        val video = delegate.video(id)
        if (!filter.allows(video)) throw VideoFiltered()
        video.copy(
            relatedVideos = video.relatedVideos.filter { filter.allows(it) && filter.keepsRelated(it, video) },
            liveChat = video.liveChat.takeIf { filter.allowsComments },
        )
    }

    override suspend fun search(query: String, page: PageToken?): ItemsPage = withFilter { filter ->
        if (!filter.allowsDiscovery) EMPTY_PAGE else filtered(filter, delegate.search(query, page)) { delegate.search(query, it) }
    }

    override suspend fun suggestions(query: String): List<String> = withFilter { filter ->
        if (filter.allowsSuggestions) delegate.suggestions(query) else emptyList()
    }

    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = withFilter { filter ->
        if (!filter.allowsDiscovery) EMPTY_PAGE else filtered(filter, delegate.browse(kiosk, page)) { delegate.browse(kiosk, it) }
    }

    /** The header still opens for a blocked channel; none of its videos list ([channelTab]). */
    override suspend fun channel(channelUrl: String): ChannelItem = withFilter { delegate.channel(channelUrl) }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage = withFilter { filter ->
        if (!filter.allowsChannel(channelUrl) || (tab == ChannelTab.SHORTS && filter.hidesShorts)) {
            EMPTY_PAGE
        } else {
            filtered(filter, delegate.channelTab(channelUrl, tab, page)) { delegate.channelTab(channelUrl, tab, it) }
        }
    }

    override suspend fun channelFeed(channelUrl: String): ItemsPage = withFilter { filter ->
        if (!filter.allowsChannel(channelUrl)) EMPTY_PAGE else delegate.channelFeed(channelUrl).filteredBy(filter)
    }

    override suspend fun playlist(playlistUrl: String): PlaylistPage = withFilter { filter ->
        val playlist = delegate.playlist(playlistUrl)
        playlist.copy(videos = filtered(filter, playlist.videos) { delegate.playlistVideos(playlistUrl, it) })
    }

    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = withFilter { filter ->
        filtered(filter, delegate.playlistVideos(playlistUrl, page)) { delegate.playlistVideos(playlistUrl, it) }
    }

    /** Only a video [video] let through opens its comments, so they pass as they come; under
     *  Restricted Mode they are off ([ContentFilter.allowsComments]). */
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = withFilter { filter ->
        if (filter.allowsComments) delegate.comments(videoId, page) else COMMENTS_OFF
    }

    /** May come back empty when filtering removes every item. */
    override suspend fun related(videoId: VideoId): List<VideoItem> = withFilter { filter ->
        delegate.related(videoId).filter { filter.allows(it) }
    }

    /** Runs [block] with the pinned filter, else the filter read now; every request it makes,
     *  refills included, carries that filter's Restricted Mode (KP-D15: the level captured when
     *  the call starts). */
    private suspend fun <T> withFilter(block: suspend (ContentFilter) -> T): T {
        val filter = currentCoroutineContext()[PinnedFilter]?.filter ?: currentFilter()
        return withContext(YouTubeRestrict.context(filter.restrictedMode.headerValue)) { block(filter) }
    }

    private suspend fun filtered(filter: ContentFilter, first: ItemsPage, fetchNext: suspend (PageToken) -> ItemsPage): ItemsPage {
        var raw = first
        var page = raw.filteredBy(filter)
        val shorts = page.shorts.toMutableList()
        var refills = 0
        while (raw.items.isNotEmpty() && page.items.isEmpty() && refills < MAX_REFILL_PAGES) {
            val next = raw.next ?: break
            raw = fetchNext(next)
            page = raw.filteredBy(filter)
            shorts += page.shorts
            refills++
        }
        return page.copy(shorts = shorts.distinctBy { it.id })
    }

    private companion object {
        /** KP-D1: two more pages at most for a page filtering emptied. */
        const val MAX_REFILL_PAGES = 2

        val EMPTY_PAGE = ItemsPage(items = emptyList(), next = null)

        val COMMENTS_OFF = CommentsPage(comments = emptyList(), next = null, commentsOff = true, offInRestrictedMode = true)
    }
}

/** The video's page was asked for, but the session profile's filters hide it (KP-D1). */
class VideoFiltered : Exception("hidden by the profile's content filters")

private fun ItemsPage.filteredBy(filter: ContentFilter): ItemsPage = ItemsPage(
    items = items.filter(filter::allows),
    next = next,
    shorts = if (filter.hidesShorts) emptyList() else shorts.filter { filter.allows(it) },
)

/** YouTube's related order is kept; under [ContentFilter.keepsRelatedToChannel] only [playing]'s
 *  channel stays (no channel on either side keeps nothing). */
private fun ContentFilter.keepsRelated(item: VideoItem, playing: VideoMetadata): Boolean {
    if (!keepsRelatedToChannel) return true
    val channel = playing.uploaderUrl ?: return false
    return item.uploaderUrl?.let(::canonicalChannelUrl) == canonicalChannelUrl(channel)
}

/** The `YouTube-Restrict` value for [this] level; Off sends none. */
private val RestrictedMode.headerValue: String?
    get() = when (this) {
        RestrictedMode.Off -> null
        RestrictedMode.Strict -> YouTubeRestrict.STRICT
    }
