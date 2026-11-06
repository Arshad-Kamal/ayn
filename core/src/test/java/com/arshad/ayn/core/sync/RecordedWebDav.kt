package com.arshad.ayn.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * One exchange the Nextcloud smoke recorded against Nextcloud 31.0.14 (`resources/webdav/<name>.json`,
 * scrubbed: host `https://cloud.example.org`, users `smoke` and `mira` (signed in with
 * `mira@example.org`), app passwords `APP-PASSWORD` and `APP-PASSWORD-EMAIL`). Served
 * by a [MockWebServer] with the recorded host replaced by the server's own address; a body the
 * recorder left out (`omittedBytes`) is [content] of that size.
 */
internal class RecordedWebDav(private val name: String) {
    private val exchange: JsonObject = Json.parseToJsonElement(
        checkNotNull(RecordedWebDav::class.java.getResourceAsStream("/webdav/$name.json")) { name }.use { it.readBytes().decodeToString() },
    ).jsonObject
    private val request = exchange.getValue("request").jsonObject
    private val response = exchange.getValue("response").jsonObject

    /** The recorded request's path and query, the address the client must have used. */
    val path: String get() = request.getValue("path").jsonPrimitive.content

    /** The size of the recorded request's or answer's left-out body. */
    val omittedBytes: Int get() = (omitted(request) ?: omitted(response)) ?: error("$name has no omitted body")

    fun response(server: MockWebServer): MockResponse {
        val builder = MockResponse.Builder().code(response.getValue("code").jsonPrimitive.int)
        response.getValue("headers").jsonObject.forEach { (header, value) -> builder.addHeader(header, value.jsonPrimitive.content) }
        val body = response.getValue("body")
        val size = omitted(response)
        when {
            size != null -> builder.body(Buffer().write(content(size)))
            body !is JsonNull -> builder.body(onServer(body.jsonPrimitive.content, server))
        }
        return builder.build()
    }

    /** [sent] has the recorded method, path, headers and body (a left-out body by its size). */
    fun assertSent(sent: RecordedRequest) {
        assertEquals("$name method", request.getValue("method").jsonPrimitive.content, sent.method)
        assertEquals("$name path", path, sent.url.encodedPath + sent.url.encodedQuery?.let { "?$it" }.orEmpty())
        request.getValue("headers").jsonObject.forEach { (header, value) ->
            assertEquals("$name $header", value.jsonPrimitive.content, sent.headers[header])
        }
        val body = request.getValue("body")
        val size = omitted(request)
        when {
            size != null -> assertArrayEquals("$name body", content(size), sent.body?.toByteArray())
            body is JsonNull -> assertEquals("$name body size", 0L, sent.bodySize)
            else -> assertEquals("$name body", body.jsonPrimitive.content, sent.body?.utf8())
        }
    }

    private fun omitted(part: JsonObject): Int? =
        (part["body"] as? JsonObject)?.get("omittedBytes")?.jsonPrimitive?.int

    private fun onServer(text: String, server: MockWebServer): String {
        val base = server.url("/").toString().removeSuffix("/")
        // Nextcloud's JSON writes "/" as "\/", so its addresses appear in both forms.
        return text.replace(RECORDED_BASE, base).replace(RECORDED_BASE.replace("/", "\\/"), base.replace("/", "\\/"))
    }

    companion object {
        const val RECORDED_BASE = "https://cloud.example.org"

        /** A recorded file's stand-in content: any bytes, as the smoke's were random. */
        fun content(size: Int): ByteArray = ByteArray(size) { (it * 31).toByte() }

        /** [names] served in order by [server]. */
        fun enqueue(server: MockWebServer, vararg names: String): List<RecordedWebDav> =
            names.map(::RecordedWebDav).onEach { server.enqueue(it.response(server)) }
    }
}

/** [action] throws [SyncStorageException] for [expected]. */
internal suspend fun assertStorageFailure(expected: StorageFailure, action: suspend () -> Unit) {
    try {
        action()
        fail("expected $expected")
    } catch (e: SyncStorageException) {
        assertEquals(expected, e.failure)
    }
}
