package com.arshad.ayn.core.access

import com.arshad.ayn.core.access.newpipe.ALBUM_PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.ALBUM_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.CHANNEL_PAGE_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_TAB_FLOWS
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.PLAYLIST_FLOW
import com.arshad.ayn.core.access.newpipe.PLAYLIST_UNAVAILABLE_FLOW
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.UNAVAILABLE_PLAYLIST_URL
import kotlinx.coroutines.CompletableDeferred

/**
 * A channel page's and its playlists' seam calls answered in memory from the recorded channel (so
 * the ViewModels run on the main looper the Compose rule idles): its header (`channel-page`), each
 * tab's first page (`channel-videos`, `-live`, `-releases`, `-playlists`), and [playlists] by URL
 * with their continuations in [playlistPages] (`playlist`, `-pages`, `-album`). A missing answer
 * fails with [failure] as its cause (an IOException reads as offline), a playlist with
 * [playlistFailure] when set; [headerGate], [tabGate] and [playlistGate] hold calls in flight;
 * [tabCalls] lists the tabs asked for, in order.
 * Home behind it finds no feeds, no Live now and no hero page.
 */
class ChannelExtractor : Extractor {
    var header: ChannelItem? = RecordedHeader
    val tabs: MutableMap<ChannelTab, ItemsPage> = RecordedTabs.toMutableMap()
    val playlists: MutableMap<String, PlaylistPage> = RecordedPlaylists.toMutableMap()
    val playlistPages: MutableMap<String, ItemsPage> = RecordedPlaylistPages.toMutableMap()
    var failure: Throwable = IllegalStateException("unreadable")
    var playlistFailure: AccessException? = null
    var headerGate: CompletableDeferred<Unit>? = null
    var tabGate: CompletableDeferred<Unit>? = null
    var playlistGate: CompletableDeferred<Unit>? = null
    var playlistPageCalls: Int = 0
        private set
    val tabCalls: MutableList<ChannelTab> = mutableListOf()

    override suspend fun channel(channelUrl: String): ChannelItem {
        headerGate?.await()
        return header ?: throw AccessException("channel down", failure)
    }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage {
        tabCalls += tab
        tabGate?.await()
        return tabs[tab] ?: throw AccessException("tab down", failure)
    }

    override suspend fun channelFeed(channelUrl: String): ItemsPage = Empty
    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = Empty
    override suspend fun video(id: VideoId): VideoMetadata = throw AccessException("video down", IllegalStateException())

    override suspend fun playlist(playlistUrl: String): PlaylistPage {
        playlistGate?.await()
        playlistFailure?.let { throw it }
        return playlists[playlistUrl] ?: throw AccessException("playlist down", failure)
    }

    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage {
        playlistPageCalls++
        return playlistPages[playlistUrl] ?: throw AccessException("playlist page down", failure)
    }

    override suspend fun search(query: String, page: PageToken?): ItemsPage = unsupported()
    override suspend fun suggestions(query: String): List<String> = unsupported()
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = unsupported()
    override suspend fun related(videoId: VideoId): List<VideoItem> = unsupported()

    private fun unsupported(): Nothing = error("The channel and playlist pages make no such call")

    companion object {
        val Empty = ItemsPage(items = emptyList(), next = null)

        /** Replayed once for every test (read-only). */
        val RecordedHeader: ChannelItem by lazy { recorded(CHANNEL_PAGE_FLOW) { channel(RECORDED_CHANNEL_URL) } }
        val RecordedTabs: Map<ChannelTab, ItemsPage> by lazy {
            mapOf(ChannelTab.VIDEOS to "channel-videos").plus(CHANNEL_TAB_FLOWS)
                .mapValues { (tab, flow) -> recorded(flow) { channelTab(RECORDED_CHANNEL_URL, tab) } }
        }

        /** The uploads playlist's first page and the page after it, from one replay session. */
        private val RecordedPagedPlaylist: Pair<PlaylistPage, ItemsPage> by lazy {
            recorded(PAGED_PLAYLIST_FLOW) {
                val first = playlist(PAGED_PLAYLIST_URL)
                first to playlistVideos(PAGED_PLAYLIST_URL, checkNotNull(first.videos.next))
            }
        }
        val RecordedPlaylists: Map<String, PlaylistPage> by lazy {
            mapOf(
                PLAYLIST_URL to recorded(PLAYLIST_FLOW) { playlist(PLAYLIST_URL) },
                ALBUM_PLAYLIST_URL to recorded(ALBUM_PLAYLIST_FLOW) { playlist(ALBUM_PLAYLIST_URL) },
                PAGED_PLAYLIST_URL to RecordedPagedPlaylist.first,
            )
        }
        val RecordedPlaylistPages: Map<String, ItemsPage> by lazy { mapOf(PAGED_PLAYLIST_URL to RecordedPagedPlaylist.second) }

        /** YouTube's answer for a playlist that doesn't exist, through the real seam. */
        val RecordedUnavailable: AccessException by lazy {
            try {
                recorded(PLAYLIST_UNAVAILABLE_FLOW) { playlist(UNAVAILABLE_PLAYLIST_URL) }
                error("the $PLAYLIST_UNAVAILABLE_FLOW replay answered with a playlist")
            } catch (e: AccessException) {
                e
            }
        }
    }
}
