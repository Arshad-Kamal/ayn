package com.arshad.ayn.core.access.newpipe

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeMwebClient

/**
 * Probes, not behaviour tests (the host half of KP-D5's MWEB route): MWEB `player` for a
 * made-for-kids video, then the fork's `n` deobfuscation on its stream URLs, then first bytes
 * without a PO token (`build/kp1a-host.txt`); and MWEB `player` for a video Restricted Mode
 * blocks (not age-restricted), with and without `YouTube-Restrict: Strict`
 * (`build/kp1a-host-restrict.txt`). Opt-in: `KE_SMOKE=1`.
 */
class MwebHostSmokeTest {

    private val http = OkHttpClient()
    private val log = StringBuilder()

    @Test
    fun mwebPlayerAndNDeobfuscation() {
        assumeTrue(System.getenv("KE_SMOKE") != null)
        NewPipeAccess.ensureInitialized()
        try {
            probe(MADE_FOR_KIDS_VIDEO_ID)
        } catch (e: Exception) {
            note("FAILED: ${e::class.java.name}: ${e.message}")
            generateSequence(e.cause) { it.cause }.forEach { note("  cause: ${it::class.java.name}: ${it.message}") }
        } finally {
            File("build/kp1a-host.txt").writeText(log.toString())
        }
    }

    @Test
    fun restrictedModeStrictViaMweb() {
        assumeTrue(System.getenv("KE_SMOKE") != null)
        NewPipeAccess.ensureInitialized()
        try {
            for (restrict in listOf(null, "Strict")) {
                val status = mwebPlayer(RESTRICTED_VIDEO_ID, restrict).getObject("playabilityStatus")
                note("$RESTRICTED_VIDEO_ID YouTube-Restrict ${restrict ?: "none"}: ${status.getString("status")} ${status.getString("reason")}")
            }
        } catch (e: Exception) {
            note("FAILED: ${e::class.java.name}: ${e.message}")
        } finally {
            File("build/kp1a-host-restrict.txt").writeText(log.toString())
        }
    }

    private fun probe(videoId: String) {
        val player = mwebPlayer(videoId, restrict = null)
        val status = player.getObject("playabilityStatus")
        note("player status ${status.getString("status")} ${status.getString("reason")}")
        val streaming = player.getObject("streamingData")
        val formats = streaming.getArray("adaptiveFormats").filterIsInstance<JsonObject>()
        note("adaptive ${formats.size}, plain url ${formats.count { it.has("url") }}, cipher ${formats.count { it.has("signatureCipher") }}")

        val sabr = streaming.getString("serverAbrStreamingUrl")
        val sabrN = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, sabr)
        note("sabr n ${nOf(sabr)} -> ${nOf(sabrN)}")

        val vp9 = formats.filter { it.getString("mimeType").contains("vp9") }.maxBy { it.getInt("height") }
        val raw = vp9.getString("url")
        val url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, raw)
        note("itag ${vp9.getInt("itag")} ${vp9.getInt("height")}p n ${nOf(raw)} -> ${nOf(url)}")
        note("https bytes, n deobfuscated, no pot: HTTP ${firstBytes(url)}")
        note("https bytes, raw n, no pot: HTTP ${firstBytes(raw)}")
        File("build/kp1a-host-player.json").writeText(JsonWriter.string(player))
    }

    /** MWEB `player` for [videoId] with a fresh visitor, optionally under Restricted Mode [restrict]. */
    private fun mwebPlayer(videoId: String, restrict: String?): JsonObject {
        val version = Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
            .find(get("https://m.youtube.com/").body!!.string())!!.groupValues[1]
        note("mweb version $version")
        val sts = YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        note("signatureTimestamp $sts")

        val client = JsonObject().apply {
            put("clientName", YoutubeMwebClient.CLIENT_NAME); put("clientVersion", version); put("userAgent", UA)
            put("hl", "en"); put("gl", "US"); put("utcOffsetMinutes", 0)
        }
        val visitor = post(VISITOR_URL, JsonObject().apply { put("context", JsonObject().apply { put("client", client) }) }, version, null, null)
            .getObject("responseContext").getString("visitorData")
        client.put("visitorData", visitor)
        val body = JsonObject().apply {
            put("context", JsonObject().apply { put("client", client) })
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
            put("playbackContext", JsonObject().apply {
                put("contentPlaybackContext", JsonObject().apply { put("signatureTimestamp", sts) })
            })
        }
        return post(PLAYER_URL, body, version, visitor, restrict)
    }

    private fun firstBytes(url: String): Int =
        http.newCall(Request.Builder().url("$url&range=0-1023").header("User-Agent", UA).build()).execute().use { it.code }

    private fun nOf(url: String): String? = Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)

    private fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()

    private fun post(url: String, body: JsonObject, version: String, visitor: String?, restrict: String?): JsonObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA).header("X-YouTube-Client-Name", YoutubeMwebClient.CLIENT_ID.toString()).header("X-YouTube-Client-Version", version)
            .header("Origin", "https://m.youtube.com").header("Cookie", "SOCS=CAE=")
            .apply { visitor?.let { header("X-Goog-Visitor-Id", it) } }
            .apply { restrict?.let { header("YouTube-Restrict", it) } }
            .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { JsonParser.`object`().from(it.body!!.string()) }
    }

    private fun note(line: String) {
        log.appendLine(line)
    }

    private companion object {
        const val RESTRICTED_VIDEO_ID = "zP6eqCOM7tk" // Restricted Mode's probe video (KP-D2)
        const val UA = YoutubeMwebClient.USER_AGENT
        const val VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    }
}
