package com.arshad.ayn.core.access

import kotlinx.coroutines.CompletableDeferred

/**
 * Subscriptions' seam calls answered in memory per channel (so the ViewModels run on the main
 * looper the Compose rule idles): a channel in [feeds] answers with its page, one in [failures]
 * fails both ways with that cause (an IOException reads as offline), any other answers with no
 * videos. Home behind it shares the feed, with no Live now and no hero page. [feedGate] holds
 * feeds in flight.
 */
class SubscriptionsExtractor : Extractor {
    val feeds = mutableMapOf<String, ItemsPage>()
    val failures = mutableMapOf<String, Throwable>()
    var feedGate: CompletableDeferred<Unit>? = null

    override suspend fun channelFeed(channelUrl: String): ItemsPage {
        feedGate?.await()
        failures[channelUrl]?.let { throw AccessException("feed down", it) }
        return feeds[channelUrl] ?: Empty
    }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage {
        failures[channelUrl]?.let { throw AccessException("uploads tab down", it) }
        return Empty
    }

    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = Empty
    override suspend fun video(id: VideoId): VideoMetadata = throw AccessException("video down", IllegalStateException())

    override suspend fun search(query: String, page: PageToken?): ItemsPage = unsupported()
    override suspend fun suggestions(query: String): List<String> = emptyList()
    // A channel opened from here shows its page failing (the channel page has its own tests).
    override suspend fun channel(channelUrl: String): ChannelItem = throw AccessException("channel down", IllegalStateException("unreadable"))
    override suspend fun playlist(playlistUrl: String): PlaylistPage = unsupported()
    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = unsupported()
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = unsupported()
    override suspend fun related(videoId: VideoId): List<VideoItem> = unsupported()

    private fun unsupported(): Nothing = error("Subscriptions makes no such call")

    private companion object {
        val Empty = ItemsPage(items = emptyList(), next = null)
    }
}
