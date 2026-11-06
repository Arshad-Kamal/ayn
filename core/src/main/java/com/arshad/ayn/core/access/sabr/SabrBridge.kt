package com.arshad.ayn.core.access.sabr

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.arshad.ayn.core.access.StreamCandidate
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.WebStreamingClient
import com.arshad.ayn.core.access.isYouTubeStreamingUrl
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrFormat
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.player.manifest.SabrStreamSet
import com.github.libretube.player.manifest.toSabrManifest
import com.github.libretube.player.parser.SabrClientIdentity
import kotlin.io.encoding.Base64
import okhttp3.OkHttpClient

/**
 * Map extraction output to a [SabrStreamSet] (M3.5). The video list is pinned to VP9 profile 0
 * at emission (TRK-1: a hard codec pin is a format-list policy, not a selector preference), and
 * capped at [maxShortSide] lines (M7-D15).
 */
internal fun sabrStreamSetFrom(metadata: VideoMetadata, maxShortSide: Int = Int.MAX_VALUE): SabrStreamSet {
    val video = metadata.streams
        .filter { it.kind != StreamKind.AUDIO }
        .filter { it.isVp9Profile0() }
        .mapNotNull { it.toSabrFormat() }
        .capped(maxShortSide)
    val audio = metadata.streams
        .filter { it.kind == StreamKind.AUDIO }
        .mapNotNull { it.toSabrFormat() }
    return SabrStreamSet(
        serverAbrStreamingUrl = metadata.serverAbrStreamingUrl,
        videoPlaybackUstreamerConfig = metadata.ustreamerConfig,
        duration = metadata.durationSeconds,
        videoStreams = video,
        audioStreams = audio,
    )
}

/**
 * VP9 profile 0 (Shield ceiling: no AV1, no VP9 profile 2/HDR). YouTube encodes VP9 as a bare
 * `vp9` codecs string (profile 0) or the RFC 6381 `vp09.00.*` form.
 */
internal fun StreamCandidate.isVp9Profile0(): Boolean =
    codec?.let { it.startsWith("vp9") || it.startsWith("vp09.00") } == true

/**
 * The formats whose picture's short side is at most [maxShortSide] (a portrait Short's 1080×1920
 * counts as 1080, as the player's badge names it). With none that small, the smallest alone: a
 * preference never leaves a video unplayable. A format of unknown size stays.
 */
private fun List<SabrFormat>.capped(maxShortSide: Int): List<SabrFormat> {
    val fitting = filter { format -> format.shortSide()?.let { it <= maxShortSide } ?: true }
    return fitting.ifEmpty { listOfNotNull(minByOrNull { it.shortSide() ?: Int.MAX_VALUE }) }
}

private fun SabrFormat.shortSide(): Int? {
    val w = width ?: return null
    val h = height ?: return null
    return minOf(w, h)
}

/** Streams missing the identity fields `Representation.formatId()` asserts cannot be emitted. */
private fun StreamCandidate.toSabrFormat(): SabrFormat? {
    val itagValue = itag ?: return null
    val modified = lastModified?.takeIf { it > 0 } ?: return null // extractor sentinels: -1 / 0
    val mime = mimeType ?: return null
    return SabrFormat(
        itag = itagValue,
        lastModified = modified,
        xtags = xtags,
        mimeType = mime,
        codec = codec,
        bitrate = bitrate,
        fps = fps,
        width = width,
        height = height,
        audioTrackId = audioTrackId,
        audioTrackLocale = audioTrackLocale,
        audioTrackType = audioTrackType,
        durationMs = durationMs,
        isDrc = isDrc,
    )
}

/** Trust + pin sanity for an emission-bound set; a missing endpoint fails the trust check. */
internal fun requirePlayable(streamSet: SabrStreamSet, videoId: String) {
    require(isYouTubeStreamingUrl(streamSet.serverAbrStreamingUrl)) {
        "untrusted SABR endpoint for $videoId"
    }
    require(streamSet.videoStreams.isNotEmpty() && streamSet.audioStreams.isNotEmpty()) {
        "no playable formats for $videoId"
    }
}

/** One video's validated SABR manifest and its [session]; opaque outside this package (spec D7). */
@OptIn(UnstableApi::class)
class SabrPlayable internal constructor(
    internal val manifest: SabrManifest,
    internal val session: SabrSession,
)

/** The client a SABR session speaks as, and the PO token it sends (`null` for VISIONOS). */
internal class SabrSession(val identity: SabrClientIdentity, val poToken: ByteArray?)

/**
 * The session [metadata]'s answer calls for: VISIONOS without a token, or the web client it came
 * from with the token its player request carried. Throws [IllegalArgumentException] for a token
 * that isn't base64url.
 */
internal fun sabrSessionFor(metadata: VideoMetadata): SabrSession {
    val client = metadata.webStreamingClient ?: return SabrSession(SabrClientIdentity.VISIONOS, poToken = null)
    val poToken = requireNotNull(webPoTokenBytes(client.poToken)) { "PO token for ${metadata.id.value} is not base64url" }
    return SabrSession(client.toSabrClientIdentity(), poToken)
}

/**
 * Validate extraction output into a [SabrPlayable] whose video formats stop at [maxShortSide]
 * lines (the TV's quality cap, M7-D15). Throws [IllegalArgumentException] for a missing or
 * untrusted endpoint, no playable formats, or a web client's PO token that isn't base64url, so a
 * resolve fails before any source exists.
 */
@OptIn(UnstableApi::class)
fun sabrPlayable(metadata: VideoMetadata, maxShortSide: Int): SabrPlayable {
    val streamSet = sabrStreamSetFrom(metadata, maxShortSide)
    requirePlayable(streamSet, metadata.id.value) // validate before any construction
    val session = sabrSessionFor(metadata)
    return SabrPlayable(streamSet.toSabrManifest(metadata.id.value), session)
}

private fun WebStreamingClient.toSabrClientIdentity(): SabrClientIdentity = SabrClientIdentity(
    clientName = clientId,
    clientVersion = clientVersion,
    deviceMake = deviceMake,
    deviceModel = deviceModel,
    osName = osName,
    osVersion = osVersion,
    userAgent = userAgent,
)

private val base64UrlLenientPadding: Base64 =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * A web PO token's bytes, which the SABR request carries as googlevideo's reference client sends
 * them; the minter hands back base64url text, whose UTF-8 bytes are not the token. Null when the
 * text is not base64url.
 */
fun webPoTokenBytes(token: String): ByteArray? =
    try {
        base64UrlLenientPadding.decode(token)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * D7 adapter from extraction output to the vendored SABR chain (THIRD-PARTY.md entry 2).
 * This package is the only place outside `:core:sabr` allowed to touch
 * `com.github.libretube.player.*` types (spec D7, project media3.md PL-8 / house M3-PIPE-08);
 * the returned [MediaSource] keeps those types out of caller code.
 *
 * A VISIONOS session sends no PO token (M5-D22): VISIONOS refuses the WEB-client token KE can mint
 * (a token is bound to the client it was minted for). A made-for-kids video's session speaks as
 * the web client its player answer came from and sends the token that player request carried
 * (spec 12 KP-D31). The client's re-mint seam stays unwired either way: attestation is answered by
 * a new session (the control plane re-resolves, minting anew). [httpClient] is the app's shared
 * client (`AppContainer.httpClient`).
 */
@OptIn(UnstableApi::class)
class SabrBridge(
    private val bandwidthEstimator: BandwidthMeter,
    private val httpClient: OkHttpClient,
) {

    /**
     * Build the SABR [MediaSource] for [playable]. [mediaItem] must carry a `LocalConfiguration`
     * (e.g. built from a URI); the source keeps the item but fetches through the SABR client,
     * never through the item's URI.
     */
    fun createMediaSource(playable: SabrPlayable, mediaItem: MediaItem): MediaSource =
        SabrMediaSource
            .Factory(
                manifest = playable.manifest,
                webPoTokenCache = { playable.session.poToken },
                bandwidthEstimator = bandwidthEstimator,
                httpClient = httpClient,
                identity = playable.session.identity,
            )
            .createMediaSource(mediaItem)
}
