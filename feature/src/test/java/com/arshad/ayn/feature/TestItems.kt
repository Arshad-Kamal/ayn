package com.arshad.ayn.feature

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
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
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.canonicalChannelUrl
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A seam fake that answers nothing: each fake extends it and overrides only the calls its
 * surface makes, so a new seam call is added here once.
 */
internal open class UnsupportedExtractor(private val label: String) : Extractor {
    override suspend fun video(id: VideoId): VideoMetadata = unsupported()
    override suspend fun search(query: String, page: PageToken?): ItemsPage = unsupported()
    override suspend fun suggestions(query: String): List<String> = unsupported()
    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = unsupported()
    override suspend fun channel(channelUrl: String): ChannelItem = unsupported()
    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage = unsupported()
    override suspend fun channelFeed(channelUrl: String): ItemsPage = unsupported()
    override suspend fun playlist(playlistUrl: String): PlaylistPage = unsupported()
    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = unsupported()
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = unsupported()
    override suspend fun related(videoId: VideoId): List<VideoItem> = unsupported()

    private fun unsupported(): Nothing = error("$label fake")
}

/**
 * Shared listing fixtures for :feature ViewModel tests (one home).
 * Thumbnails are null so Coil never touches the network (TEST-DETERM-01).
 */
internal fun testVideo(
    id: String,
    title: String = "title $id",
    uploaderName: String? = "uploader",
): VideoItem = VideoItem(
    id = VideoId(id),
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = "https://www.youtube.com/channel/UC$id",
    durationSeconds = 100,
    viewCount = 1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = false,
)

internal fun testChannel(
    url: String = "https://www.youtube.com/channel/UCcccccccccccccccccccccc",
    name: String = "channel",
    tabs: List<ChannelTab> = emptyList(),
): ChannelItem = ChannelItem(
    name = name,
    url = url,
    avatarUrl = null,
    subscriberCount = -1,
    description = null,
    verified = false,
    tabs = tabs,
)

internal fun testSubscription(
    channelUrl: String = "https://www.youtube.com/channel/UCcccccccccccccccccccccc",
    name: String = "channel",
    avatarUrl: String? = null,
): Subscription = Subscription(
    channelUrl = channelUrl,
    name = name,
    avatarUrl = avatarUrl,
    subscribedAtMillis = 0L,
    updatedAtMillis = 0L,
)

/** Settable clock for feed ages and cache expiry (KT-IDIOM-06, TEST-DETERM-01). */
internal class TestClock(var current: Instant = Instant.parse("2026-06-28T00:00:00Z")) : Clock {
    override fun now(): Instant = current
}

/** In-memory SubscriptionStore for ViewModel tests. */
internal class FakeSubscriptionStore : SubscriptionStore {
    val rows = MutableStateFlow<List<Subscription>>(emptyList())

    /** When set, writes throw (Room-I/O failure pins). */
    var failWrites = false

    override fun subscriptions(): Flow<List<Subscription>> = rows
    // Keyed as the real store keys (M5-D19): any URL form of a channel finds its one row.
    override fun subscription(channelUrl: String): Flow<Subscription?> =
        rows.map { list -> list.firstOrNull { it.isFor(channelUrl) } }
    override suspend fun subscribe(channel: ChannelItem) {
        if (failWrites) throw IllegalStateException("disk full")
        rows.value = rows.value.filterNot { it.isFor(channel.url) } +
            testSubscription(canonicalChannelUrl(channel.url), channel.name, channel.avatarUrl)
    }
    override suspend fun unsubscribe(channelUrl: String) {
        if (failWrites) throw IllegalStateException("disk full")
        rows.value = rows.value.filterNot { it.isFor(channelUrl) }
    }

    private fun Subscription.isFor(url: String): Boolean = canonicalChannelUrl(channelUrl) == canonicalChannelUrl(url)
}

/**
 * Channel-feed fake shared by the feed/subs tests: per-URL pages, per-URL failures (RSS only,
 * or RSS and uploads tab), in-flight tracking (for the bounded-concurrency pin), and
 * call-sequenced pages (for superseded-refresh pins via [sequencePages]).
 */
internal class FakeFeedExtractor : UnsupportedExtractor("feed-only") {
    val feeds = mutableMapOf<String, ItemsPage>()

    /** Channels down both ways: RSS and the uploads tab fail. */
    val failingChannels = mutableSetOf<String>()

    /** Per-channel uploads-tab failure cause (an IOException reads as offline); unreadable otherwise. */
    val failureCauses = mutableMapOf<String, Throwable>()

    /** Channels whose RSS fails while the uploads tab answers from [uploadsTabs]. */
    val rssDownChannels = mutableSetOf<String>()
    val uploadsTabs = mutableMapOf<String, ItemsPage>()
    var inFlight = 0
        private set
    var maxInFlight = 0
        private set

    /** When set, the n-th channelFeed call returns this page instead of [feeds]. */
    var sequencePages: MutableList<ItemsPage>? = null

    /** When set, the n-th call suspends on this gate before returning (overlap pins). */
    var gateCall: Int? = null
    var gate: CompletableDeferred<Unit>? = null

    /** channelFeed calls made, failures included (cache pins). */
    var calls = 0
        private set

    /** Uploads-tab (fallback) calls made. */
    var uploadsTabCalls = 0
        private set

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage {
        check(tab == ChannelTab.VIDEOS) { "feed fake: uploads tab only" }
        uploadsTabCalls++
        if (channelUrl in failingChannels) throw AccessException("channel down", failureCauses[channelUrl] ?: RuntimeException())
        return inFlightTracked { uploadsTabs.getOrDefault(channelUrl, ItemsPage(items = emptyList(), next = null)) }
    }

    override suspend fun channelFeed(channelUrl: String): ItemsPage {
        val call = ++calls // this call's number; `calls` moves on while it is suspended
        if (channelUrl in failingChannels || channelUrl in rssDownChannels) {
            throw AccessException("rss down", RuntimeException())
        }
        return inFlightTracked {
            if (call == gateCall) gate?.await()
            sequencePages?.getOrNull(call - 1)
                ?: feeds.getOrDefault(channelUrl, ItemsPage(items = emptyList(), next = null))
        }
    }

    private suspend fun inFlightTracked(answer: suspend () -> ItemsPage): ItemsPage {
        inFlight++
        if (inFlight > maxInFlight) maxInFlight = inFlight
        try {
            delay(1) // let siblings overlap so the bound is observable
            return answer()
        } finally {
            inFlight--
        }
    }
}

/**
 * Channel-page fake: per-URL headers, per-tab pages, per-URL header failures, per-tab
 * failures, the (URL, tab) asks made, the RSS feed page (or its failure) and the URLs it was asked
 * by, and a gate on the first header call (superseded-refresh pins).
 */
internal class FakeChannelExtractor : UnsupportedExtractor("channel-only") {
    val channels = mutableMapOf<String, ChannelItem>()
    val tabPages = mutableMapOf<ChannelTab, ItemsPage>()
    val failingHeaderUrls = mutableSetOf<String>()
    val failingTabs = mutableSetOf<ChannelTab>()
    val tabCalls = mutableListOf<Pair<String, ChannelTab>>()

    var feed = ItemsPage(items = emptyList(), next = null)
    var failingFeed = false
    val feedCalls = mutableListOf<String>()

    /** When set, the FIRST header call suspends on this gate before answering
     *  (superseded-refresh pins; retries are ungated). */
    var firstHeaderGate: CompletableDeferred<Unit>? = null
    private var headerCalls = 0

    /** When set, an ask for this tab suspends on [tabGate] (in-flight pins). */
    var gatedTab: ChannelTab? = null
    var tabGate: CompletableDeferred<Unit>? = null

    /** What every failing call reports. */
    var failure: AccessFailure = AccessFailure.UNREADABLE

    /** A tab's later pages by token, and the tokens whose page fails. */
    val laterPages = mutableMapOf<PageToken, ItemsPage>()
    val failingPages = mutableSetOf<PageToken>()

    override suspend fun channel(channelUrl: String): ChannelItem {
        if (++headerCalls == 1) firstHeaderGate?.await()
        if (channelUrl in failingHeaderUrls) throw AccessException("channel down", RuntimeException(), failure)
        return channels.getValue(channelUrl)
    }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage {
        tabCalls += channelUrl to tab
        if (page != null) {
            if (page in failingPages) throw AccessException("page down", RuntimeException(), failure)
            return laterPages.getValue(page)
        }
        if (tab == gatedTab) tabGate?.await()
        if (tab in failingTabs) throw AccessException("tab down", RuntimeException(), failure)
        return tabPages.getOrDefault(tab, ItemsPage(items = emptyList(), next = null))
    }

    override suspend fun channelFeed(channelUrl: String): ItemsPage {
        feedCalls += channelUrl
        if (failingFeed) throw AccessException("feed down", RuntimeException(), failure)
        return feed
    }
}
