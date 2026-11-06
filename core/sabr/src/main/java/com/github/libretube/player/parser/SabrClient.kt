// MODIFIED (YouTube KE, 2026-09-27): severed LibreTube app couplings — PoTokenGenerator replaced
// by an injected web-po-token cache lambda; DefaultBandwidthMeter/LibreTubeApp singleton replaced
// by an injected BandwidthMeter; DisplayHelper HDR probe replaced by an injected flag; the
// ShareDialog URL constant is local now; protocol signals are typed [SabrFailure]s (PL-6) and
// the non-2xx fetch path closes its response.
// MODIFIED (YouTube KE, 2026-09-29): the "requested segment for finished format" guard and
// the UMP left-over-data guard throw typed SabrFailures instead of `assert` — an Error on
// ExoPlayer's loader thread kills the process (Loader catches Exception, not Error; the
// finished-format case was hit on-device at M4.10). The reload comment no longer claims a
// purposeful crash. See THIRD-PARTY.md entry 2.
// MODIFIED (YouTube KE, 2026-10-02): one Mutex (was a 1-thread dispatcher) guards all client
// state, including the reads getNextSegment made before entering it, and is held across the
// fetch; fields touched outside it are @Volatile; the unused, unsynchronized
// getEndSegmentNumber is removed. The fetch is cancellable (enqueue + cancel on coroutine
// cancellation) so a seek no longer waits for the whole response. The finished-format guard
// compares the requested segment's start instead of the playhead. The OkHttpClient is
// injected (one app-wide connection pool), and a failed re-mint keeps the current po-token.
// MODIFIED (YouTube KE, 2026-10-09): each response body read is one network transfer on the
// bandwidth meter (SabrDataSource waits on the fetch here but transfers nothing itself).
// MODIFIED (YouTube KE, 2026-10-23): the client identity (ClientInfo + User-Agent) is injected as
// a [SabrClientIdentity] (was hard-coded VISIONOS, still the default). See THIRD-PARTY.md entry 2.
package com.github.libretube.player.parser

import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrManifest
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import misc.Common.FormatId
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import video_streaming.BufferedRangeOuterClass.BufferedRange
import video_streaming.ClientAbrStateOuterClass.ClientAbrState
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaCapabilitiesOuterClass.MediaCapabilities
import video_streaming.MediaCapabilitiesOuterClass.MediaCapabilities.VideoFormatCapability
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy
import video_streaming.PlaybackCookieOuterClass.PlaybackCookie
import video_streaming.SabrContextSendingPolicyOuterClass.SabrContextSendingPolicy
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate
import video_streaming.SabrContextUpdateOuterClass.SabrContextUpdate.SabrContextWritePolicy
import video_streaming.SabrErrorOuterClass.SabrError
import video_streaming.SabrRedirectOuterClass.SabrRedirect
import video_streaming.StreamProtectionStatusOuterClass.StreamProtectionStatus
import video_streaming.StreamerContextOuterClass.StreamerContext
import video_streaming.StreamerContextOuterClass.StreamerContext.SabrContext
import video_streaming.UmpPartId.UMPPartId
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

// ADDED (YouTube KE, 2026-09-27): was ShareDialog.YOUTUBE_FRONTEND_URL (a UI class, not vendored).
private const val YOUTUBE_FRONTEND_URL = "https://www.youtube.com"

class PlaybackRequest(
    /* Format for which new media data is being requested */
    val format: FormatId,
    /* Position of the player in milliseconds */
    val playerPosition: Long,
    /* Multiplier applied to the speed at which content is played. */
    val playbackSpeed: Float,
    /* Sequence number of which segment is loaded */
    val segment: Long,
    /* List of segments which are buffered for the format */
    val segmentStartTimeMs: Long,
    /* List of segments which are buffered for the format */
    val bufferedSegments: List<Long>,
) {
    companion object {
        fun initRequest(
            format: FormatId, playerPosition: Long, playbackSpeed: Float,
        ): PlaybackRequest = PlaybackRequest(
            format, playerPosition, playbackSpeed, 0, 0, emptyList()
        )
    }
}

/**
 * A segment of a media stream.
 *
 * Contains metadata, such as the position in the stream, its own duration, as well the raw
 * media data.
 */
data class Segment(
    /** Header of the media segment containing metadata. */
    val header: MediaHeader,
    /** Sequence number indicating the position of the segment in the media stream. */
    val sequenceNumber: Long,
    /** Raw media data for the segment. */
    val data: MutableList<ByteArray>,
    /** Duration of the segment in milliseconds. */
    val duration: Long,
) {
    /**
     * Length of the media data.
     */
    fun length(): Int = data.sumOf { it.size }
}

/**
 * An initialized format within a video stream.
 */
private data class InitializedFormat(
    /** Identifier of the format. */
    val id: FormatId,
    /** Segments that have been downloaded for this format. */
    val downloadedSegments: MutableMap<Long, Segment> = mutableMapOf(),
    /** Segments that have been downloaded for this format. */
    val bufferedSegments: MutableMap<Long, Segment> = mutableMapOf(),
    /** Sequence number of the last segment in the format. */
    val endSegmentNumber: Long,
    /** Initial segment containing metadata about the stream,
     *  such as the position of the other segments.
     **/
    var initSegment: Segment? = null,
    /** Duration of the format in milliseconds. */
    val duration: Long,
) {
    /** Returns a list of all downloaded segments for the format. */
    fun getSegment(sequenceNumber: Long): Segment? {
        val segment = downloadedSegments.remove(sequenceNumber)
            ?: initSegment?.takeIf { it.sequenceNumber == sequenceNumber }
            ?: return null
        // mark retrieved segment as buffered
        bufferedSegments[sequenceNumber] = segment.copy(data = mutableListOf())
        return segment
    }

    /** Returns a list of all downloaded segments for the format. */
    fun buildBufferedRanges(): List<BufferedRange> =
        bufferedSegments.entries.union(downloadedSegments.entries).sortedBy { it.key }
        .fold(mutableListOf<MutableList<Pair<Long, Segment>>>()) { acc, (id, segment) ->
            val previousId = acc.lastOrNull()?.lastOrNull()?.first
            if (previousId?.plus(1) != id) {
                //we found a discontinuity, create a new partition
                acc.add(mutableListOf())
            }
            acc.lastOrNull()!!.add(Pair(id, segment))
            acc
        }.map { partition ->
            val duration = partition.sumOf { it.second.duration }
            val (firstId, firstSegment) = partition.first()
            BufferedRange.newBuilder().setFormatId(id).setStartTimeMs(firstSegment.header.startMs)
                .setDurationMs(duration).setStartSegmentIndex(firstId.toInt())
                .setEndSegmentIndex(partition.last().first.toInt()).build()
        }

    /**
     * Whether the format has non-retrieved data.
     */
    fun hasSegment(segmentNumber: Long): Boolean =
        downloadedSegments.containsKey(segmentNumber) || initSegment?.sequenceNumber == segmentNumber
}

/**
 * ADDED (YouTube KE, 2026-10-23): the client a SABR session speaks as, sent as the request's
 * `ClientInfo` and `User-Agent`. It must be the client whose player response gave the session's
 * URL and ustreamer config.
 */
data class SabrClientIdentity(
    val clientName: Int,
    val clientVersion: String,
    val deviceMake: String,
    val deviceModel: String,
    val osName: String,
    val osVersion: String,
    val userAgent: String,
) {
    companion object {
        /** The upstream identity (LibreTube's VISIONOS player client). */
        val VISIONOS = SabrClientIdentity(
            clientName = 101,
            clientVersion = "1.02",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "25.6.0.23O471",
            userAgent = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)",
        )
    }
}

/**
 * A SABR/UMP streaming client.
 *
 * Handles the fetching and processing of streaming media data using the UMP protocol.
 */
@OptIn(UnstableApi::class)
class SabrClient private constructor(
    /** Unique identifier for the SABR stream resource. */
    private val videoId: String,
    /** The URL pointing to the SABR/UMP stream. */
    @Volatile var url: String,
    /** UStreamer configuration data. */
    private val ustreamerConfig: ByteString,
    /** MODIFIED: cached streaming po-token bytes (was LibreTube's PoTokenGenerator). */
    private val webPoTokenCache: () -> ByteArray?,
    /** MODIFIED: mint a fresh streaming po-token for a video id (attestation recovery included). */
    private val webPoTokenMint: (String) -> ByteArray?,
    /** MODIFIED: injected bandwidth meter (was the DefaultBandwidthMeter app singleton). */
    private val bandwidthEstimator: BandwidthMeter,
    /** MODIFIED: HDR capability flag (was DisplayHelper.supportsHdr on the app context). */
    private val hdrSupported: Boolean,
    /** MODIFIED: the app's shared client; the SABR headers are added on a derived client. */
    httpClient: OkHttpClient,
    /** MODIFIED: was hard-coded VISIONOS. */
    private val identity: SabrClientIdentity,
) {

    /** Po (Proof of Origin) Token. */
    private var poToken: ByteString? = null

    private var fatalError: SabrError? = null

    /**
     * MODIFIED: guards all client state. Held across the fetch, so loaders for other formats
     * wait instead of interleaving requests. Fields touched outside it are @Volatile: the
     * playback thread's writes, and [url], which SabrDataSource.getUri reads.
     */
    private val stateLock = Mutex()

    /** Audio format video format */
    @Volatile private var audioFormat: Representation? = null
    /** Optional video format */
    @Volatile private var videoFormat: Representation? = null

    constructor(
        manifest: SabrManifest,
        webPoTokenCache: () -> ByteArray?,
        webPoTokenMint: (String) -> ByteArray?,
        bandwidthEstimator: BandwidthMeter,
        httpClient: OkHttpClient,
        hdrSupported: Boolean = false,
        identity: SabrClientIdentity = SabrClientIdentity.VISIONOS,
    ) : this(
        manifest.videoId,
        manifest.serverAbrStreamingUri.toString(),
        ByteString.copyFrom(manifest.videoPlaybackUstreamerConfig),
        webPoTokenCache,
        webPoTokenMint,
        bandwidthEstimator,
        hdrSupported,
        httpClient,
        identity,
    )

    init {
        webPoTokenCache()?.let {
            poToken = ByteString.copyFrom(it)
        }
    }

    /**
     * Initialized formats.
     *
     * A format is initialized when the stream sends a [UMPPartId.FORMAT_INITIALIZATION_METADATA] part,
     * containing the metadata of the format.
     *
     * Each format is identified by its `itag`.
     */
    private val initializedFormats = mutableMapOf<Int, InitializedFormat>()

    /**
     * Partial segments that are being processed.
     *
     * Segments are stored here temporarily until they are fully processed.
     */
    private val partialSegments = mutableMapOf<Int, Segment>()

    /** HTTP Client for requesting UMP data. */
    private val client: OkHttpClient = httpClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Content-Type", CONTENT_TYPE)
                .addHeader("Accept-Encoding", ENCODING)
                .addHeader("Accept", ACCEPT)
                .addHeader("Origin", YOUTUBE_FRONTEND_URL)
                .addHeader("Referer", "$YOUTUBE_FRONTEND_URL/")
                .addHeader("User-Agent", identity.userAgent)
                .build()
            chain.proceed(request)
        }
        .build()

    /** Sequence number of the request. */
    private var requestNumber = 1

    /**
     * PlaybackCookie
     *
     * This cookie needs to be passed to subsequent requests.
     */
    private var playbackCookie: PlaybackCookie? = null

    /**
     * Back off time until the server accepts the next request in milliseconds.
     *
     * When set, the client should wait the specified amount of time before making another
     * request. The server will not send any further data during this period.
     */
    private var backoffTime: Int? = null

    /** SABR contexts for the stream. */
    private val sabrContexts = mutableMapOf<Int, SabrContext>()

    /** Active SABR contexts that should be sent with requests. */
    private val activeSabrContexts = mutableSetOf<Int>()

    /** Timestamp of the last seek */
    @Volatile var lastSeekMs: Long? = null

    /** Timestamp when the last request was made  */
    private var lastRequestMs: Long? = null

    /**
     * Timestamp when the user/player last selected a format.
     *
     * For us, all format selections are manual, as we do not let the server decide the format.
     **/
    @Volatile var lastManualFormatSelectionMs: Long? = null

    /**
     * Timestamp when the user last made an action.
     *
     * This is likely the same as [lastManualFormatSelectionMs] for us,
     * as we handle no other actions.
     **/
    @Volatile var lastActionMs: Long? = null



    @OptIn(UnstableApi::class)
    fun selectFormat(representation: Representation) {
        if (videoFormat == representation || audioFormat == representation) {
            return
        }

        if (MimeTypes.isAudio(representation.format.containerMimeType)) {
            audioFormat = representation
        } else if (MimeTypes.isVideo(representation.format.containerMimeType)) {
            videoFormat = representation
        }
    }

    /**
     * Returns the segment specified in [playbackRequest].
     * Segments are usually of a length between 2 and 10 seconds.
     *
     * Blocks the calling (loader) thread; interrupting it cancels an in-flight fetch. The work
     * runs on IO, so that interrupt never reaches blocking calls such as the po-token re-mint.
     */
    fun getNextSegment(playbackRequest: PlaybackRequest): Segment? = runBlocking(Dispatchers.IO) {
        stateLock.withLock {
            if (fatalError != null) {
                throw SabrFailure.ProtocolError(fatalError!!.type)
            }
            val itag = playbackRequest.format.itag

            // The playhead may sit at or past the format's end after a seek or a resume near the
            // end while the last segment is still to load, so only the requested segment's start
            // is checked. Past the end is a typed failure, never an Error on the loader thread.
            if (playbackRequest.segmentStartTimeMs >= (initializedFormats[itag]?.duration ?: Long.MAX_VALUE)) {
                throw SabrFailure.ProtocolError("requested segment past format end")
            }

            Log.d(
                TAG,
                "getNextSegment: loading media data for $itag at position ${playbackRequest.playerPosition}"
            )

            // synchronize buffered segments with the actually buffered segments from the player
            initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(playbackRequest.bufferedSegments)

            var format = initializedFormats[itag]
            if (format == null || !format.hasSegment(playbackRequest.segment)) {
                // remove segments that where downloaded, but never requested by the player
                // (e.g. due to seeking), to avoid keeping them around forever and thus leaking memory
                format?.downloadedSegments?.clear()

                // fetch new data
                media(playbackRequest)

                // clear previous formats to prevent advertising stale data to the server/buffering them
                // we only dio this after requesting new data, to avoid accidentally clearing to the currently selected format,
                // e.g. killing the current buffer
                initializedFormats.keys.retainAll { audioFormat?.stream?.itag == it || videoFormat?.stream?.itag == it }
            }
            format = format ?: initializedFormats[itag]
            format?.getSegment(playbackRequest.segment)
        }
    }


    /**
     * Extracts the raw media data from the stream.
     *
     * The data is returned as a pair of lists: (audio segments, video segments).
     */
    private suspend fun media(playbackRequest: PlaybackRequest) {
        // update currently held UMP data
        val data = fetchStreamData(playbackRequest, audioFormat, videoFormat)

        val parser = UmpParser(data)
        while (true) {
            val part = parser.readPart() ?: break
            processPart(part)
        }
        // Left-over bytes mean the response framing changed — a protocol failure, never an
        // Error on the loader thread (same rule as the past-the-end guard in getNextSegment).
        if (parser.data().isNotEmpty()) {
            throw SabrFailure.ProtocolError("parser left-over data")
        }
    }

    /**
     * Fetches streaming data from the URL.
     */
    private suspend fun fetchStreamData(
        playbackRequest: PlaybackRequest,
        audioFormat: Representation?,
        videoFormat: Representation?,
    ): ByteArray {
        backoffTime?.let { backoff ->
            Log.i(TAG, "fetchStreamData: Waiting for ${backoff}ms before making a request")
            delay(backoff.toLong())
            backoffTime = null
        }

        val now = Instant.now().toEpochMilli()
        val xtags = audioFormat?.formatId()?.xtags?.let { Xtags(it) }

        val playerTimeMs = playbackRequest.segmentStartTimeMs
        val clientState = ClientAbrState.newBuilder()
            .setPlayerTimeMs(playerTimeMs)
            // only set for mobile clients
            .setMediaCapabilities(
                MediaCapabilities.newBuilder()
                    .setHdrModeBitmask(if (hdrSupported) 3 else 0)
                    .addAllVideoFormatCapabilities(
                        VideoFormatCapability.VideoCodec.entries.map {
                            VideoFormatCapability.newBuilder()
                                .setVideoCodec(it.ordinal)
                                .setIs10BitSupported(true)
                                .setEfficient(true)
                                .build()
                        }
                    )
                    .build()
            )
            .setEnabledTrackTypesBitfield(if (videoFormat == null) 1 else 0)
            .setPlaybackRate(playbackRequest.playbackSpeed)
            .setElapsedWallTimeMs(lastRequestMs?.let { now -  it } ?: 0 )
            .setTimeSinceLastSeek(lastSeekMs?.let { now - it } ?: 0)
            .setTimeSinceLastManualFormatSelectionMs(lastManualFormatSelectionMs?.let { now - it } ?: 0)
            .setTimeSinceLastActionMs(lastActionMs?.let { now - it } ?: 0)
            .setAudioTrackId(audioFormat?.stream?.audioTrackId.orEmpty())
            .setDrcEnabled(audioFormat?.stream?.isDrc ?: false || xtags?.isDrcAudio() ?: false)
            .setEnableVoiceBoost(xtags?.isVoiceBoosted() ?: false)
            .setClientViewportIsFlexible(false)
            .setBandwidthEstimate(bandwidthEstimator.bitrateEstimate)
            .setStickyResolution(max(videoFormat?.stream?.height ?: 0, 360))
            .setClientViewportHeight(max(videoFormat?.stream?.height ?: 0, 360))
            .setClientViewportWidth(max(videoFormat?.stream?.width ?: 0, 640))
            .setLastManualSelectedResolution(max(videoFormat?.stream?.height ?: 0, 360))
            .setVisibility(1)
            .build()

        val playbackRequest = VideoPlaybackAbrRequest.newBuilder().setClientAbrState(clientState)
            .setPlayerTimeMs(playerTimeMs)
            .setVideoPlaybackUstreamerConfig(ustreamerConfig)
            .addAllPreferredAudioFormatIds(listOfNotNull(audioFormat?.formatId()))
            .addAllPreferredVideoFormatIds(listOfNotNull(videoFormat?.formatId()))
            .addAllSelectedFormatIds(initializedFormats.values.map { it.id }.toList())
            .addAllBufferedRanges(initializedFormats.values.flatMap { it.buildBufferedRanges() })
            .setStreamerContext(
                StreamerContext.newBuilder()
                    .setPoToken(poToken ?: ByteString.empty())
                    .setClientInfo(
                        StreamerContext.ClientInfo.newBuilder()
                            .setClientName(identity.clientName)
                            .setClientVersion(identity.clientVersion)
                            .setDeviceMake(identity.deviceMake)
                            .setDeviceModel(identity.deviceModel)
                            .setOsName(identity.osName)
                            .setOsVersion(identity.osVersion)
                            .build()
                    )
                    .addAllSabrContexts(activeSabrContexts.mapNotNull { sabrContexts[it] })
                    .addAllUnsentSabrContexts(
                        sabrContexts.keys.filter { it !in activeSabrContexts })
                    .setPlaybackCookie(playbackCookie?.toByteString() ?: ByteString.empty())
                    .build()
            )
            .build()

        // ideally we would use HTTP3 here, like the official, however okhttp does not support it
        val request = Request.Builder()
            .url("$url&rn=${requestNumber++}")
            .post(
                playbackRequest.toByteArray()
                    .toRequestBody(CONTENT_TYPE.toMediaType())
            )
            .build()

        lastRequestMs = Instant.now().toEpochMilli()
        return client.newCall(request).awaitBody()
    }

    /**
     * MODIFIED: replaces a blocking execute(), which ignored cancellation. The body is read in
     * the callback, so cancelling the coroutine also aborts a body download in progress.
     */
    private suspend fun Call.awaitBody(): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.e(TAG, "fetchStreamData: Failed to fetch data (${it.code})")
                        continuation.resumeWithException(Exception("HTTP request failed: ${it.code}"))
                        return
                    }
                    // Throwable, not IOException: anything escaping this callback (an OOM on a
                    // large response) would kill the process on OkHttp's thread and never resume
                    // the loader; resumed, it reaches Media3's Loader, which handles it.
                    val body = try {
                        meteredBody(it)
                    } catch (e: Throwable) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(body)
                }
            }
        })
    }

    /**
     * MODIFIED: reads the body as one network transfer on the bandwidth meter, from the response
     * headers to the last byte (as Media3's HTTP data sources time it), so the estimate sees the
     * whole response's bytes and download time, not the lock, backoff, or request wait. Bytes are
     * reported per read, so a body cut short by a seek still counts what arrived.
     */
    private fun meteredBody(response: Response): ByteArray {
        val listener = bandwidthEstimator.transferListener ?: return response.body.bytes()
        val source = PlaceholderDataSource.INSTANCE
        val dataSpec = DataSpec(response.request.url.toString().toUri())
        val body = response.body.source()
        val buffer = Buffer()
        listener.onTransferStart(source, dataSpec, true)
        try {
            while (true) {
                val read = body.read(buffer, READ_SIZE)
                if (read == -1L) break
                listener.onBytesTransferred(source, dataSpec, true, read.toInt())
            }
        } finally {
            listener.onTransferEnd(source, dataSpec, true)
        }
        return buffer.readByteArray()
    }

    /**
     * Parse a UMP Part, handling its contents as appropriate.
     *
     * @throws Exception if parsing fails or the part is invalid
     */
    private fun processPart(part: UmpParser.Part) {
        when (part.type) {
            UMPPartId.MEDIA_HEADER -> {
                val header = MediaHeader.parseFrom(part.data)
                val videoId = header.videoId
                val headerId = header.headerId
                val sequenceNumber = header.sequenceNumber
                val duration = if (header.hasDurationMs()) header.durationMs else {
                    ((header.timeRange.durationTicks.toDouble() / header.timeRange.timescale.toDouble()) * 1000).toLong()
                }

                if (videoId != this.videoId) {
                    Log.e(TAG, "processPart: Received unexpected media header for $videoId")
                    throw Exception("Header mismatch")
                }

                val format = initializedFormats[header.formatId.itag]!!

                if (format.downloadedSegments.containsKey(sequenceNumber)) {
                    Log.w(TAG, "processPart: Segment $sequenceNumber is already downloaded. Ignoring.")
                    return
                }

                Log.v(TAG, "processPart: Enqueuing partial segment $headerId")
                partialSegments[headerId] = Segment(
                    header = header,
                    sequenceNumber = sequenceNumber,
                    data = mutableListOf(),
                    duration = duration
                )
            }

            UMPPartId.MEDIA -> {
                val parser = UmpParser(part.data)
                val headerId = parser.readVarint()?.toInt()!!

                // repeated segment are skipped, when their header is found and their not added
                // to the partial segment queue
                val segment = partialSegments[headerId] ?: return
                //TODO: decompress gzipped data (not send for the WEB client)
                segment.data.add(parser.data())
            }

            UMPPartId.MEDIA_END -> {
                val parser = UmpParser(part.data)
                val headerId = parser.readVarint()?.toInt()!!
                val segment = partialSegments.remove(headerId) ?: return
                Log.v(TAG, "processPart: Dequeuing partial segment $headerId")

                val segmentLength = segment.length()
                if (segmentLength != segment.header.contentLength.toInt()) {
                    Log.w(
                        TAG,
                        "processPart: Content length mismatch for segment $headerId: expected ${segment.header.contentLength}, got $segmentLength"
                    )
                    throw Exception("Content length mismatch")
                }

                val format = initializedFormats[segment.header.itag]!!
                format.downloadedSegments[segment.sequenceNumber] = segment

                if (segment.header.isInitSeg) {
                    format.initSegment = segment
                }
            }

            UMPPartId.NEXT_REQUEST_POLICY -> {
                val policy = NextRequestPolicy.parseFrom(part.data)
                backoffTime = policy.backoffTimeMs
                playbackCookie = policy.playbackCookie
            }

            UMPPartId.FORMAT_INITIALIZATION_METADATA -> {
                val metadata = FormatInitializationMetadata.parseFrom(part.data)

                val duration = metadata.endTimeMs
                val endSegmentNumber = metadata.endSegmentNumber
                val formatId = metadata.formatId
                val itag = formatId.itag

                if (initializedFormats.containsKey(itag)) {
                    Log.w(TAG, "processPart: Skipping already initialized format `$itag`")
                    return
                }

                val format = InitializedFormat(
                    id = formatId,
                    endSegmentNumber = endSegmentNumber,
                    duration = duration
                )
                initializedFormats[itag] = format
            }

            UMPPartId.SABR_REDIRECT -> {
                val redirect = SabrRedirect.parseFrom(part.data)
                url = redirect.url
            }

            UMPPartId.SABR_CONTEXT_UPDATE -> {
                val contextUpdate = SabrContextUpdate.parseFrom(part.data)

                if (contextUpdate.writePolicy == SabrContextWritePolicy.KEEP_EXISTING &&
                    sabrContexts.containsKey(contextUpdate.type)) {
                    return
                }

                if (contextUpdate.sendByDefault) {
                    activeSabrContexts.add(contextUpdate.type)
                }

                sabrContexts[contextUpdate.type] =
                    SabrContext.newBuilder().setType(contextUpdate.type)
                        .setValue(contextUpdate.value).build()
            }

            UMPPartId.SABR_CONTEXT_SENDING_POLICY -> {
                val policy = SabrContextSendingPolicy.parseFrom(part.data)

                policy.startPolicyList.forEach { startPolicy ->
                    if (!activeSabrContexts.contains(startPolicy)) {
                        Log.v(TAG, "processPart: Server requested to enable SABR Context Update ($startPolicy)")
                        activeSabrContexts.add(startPolicy)
                    }
                }

                policy.stopPolicyList.forEach { stopPolicy ->
                    if (activeSabrContexts.contains(stopPolicy)) {
                        Log.v(TAG, "processPart: Server requested to disable SABR Context Update ($stopPolicy)")
                        activeSabrContexts.remove(stopPolicy)
                    }
                }

                policy.discardPolicyList.forEach { discardPolicy ->
                    if (activeSabrContexts.contains(discardPolicy)) {
                        Log.v(TAG, "processPart: Server requested to discard SABR Context Update ($discardPolicy)")
                        sabrContexts.remove(discardPolicy)
                    }
                }
            }

            UMPPartId.RELOAD_PLAYER_RESPONSE -> {
                // streams expired or a new server configuration: surface as a typed failure —
                // the session layer refreshes the session and retries (M3.6 classification).
                throw SabrFailure.PlayerReloadRequested()
            }

            UMPPartId.STREAM_PROTECTION_STATUS -> {
                val status = StreamProtectionStatus.parseFrom(part.data)
                // https://github.com/coletdjnz/yt-dlp-dev/blob/5c0c2963396009a92101bc6e038b61844368409d/yt_dlp/extractor/youtube/_streaming/sabr/part.py
                when (status.status) {
                    1 -> Log.i(TAG, "processPart: [StreamProtectionStatus] OK")
                    2 -> {
                        Log.i(TAG, "processPart: [StreamProtectionStatus] Attestation pending.")
                        // try to regenerate the poToken for the next request
                        // MODIFIED: a failed mint (null) keeps the current token.
                        poToken = generatePoToken() ?: poToken
                    }
                    // we assume that we got an attestation pending warning before and already tried to regenerate the token,
                    // but it's not accepted, so we bail
                    3 -> throw SabrFailure.AttestationRequired()
                    else -> Log.e(TAG, "processPart: Unknown StreamProtectionStatus (${status.status})")
                }
            }

            UMPPartId.SABR_ERROR -> {
                val error = SabrError.parseFrom(part.data)
                Log.e(TAG, "processPart: Received SABR error: ${error.type} (${error.code})")
                fatalError = error
                throw SabrFailure.ProtocolError(error.type)
            }

            else -> {
                Log.w(TAG, "processPart: Unhandled UMP part ${part.type}")
            }
        }
    }

    /**
     * Returns a fresh poToken.
     *
     * MODIFIED: was poTokenGenerator.getWebClientPoToken(videoId) (LibreTube's WebView minting).
     * Minting lives behind the injected webPoTokenMint seam, which must mint with recovery — the
     * STREAM_PROTECTION_STATUS attestation path calls this to regenerate a token the server
     * rejected (M3.4 wires the WebView minter).
     *
     * NOTE: This should use the same client used for the requests made for the server
     */
    fun generatePoToken() : ByteString? {
        val poTokenBytes = webPoTokenMint(videoId) ?: return null
        return ByteString.copyFrom(poTokenBytes)
    }

    companion object {
        private const val TAG = "SabrStream"
        private const val READ_SIZE = 64L * 1024
        private const val CONTENT_TYPE = "application/x-protobuf"
        private const val ENCODING = "identity"
        private const val ACCEPT = "application/vnd.yt-ump"
    }
}