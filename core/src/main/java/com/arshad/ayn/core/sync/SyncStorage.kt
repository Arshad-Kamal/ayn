package com.arshad.ayn.core.sync

import com.arshad.ayn.core.access.decodeOrNull
import com.arshad.ayn.core.access.executeCancellably
import com.arshad.ayn.core.access.nestsWithin
import com.arshad.ayn.core.access.textWithin
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A file in the sync folder. [etag] changes whenever the content does, when the service gives
 * one; [modifiedMillis] and [sizeBytes] are `null` when it doesn't say.
 */
internal data class RemoteFile(val name: String, val etag: String?, val modifiedMillis: Long?, val sizeBytes: Long?)

/**
 * One folder at a cloud service (M8-D1), where each device writes only its own files (M8-D2).
 * Every operation does what it says or throws [SyncStorageException]; none touches local data.
 */
internal interface SyncStorage {
    /** The folder's files, without sub-folders. */
    suspend fun list(): List<RemoteFile>

    /** [name]'s content, or `null` when no file has that name (another device removed it). */
    suspend fun get(name: String): ByteArray?

    /** Writes [content] as [name], replacing any file of that name. */
    suspend fun put(name: String, content: ByteArray)

    /** Removes [name]; a file already gone counts as removed. */
    suspend fun delete(name: String)

    /** Makes the folder; one already there counts as made. [StorageFailure.FOLDER_MISSING] when
     *  the place it goes in isn't there. */
    suspend fun createFolder()
}

internal enum class StorageFailure {
    /** A wrong user name or password, or a sign-in revoked or expired at the service: sign in
     *  again. */
    SIGN_IN_REJECTED,

    /** The service has no room for the file. */
    STORAGE_FULL,

    /** The service couldn't be reached, or stopped answering (a timeout). */
    NO_CONNECTION,

    /** No folder at the account's address. */
    FOLDER_MISSING,

    /** Any other answer, or one Ayn can't read. */
    SERVER_ERROR,
}

/** Its message names only the [failure]: a cause's message may carry the server's address. */
internal class SyncStorageException(val failure: StorageFailure, cause: Throwable? = null) :
    IOException("sync storage: $failure", cause)

/** Names the device in a Nextcloud's list of signed-in devices. */
internal const val SYNC_USER_AGENT = "Ayn (Android)"

/** Runs [request] on [io] and reads its answer with [read]; a failure to reach the service or to
 *  read its answer (a timeout included) is [StorageFailure.NO_CONNECTION]. */
internal suspend fun <T> OkHttpClient.exchange(request: Request, io: CoroutineDispatcher, read: (Response) -> T): T = try {
    newCall(request).executeCancellably(io, read)
} catch (e: SyncStorageException) {
    throw e
} catch (e: IOException) {
    throw SyncStorageException(StorageFailure.NO_CONNECTION, e)
}

internal fun failWith(failure: StorageFailure): Nothing = throw SyncStorageException(failure)

/** The body as [T] when it is at most [maxBytes] long and nests at most [maxDepth] deep
 *  (KT-JSON-03), else [StorageFailure.SERVER_ERROR]. */
internal fun <T> Response.decodeWithin(deserializer: DeserializationStrategy<T>, maxBytes: Long, maxDepth: Int): T =
    decodeWithinOrNull(deserializer, maxBytes, maxDepth) ?: failWith(StorageFailure.SERVER_ERROR)

/** [decodeWithin], `null` where that fails. */
internal fun <T> Response.decodeWithinOrNull(deserializer: DeserializationStrategy<T>, maxBytes: Long, maxDepth: Int): T? =
    textWithin(maxBytes)?.takeIf { nestsWithin(it, maxDepth) }?.let { decodeOrNull(it, deserializer) }

/** A folder holds two files per device; Nextcloud listed the folder and one file in 1.6 KB. */
internal const val MAX_LISTING_BYTES = 1024L * 1024

internal const val HTTP_UNAUTHORIZED = 401
internal const val HTTP_FORBIDDEN = 403
internal const val HTTP_NOT_FOUND = 404

/** A request to [url] that names the device ([SYNC_USER_AGENT]). */
internal fun syncRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("User-Agent", SYNC_USER_AGENT)
