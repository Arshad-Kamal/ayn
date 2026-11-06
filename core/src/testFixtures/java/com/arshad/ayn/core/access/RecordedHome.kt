package com.arshad.ayn.core.access

import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/** A recorded flow's answer, replayed through the real seam before the UI runs. */
fun <T> recorded(flow: String, call: suspend Extractor.() -> T): T =
    runBlocking { NewPipeAccess(downloader = FixtureDownloader(flow)).call() }

/**
 * Home's seam calls answered in memory from recorded pages (so the ViewModel runs on the main
 * looper the Compose rule idles): channel feeds, the Live kiosk and the hero's video page.
 * A `null` page fails the call with [failure] as its cause; [feedGate] holds feeds in flight.
 */
class HomeExtractor : Extractor {
    var feed: ItemsPage? = null
    var live: ItemsPage? = ItemsPage(items = emptyList(), next = null)
    var liveFailure: Throwable = IllegalStateException("unreadable")
    var heroPage: VideoMetadata? = null
    var feedGate: CompletableDeferred<Unit>? = null

    override suspend fun channelFeed(channelUrl: String): ItemsPage {
        feedGate?.await()
        return feed ?: throw AccessException("feed down", IllegalStateException())
    }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage =
        feed ?: throw AccessException("uploads tab down", IllegalStateException())

    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage =
        live ?: throw AccessException("kiosk down", liveFailure)

    override suspend fun video(id: VideoId): VideoMetadata =
        heroPage?.copy(id = id) ?: throw AccessException("video down", IllegalStateException())

    override suspend fun search(query: String, page: PageToken?): ItemsPage = unsupported()
    override suspend fun suggestions(query: String): List<String> = unsupported()
    override suspend fun channel(channelUrl: String): ChannelItem = unsupported()
    override suspend fun playlist(playlistUrl: String): PlaylistPage = unsupported()
    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = unsupported()
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = unsupported()
    override suspend fun related(videoId: VideoId): List<VideoItem> = unsupported()

    private fun unsupported(): Nothing = error("Home makes no such call")
}
