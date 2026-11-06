package com.arshad.ayn.core.access

import kotlinx.coroutines.CompletableDeferred

/**
 * Search's seam calls answered in memory from recorded pages (so the ViewModel runs on the main
 * looper the Compose rule idles). Home behind it has no subscriptions and an empty Live now, so
 * its blank slate opens Search. A query without an answer fails with [failure] as its cause;
 * [searchGate] holds searches in flight.
 */
class SearchExtractor : Extractor {
    val answers = mutableMapOf<String, ItemsPage>()
    val suggestionAnswers = mutableMapOf<String, List<String>>()
    var failure: Throwable = IllegalStateException("unreadable")
    var searchGate: CompletableDeferred<Unit>? = null

    override suspend fun search(query: String, page: PageToken?): ItemsPage {
        searchGate?.await()
        return answers[query] ?: throw AccessException("search down", failure)
    }

    override suspend fun suggestions(query: String): List<String> = suggestionAnswers[query].orEmpty()

    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = ItemsPage(items = emptyList(), next = null)

    override suspend fun channelFeed(channelUrl: String): ItemsPage = unsupported()
    override suspend fun video(id: VideoId): VideoMetadata = unsupported()
    // A channel opened from here shows its page failing (the channel page has its own tests).
    override suspend fun channel(channelUrl: String): ChannelItem = throw AccessException("channel down", IllegalStateException("unreadable"))
    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage =
        throw AccessException("channel down", IllegalStateException("unreadable"))
    override suspend fun playlist(playlistUrl: String): PlaylistPage = unsupported()
    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = unsupported()
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = unsupported()
    override suspend fun related(videoId: VideoId): List<VideoItem> = unsupported()

    private fun unsupported(): Nothing = error("Search makes no such call")
}
