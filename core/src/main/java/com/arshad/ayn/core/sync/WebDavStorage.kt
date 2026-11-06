package com.arshad.ayn.core.sync

import android.util.Xml
import com.arshad.ayn.core.access.bytesWithin
import com.arshad.ayn.core.access.textWithin
import java.io.IOException
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * A device's sign-in to a WebDAV folder: Basic auth with [user] and [password] (for Nextcloud a
 * per-device app password from [NextcloudLogin]). Not a data class, so the password never reaches
 * a `toString`.
 */
internal class WebDavAccount(val folder: HttpUrl, val user: String, val password: String)

/**
 * [SyncStorage] over WebDAV (RFC 4918) on the app's OkHttp, as recorded against Nextcloud 31:
 * PROPFIND (Depth 1) lists, GET, PUT and DELETE work on one file, MKCOL makes the folder.
 * Redirects aren't followed: OkHttp turns a redirected PUT or DELETE into a GET whose 200 would
 * read as done (an auth proxy's login page, say), so any 3xx is [StorageFailure.SERVER_ERROR].
 */
internal class WebDavStorage(
    account: WebDavAccount,
    client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncStorage {
    private val client = client.newBuilder().followRedirects(false).build()

    /** Ends in `/`: a collection's address, and the base its files' names are added to. */
    private val folder: HttpUrl = account.folder.let { if (it.encodedPath.endsWith('/')) it else it.newBuilder().addPathSegment("").build() }
    private val authorization = Credentials.basic(account.user, account.password, Charsets.UTF_8)

    override suspend fun list(): List<RemoteFile> {
        val request = request(folder).header("Depth", "1").method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).build()
        return client.exchange(request, io) { response ->
            when (response.code) {
                HTTP_MULTI_STATUS -> response.textWithin(MAX_LISTING_BYTES)?.let { parseListing(it, folder) } ?: failWith(StorageFailure.SERVER_ERROR)
                HTTP_NOT_FOUND -> failWith(StorageFailure.FOLDER_MISSING)
                else -> failWith(failureOf(response.code))
            }
        }
    }

    override suspend fun get(name: String): ByteArray? = client.exchange(request(fileUrl(name)).get().build(), io) { response ->
        when {
            response.isSuccessful -> response.bytesWithin(MAX_ENVELOPE_BYTES.toLong()) ?: failWith(StorageFailure.SERVER_ERROR)
            response.code == HTTP_NOT_FOUND -> null
            else -> failWith(failureOf(response.code))
        }
    }

    override suspend fun put(name: String, content: ByteArray) {
        client.exchange(request(fileUrl(name)).put(content.toRequestBody(OCTET_STREAM)).build(), io) { response ->
            if (!response.isSuccessful) failWith(failureOf(response.code))
        }
    }

    override suspend fun delete(name: String) {
        client.exchange(request(fileUrl(name)).delete().build(), io) { response ->
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) failWith(failureOf(response.code))
        }
    }

    /** One already there answers 405 (as Nextcloud does); a missing parent 409 (RFC 4918 9.3.1). */
    override suspend fun createFolder() {
        client.exchange(request(folder).method("MKCOL", null).build(), io) { response ->
            when {
                response.isSuccessful || response.code == HTTP_METHOD_NOT_ALLOWED -> Unit
                response.code == HTTP_CONFLICT -> failWith(StorageFailure.FOLDER_MISSING)
                else -> failWith(failureOf(response.code))
            }
        }
    }

    private fun fileUrl(name: String): HttpUrl = folder.newBuilder().addPathSegment(name).build()

    private fun request(url: HttpUrl): Request.Builder = syncRequest(url).header("Authorization", authorization)

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getlastmodified/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        val XML = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val HTTP_MULTI_STATUS = 207
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_CONFLICT = 409
    }
}

/** The failure an unexpected WebDAV answer [code] stands for. */
private fun failureOf(code: Int): StorageFailure = when (code) {
    HTTP_UNAUTHORIZED -> StorageFailure.SIGN_IN_REJECTED
    HTTP_INSUFFICIENT_STORAGE -> StorageFailure.STORAGE_FULL
    else -> StorageFailure.SERVER_ERROR
}

private const val HTTP_INSUFFICIENT_STORAGE = 507

/**
 * The files directly in [folder] (an address ending in `/`) that a PROPFIND multistatus [xml]
 * lists, with the properties of each 200 propstat; the folder itself and sub-folders are left
 * out. `null` when [xml] isn't a multistatus Ayn can read, or declares a DTD (refused before any
 * entity in it could expand).
 */
internal fun parseListing(xml: String, folder: HttpUrl): List<RemoteFile>? = try {
    readResponses(xml).mapNotNull { it.fileIn(folder) }
} catch (_: XmlPullParserException) {
    null
} catch (_: IOException) {
    null
}

private class DavResponse(val href: String, val properties: Map<String, String>, val isCollection: Boolean) {
    fun fileIn(folder: HttpUrl): RemoteFile? {
        if (isCollection) return null
        val segments = folder.resolve(href)?.pathSegments ?: return null
        val name = segments.last()
        if (name.isEmpty() || segments.dropLast(1) != folder.pathSegments.dropLast(1)) return null
        return RemoteFile(
            name = name,
            etag = properties[GETETAG],
            modifiedMillis = properties[GETLASTMODIFIED]?.let(::httpDateMillis),
            sizeBytes = properties[GETCONTENTLENGTH]?.toLongOrNull(),
        )
    }
}

private class Propstat(val properties: Map<String, String>, val isCollection: Boolean)

private fun readResponses(xml: String): List<DavResponse> {
    val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
    while (parser.nextToken() != XmlPullParser.START_TAG) {
        when (parser.eventType) {
            XmlPullParser.DOCDECL -> throw XmlPullParserException("a DTD")
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("no root")
        }
    }
    if (!parser.isDav("multistatus")) throw XmlPullParserException("not a multistatus")
    val responses = mutableListOf<DavResponse>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.isDav("response")) readResponse(parser)?.let(responses::add)
    }
    return responses
}

/**
 * From a `response` start tag to its end tag; `null` when it lists nothing found: its own status
 * (RFC 4918's href-and-status form) isn't 200, or none of its propstats is.
 */
private fun readResponse(parser: XmlPullParser): DavResponse? {
    var href: String? = null
    var status: String? = null
    val found = mutableListOf<Propstat>()
    val depth = parser.depth
    while (parser.nextChildStart(depth)) {
        if (parser.depth != depth + 1) continue
        when {
            parser.isDav("href") -> if (href == null) href = parser.nextText().trim()
            parser.isDav("status") -> status = parser.nextText()
            parser.isDav("propstat") -> readPropstat(parser)?.let(found::add)
        }
    }
    if (href == null) throw XmlPullParserException("a response without an href")
    val listed = status?.let(::isOk) ?: found.isNotEmpty()
    if (!listed) return null
    return DavResponse(href, found.fold(emptyMap()) { all, propstat -> all + propstat.properties }, found.any { it.isCollection })
}

/** From a `propstat` start tag to its end tag; `null` when its status isn't 200 (properties the
 *  server doesn't have). */
private fun readPropstat(parser: XmlPullParser): Propstat? {
    val properties = mutableMapOf<String, String>()
    var isCollection = false
    var status: String? = null
    val depth = parser.depth
    while (parser.nextChildStart(depth)) {
        when {
            parser.isDav("status") -> status = parser.nextText()
            parser.isDav("collection") -> isCollection = true
            parser.namespace == DAV && parser.name in TEXT_PROPERTIES -> properties[parser.name] = parser.nextText().trim()
        }
    }
    return if (status?.let(::isOk) == true) Propstat(properties, isCollection) else null
}

/** Whether a status line ("HTTP/1.1 200 OK") says 200. */
private fun isOk(statusLine: String): Boolean = statusLine.trim().split(' ').getOrNull(1) == "200"

private fun XmlPullParser.isDav(localName: String): Boolean = namespace == DAV && name == localName

/** Moves to the next start tag inside the element opened at [depth]; false at that element's end
 *  tag. */
private fun XmlPullParser.nextChildStart(depth: Int): Boolean {
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> return true
            XmlPullParser.END_TAG -> if (this.depth == depth) return false
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("cut short")
        }
    }
}

/** An HTTP date ("Fri, 30 Oct 2026 05:53:14 GMT") in epoch millis, or `null` when it isn't one. */
private fun httpDateMillis(text: String): Long? = try {
    ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

private const val DAV = "DAV:"
private const val GETETAG = "getetag"
private const val GETLASTMODIFIED = "getlastmodified"
private const val GETCONTENTLENGTH = "getcontentlength"
private val TEXT_PROPERTIES = setOf(GETETAG, GETLASTMODIFIED, GETCONTENTLENGTH)
