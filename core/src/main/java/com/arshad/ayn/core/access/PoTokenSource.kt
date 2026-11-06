package com.arshad.ayn.core.access

/**
 * PO-token minting seam (spec D7). Minting runs in a WebView (BotGuard) at M3.4; until then
 * [NoPoTokenSource] is wired and extraction proceeds without tokens (possibly degraded).
 *
 * Methods are **blocking by contract**: the vendored extractor calls them synchronously on the
 * extraction dispatcher; a WebView-backed implementation hops to the main thread internally.
 * Implementations must be **thread-safe** (the vendored provider interface requires it).
 */
data class PoToken(
    val visitorData: String,
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)

interface PoTokenSource {
    fun webClientPoToken(videoId: VideoId): PoToken?
    fun webEmbedClientPoToken(videoId: VideoId): PoToken?
    fun androidClientPoToken(videoId: VideoId): PoToken?
    fun iosClientPoToken(videoId: VideoId): PoToken?
}

/** Until M3.4: no tokens minted; the extractor falls back to token-free client paths. */
object NoPoTokenSource : PoTokenSource {
    override fun webClientPoToken(videoId: VideoId): PoToken? = null
    override fun webEmbedClientPoToken(videoId: VideoId): PoToken? = null
    override fun androidClientPoToken(videoId: VideoId): PoToken? = null
    override fun iosClientPoToken(videoId: VideoId): PoToken? = null
}
