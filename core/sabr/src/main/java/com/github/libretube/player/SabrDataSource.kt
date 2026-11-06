// MODIFIED (YouTube KE, 2026-09-27): segment-fetch failures carry their cause instead of a bare
// IOException (house media3.md PL-6: keep errors typed for LoadErrorHandlingPolicy).
// MODIFIED (2026-10-02): a response without the requested segment fails as an IOException, not
// an NPE (the session layer treats a RuntimeException caught here as a bug).
// MODIFIED (2026-10-09): not a network source (was BaseDataSource(true)): open blocks on
// SabrClient's fetch but transfers nothing itself, and counting that whole wait against one
// segment's bytes collapsed the bandwidth meter on a start. SabrClient meters each response.
// See THIRD-PARTY.md entry 2.
package com.github.libretube.player

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.github.libretube.player.parser.CompositeBuffer
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import java.io.IOException

@UnstableApi
class SabrDataSource(
    private val sabrClient: SabrClient,
) : BaseDataSource(false) {
    private var data: CompositeBuffer? = null

    class Factory(
        private val sabrClient: SabrClient
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrDataSource(sabrClient)
    }

    override fun open(dataSpec: DataSpec): Long {
        val playbackRequest = dataSpec.customData as PlaybackRequest?

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        val segment = try {
            sabrClient.getNextSegment(playbackRequest!!)
                ?: throw IOException("requested segment not in the SABR response")
        } catch (e: Exception) {
            Log.e(
                SabrClient::class.java.name,
                "open: failed to get segment ${playbackRequest!!.segment} for ${playbackRequest.format.itag}: $e"
            )
            throw IOException("SABR segment fetch failed", e)
        }

        data = CompositeBuffer(segment.data)
        return data!!.remaining().toLong()
    }

    override fun getUri(): Uri? {
        if (data?.hasRemaining() != true) {
            // signal that this data source failed to be opened
            return null
        }
        return sabrClient.url.toUri()
    }

    override fun close() {
        transferEnded()
        data = null
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(data != null) // contract violation surfaces as ISE — an Exception, so Loader handles it
        if (length == 0) {
            return 0;
        }

        if (!data!!.hasRemaining()) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead = minOf(length, data!!.remaining())
        data!!.read(buffer, offset, bytesToRead)

        // this is not the actual amount of bytes transferred, since the SABR stream has some overhead,
        // e.g. for format metadata
        bytesTransferred(bytesToRead)
        return bytesToRead
    }
}