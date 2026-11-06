package com.arshad.ayn.core.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where backups are written and read; [DownloadsBackupFiles] on a device. */
interface BackupFiles {
    /**
     * Writes [bytes] to Downloads as [fileName], hidden until [verify] accepts what reads back
     * ([verify] runs on the files' own IO thread). Returns the name Android published it under, or
     * `null` when writing, reading back, [verify] or publishing failed; the hidden file is then
     * deleted (best effort: one left behind stays hidden and Android expires it).
     */
    suspend fun save(fileName: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): String?

    /** The backups this install saved, newest first. */
    suspend fun list(): List<SavedBackup>

    /** [uri]'s bytes when it can be read and holds at most [maxBytes]; a longer file is never
     *  loaded whole. */
    suspend fun read(uri: Uri, maxBytes: Int): FileRead

    /** Deletes [uri] when it is one of [list]'s backups (never another file, a picked one
     *  included); false when it isn't or Android refused. */
    suspend fun delete(uri: Uri): Boolean
}

sealed interface FileRead {
    class Bytes(val bytes: ByteArray) : FileRead

    data object TooLarge : FileRead

    data object Unreadable : FileRead
}

/**
 * [BackupFiles] over `MediaStore.Downloads`, which needs no permission on API 29+ and lists only
 * this install's own files (scoped storage). Reads take any readable
 * content URI, a picked file's included.
 */
class DownloadsBackupFiles(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupFiles {
    private val resolver: ContentResolver = context.contentResolver
    private val downloads: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override suspend fun save(fileName: String, bytes: ByteArray, verify: (ByteArray) -> Boolean): String? = withContext(io) {
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, BACKUP_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = orNullOnFailure("insert") { resolver.insert(downloads, pending) } ?: return@withContext null
        val published = orNullOnFailure("write") {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            val written = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            if (verify(written)) {
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, publish, null, null) == 1) { "not published" }
                displayName(uri) ?: fileName
            } else {
                Log.w(BACKUP_LOG_TAG, "backup didn't read back")
                null
            }
        }
        if (published == null) orNullOnFailure("delete") { resolver.delete(uri, null, null) }
        published
    }

    override suspend fun list(): List<SavedBackup> = withContext(io) {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
        orNullOnFailure("list") {
            resolver.query(
                downloads,
                columns,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$BACKUP_NAME_PREFIX%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SavedBackup(
                                uri = ContentUris.withAppendedId(downloads, cursor.getLong(0)),
                                fileName = cursor.getString(1),
                                savedAtMillis = cursor.getLong(2) * MILLIS_PER_SECOND,
                            ),
                        )
                    }
                }
            }
        }.orEmpty()
    }

    override suspend fun read(uri: Uri, maxBytes: Int): FileRead = withContext(io) {
        orNullOnFailure("read") {
            val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: AssetFileDescriptor.UNKNOWN_LENGTH
            if (length > maxBytes) {
                FileRead.TooLarge
            } else {
                resolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readAtMost(maxBytes + 1, expected = length)
                    if (bytes.size > maxBytes) FileRead.TooLarge else FileRead.Bytes(bytes)
                }
            }
        } ?: FileRead.Unreadable
    }

    override suspend fun delete(uri: Uri): Boolean = withContext(io) {
        // Exactly a row URI as list() builds them: a prefix check would admit the collection
        // itself (a trailing slash), whose delete removes every row this install owns.
        val id = orNullOnFailure("delete check") { ContentUris.parseId(uri) }
        val ours = id != null && id >= 0 && ContentUris.withAppendedId(downloads, id) == uri &&
            orNullOnFailure("delete check") { displayName(uri) }?.startsWith(BACKUP_NAME_PREFIX) == true
        ours && orNullOnFailure("delete") { resolver.delete(uri, null, null) } == 1
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

/**
 * [block]'s result, or `null` when it fails. Any provider may be on the other end of a picked
 * file, and Binder carries its runtime exceptions across, so every one counts as a failure;
 * logged by kind only (a message can carry a path). Cancellation still propagates.
 */
private inline fun <T> orNullOnFailure(step: String, block: () -> T?): T? {
    val failure: Exception = try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        e
    } catch (e: RuntimeException) {
        e
    }
    Log.w(BACKUP_LOG_TAG, "backup file $step failed: ${failure.javaClass.simpleName}")
    return null
}

/** At most [limit] bytes; [expected] (the provider's length, negative when unknown) sizes the
 *  buffer so a known length is read without regrowing. */
internal fun InputStream.readAtMost(limit: Int, expected: Long): ByteArray {
    val out = ByteArrayOutputStream(if (expected in 0 until limit) expected.toInt() else BUFFER_BYTES)
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

internal const val BACKUP_NAME_PREFIX = "Ayn-backup-"
internal const val BACKUP_LOG_TAG = "KeBackup"
private const val BACKUP_MIME_TYPE = "application/json"
private const val MILLIS_PER_SECOND = 1_000L
private const val BUFFER_BYTES = 64 * 1024
