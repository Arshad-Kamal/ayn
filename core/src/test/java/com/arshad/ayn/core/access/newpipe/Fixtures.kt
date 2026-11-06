package com.arshad.ayn.core.access.newpipe

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Drives a channel's shorts tab raw (public extractor API only) — used by the recorder to
 * capture the corpus and by the offline test to map real short-form items; the seam's own
 * Shorts tab call replays the same request.
 */
internal fun rawShortsTabItems(downloader: Downloader): List<StreamInfoItem> {
    NewPipeAccess(downloader = downloader) // installs the transport the raw extractor uses
    val channelId = YoutubeChannelLinkHandlerFactory.getInstance().getId(SHORTS_CHANNEL_URL)
    val shortsTab = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, ChannelTabs.SHORTS)
    shortsTab.fetchPage()
    return shortsTab.initialPage.items.filterIsInstance<StreamInfoItem>()
}

/**
 * Swaps a completed recording in for [corpus] by directory renames, so a run that fails
 * before this point leaves the previous corpus whole. No rollback between the two renames:
 * git holds the corpus. [recording] must sit on the same file system (a sibling directory).
 */
internal fun replaceCorpus(recording: Path, corpus: Path) {
    val previous = corpus.resolveSibling("${corpus.fileName}-previous")
    previous.toFile().deleteRecursively()
    if (Files.exists(corpus)) Files.move(corpus, previous, ATOMIC_MOVE)
    Files.move(recording, corpus, ATOMIC_MOVE)
    previous.toFile().deleteRecursively()
}

/**
 * One-time capture tool: records live extraction traffic for one flow into
 * `<outDir>/<flow>/` (plus the bootstrap mirror) so
 * [FixtureDownloader] can replay it offline. Runs only under KE_SMOKE (it talks to YouTube).
 * Files are numbered per occurrence; every occurrence is written even when bodies repeat, so
 * replay order is exactly capture order.
 */
class RecordingDownloader(
    private val outDir: Path,
    private val flow: String,
    private val delegate: Downloader = OkHttpDownloader(),
) : Downloader() {

    private val occurrences = HashMap<String, Int>()
    private val recorded = HashSet<String>()

    /** Bodies captured by this recorder (this flow's slice, not whatever the dir already held;
     *  `Content-Type` sidecars not listed). */
    val recordedFiles: Set<String> get() = recorded

    init {
        Files.createDirectories(outDir.resolve(flow))
        Files.createDirectories(outDir.resolve("bootstrap"))
    }

    override fun execute(request: Request): Response = synchronized(this) {
        val response = delegate.execute(request)
        val key = FixtureKeys.of(request)
        val occurrence = occurrences.merge(key, 1, Int::plus)!!
        val name = FixtureKeys.fileName(key, occurrence)
        val contentType = response.getHeader("Content-Type")
        val dirs = listOfNotNull(flow, "bootstrap".takeIf { FixtureKeys.seedsProcessCache(key) })
        for (dir in dirs) {
            write(dir, name, response.responseBody())
            contentType?.let { write(dir, FixtureKeys.contentTypeFileName(name), it) }
        }
        recorded.add("$flow/$name")
        return response
    }

    private fun write(dir: String, name: String, text: String) {
        Files.write(outDir.resolve(dir).resolve(name), text.toByteArray(Charsets.UTF_8))
    }
}

/** The fixture key of the WEB `next` request: the video page's and the related call's alike. */
internal const val WEB_NEXT_KEY = "www.youtube.com_youtubei_v1_next__WEB"

/** On [CHAPTERS_VIDEO_FLOW]'s `next` answer: its first related lockup without its date text, which
 *  throws an unchecked exception past the vendored collector's per-item catch (the page reader then
 *  loses its whole related list). */
internal val UNDATED_RELATED_LOCKUP: Pair<String, String> =
    "{\"text\":{\"content\":\"9 mo ago\"}" to "{\"text\":{\"contentGone\":\"9 mo ago\"}"

/** On a WEB `next` answer: its related list renamed away. */
internal val NO_RELATED_LIST: Pair<String, String> =
    "\"secondaryResults\":{\"secondaryResults\"" to "\"secondaryResultsGone\":{\"secondaryResults\""

/** Replays [delegate], except that a request [matches] gets [answer] instead (a status of its own,
 *  or a thrown transport failure). */
internal class AnsweringDownloader(
    private val delegate: Downloader,
    private val matches: (Request) -> Boolean,
    private val answer: (Request) -> Response,
) : Downloader() {
    override fun execute(request: Request): Response = if (matches(request)) answer(request) else delegate.execute(request)
}

/** Passes every request to [delegate], keeping each one tried (failed replays included). */
internal class CapturingDownloader(private val delegate: Downloader) : Downloader() {
    val requests = mutableListOf<Request>()

    override fun execute(request: Request): Response {
        requests += request
        return delegate.execute(request)
    }
}

/** Replays [delegate] with each replacement applied to every response body (only [onlyKey]'s
 *  when set). */
internal class CorruptingDownloader(
    private val delegate: Downloader,
    vararg replacements: Pair<String, String>,
    private val onlyKey: String? = null,
) : Downloader() {
    private val pending = replacements.toMutableList()
    private val all = replacements.toList()

    fun unusedReplacements(): List<Pair<String, String>> = pending

    override fun execute(request: Request): Response {
        val response = delegate.execute(request)
        if (onlyKey != null && FixtureKeys.of(request) != onlyKey) return response
        var body = response.responseBody()
        all.forEach { (from, to) ->
            if (from in body) {
                body = body.replace(from, to)
                pending.remove(from to to)
            }
        }
        return Response(
            response.responseCode(), response.responseMessage(), response.responseHeaders(), body,
            response.latestUrl(),
        )
    }
}
