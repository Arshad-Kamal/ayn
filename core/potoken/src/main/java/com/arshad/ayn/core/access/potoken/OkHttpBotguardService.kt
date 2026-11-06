package com.arshad.ayn.core.access.potoken

import com.arshad.ayn.core.access.defaultHttpClient
import com.github.libretube.api.poToken.BOTGUARD_USER_AGENT
import com.github.libretube.api.poToken.BotguardService
import com.github.libretube.api.poToken.PoTokenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp implementation of the vendored [BotguardService] seam: POSTs the JSON array payload to
 * the BotGuard web service with the headers the service expects (mirrors upstream's Retrofit
 * declaration), and GETs the homepage and interpreter as the BotGuard browser would. Transport
 * only — no minting logic (house media3.md PL-7 pattern).
 */
class OkHttpBotguardService(
    private val client: OkHttpClient = defaultHttpClient(),
) : BotguardService {

    override suspend fun post(url: String, data: List<String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BOTGUARD_USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", CONTENT_TYPE)
            .header("x-goog-api-key", WEB_API_KEY)
            .header("x-user-agent", GRPC_WEB_USER_AGENT)
            .post(Json.encodeToString(data).toRequestBody(CONTENT_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", BOTGUARD_USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PoTokenException("HTTP ${response.code} for a BotGuard page")
            // The interpreter's host is allowlisted before the fetch; a redirect must not move it.
            if (response.request.url.host != request.url.host) throw PoTokenException("BotGuard page redirected off its host")
            response.body.string()
        }
    }

    private companion object {
        // MODIFIED: local constants (were LibreTube's ExternalApi header values, verbatim).
        const val CONTENT_TYPE = "application/json+protobuf"
        const val GRPC_WEB_USER_AGENT = "grpc-web-javascript/0.1"

        /** Public YouTube web client key (same value as upstream's ExternalApi constant). */
        const val WEB_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    }
}
