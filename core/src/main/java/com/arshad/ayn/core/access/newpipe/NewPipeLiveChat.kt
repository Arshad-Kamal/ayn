package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.ReplayPage
import com.arshad.ayn.core.access.liveChatUpdates
import com.arshad.ayn.core.access.nestsWithin
import com.grack.nanojson.JsonBuilder
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * [LiveChat] over InnerTube's WEB chat endpoints (LIVE-D6, D7), built the way the vendored extractor
 * builds its WEB requests (`prepareDesktopJsonBuilder`, its headers), through [downloader]. No API
 * key, PO token, cookies or visitor data: the LIVE.S recordings were made without them.
 *
 * Failures: no connection, a 5xx and a 429 read as [AccessFailure.OFFLINE] (`live`'s poll loop
 * retries them after the first answer; a replay's caller decides); any other status, an answer
 * over [MAX_ANSWER_CHARS] or nested past [MAX_JSON_DEPTH] (KT-JSON-03: the parser recurses), or one
 * that isn't chat as [AccessFailure.UNREADABLE]. The catch wall is [NewPipeAccess]'s `guarded` for one call. A
 * request in flight finishes before a cancelled caller ends (the vendored transport blocks), its
 * answer dropped.
 */
class NewPipeLiveChat(
    private val downloader: Downloader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LiveChat {

    override fun live(token: String): Flow<LiveChatUpdate> = liveChatUpdates(token) { next ->
        fetch(LIVE_ENDPOINT, next, ::liveChatReplyOf)
    }

    override suspend fun replay(token: String, offsetMs: Long): ReplayPage =
        fetch(REPLAY_ENDPOINT, token, ::replayPageOf) {
            `object`("currentPlayerState").value("playerOffsetMs", offsetMs.toString()).end()
        }

    /**
     * [token] posted to [endpoint] as WEB with [fields] added, its answer bounded, parsed and
     * [read] (`null`: not chat). The body is built inside the catch wall: the builder may fetch
     * YouTube's client version.
     */
    private suspend fun <T : Any> fetch(
        endpoint: String,
        token: String,
        read: (JsonObject) -> T?,
        fields: JsonBuilder<JsonObject>.() -> Unit = {},
    ): T = withContext(io) {
        val body = try {
            post(endpoint, token, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReCaptchaException) {
            throw AccessException("live chat rate limited", e, AccessFailure.OFFLINE)
        } catch (e: Exception) {
            throw AccessException("live chat request failed", e)
        } catch (e: LinkageError) {
            throw AccessException("live chat incompatible with this device", e)
        }
        if (body.length > MAX_ANSWER_CHARS || !nestsWithin(body, MAX_JSON_DEPTH)) {
            throw AccessException("live chat answer out of bounds", IllegalStateException("${body.length} chars"), AccessFailure.UNREADABLE)
        }
        val answer = try {
            JsonParser.`object`().from(body)
        } catch (e: JsonParserException) {
            throw AccessException("live chat answer unreadable", e, AccessFailure.UNREADABLE)
        }
        read(answer)
            ?: throw AccessException("not a live chat answer", IllegalStateException("no chat contents"), AccessFailure.UNREADABLE)
    }

    /** The answer's body; a status other than 200 throws (5xx as [IOException], so it retries). */
    private fun post(endpoint: String, token: String, fields: JsonBuilder<JsonObject>.() -> Unit): String {
        val localization = ServiceList.YouTube.localization
        val request = YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, ServiceList.YouTube.contentCountry)
            .value("continuation", token)
            .apply(fields)
            .done()
        val response = downloader.postWithContentTypeJson(
            YoutubeParsingHelper.YOUTUBEI_V1_URL + endpoint + "?" + YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER,
            YoutubeParsingHelper.getYouTubeHeaders(),
            JsonWriter.string(request).toByteArray(Charsets.UTF_8),
            localization,
        )
        val code = response.responseCode()
        if (code >= HTTP_SERVER_ERROR) throw IOException("live chat answered HTTP $code")
        check(code == HTTP_OK) { "live chat answered HTTP $code" }
        return response.responseBody()
    }

    private companion object {
        const val LIVE_ENDPOINT = "live_chat/get_live_chat"
        const val REPLAY_ENDPOINT = "live_chat/get_live_chat_replay"
        const val HTTP_OK = 200
        const val HTTP_SERVER_ERROR = 500

        /** The recordings reach 186 K characters and depth 26; a busy chat's ~200 lines stay far
         *  inside both. */
        const val MAX_ANSWER_CHARS = 4_000_000
        const val MAX_JSON_DEPTH = 64
    }
}
