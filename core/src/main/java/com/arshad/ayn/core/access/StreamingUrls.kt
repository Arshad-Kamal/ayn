package com.arshad.ayn.core.access

/**
 * M3-SEC-01: extraction output is untrusted input, and the player sends its requests to the URL a
 * page names (the SABR endpoint gets the session's ustreamer config and playback state; a live
 * page's HLS manifest is fetched as is), so the URL must be https on a known YouTube serving host.
 */
internal fun isYouTubeStreamingUrl(url: String?): Boolean {
    val uri = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return YOUTUBE_STREAMING_HOSTS.any { host == it || host.endsWith(".$it") }
}

/** The hosts YouTube serves SABR/UMP streams and HLS manifests from (least privilege, SEC-SSRF-01). */
private val YOUTUBE_STREAMING_HOSTS = listOf("googlevideo.com", "youtube.com")
