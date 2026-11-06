package com.arshad.ayn.core.access

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/**
 * Runs the call on [io]. A blocked socket ignores thread interrupts, so cancellation reaches the
 * call through [Call.cancel], which closes it and fails the blocked connect or read (a stalled DNS
 * lookup can't be cancelled; it ends at the system resolver's timeout). A cancelled caller gets a
 * `CancellationException`, never the cancelled call's `IOException`.
 */
internal suspend fun <T> Call.executeCancellably(io: CoroutineDispatcher, read: (Response) -> T): T = coroutineScope {
    val job = coroutineContext.job
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (job.isCancelled) this@executeCancellably.cancel()
        }
    }
    try {
        withContext(io) { execute().use(read) }
    } catch (e: IOException) {
        ensureActive()
        throw e
    } finally {
        canceller.cancel()
    }
}

/** The body as text, or `null` when it is longer than [maxBytes] (KT-JSON-03's size cap; only up
 *  to the cap is ever buffered). */
internal fun Response.textWithin(maxBytes: Long): String? = bytesWithin(maxBytes)?.decodeToString()

/** The body, or `null` when it is longer than [maxBytes] (only up to the cap is ever buffered). */
internal fun Response.bytesWithin(maxBytes: Long): ByteArray? {
    val source = body.source()
    if (source.request(maxBytes + 1)) return null
    return source.buffer.readByteArray()
}
