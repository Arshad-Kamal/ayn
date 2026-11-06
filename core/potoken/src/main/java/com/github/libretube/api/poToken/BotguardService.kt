// ADDED (YouTube KE, 2026-09-27) — part of the vendoring delta (THIRD-PARTY.md entry 3).
// Replaces LibreTube's RetrofitInstance.externalApi.botguardRequest dependency: the BotGuard
// web service is reached through this seam (implemented in :core's package with OkHttp).
// 2026-10-23: plus get() for the homepage challenge and its interpreter script.
package com.github.libretube.api.poToken

/** User agent shared by the BotGuard WebView and the web-service requests (upstream constant). */
const val BOTGUARD_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.3"

interface BotguardService {
    /** POST to the BotGuard web service (`GenerateIT`), returning the response body. */
    suspend fun post(url: String, data: List<String>): String

    /** GET [url] (the YouTube homepage, the BotGuard interpreter), returning the body; non-2xx throws. */
    suspend fun get(url: String): String
}
