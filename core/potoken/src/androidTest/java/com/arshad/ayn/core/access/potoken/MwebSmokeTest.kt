package com.arshad.ayn.core.access.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.sabr.webPoTokenBytes
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrFormat
import com.github.libretube.player.manifest.SabrStreamSet
import com.github.libretube.player.manifest.toSabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import com.github.libretube.player.parser.SabrClientIdentity
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.services.youtube.YoutubeMwebClient

/**
 * KP.1a probe (KP-D5's route, KP-D27's solver): for a made-for-kids video, mint a web PO token,
 * ask MWEB `player` with it, solve `n` with yt-dlp EJS in a WebView Web Worker, then stream SABR
 * as MWEB through the vendored client past the point where an unattested session is stopped
 * (~65 s of media), with the player call's token and with a later mint's (one minter serves every
 * mint; one made per mint gave tokens GVS refuses past ~65 s). The https way (a token-less player
 * call, `pot` on the URL) and the controls (no token, raw `n`) are logged for comparison. Every
 * step is logged under [TAG] with timings (no token values). Opt-in: `KE_SMOKE=1` (runner argument).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MwebSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val http = OkHttpClient()

    @Test
    fun madeForKidsPlaysThroughMweb() {
        assumeTrue(
            "set KE_SMOKE=1 to run network smoke tests",
            InstrumentationRegistry.getArguments().containsKey("KE_SMOKE"),
        )
        val lib = fetchVerified(EJS_LIB_URL, EJS_LIB_SHA256)
        val core = fetchVerified(EJS_CORE_URL, EJS_CORE_SHA256)

        val page = get("https://m.youtube.com/").body.string()
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(page)!!.groupValues[1]
        val jsUrl = Regex("\"jsUrl\":\"([^\"]+)\"").find(page)!!.groupValues[1]
        val playerId = Regex("/s/player/([0-9a-zA-Z]+)/").find(jsUrl)!!.groupValues[1]
        val (baseJs, baseJsMs) = timed { get("https://www.youtube.com$jsUrl").body.string() }
        val sts = Regex("signatureTimestamp:(\\d+)").find(baseJs)!!.groupValues[1].toInt()
        note("mweb $version, player $jsUrl (id $playerId), sts $sts, base.js ${baseJs.length} chars in $baseJsMs ms")

        val source = WebViewPoTokenSource(context)
        val (token, mintMs) = timed { source.webClientPoToken(VideoId(VIDEO_ID)) }
        note("mint ${if (token == null) "FAILED" else "ok"} in $mintMs ms")
        token!!
        val (second, warmMintMs) = timed { source.webClientPoToken(VideoId(VIDEO_ID))!! }
        note("warm mint in $warmMintMs ms, same token ${second.streamingDataPoToken == token.streamingDataPoToken}")

        val (player, playerMs) = timed { mwebPlayer(version, sts, token.visitorData, token.playerRequestPoToken) }
        val streaming = describe("player with pot", player, playerMs)
        val formats = streaming["adaptiveFormats"]!!.jsonArray.map { it.jsonObject }
        val video = formats.filter { it.str("mimeType")!!.contains("vp9") && it.int("height") == 1080 }
            .ifEmpty { formats.filter { it.str("mimeType")!!.contains("vp9") } }
            .maxBy { it.int("height") ?: 0 }
        val audio = formats.filter { it.str("mimeType")!!.startsWith("audio/webm") }.maxBy { it.int("bitrate") ?: 0 }
        val sabrUrl = streaming.str("serverAbrStreamingUrl")!!

        // The https way: a player call without the token answers per-format URLs (yt-dlp's mweb).
        val (plain, plainMs) = timed { mwebPlayer(version, sts, token.visitorData, null) }
        val plainVideo = describe("player without pot", plain, plainMs)["adaptiveFormats"]!!.jsonArray
            .map { it.jsonObject }.first { it.int("itag") == video.int("itag") }
        val videoUrl = plainVideo.str("url")
        val challenges = listOfNotNull(videoUrl?.let(::nOf), nOf(sabrUrl)).distinct()

        val (solved, solveMs) = timed { EjsWorker(lib, core, baseJs).solveN(challenges) }
        challenges.forEach { note("n $playerId ${it} -> ${solved[it]}") }
        note("solve wall $solveMs ms (worker ${solved[WORKER_MS]} ms; from the preprocessed player ${solved[WARM_MS]} ms, same answers ${solved[SAME]})")

        val pot = token.streamingDataPoToken
        if (videoUrl != null) {
            val solvedVideoUrl = withN(videoUrl, solved[nOf(videoUrl)]!!)
            val (bytes, httpsMs) = timed { firstBytes("$solvedVideoUrl&pot=${enc(pot)}") }
            note("https itag ${video.int("itag")} ${video.int("height")}p, solved n + pot: $bytes in $httpsMs ms")
            note("https control, raw n + pot: ${firstBytes("$videoUrl&pot=${enc(pot)}")}")
            val deep = plainVideo.str("contentLength")!!.toLong() * 6 / 10
            note("https at byte $deep, solved n + pot: ${firstBytes("$solvedVideoUrl&pot=${enc(pot)}", deep)}")
            note("https at byte $deep, solved n, no pot: ${firstBytes(solvedVideoUrl, deep)}")
        } else {
            note("https: no per-format URL")
        }

        val sabrConfig = player["playerConfig"]!!.jsonObject["mediaCommonConfig"]!!.jsonObject
            .get("mediaUstreamerRequestConfig")!!.jsonObject.str("videoPlaybackUstreamerConfig")!!
        val solvedSabrUrl = withN(sabrUrl, solved[nOf(sabrUrl)]!!)
        val identity = SabrClientIdentity(
            YoutubeMwebClient.CLIENT_ID, version, YoutubeMwebClient.DEVICE_MAKE, YoutubeMwebClient.DEVICE_MODEL,
            YoutubeMwebClient.OS_NAME, YoutubeMwebClient.OS_VERSION, UA,
        )
        val (sabrBytes, sabrMs) = timed {
            sabrFirstBytes(solvedSabrUrl, sabrConfig, video, audio, identity, webPoTokenBytes(token.streamingDataPoToken))
        }
        note("sabr as MWEB, solved n + the player call's pot: $sabrBytes in $sabrMs ms")
        val other = sabrFirstBytes(solvedSabrUrl, sabrConfig, video, audio, identity, webPoTokenBytes(second.streamingDataPoToken))
        note("sabr as MWEB, solved n + a later mint's pot: $other")
        note("sabr control, no pot: ${sabrFirstBytes(solvedSabrUrl, sabrConfig, video, audio, identity, null)}")
        source.close()

        assertTrue("SABR as MWEB must stream past attestation: $sabrBytes", sabrBytes.contains("bytes $WEBM_MAGIC") && sabrBytes.endsWith("; ok"))
        assertTrue("a later mint's token must stream past attestation too: $other", other.endsWith("; ok"))
    }

    /** Logs a player answer's status and format shapes; returns its streamingData. */
    private fun describe(label: String, player: JsonObject, ms: Long): JsonObject {
        val status = player["playabilityStatus"]!!.jsonObject
        val streaming = player["streamingData"]!!.jsonObject
        val formats = streaming["adaptiveFormats"]!!.jsonArray.map { it.jsonObject }
        note(
            "$label: ${status.str("status")} in $ms ms; adaptive ${formats.size}, url ${formats.count { "url" in it }}, " +
                "cipher ${formats.count { "signatureCipher" in it }}, sabr ${"serverAbrStreamingUrl" in streaming}"
        )
        return streaming
    }

    private fun mwebPlayer(version: String, sts: Int, visitorData: String, playerToken: String?): JsonObject {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", YoutubeMwebClient.CLIENT_NAME); put("clientVersion", version); put("userAgent", UA)
                    put("deviceMake", YoutubeMwebClient.DEVICE_MAKE); put("deviceModel", YoutubeMwebClient.DEVICE_MODEL)
                    put("osName", YoutubeMwebClient.OS_NAME); put("osVersion", YoutubeMwebClient.OS_VERSION); put("hl", "en"); put("gl", "US")
                    put("utcOffsetMinutes", 0); put("visitorData", visitorData)
                }
            }
            put("videoId", VIDEO_ID); put("contentCheckOk", true); put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("signatureTimestamp", sts); put("html5Preference", "HTML5_PREF_WANTS")
                }
            }
            if (playerToken != null) putJsonObject("serviceIntegrityDimensions") { put("poToken", playerToken) }
        }
        val request = Request.Builder().url(PLAYER_URL)
            .header("User-Agent", UA).header("X-YouTube-Client-Name", YoutubeMwebClient.CLIENT_ID.toString()).header("X-YouTube-Client-Version", version)
            .header("X-Goog-Visitor-Id", visitorData).header("Origin", "https://m.youtube.com").header("Cookie", "SOCS=CAE=")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { Json.parseToJsonElement(it.body.string()).jsonObject }
    }

    /** "HTTP code, first 4 bytes" of the KiB at [from] in [url]'s resource. */
    private fun firstBytes(url: String, from: Long = 0): String =
        http.newCall(Request.Builder().url("$url&range=$from-${from + 1023}").header("User-Agent", UA).build()).execute().use {
            "HTTP ${it.code} ${it.body.bytes().take(4).hex()}"
        }

    /**
     * The video format through the vendored SABR client: its init segment ("N bytes, first 4"),
     * then segments in order until [SABR_PLAY_MS] of media or a failure (the server's protection
     * status per response is in logcat under SabrStream).
     */
    private fun sabrFirstBytes(
        url: String,
        ustreamerConfig: String,
        video: JsonObject,
        audio: JsonObject,
        identity: SabrClientIdentity,
        token: ByteArray?,
    ): String = runCatching {
        val (videoFormat, audioFormat) = sabrFormat(video) to sabrFormat(audio)
        val manifest = SabrStreamSet(url, ustreamerConfig, (video.str("approxDurationMs")!!.toLong()) / 1000, listOf(videoFormat), listOf(audioFormat))
            .toSabrManifest(VIDEO_ID)
        val client = SabrClient(manifest, { token }, { null }, DefaultBandwidthMeter.Builder(context).build(), http, identity = identity)
        val videoRep = Representation(videoFormat)
        client.selectFormat(videoRep)
        client.selectFormat(Representation(audioFormat))
        val formatId = videoRep.formatId()
        val (initSegment, initMs) = timed { client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }
        val init = initSegment ?: return@runCatching "no init segment"
        val bytes = init.data.fold(ByteArray(0)) { acc, part -> acc + part }
        val buffered = mutableListOf<Long>()
        var mediaMs = 0L
        val ended = runCatching {
            while (mediaMs < SABR_PLAY_MS) {
                val number = buffered.size + 1L
                val segment = client.getNextSegment(PlaybackRequest(formatId, mediaMs, 1f, number, mediaMs, buffered.toList()))
                    ?: return@runCatching "no segment $number"
                buffered += number
                mediaMs = segment.header.startMs + segment.duration
            }
            "ok"
        }.getOrElse { "${it::class.java.simpleName}: ${it.message}" }
        "init ${bytes.size} bytes ${bytes.take(4).hex()} in $initMs ms; ${buffered.size} segments to $mediaMs ms; $ended"
    }.getOrElse { "failed ${it::class.java.simpleName}: ${it.message}" }

    private fun sabrFormat(format: JsonObject): SabrFormat {
        val mime = format.str("mimeType")!!
        return SabrFormat(
            itag = format.int("itag"), lastModified = format.str("lastModified")!!.toLong(), xtags = format.str("xtags"),
            mimeType = mime.substringBefore(";"), codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1),
            bitrate = format.int("bitrate"), fps = format.int("fps"), width = format.int("width"), height = format.int("height"),
            audioTrackId = null, audioTrackLocale = null, audioTrackType = null,
            durationMs = format.str("approxDurationMs")?.toLong(), isDrc = null,
        )
    }

    private fun fetchVerified(url: String, sha256: String): String {
        val bytes = get(url).body.bytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        check(actual == sha256) { "sha256 mismatch for $url: $actual" }
        return bytes.decodeToString()
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()

    /**
     * yt-dlp EJS in a Web Worker inside a WebView (KP-D27): the scripts and base.js cross into the
     * page through a JavaScript interface and are posted to the worker; the page's main thread
     * never evaluates the solver (its `location` shim navigates, ejs #76).
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private inner class EjsWorker(private val lib: String, private val core: String, private val player: String) {
        private val done = CountDownLatch(1)
        @Volatile private var result: String? = null
        private var challenges = "[]"

        @JavascriptInterface fun lib(): String = lib
        @JavascriptInterface fun core(): String = core
        @JavascriptInterface fun player(): String = player
        @JavascriptInterface fun challenges(): String = challenges
        @JavascriptInterface fun done(json: String) {
            result = json
            done.countDown()
        }

        /**
         * Challenge → answer, plus [WORKER_MS] → the worker's full solve time, [WARM_MS] → its
         * re-solve from the preprocessed player, [SAME] → whether both gave the same answers.
         */
        fun solveN(challengeList: List<String>): Map<String, String> {
            challenges = JsonArray(challengeList.map(::JsonPrimitive)).toString()
            var webView: WebView? = null
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(this@EjsWorker, "KeEjs")
                    loadDataWithBaseURL("https://www.youtube.com/", WORKER_PAGE, "text/html", "utf-8", null)
                }
            }
            try {
                check(done.await(60, TimeUnit.SECONDS)) { "EJS worker timed out" }
            } finally {
                instrumentation.runOnMainSync { webView?.destroy() }
            }
            val message = Json.parseToJsonElement(result!!).jsonObject
            message["error"]?.let { error("EJS worker: $it") }
            val output = message["r"]!!.jsonObject
            check(output.str("type") == "result") { "EJS: $output" }
            val response = output["responses"]!!.jsonArray.single().jsonObject
            check(response.str("type") == "result") { "EJS n: $response" }
            return response["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } +
                (WORKER_MS to message["ms"]!!.jsonPrimitive.content.toDouble().toLong().toString()) +
                (WARM_MS to message["warmMs"]!!.jsonPrimitive.content.toDouble().toLong().toString()) +
                (SAME to message["same"]!!.jsonPrimitive.content)
        }
    }

    private fun note(line: String) {
        Log.i(TAG, line)
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = SystemClock.elapsedRealtime()
        val value = block()
        return value to SystemClock.elapsedRealtime() - start
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.int

    private fun nOf(url: String): String? = Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)

    private fun withN(url: String, n: String): String = url.replace(Regex("([?&]n=)[^&]+"), "$1$n")

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun List<Byte>.hex(): String = toByteArray().hex()

    private companion object {
        const val TAG = "KeKp1a"
        const val VIDEO_ID = "L8A4XbM5sXA" // Dora, made for kids
        const val WORKER_MS = "_workerMs"
        const val WARM_MS = "_warmMs"
        const val SAME = "_same"
        const val WEBM_MAGIC = "1a45dfa3"
        const val SABR_PLAY_MS = 150_000L
        const val UA = YoutubeMwebClient.USER_AGENT
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val EJS_LIB_URL = "https://github.com/yt-dlp/ejs/releases/download/0.8.0/yt.solver.lib.min.js"
        const val EJS_LIB_SHA256 = "c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577"
        const val EJS_CORE_URL = "https://github.com/yt-dlp/ejs/releases/download/0.8.0/yt.solver.core.min.js"
        const val EJS_CORE_SHA256 = "18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00"

        val WORKER_PAGE = """
            <!DOCTYPE html><html><body><script>
            const src = [KeEjs.lib(), "\nObject.assign(globalThis, lib);\n", KeEjs.core(),
              "\nonmessage = (e) => { let r, ms, warmMs = -1, same = false; const t = performance.now();" +
              " try { r = jsc(Object.assign({}, e.data, { output_preprocessed: true })); ms = performance.now() - t;" +
              " const t2 = performance.now();" +
              " const r2 = jsc({ type: 'preprocessed', preprocessed_player: r.preprocessed_player, requests: e.data.requests });" +
              " warmMs = performance.now() - t2; delete r.preprocessed_player;" +
              " same = JSON.stringify(r.responses) === JSON.stringify(r2.responses);" +
              " } catch (x) { r = { type: 'error', error: String(x) }; ms = -1; }" +
              " postMessage({ r, ms, warmMs, same }); };"];
            const worker = new Worker(URL.createObjectURL(new Blob(src, { type: "text/javascript" })));
            worker.onmessage = (e) => KeEjs.done(JSON.stringify(e.data));
            worker.onerror = (e) => KeEjs.done(JSON.stringify({ error: String(e.message) }));
            worker.postMessage({ type: "player", player: KeEjs.player(),
              requests: [{ type: "n", challenges: JSON.parse(KeEjs.challenges()) }], output_preprocessed: false });
            </script></body></html>
        """.trimIndent()
    }
}
