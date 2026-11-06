package com.arshad.ayn.core.access.newpipe

import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * Fixture keys are stable across live recordings: the request URL's path (its query carries
 * per-request timestamps/cpn) plus the InnerTube `clientName` from the request body, which is
 * what distinguishes the several `player` POSTs.
 */
object FixtureKeys {
    private val CLIENT_NAME = Regex("\"clientName\"\\s*:\\s*\"([A-Za-z0-9_]+)\"")

    fun of(request: Request): String {
        val path = request.url()
            .substringAfter("://")
            .substringBefore("?")
            .trim('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val client = request.dataToSend()
            ?.toString(Charsets.UTF_8)
            ?.let { CLIENT_NAME.find(it)?.groupValues?.get(1) }
        return if (client != null) "${path}__$client" else path
    }

    /**
     * Requests whose responses seed the vendored extractor's process-static caches
     * (`sw.js` → clientVersion, `visitor_id` → visitor data, `iframe_api` + `base.js` → the
     * player code's signature timestamp) are mirrored into `bootstrap/`
     * by the recorder: a flow recorded with an already-warm process (an earlier flow in the
     * same session fetched them) still replays standalone on a cold test JVM via the
     * bootstrap fallback.
     */
    fun seedsProcessCache(key: String): Boolean =
        listOf("sw.js", "visitor_id", "iframe_api", "base.js").any { key.contains(it) }

    fun fileName(key: String, occurrence: Int): String =
        if (occurrence == 1) "$key.body" else "${key}__$occurrence.body"

    /** The sidecar holding a recorded body's `Content-Type` (the suggest extractor rejects a
     *  response without `application/json`). Recordings older than the sidecar have none. */
    fun contentTypeFileName(bodyFileName: String): String =
        bodyFileName.removeSuffix(".body") + ".content-type"
}

/**
 * Whether [flow] holds its recorded player code (`base.js`), under whatever player hash a
 * re-record gave it. YouTube's own code stays out of the public source tree, so the tests that
 * run it skip where this is false.
 */
fun playerCodeRecorded(flow: String): Boolean {
    val dir = FixtureDownloader::class.java.getResource("/extract-fixtures/$flow")
        ?: error("no fixture flow $flow")
    check(dir.protocol == "file") { "fixture flow $flow is not a directory: $dir" }
    return java.io.File(dir.toURI()).list()!!.any { it.endsWith("base.js.body") }
}

/**
 * Replays recorded extraction traffic from this source set's `resources/extract-fixtures/<flow>/`:
 * one subdirectory per extraction flow, files numbered per occurrence within the flow because
 * continuation requests repeat the same endpoint+client with different bodies. Requests that
 * seed process caches fall back to `bootstrap/`. An un-recorded request is a loud failure so
 * fixture drift shows up as a test failure, except the player-code fetch (`iframe_api`, the embed
 * page, `base.js`): the extractor tolerates its failure (no signature timestamp).
 *
 * Shipped as `:core` test fixtures so consuming modules can drive the real seam
 * ([NewPipeAccess]) on recorded payloads. Occurrence counters are per instance: construct one
 * per replay session and keep using it across calls of that session (paged listings continue
 * the occurrence sequence, in capture order); a fresh instance replays its flow from
 * occurrence 1. A recorded `Content-Type` sidecar is replayed as that header; no other header
 * is. [responseCode] replays the recorded bodies under another HTTP status (every
 * recording was a 200); keep it to flows that never reach InnerTube's client-version probe,
 * which caches the answer's status in process-static state.
 */
class FixtureDownloader(
    private val flow: String,
    private val responseCode: Int = 200,
) : Downloader() {

    private val occurrences = HashMap<String, Int>()

    override fun execute(request: Request): Response = synchronized(this) {
        val key = FixtureKeys.of(request)
        val occurrence = occurrences.merge(key, 1, Int::plus)!!
        val name = FixtureKeys.fileName(key, occurrence)
        val dir = if (exists("$flow/$name")) flow else "bootstrap"
        val body = text("$dir/$name")
            ?: throw IOException("no fixture for $flow/$name (occurrence $occurrence of $key: ${request.httpMethod()} ${request.url()})")
        val headers = text("$dir/${FixtureKeys.contentTypeFileName(name)}")
            ?.let { mapOf("Content-Type" to listOf(it)) }
            .orEmpty()
        return Response(responseCode, "replayed", headers, body, request.url())
    }

    private fun exists(path: String): Boolean =
        FixtureDownloader::class.java.getResource("/extract-fixtures/$path") != null

    private fun text(path: String): String? =
        FixtureDownloader::class.java.getResourceAsStream("/extract-fixtures/$path")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
}
