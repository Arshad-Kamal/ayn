package com.arshad.ayn.core.sync

import com.arshad.ayn.core.access.bytesWithin
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [SyncStorage] in the "Ayn sync" folder of the user's My Drive, as recorded 2026-11-02 (Drive API
 * v3). Its scope, `drive.file`, shows Ayn only the files and folders Ayn made (spec 20 Q24).
 *
 * One instance serves one round or setup step, its calls one at a time: the runner makes one each
 * time, under its round lock. Its first call gets an access token from [newAccessToken] (an hour's,
 * [GoogleLogin.accessToken]) and finds the folder by name. Several folders of that name (two
 * devices starting at once) resolve to the smallest id, so every device picks the same one.
 *
 * Drive gives no ETag; a file's `md5Checksum` serves as its [RemoteFile.etag].
 */
internal class DriveStorage(
    private val newAccessToken: suspend () -> String,
    client: OkHttpClient,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncStorage {
    private val client = client.newBuilder().followRedirects(false).build()
    private var accessToken: String? = null
    private var folderId: String? = null

    /**
     * The ids of the folder's files by name, from the last [list], newest copy first. Drive allows
     * two files of one name (an upload whose answer was lost, then made again): reads and writes
     * use the newest, a delete removes every copy.
     */
    private var ids: MutableMap<String, List<String>>? = null

    override suspend fun list(): List<RemoteFile> {
        val folder = folderId() ?: failWith(StorageFailure.FOLDER_MISSING)
        val copies = findFiles("'$folder' in parents and trashed = false", FILE_FIELDS)
            .groupBy { it.name }
            .mapValues { (_, files) -> files.sortedByDescending { it.modifiedMillis() ?: Long.MIN_VALUE } }
        ids = copies.mapValuesTo(mutableMapOf()) { (_, files) -> files.map { it.id } }
        return copies.values.map { files -> files.first().let { RemoteFile(it.name, it.md5Checksum, it.modifiedMillis(), it.size?.toLongOrNull()) } }
    }

    override suspend fun get(name: String): ByteArray? {
        val id = idsOf(name).firstOrNull() ?: return null
        val url = fileUrl(FILES_PATH, id).newBuilder().addQueryParameter("alt", "media").build()
        return client.exchange(request(url).get().build(), io) { response ->
            when {
                response.isSuccessful -> response.bytesWithin(MAX_ENVELOPE_BYTES.toLong()) ?: failWith(StorageFailure.SERVER_ERROR)
                response.code == HTTP_NOT_FOUND -> null
                else -> failWith(driveFailureOf(response))
            }
        }
    }

    /** Overwrites [name] in place when the last listing had it (a PATCH keeps its id), else
     *  uploads it new; one that went meanwhile is uploaded new. */
    override suspend fun put(name: String, content: ByteArray) {
        val folder = folderId() ?: failWith(StorageFailure.FOLDER_MISSING)
        val known = idsOf(name)
        val written = known.firstOrNull()?.let { overwrite(it, content) } ?: create(name, folder, content)
        ids?.put(name, listOf(written.id) + known.drop(1))
    }

    override suspend fun delete(name: String) {
        for (id in idsOf(name)) {
            client.exchange(request(fileUrl(FILES_PATH, id)).delete().build(), io) { response ->
                if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) failWith(driveFailureOf(response))
            }
        }
        ids?.remove(name)
    }

    override suspend fun createFolder() {
        if (folderId() != null) return
        val metadata = buildJsonObject {
            put("name", SYNC_FOLDER_NAME)
            put("mimeType", FOLDER_TYPE)
            putJsonArray("parents") { add("root") }
        }
        val url = apiUrl(FILES_PATH).newBuilder().addQueryParameter("fields", WRITTEN_FIELDS).build()
        folderId = post(url, metadata.toString().toRequestBody(JSON)).id
        ids = mutableMapOf()
    }

    private suspend fun overwrite(id: String, content: ByteArray): DriveFile? {
        val url = fileUrl(UPLOAD_FILES_PATH, id).newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", WRITTEN_FIELDS)
            .build()
        return client.exchange(request(url).patch(content.toRequestBody(OCTET_STREAM)).build(), io) { response ->
            when {
                response.isSuccessful -> response.decodeWithin(DriveFile.serializer(), MAX_FILE_BYTES, FILE_DEPTH)
                response.code == HTTP_NOT_FOUND -> null
                else -> failWith(driveFailureOf(response))
            }
        }
    }

    private suspend fun create(name: String, folder: String, content: ByteArray): DriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            putJsonArray("parents") { add(folder) }
        }
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(content.toRequestBody(OCTET_STREAM))
            .build()
        val url = apiUrl(UPLOAD_FILES_PATH).newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", WRITTEN_FIELDS)
            .build()
        return post(url, body)
    }

    /** A POST that makes a file (or the folder): its id and the rest of [WRITTEN_FIELDS]. */
    private suspend fun post(url: HttpUrl, body: RequestBody): DriveFile = client.exchange(request(url).post(body).build(), io) { response ->
        if (!response.isSuccessful) failWith(driveFailureOf(response))
        response.decodeWithin(DriveFile.serializer(), MAX_FILE_BYTES, FILE_DEPTH)
    }

    /** [name]'s copies by the last listing (one is made when none was), newest first; none when the
     *  file or the whole folder is gone, as a missing file reads elsewhere. */
    private suspend fun idsOf(name: String): List<String> {
        if (ids == null) {
            if (folderId() == null) return emptyList()
            list()
        }
        return ids?.get(name).orEmpty()
    }

    /** The sync folder's id; `null` when My Drive has none Ayn can see. */
    private suspend fun folderId(): String? = folderId ?: findFiles(FOLDER_QUERY, FOLDER_FIELDS).minOfOrNull { it.id }?.also { folderId = it }

    private suspend fun findFiles(query: String, fields: String): List<DriveFile> {
        val url = apiUrl(FILES_PATH).newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", fields)
            .addQueryParameter("pageSize", PAGE_SIZE)
            .addQueryParameter("spaces", "drive")
            .build()
        return client.exchange(request(url).get().build(), io) { response ->
            if (!response.isSuccessful) failWith(driveFailureOf(response))
            response.decodeWithin(FileList.serializer(), MAX_LISTING_BYTES, LIST_DEPTH).files
        }
    }

    private suspend fun request(url: HttpUrl): Request.Builder = syncRequest(url).header("Authorization", "Bearer ${token()}")

    private suspend fun token(): String = accessToken ?: newAccessToken().also { accessToken = it }

    private fun apiUrl(path: String): HttpUrl = endpoints.api.newBuilder().addPathSegments(path).build()

    private fun fileUrl(path: String, id: String): HttpUrl = apiUrl(path).newBuilder().addPathSegment(id).build()

    /** 401: the access token was refused, so the sign-in behind it is gone. 403 is also Drive's
     *  answer to rate limits, so only its quota reason reads as full. */
    private fun driveFailureOf(response: Response): StorageFailure = when (response.code) {
        HTTP_UNAUTHORIZED -> StorageFailure.SIGN_IN_REJECTED
        HTTP_FORBIDDEN -> {
            val reasons = response.decodeWithinOrNull(ErrorAnswer.serializer(), MAX_ERROR_BYTES, ERROR_DEPTH)?.error?.errors.orEmpty().map { it.reason }
            if (STORAGE_QUOTA_EXCEEDED in reasons) StorageFailure.STORAGE_FULL else StorageFailure.SERVER_ERROR
        }
        else -> StorageFailure.SERVER_ERROR
    }

    @Serializable
    private class FileList(val files: List<DriveFile>)

    /** [size] is a string in Drive's JSON (an int64). An [id] outside Drive's alphabet fails the
     *  decode: it goes into a query between quotes. */
    @Serializable
    private class DriveFile(
        val id: String,
        val name: String,
        val md5Checksum: String? = null,
        val modifiedTime: String? = null,
        val size: String? = null,
    ) {
        init {
            require(DRIVE_ID.matches(id))
        }

        fun modifiedMillis(): Long? = modifiedTime?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
    }

    @Serializable
    private class ErrorAnswer(val error: Error? = null) {
        @Serializable
        class Error(val errors: List<Reason> = emptyList())

        @Serializable
        class Reason(val reason: String? = null)
    }

    private companion object {
        const val SYNC_FOLDER_NAME = "Ayn sync"
        const val FOLDER_TYPE = "application/vnd.google-apps.folder"
        const val FOLDER_QUERY = "name = '$SYNC_FOLDER_NAME' and mimeType = '$FOLDER_TYPE' and 'root' in parents and trashed = false"
        const val FOLDER_FIELDS = "files(id,name,modifiedTime),nextPageToken"
        const val FILE_FIELDS = "files(id,name,md5Checksum,modifiedTime,size),nextPageToken"
        const val WRITTEN_FIELDS = "id,name,md5Checksum,modifiedTime,size"
        const val FILES_PATH = "drive/v3/files"
        const val UPLOAD_FILES_PATH = "upload/drive/v3/files"

        /** Drive's largest page; a folder holds two files per device, so one page is all of it. */
        const val PAGE_SIZE = "1000"
        const val STORAGE_QUOTA_EXCEEDED = "storageQuotaExceeded"
        val DRIVE_ID = Regex("[A-Za-z0-9_-]+")

        val JSON = "application/json; charset=UTF-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val MULTIPART_RELATED = "multipart/related".toMediaType()

        /** A written file's answer was under 200 bytes and nests 1 deep; 2 leaves headroom. */
        const val MAX_FILE_BYTES = 64L * 1024
        const val FILE_DEPTH = 2

        /** `{"files": [{…}]}` nests 3 deep; 4 leaves headroom. */
        const val LIST_DEPTH = 4

        /** `{"error": {"errors": [{…}]}}` nests 4 deep; 5 leaves headroom. The recorded ones were
         *  under 600 bytes. */
        const val MAX_ERROR_BYTES = 64L * 1024
        const val ERROR_DEPTH = 5
    }
}
