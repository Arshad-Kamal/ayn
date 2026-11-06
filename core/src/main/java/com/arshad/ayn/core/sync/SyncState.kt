package com.arshad.ayn.core.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.access.decodeOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * This device's link to a sync folder, beside its secrets (M8-D17): `null` while unlinked.
 * [failure] is a [SyncFailure] name, read leniently so a later build's value can't unlink.
 */
@Serializable
internal data class SyncLink(
    /** Names this device's files: `<deviceId>.ayn` and `<deviceId>.prev.ayn`. */
    val deviceId: String,
    /** Whether a sync has paired this device's profiles with the folder's (M8-D16). */
    val paired: Boolean = false,
    /** Each other device's file → the [RemoteFile.version] last merged here. */
    val merged: Map<String, String> = emptyMap(),
    /** SHA-256 (hex) of the snapshot last uploaded, its export time aside. */
    val uploadedHash: String? = null,
    val syncedAtMillis: Long? = null,
    val failure: String? = null,
    val account: StoredAccount? = null,
    /** A joining device's rounds wait for the owner's Match choices (M8-D18). */
    val awaitingMatch: Boolean = false,
    val choices: MatchChoices = MatchChoices(),
    /** The other devices in the folder at the last round. */
    val devices: List<SyncDevice> = emptyList(),
    /** The folder's files' total size after the last round, its own upload counted (a round that
     *  failed uploading: as it listed them), when the service gives sizes. */
    val folderBytes: Long? = null,
)

/**
 * [SyncLink] in the app's private `sync-state` preferences, and the envelope last uploaded (for
 * the next upload's `.prev.ayn`, M8-D2) in `noBackupFilesDir`; both read and written on [io].
 */
internal class SyncState(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE) }
    private val lastUploadFile by lazy { AtomicFile(File(appContext.noBackupFilesDir, LAST_UPLOAD_FILE)) }

    suspend fun link(): SyncLink? = withContext(io) {
        preferences.getString(LINK_ENTRY, null)?.let { decodeOrNull(it, SyncLink.serializer()) }
    }

    /** False when the preferences file wasn't written. */
    suspend fun save(link: SyncLink): Boolean = withContext(io) {
        preferences.edit().putString(LINK_ENTRY, boundaryJson.encodeToString(SyncLink.serializer(), link)).commit()
    }

    /** `null` before the first upload, or when it can't be read. */
    suspend fun lastUpload(): ByteArray? = withContext(io) {
        try {
            lastUploadFile.readFully()
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(SYNC_LOG_TAG, "last upload unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** A copy that couldn't be kept only costs the next upload its `.prev.ayn`: the older copy
     *  goes too, so it can't stand in for this one. */
    suspend fun keepLastUpload(envelope: ByteArray): Unit = withContext(io) {
        try {
            val out = lastUploadFile.startWrite()
            try {
                out.write(envelope)
                lastUploadFile.finishWrite(out)
            } catch (e: IOException) {
                lastUploadFile.failWrite(out)
                throw e
            }
        } catch (e: IOException) {
            lastUploadFile.delete()
            Log.w(SYNC_LOG_TAG, "last upload not kept: ${e.javaClass.simpleName}")
        }
    }

    /** Drops the link and the last upload (unlinking, or before a link is saved). False when the
     *  preferences file wasn't written. */
    suspend fun clear(): Boolean = withContext(io) {
        lastUploadFile.delete()
        preferences.edit().clear().commit()
    }
}

internal const val SYNC_LOG_TAG = "Ayn-sync"
private const val STATE_PREFERENCES = "sync-state"
private const val LINK_ENTRY = "link"
private const val LAST_UPLOAD_FILE = "sync-last-upload.ayn"
