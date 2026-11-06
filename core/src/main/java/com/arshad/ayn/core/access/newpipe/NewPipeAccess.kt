package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.NChallengeSolver
import com.arshad.ayn.core.access.NoPoTokenSource
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.core.access.PoTokenSource
import com.arshad.ayn.core.access.StreamCandidate
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.WebStreamingClient
import com.arshad.ayn.core.access.causeChain
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import java.io.IOException
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.ThrottlingParameterSolver
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeMwebClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * [Extractor] implementation on the vendored NewPipeExtractor fork (THIRD-PARTY.md entry 1).
 * This package is the only place outside `:core:extractor` allowed to touch
 * `org.schabi.newpipe.*` types (spec D7). Our own delta on the fork stays small (spec R4).
 *
 * Listing contract: Shorts are kept out of a page's items here and listed apart in its
 * `shorts` (charter D5, M7-D12), except on playlist pages, which carry no Shorts marker; live
 * streams stay and carry `isLive` (spec D11). A single unparseable item is dropped, never a
 * failed page — one bad row must not take down a feed.
 *
 * A made-for-kids video, which VISIONOS refuses, resolves through MWEB with a web PO token from
 * [poTokenSource] and its `n` solved by [nChallengeSolver]; without a solver the refusal stands
 * (spec 12 KP-D29).
 */
class NewPipeAccess(
    private val poTokenSource: PoTokenSource = NoPoTokenSource,
    private val nChallengeSolver: NChallengeSolver? = null,
    private val downloader: Downloader = OkHttpDownloader(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Extractor {

    init {
        // The vendored extractor keeps process-global init state; construction installs our
        // transport, PO-token bridge and n solver. One NewPipeAccess per process (app container).
        ensureInitialized(downloader)
        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenBridge(poTokenSource))
        YoutubeJavaScriptPlayerManager.setThrottlingParameterSolver(nChallengeSolver?.let(::VendoredNSolver))
    }

    override suspend fun video(id: VideoId): VideoMetadata = withContext(io) {
        guarded("video ${id.value}", readsRefusals = true) { extractVideo(id) }
    }

    override suspend fun search(query: String, page: PageToken?): ItemsPage = withContext(io) {
        guarded("search \"$query\"") {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.listingPage(page)
        }
    }

    /** The query stays out of the failure message: it is a keystroke-level trace of typing. */
    override suspend fun suggestions(query: String): List<String> = withContext(io) {
        guarded("search suggestions") {
            ServiceList.YouTube.suggestionExtractor.suggestionList(query)
                .map(::decodeNumericCharacterReferences)
        }
    }

    /** A kiosk listing (Home browse rows). The kiosk id is always explicit — the service's
     *  default kiosk is Live. */
    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = withContext(io) {
        guarded("kiosk ${kiosk.kioskId}") {
            val extractor = ServiceList.YouTube.getKioskList().getExtractorById(kiosk.kioskId, null)
            extractor.listingPage(page)
        }
    }

    override suspend fun channel(channelUrl: String): ChannelItem = withContext(io) {
        guarded("channel $channelUrl") {
            ChannelInfo.getInfo(channelUrl).toChannelItem()
        }
    }

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage =
        withContext(io) {
            guarded("channel tab ${tab.tabName} of $channelUrl") {
                // The tab LHF's fromUrl leaves content filters empty in this fork (getUrl then
                // crashes on the suffix lookup); the fromQuery path is the supported one.
                val channelId = YoutubeChannelLinkHandlerFactory.getInstance().getId(channelUrl)
                ServiceList.YouTube.getChannelTabExtractorFromId(channelId, tab.tabName)
                    .listingPage(page) { it.toChannelTabItem() }
            }
        }

    override suspend fun channelFeed(channelUrl: String): ItemsPage = withContext(io) {
        guarded("feed of $channelUrl") {
            val page = ServiceList.YouTube.getFeedExtractor(channelUrl).listingPage(null)
            // RSS `<published>` arrives as the textual date but is an ISO timestamp, not display
            // text; the exact instant already rides uploadDate.
            page.copy(
                items = page.items.map { if (it is VideoItem) it.copy(textualUploadDate = null) else it },
                shorts = page.shorts.map { it.copy(textualUploadDate = null) },
            )
        }
    }

    override suspend fun playlist(playlistUrl: String): PlaylistPage = withContext(io) {
        guarded("playlist $playlistUrl", readsRefusals = true) {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(playlistUrl)
            val videos = extractor.listingPage(null)
            PlaylistPage(details = extractor.toPlaylistDetails(), videos = videos)
        }
    }

    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = withContext(io) {
        guarded("playlist page of $playlistUrl", readsRefusals = true) {
            ServiceList.YouTube.getPlaylistExtractor(playlistUrl).listingPage(page)
        }
    }

    /** Every page, replies included, goes through a fresh extractor: a continuation token is all
     *  the vendored `getPage` reads, so a later page costs one `/next` call (the first costs two). */
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage = withContext(io) {
        guarded("comments of ${videoId.value}") {
            val extractor = ServiceList.YouTube.getCommentsExtractor(watchUrl(videoId))
            if (page == null) firstCommentsPage(extractor) else laterCommentsPage(extractor, page)
        }
    }

    /** The `next` request the video page itself makes, for its related list only: one request where
     *  [video] makes 5–8 (FY ledger, 2026-10-09), once the process has YouTube's client version (the
     *  first call of any kind fetches it). */
    override suspend fun related(videoId: VideoId): List<VideoItem> = withContext(io) {
        guarded("related of ${videoId.value}", readsRateLimit = true) { fetchRelated(videoId) }
    }

    /**
     * Reads the answer's related list as the vendored `YoutubeStreamExtractor.getRelatedItems` reads
     * the page's (two readers of one shape: a tripwire), except that an unreadable
     * item drops alone, and maps it as [extractVideo] does. That reader also skips an age-restricted
     * page's list; this one can't tell (the page's metadata isn't fetched). An answer without related
     * videos, or with none usable, fails as unreadable: a reshaped answer shows as failed fetches
     * in For you's budget, never as empty lists. Localization and country resolve through the
     * service, as the page extractor's do, so the request is the page's byte for byte.
     */
    @Throws(IOException::class, ExtractionException::class)
    private fun fetchRelated(id: VideoId): List<VideoItem> {
        val localization = ServiceList.YouTube.localization
        val request = YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, ServiceList.YouTube.contentCountry)
            .value("videoId", id.value)
            .value("contentCheckOk", true)
            .value("racyCheckOk", true)
            .done()
        val results = YoutubeParsingHelper
            .getJsonPostResponse("next", JsonWriter.string(request).toByteArray(Charsets.UTF_8), localization)
            .getObject("contents").getObject("twoColumnWatchNextResults")
            .getObject("secondaryResults").getObject("secondaryResults").getArray("results")
        val timeAgoParser = ServiceList.YouTube.getTimeAgoParser(localization)
        val videos = results.mapNotNull { (it as? JsonObject)?.relatedVideoReaderOrNull(timeAgoParser) }
        check(videos.isNotEmpty()) { "no related videos in the next answer" }
        val collector = StreamInfoItemsCollector(ServiceList.YouTube.serviceId)
        val related = videos.mapNotNull { catchingNull { collector.extract(it) }?.toRelatedVideoOrNull() }
        check(related.isNotEmpty()) { "no usable related video among ${videos.size}" }
        return related
    }

    /** A related result's reader as the vendored page reader picks it; playlists, mixes and
     *  anything else get none. */
    private fun JsonObject.relatedVideoReaderOrNull(timeAgoParser: TimeAgoParser): StreamInfoItemExtractor? = when {
        has("compactVideoRenderer") -> YoutubeStreamInfoItemExtractor(getObject("compactVideoRenderer"), timeAgoParser)
        getObject("lockupViewModel").getString("contentType") == LOCKUP_VIDEO ->
            YoutubeStreamInfoItemLockupExtractor(getObject("lockupViewModel"), timeAgoParser)
        else -> null
    }

    /** YouTube offers a continuation only while more comments follow, so a continued page with
     *  nothing and no next token is an answer the vendored reader skipped (an error body, a
     *  reshaped wrapper): it fails rather than silently ending the list. */
    private fun laterCommentsPage(extractor: CommentsExtractor, page: PageToken): CommentsPage {
        val result = extractor.getPage(page.toVendoredPage()).toCommentsPage()
        check(result.comments.isNotEmpty() || result.next != null) { "empty comments continuation" }
        return result
    }

    private fun firstCommentsPage(extractor: CommentsExtractor): CommentsPage {
        extractor.fetchPage()
        if (extractor.isCommentsDisabled) return CommentsPage(comments = emptyList(), next = null, commentsOff = true)
        return extractor.initialPage.toCommentsPage()
            .copy(totalCount = catchingNull { extractor.commentsCount.toLong() }?.takeIf { it >= 0 })
    }

    /** A page that held comments but none readable fails as unreadable (YouTube reshaped them), so
     *  it never reads as a video without comments. */
    private fun ListExtractor.InfoItemsPage<CommentsInfoItem>.toCommentsPage(): CommentsPage {
        val comments = items.mapNotNull { it.toCommentOrNull() }
        check(comments.isNotEmpty() || (items.isEmpty() && errors.isEmpty())) {
            "no readable comment among ${items.size} (${errors.size} errors)"
        }
        return CommentsPage(comments = comments, next = nextPage.toPageToken())
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun extractVideo(id: VideoId): VideoMetadata {
        val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(watchUrl(id))
        extractor.fetchPage()
        val streams = buildList {
            extractor.videoStreams.mapTo(this) { it.toCandidate(StreamKind.VIDEO) }
            extractor.videoOnlyStreams.mapTo(this) { it.toCandidate(StreamKind.VIDEO_ONLY) }
            extractor.audioStreams.mapTo(this) { it.toCandidate() }
        }
        return VideoMetadata(
            id = id,
            title = extractor.name,
            uploaderName = extractor.uploaderName,
            uploaderUrl = catchingNull { extractor.uploaderUrl.ifBlank { null } },
            uploaderAvatarUrl = catchingNull { bestImage(extractor.uploaderAvatars) },
            uploaderSubscriberCount = catchingNull { extractor.uploaderSubscriberCount }?.takeIf { it >= 0 }, // -1 = no count text; a missing one throws
            durationSeconds = extractor.length,
            // The avatar, subscriber count, live marker and start, picture, likes, description, chapters, storyboards,
            // related videos, caption tracks and chat dress the UI; a parse failure in one never fails playback, so
            // each degrades to absent.
            isLive = catchingNull { extractor.streamType }.isLive(),
            liveStartedAt = catchingNull { (extractor as? YoutubeStreamExtractor)?.liveStartDate?.instant?.toKotlinInstant() },
            thumbnailUrl = catchingNull { bestImage(extractor.thumbnails) },
            likeCount = catchingNull { extractor.likeCount }?.takeIf { it >= 0 }, // -1 = ratings hidden
            description = catchingNull { extractor.description.toPlainText() },
            chapters = catchingNull { extractor.streamSegments.map { it.toChapter() } }.orEmpty(),
            storyboards = catchingNull { extractor.frames.map { it.toStoryboard() }.sortedBy { it.frameWidth } }
                .orEmpty(),
            // Read from the `next` answer fetchPage already holds: no extra request.
            relatedVideos = catchingNull { extractor.relatedItems?.items?.mapNotNull { it.toRelatedVideoOrNull() } }
                .orEmpty(),
            captionTracks = catchingNull { extractor.subtitlesDefault.mapNotNull { it.toCaptionTrackOrNull() } }.orEmpty(),
            liveChat = catchingNull { (extractor as? YoutubeStreamExtractor)?.conversationBar?.let(::liveChatSeedOf) },
            hlsUrl = catchingNull { extractor.hlsUrl.ifBlank { null } },
            dashUrl = catchingNull { extractor.dashMpdUrl.ifBlank { null } },
            streams = streams,
            serverAbrStreamingUrl = catchingNull { extractor.serverAbrStreamingUrl.ifBlank { null } },
            ustreamerConfig = catchingNull { extractor.ustreamerConfig.ifBlank { null } },
            webStreamingClient = (extractor as? YoutubeStreamExtractor)?.mwebClient?.toWebStreamingClient(),
        )
    }

    private fun YoutubeMwebClient.toWebStreamingClient(): WebStreamingClient = WebStreamingClient(
        clientId = YoutubeMwebClient.CLIENT_ID,
        clientVersion = clientVersion,
        deviceMake = YoutubeMwebClient.DEVICE_MAKE,
        deviceModel = YoutubeMwebClient.DEVICE_MODEL,
        osName = YoutubeMwebClient.OS_NAME,
        osVersion = YoutubeMwebClient.OS_VERSION,
        userAgent = YoutubeMwebClient.USER_AGENT,
        poToken = poToken,
    )

    /** The vendored solver seam on ours. */
    private class VendoredNSolver(private val solver: NChallengeSolver) : ThrottlingParameterSolver {
        override fun prepare(playerCode: String) {
            solver.prepare(playerCode)
        }

        override fun solve(playerCode: String, challenge: String): String = solver.solve(playerCode, challenge)
    }

    private fun watchUrl(id: VideoId): String = WATCH_URL_PREFIX + id.value

    /** `null` for a track whose link isn't YouTube's timedtext endpoint over HTTPS: the captions
     *  seam fetches only what this lets through (SEC-SSRF-01). */
    private fun SubtitlesStream.toCaptionTrackOrNull(): CaptionTrack? {
        val url = content.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == YOUTUBE_HOST && it.encodedPath == TIMEDTEXT_PATH }
            ?: return null
        return CaptionTrack(
            languageTag = languageTag,
            name = trackName?.takeIf { it.isNotBlank() } ?: displayLanguageName,
            autoGenerated = isAutoGenerated,
            url = url.toString(),
        )
    }

    private fun VideoStream.toCandidate(kind: StreamKind): StreamCandidate =
        toCandidate(kind, itag = itag, bitrate = bitrate, resolution = resolution)

    private fun AudioStream.toCandidate(): StreamCandidate =
        toCandidate(StreamKind.AUDIO, itag = itag, bitrate = bitrate, resolution = null)

    /** Stream fields + the `ItagItem` extras the SABR format mapping needs. */
    private fun Stream.toCandidate(
        kind: StreamKind,
        itag: Int,
        bitrate: Int,
        resolution: String?,
    ): StreamCandidate {
        val item = itagItem
        return StreamCandidate(
            url = content,
            kind = kind,
            mimeType = format?.mimeType,
            itag = item?.id ?: itag,
            resolution = resolution,
            bitrate = item?.bitrate ?: bitrate,
            codec = item?.codec,
            fps = item?.fps?.takeIf { it > 0 },
            width = item?.width?.takeIf { it > 0 },
            height = item?.height?.takeIf { it > 0 },
            lastModified = item?.lastModified?.takeIf { it > 0 },
            xtags = item?.xtags,
            audioTrackId = item?.audioTrackId,
            audioTrackLocale = item?.audioLocale?.toLanguageTag(),
            audioTrackType = item?.audioTrackType?.name?.lowercase(),
            durationMs = item?.approxDurationMs?.takeIf { it > 0 },
            isDrc = item?.isDrc,
        )
    }

    /** One listing page through the vendored list extractor: initial or continued. */
    private fun <T : InfoItem> ListExtractor<T>.listingPage(
        page: PageToken?,
        toItem: (InfoItem) -> ListingItem? = { it.toListingItem() },
    ): ItemsPage {
        val result = if (page == null) {
            fetchPage()
            initialPage
        } else {
            // Continuations are self-contained (the vendored getPage contract); no landing
            // refetch — page N+1 costs one request, not two.
            getPage(page.toVendoredPage())
        }
        return ItemsPage(
            items = result.items.mapNotNull(toItem),
            next = result.nextPage.toPageToken(),
            // Search shelves repeat a Short (one recorded page lists 35 Shorts 56 times).
            shorts = result.items.mapNotNull { (it as? StreamInfoItem)?.toShortOrNull() }.distinctBy { it.id },
        )
    }

    /**
     * The seam's failure wall: no vendored exception type crosses (incl. unchecked ones the
     * extractor throws), and Android API-band `LinkageError`s from the JDK-compiled vendored
     * tree (a tripwire) fail the call, not the process. With
     * [readsRefusals], the vendored content-unavailable family reads as YouTube's refusal,
     * UNAVAILABLE: verified for playlists (YouTube's "does not exist" alert) and video pages
     * (their playability status; age gates included, which the vendored check doesn't name,
     * stack-notes). The same type covers any InnerTube 404 and error-page redirect, so on these
     * calls endpoint drift reads as UNAVAILABLE too (accepted: telling them apart needs message
     * text or a vendored change); everywhere else it stays UNREADABLE. A video refusal the player
     * names (made-for-kids, not started: [videoRefusalOf]) rides along. With [readsRateLimit], the
     * transport's HTTP 429 ([OkHttpDownloader] throws it as the vendored [ReCaptchaException]) reads
     * as RATE_LIMITED; elsewhere it stays UNREADABLE.
     */
    private inline fun <T> guarded(
        what: String,
        readsRefusals: Boolean = false,
        readsRateLimit: Boolean = false,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e // KT-CONC-03: cancellation always flows through
        } catch (e: Exception) {
            val refused = if (readsRefusals) causeChain(e).firstNotNullOfOrNull { it as? ContentNotAvailableException } else null
            if (refused != null) {
                throw AccessException("content unavailable for $what", e, AccessFailure.UNAVAILABLE, videoRefusalOf(refused))
            }
            if (readsRateLimit && causeChain(e).any { it is ReCaptchaException }) {
                throw AccessException("rate limited for $what", e, AccessFailure.RATE_LIMITED)
            }
            throw AccessException("extraction failed for $what", e)
        } catch (e: LinkageError) {
            throw AccessException("extraction incompatible with this device for $what", e)
        }

    companion object {
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_COUNTRY = "US"
        private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="
        private const val YOUTUBE_HOST = "www.youtube.com"
        private const val TIMEDTEXT_PATH = "/api/timedtext"
        private const val LOCKUP_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO"

        private val installLock = Any()

        /**
         * Install the vendored extractor's process-global transport (its `NewPipe.init` state).
         * Both consumers of that state — this class and `WebViewPoTokenSource`'s minting — install
         * through here, so construction order cannot leave a downloader-less extractor behind.
         * Every call installs its downloader (`NewPipe.init` is a plain assignment), so each
         * NewPipeAccess deterministically drives its own transport — tests inject fixtures
         * without order hazards.
         */
        fun ensureInitialized(downloader: Downloader = OkHttpDownloader()) {
            synchronized(installLock) {
                NewPipe.init(
                    downloader,
                    Localization(DEFAULT_LANGUAGE, DEFAULT_COUNTRY),
                    ContentCountry(DEFAULT_COUNTRY),
                )
            }
        }

        /**
         * Minting-side guard: install the default transport only when none is present, so a
         * mid-extraction lazy mint can never clobber the transport a test (or a custom wiring)
         * installed.
         */
        fun ensureTransportPresent() {
            synchronized(installLock) {
                if (NewPipe.getDownloader() == null) ensureInitialized()
            }
        }
    }
}

internal fun StreamSegment.toChapter(): Chapter = Chapter(title, startTimeSeconds.toLong())

internal fun Frameset.toStoryboard(): Storyboard = Storyboard(
    pageUrls = urls,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
    frameCount = totalCount,
    frameDurationMs = durationPerFrame,
    framesPerPageX = framesPerPageX,
    framesPerPageY = framesPerPageY,
)

/**
 * The vendored extractor renders a description with links as HTML (`<br>` line breaks, `<a>`,
 * `<b>`/`<i>`/`<s>`, jsoup-escaped text); TV surfaces show it as plain text, its line breaks as
 * `\n` (comments arrive with `\r\n` too). `null` when blank.
 */
internal fun Description.toPlainText(): String? {
    val text = when (type) {
        Description.Type.HTML -> Jsoup.parseBodyFragment(content).body().wholeText().replace(NO_BREAK_SPACE, ' ')
        Description.Type.MARKDOWN, Description.Type.PLAIN_TEXT -> content
    }
    return text.replace(CARRIAGE_RETURNS, "\n").trim().ifEmpty { null }
}

private val CARRIAGE_RETURNS = Regex("\r\n?")

/** The extractor keeps runs of spaces visible as " &nbsp;". */
private const val NO_BREAK_SPACE = '\u00a0'

private val NUMERIC_CHARACTER_REFERENCE = Regex("&#(?:([0-9]{1,7})|[xX]([0-9a-fA-F]{1,6}));")

/**
 * The suggest endpoint declares ISO-8859-1 and sends every character outside it as an HTML
 * numeric character reference (`&#26481;&#20140;` for 東京; observed 2026-10-03), which the
 * vendored extractor passes through. A reference that names no valid code point stays as is.
 */
internal fun decodeNumericCharacterReferences(text: String): String =
    NUMERIC_CHARACTER_REFERENCE.replace(text) { match ->
        val (decimal, hex) = match.destructured
        val codePoint = decimal.toIntOrNull() ?: hex.toIntOrNull(radix = 16)
        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
