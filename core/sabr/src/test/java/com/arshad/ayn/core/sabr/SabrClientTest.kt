package com.arshad.ayn.core.sabr

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.github.libretube.player.SabrDataSource
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrFormat
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import com.github.libretube.player.parser.SabrClientIdentity
import com.github.libretube.player.parser.SabrFailure
import com.github.libretube.player.parser.Segment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import video_streaming.FormatInitializationMetadataOuterClass.FormatInitializationMetadata
import video_streaming.MediaHeaderOuterClass.MediaHeader
import video_streaming.StreamProtectionStatusOuterClass.StreamProtectionStatus
import video_streaming.UmpPartId.UMPPartId
import video_streaming.VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest

/**
 * The vendored SABR client against canned UMP responses (no network): an OkHttp interceptor in
 * the injected client plays the server. Robolectric supplies a real `Uri` for [SabrManifest].
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SabrClientTest {

    private val audio = Representation(
        SabrFormat(
            itag = 251, lastModified = 1_700_000_000L, xtags = null, mimeType = "audio/webm",
            codec = "opus", bitrate = 128_000, fps = null, width = null, height = null,
            audioTrackId = null, audioTrackLocale = null, audioTrackType = null,
            durationMs = FORMAT_END_MS, isDrc = null,
        )
    )
    private val formatId = audio.formatId()

    @Test
    fun lastSegmentLoadsWhileThePlayheadIsAtTheFormatEnd() {
        val server = FakeUmpServer { request, _ ->
            if (request == 1) initResponse() else ump(segment(headerId = 1, sequence = 3, startMs = 7_000))
        }
        val client = client(server)
        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        // A seek or resume near the end: the playhead is already at the format end, the last
        // segment is still to load.
        val last = client.getNextSegment(request(segment = 3, startMs = 7_000, playerPositionMs = FORMAT_END_MS))

        assertEquals(3L, last?.sequenceNumber)
    }

    @Test
    fun segmentStartingAtTheFormatEndIsATypedFailureWithoutAFetch() {
        val server = FakeUmpServer { _, _ -> initResponse() }
        val client = client(server)
        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        assertThrows(SabrFailure.ProtocolError::class.java) {
            client.getNextSegment(request(segment = 4, startMs = FORMAT_END_MS, playerPositionMs = 9_000))
        }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun interruptingTheLoaderCancelsTheInFlightFetchAndFreesTheClient() {
        val sawCancel = CountDownLatch(1)
        val server = FakeUmpServer { request, chain ->
            if (request > 1) return@FakeUmpServer initResponse()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) sawCancel.countDown()
            throw IOException("canceled")
        }
        val client = client(server)
        var failure: Throwable? = null
        val loader = Thread {
            try {
                client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))
            } catch (e: Throwable) {
                failure = e
            }
        }
        loader.start()
        server.awaitFirstRequest()

        loader.interrupt() // what Media3's Loader does to the loading thread on cancelLoad

        loader.join(2_000)
        assertFalse("the loader must return without waiting for the response", loader.isAlive)
        assertTrue("runBlocking reports the interrupt", failure is InterruptedException)
        assertTrue("the HTTP call must be cancelled", sawCancel.await(2, TimeUnit.SECONDS))
        // The next load (the seek's) gets the lock back and is served.
        assertEquals(0L, bounded { client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }.getOrThrow()?.sequenceNumber)
    }

    @Test
    fun fetchesStaySerializedWhileOneIsInFlight() {
        val release = CountDownLatch(1)
        val server = FakeUmpServer { _, _ ->
            release.await(10, TimeUnit.SECONDS)
            initResponse()
        }
        val client = client(server)
        val results = CopyOnWriteArrayList<Segment?>()
        val loaders = List(2) {
            Thread { results.add(client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))) }
        }
        loaders[0].start()
        server.awaitFirstRequest()
        loaders[1].start()

        Thread.sleep(300) // the second loader must wait for the lock, not start its own request
        assertEquals(1, server.requests.size)

        release.countDown()
        loaders.forEach { it.join(5_000) }
        assertEquals("the second loader reuses the first response", 1, server.requests.size)
        assertEquals(listOf(0L, 0L), results.map { it?.sequenceNumber })
    }

    @Test
    fun thePastEndGuardReadsFormatStateUnderTheLock() {
        val release = CountDownLatch(1)
        val server = FakeUmpServer { _, _ ->
            release.await(10, TimeUnit.SECONDS)
            initResponse()
        }
        val client = client(server)
        val initLoader = Thread { client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }
        initLoader.start()
        server.awaitFirstRequest()

        // Requested while the format is still being initialized: the guard must see the format
        // the in-flight response initializes, not the empty state before it.
        var pastEndResult: Result<Segment?>? = null
        val pastEnd = Thread {
            pastEndResult = runCatching {
                client.getNextSegment(request(segment = 4, startMs = FORMAT_END_MS, playerPositionMs = 9_000))
            }
        }
        pastEnd.start()
        Thread.sleep(300)
        release.countDown()

        initLoader.join(5_000)
        pastEnd.join(5_000)
        assertFalse(pastEnd.isAlive)
        val failure = pastEndResult?.exceptionOrNull()
        assertTrue("$failure", failure is SabrFailure.ProtocolError)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun httpAndTransportFailuresReachTheLoader() {
        val transfers = RecordingTransfers()
        val meter = FakeBandwidthMeter(transfers)
        val forbidden = client(FakeUmpServer(code = 403) { _, _ -> ByteArray(0) }, meter = meter)
        val error = bounded { forbidden.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }
            .exceptionOrNull()
        assertTrue("$error", error?.message?.contains("403") == true)

        val unreachable = client(FakeUmpServer { _, _ -> throw IOException("connection reset") }, meter = meter)
        val transport = bounded { unreachable.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }
            .exceptionOrNull()
        assertTrue("$transport", transport is IOException)
        assertEquals("no body read, no transfer", emptyList<String>(), transfers.network())
    }

    @Test
    fun aBodyCutShortEndsItsTransferWithTheBytesThatArrived() {
        val arrived = initResponse()
        val transfers = RecordingTransfers()
        val server = FakeUmpServer(body = ::failingAfter) { _, _ -> arrived }
        val client = client(server, meter = FakeBandwidthMeter(transfers))

        val error = bounded { client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) }
            .exceptionOrNull()

        assertTrue("$error", error is IOException)
        // Balanced (the meter's checkState) and the partial bytes count, so a seek during the
        // first response can't leave a zero-byte first sample.
        assertEquals(listOf("start", "bytes ${arrived.size}", "end"), transfers.network())
    }

    @Test
    fun aResponseWithoutTheRequestedSegmentFailsTheDataSourceWithAnIoCause() {
        val server = FakeUmpServer { request, _ ->
            if (request == 1) initResponse() else ump(segment(headerId = 1, sequence = 2, startMs = 3_000))
        }
        val client = client(server)
        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"))
            .setCustomData(request(segment = 1, startMs = 0, playerPositionMs = 0))
            .build()

        val error = assertThrows(IOException::class.java) { SabrDataSource(client).open(dataSpec) }

        // The media response arrived (no transport failure) without segment 1, and the data
        // source caught an IOException: the session layer reads a RuntimeException there as a bug.
        assertEquals(2, server.requests.size)
        val caught = error.cause
        assertTrue("$caught", caught is IOException)
    }

    @Test
    fun theMeterTimesEachResponseBodyNotTheSegmentWait() {
        val response = initResponse()
        val respondedAt = AtomicLong()
        val server = FakeUmpServer { _, _ ->
            Thread.sleep(200) // stands in for the request/server wait SabrDataSource.open used to time
            respondedAt.set(System.nanoTime())
            response
        }
        val transfers = RecordingTransfers()
        val client = client(server, meter = FakeBandwidthMeter(transfers))
        // Wired as DefaultSabrChunkSource wires the player's meter into each data source.
        val dataSource = SabrDataSource(client).apply { addTransferListener(transfers) }
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"))
            .setCustomData(PlaybackRequest.initRequest(formatId, 0, 1f))
            .build()

        dataSource.open(dataSpec)
        val buffer = ByteArray(64)
        while (dataSource.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        dataSource.close()

        // One network sample: the whole response's bytes, timed from its arrival, never the
        // segment's 2 bytes timed across the wait (which collapsed the estimate on a 4K start).
        assertEquals(listOf("start", "bytes ${response.size}", "end"), transfers.network())
        val start = transfers.events.first { it.isNetwork }
        assertTrue("timed from the response, not the open", start.atNanos >= respondedAt.get())
    }

    @Test
    fun failedReMintKeepsTheCurrentPoToken() {
        val token = byteArrayOf(7, 7, 7)
        val mints = AtomicInteger()
        val server = FakeUmpServer { request, _ ->
            if (request == 1) {
                initResponse() + part(UMPPartId.STREAM_PROTECTION_STATUS, StreamProtectionStatus.newBuilder().setStatus(2).build().toByteArray())
            } else {
                ump(segment(headerId = 1, sequence = 1, startMs = 0))
            }
        }
        val client = client(server, cachedToken = token, mint = { mints.incrementAndGet(); null })
        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        client.getNextSegment(request(segment = 1, startMs = 0, playerPositionMs = 0))

        assertEquals(1, mints.get())
        assertArrayEquals(token, server.requests[1].streamerContext.poToken.toByteArray())
    }

    @Test
    fun successfulReMintReplacesThePoToken() {
        val fresh = byteArrayOf(9, 9)
        val server = FakeUmpServer { request, _ ->
            if (request == 1) {
                initResponse() + part(UMPPartId.STREAM_PROTECTION_STATUS, StreamProtectionStatus.newBuilder().setStatus(2).build().toByteArray())
            } else {
                ump(segment(headerId = 1, sequence = 1, startMs = 0))
            }
        }
        val client = client(server, cachedToken = byteArrayOf(7, 7, 7), mint = { fresh })
        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        client.getNextSegment(request(segment = 1, startMs = 0, playerPositionMs = 0))

        assertArrayEquals(fresh, server.requests[1].streamerContext.poToken.toByteArray())
    }

    @Test
    fun interruptingTheLoaderNeverInterruptsABlockingReMint() {
        val mintEntered = CountDownLatch(1)
        var mintInterrupted = false
        val server = FakeUmpServer { _, _ ->
            initResponse() + part(UMPPartId.STREAM_PROTECTION_STATUS, StreamProtectionStatus.newBuilder().setStatus(2).build().toByteArray())
        }
        // The vendored minter treats an interrupt as a broken WebView and rebuilds it.
        val client = client(server, mint = {
            mintEntered.countDown()
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                mintInterrupted = true
            }
            null
        })
        val loader = Thread { runCatching { client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f)) } }
        loader.start()
        assertTrue(mintEntered.await(5, TimeUnit.SECONDS))

        loader.interrupt()

        loader.join(5_000)
        assertFalse(loader.isAlive)
        assertFalse("the re-mint must not see the loader's interrupt", mintInterrupted)
    }

    @Test
    fun theDefaultIdentityIsVisionOs() {
        val server = FakeUmpServer { _, _ -> initResponse() }
        val client = client(server)

        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        val info = server.requests.single().streamerContext.clientInfo
        assertEquals(
            listOf("101", "1.02", "Apple", "RealityDevice14,1", "visionOS", "25.6.0.23O471"),
            listOf(info.clientName.toString(), info.clientVersion, info.deviceMake, info.deviceModel, info.osName, info.osVersion),
        )
    }

    @Test
    fun anInjectedIdentityIsTheRequestsClientInfo() {
        val mweb = SabrClientIdentity(2, "2.20261022.04.00", "Apple", "iPad", "iPadOS", "16.7.10", "Mozilla/5.0 (iPad)")
        val server = FakeUmpServer { _, _ -> initResponse() }
        val client = client(server, identity = mweb)

        client.getNextSegment(PlaybackRequest.initRequest(formatId, 0, 1f))

        val info = server.requests.single().streamerContext.clientInfo
        assertEquals(
            listOf("2", "2.20261022.04.00", "Apple", "iPad", "iPadOS", "16.7.10"),
            listOf(info.clientName.toString(), info.clientVersion, info.deviceMake, info.deviceModel, info.osName, info.osVersion),
        )
    }

    /** Runs [block] on its own thread so a loader that is never resumed fails the test, not hangs it. */
    private fun <T> bounded(block: () -> T): Result<T> {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join(5_000)
        assertFalse("the call must return", thread.isAlive)
        return result!!
    }

    private fun client(
        server: FakeUmpServer,
        cachedToken: ByteArray? = null,
        mint: (String) -> ByteArray? = { null },
        meter: BandwidthMeter = FakeBandwidthMeter(),
        identity: SabrClientIdentity? = null, // null: the constructor's default, as SabrMediaSource uses it
    ): SabrClient {
        val manifest = SabrManifest(
            VIDEO_ID,
            Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback?id=1"),
            byteArrayOf(1, 2, 3),
            FORMAT_END_MS,
        )
        val http = OkHttpClient.Builder().addInterceptor(server).build()
        val client = if (identity == null) {
            SabrClient(manifest, { cachedToken }, mint, meter, http)
        } else {
            SabrClient(manifest, { cachedToken }, mint, meter, http, identity = identity)
        }
        return client.apply {
            selectFormat(audio)
        }
    }

    private fun request(segment: Long, startMs: Long, playerPositionMs: Long) =
        PlaybackRequest(formatId, playerPositionMs, 1f, segment, startMs, emptyList())

    /** Format metadata (3 segments, ends at [FORMAT_END_MS]) plus the init segment. */
    private fun initResponse(): ByteArray {
        val metadata = FormatInitializationMetadata.newBuilder()
            .setVideoId(VIDEO_ID)
            .setFormatId(formatId)
            .setEndTimeMs(FORMAT_END_MS)
            .setEndSegmentNumber(3)
            .build()
        return part(UMPPartId.FORMAT_INITIALIZATION_METADATA, metadata.toByteArray()) +
            segment(headerId = 0, sequence = 0, startMs = 0, isInit = true)
    }

    /** MEDIA_HEADER + MEDIA + MEDIA_END for one segment carrying [SEGMENT_BYTES]. */
    private fun segment(headerId: Int, sequence: Long, startMs: Long, isInit: Boolean = false): ByteArray {
        val header = MediaHeader.newBuilder()
            .setHeaderId(headerId)
            .setVideoId(VIDEO_ID)
            .setItag(formatId.itag)
            .setFormatId(formatId)
            .setIsInitSeg(isInit)
            .setSequenceNumber(sequence)
            .setStartMs(startMs)
            .setDurationMs(if (isInit) 0 else 3_000)
            .setContentLength(SEGMENT_BYTES.size.toLong())
            .build()
        return part(UMPPartId.MEDIA_HEADER, header.toByteArray()) +
            part(UMPPartId.MEDIA, varint(headerId) + SEGMENT_BYTES) +
            part(UMPPartId.MEDIA_END, varint(headerId))
    }

    private fun ump(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun part(type: UMPPartId, data: ByteArray): ByteArray =
        varint(type.number) + varint(data.size) + data

    /** UMP varint (UmpParser.readVarint inverse), 1 or 2 bytes: enough for these payloads. */
    private fun varint(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf(value.toByte())
        value < 0x4000 -> byteArrayOf((0x80 or (value and 0x3F)).toByte(), (value shr 6).toByte())
        else -> error("varint $value too large for the fake")
    }

    /** A body that delivers [bytes], then fails as a reset connection would. */
    private fun failingAfter(bytes: ByteArray): ResponseBody = object : Source {
        private var sent = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (sent) throw IOException("connection reset mid-body")
            sent = true
            sink.write(bytes)
            return bytes.size.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }.buffer().asResponseBody()

    /**
     * Plays the SABR server: [respond] gets the 1-based request number and the chain; [body]
     * wraps the bytes as the response body.
     */
    private class FakeUmpServer(
        private val code: Int = 200,
        private val body: (ByteArray) -> ResponseBody = { it.toResponseBody() },
        private val respond: (request: Int, chain: Interceptor.Chain) -> ByteArray,
    ) : Interceptor {
        val requests = CopyOnWriteArrayList<VideoPlaybackAbrRequest>()
        private val firstRequest = CountDownLatch(1)

        fun awaitFirstRequest() = check(firstRequest.await(5, TimeUnit.SECONDS))

        override fun intercept(chain: Interceptor.Chain): Response {
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readByteArray()
            requests.add(VideoPlaybackAbrRequest.parseFrom(body))
            firstRequest.countDown()
            val bytes = respond(requests.size, chain)
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body(bytes))
                .build()
        }
    }

    private class FakeBandwidthMeter(private val listener: TransferListener? = null) : BandwidthMeter {
        override fun getBitrateEstimate(): Long = 1_000_000L
        override fun getTransferListener(): TransferListener? = listener
        override fun getTimeToFirstByteEstimateUs(): Long = C.TIME_UNSET
        override fun addEventListener(handler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    private class TransferEvent(val name: String, val isNetwork: Boolean, val atNanos: Long)

    /** Records what a meter's transfer listener hears, in order. */
    private class RecordingTransfers : TransferListener {
        val events = CopyOnWriteArrayList<TransferEvent>()

        fun network(): List<String> = events.filter { it.isNetwork }.map { it.name }

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            record("start", isNetwork)
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) =
            record("bytes $bytesTransferred", isNetwork)
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            record("end", isNetwork)

        private fun record(name: String, isNetwork: Boolean) {
            events.add(TransferEvent(name, isNetwork, System.nanoTime()))
        }
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val FORMAT_END_MS = 10_000L
        val SEGMENT_BYTES = byteArrayOf(1, 2)
    }
}
