package com.arshad.ayn.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * A YouTube-shaped live HLS stream served from memory (LIVE-D9: no test dependency, no network): a
 * multivariant playlist naming a 720p and a 1080p variant by absolute URL on YouTube's manifest
 * host, each a media playlist of [SEGMENTS] one-second segments that never changes (a live
 * window), or ends with `#EXT-X-ENDLIST` once [ended] is set. Segments answer empty: the inert
 * test player decodes nothing. Unknown URLs fail as an unreachable resource would (a plain IOException).
 */
@OptIn(UnstableApi::class)
internal class SyntheticHls : DataSource.Factory {
    @Volatile var ended = false

    override fun createDataSource(): DataSource = object : BaseDataSource(/* isNetwork = */ true) {
        private var uri: Uri? = null
        private var body = ByteArray(0)
        private var offset = 0
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            body = bodyFor(dataSpec.uri.toString())?.toByteArray() ?: throw IOException("no such resource")
            offset = dataSpec.position.toInt()
            opened = true
            transferStarted(dataSpec)
            return (body.size - offset).toLong()
        }

        override fun read(buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (length == 0) return 0
            val left = body.size - offset
            if (left == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(left, length)
            System.arraycopy(body, offset, buffer, bufferOffset, count)
            offset += count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun bodyFor(url: String): String? = when {
        url == MULTIVARIANT_URL -> MULTIVARIANT
        url == "$BASE/720.m3u8" -> media("720")
        url == "$BASE/1080.m3u8" -> media("1080")
        url.startsWith("$BASE/seg/") -> ""
        else -> null
    }

    private fun media(variant: String): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$FIRST_SEQUENCE\n")
        for (n in FIRST_SEQUENCE..<FIRST_SEQUENCE + SEGMENTS) append("#EXTINF:1.0,\n$BASE/seg/$variant/$n.ts\n")
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    companion object {
        const val BASE = "https://manifest.googlevideo.com/api/manifest/synthetic"
        const val MULTIVARIANT_URL = "$BASE/hls_variant.m3u8"
        const val SEGMENTS = 60
        private const val FIRST_SEQUENCE = 4_100

        val MULTIVARIANT = """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=30
            $BASE/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=30
            $BASE/1080.m3u8
        """.trimIndent() + "\n"
    }
}

