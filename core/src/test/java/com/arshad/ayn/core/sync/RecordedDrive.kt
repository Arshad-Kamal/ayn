package com.arshad.ayn.core.sync

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartReader
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One exchange the Drive smoke recorded against Google on 2026-11-02 (`resources/drive/<name>.json`,
 * scrubbed: `CLIENT-ID`, `CLIENT-SECRET`, `DEVICE-CODE`, `USER-CODE`, `ACCESS-TOKEN`(-2),
 * `REFRESH-TOKEN`, `FOLDER-ID`, `FILE-ID-08`/`-09`, multipart boundary `BOUNDARY`). Served by a
 * [MockWebServer] standing in for both of Google's hosts ([endpoints]); a body the recorder left
 * out (`omittedBytes`) is [RecordedWebDav.content] of that size. Requests are compared by meaning
 * where the recorder's Python spelled them differently: query and form fields decoded, JSON parsed,
 * multipart parts read through their own boundary.
 */
internal class RecordedDrive(private val name: String) {
    private val exchange: JsonObject = Json.parseToJsonElement(
        checkNotNull(RecordedDrive::class.java.getResourceAsStream("/drive/$name.json")) { name }.use { it.readBytes().decodeToString() },
    ).jsonObject
    private val request = exchange.getValue("request").jsonObject
    private val response = exchange.getValue("response").jsonObject

    fun response(): MockResponse {
        val builder = MockResponse.Builder().code(response.getValue("code").jsonPrimitive.int)
        response.getValue("headers").jsonObject.forEach { (header, value) -> builder.addHeader(header, value.jsonPrimitive.content) }
        val body = response.getValue("body")
        val size = omitted(response)
        when {
            size != null -> builder.body(Buffer().write(RecordedWebDav.content(size)))
            body !is JsonNull -> builder.body(body.jsonPrimitive.content)
        }
        return builder.build()
    }

    /** [sent] has the recorded method, path, query, headers and body; a left-out body is the
     *  test's to check. */
    fun assertSent(sent: RecordedRequest) {
        val recordedUrl = request.getValue("url").jsonPrimitive.content.toHttpUrl()
        assertEquals("$name method", request.getValue("method").jsonPrimitive.content, sent.method)
        assertEquals("$name path", recordedUrl.encodedPath, sent.url.encodedPath)
        assertEquals("$name query", queryOf(recordedUrl), queryOf(sent.url))
        val headers = request.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }
        headers.forEach { (header, value) ->
            if (header == "Content-Type" && value.startsWith(MULTIPART)) {
                assertTrue("$name $header", sent.headers[header].orEmpty().startsWith("$MULTIPART; boundary="))
            } else {
                assertEquals("$name $header", value, sent.headers[header])
            }
        }
        val body = request.getValue("body")
        if (omitted(request) != null) return
        val contentType = headers["Content-Type"].orEmpty()
        when {
            body is JsonNull -> assertEquals("$name body size", 0L, sent.bodySize)
            contentType.startsWith("application/x-www-form-urlencoded") ->
                assertEquals("$name form", formOf(body.jsonPrimitive.content), formOf(sent.body!!.utf8()))
            contentType.startsWith("application/json") ->
                assertEquals("$name json", Json.parseToJsonElement(body.jsonPrimitive.content), Json.parseToJsonElement(sent.body!!.utf8()))
            contentType.startsWith(MULTIPART) ->
                assertEquals("$name parts", partsOf(body.jsonPrimitive.content.encodeUtf8(), "BOUNDARY"), partsOf(sent))
            else -> assertEquals("$name body", body.jsonPrimitive.content, sent.body?.utf8())
        }
    }

    private fun omitted(part: JsonObject): Int? = (part["body"] as? JsonObject)?.get("omittedBytes")?.jsonPrimitive?.int

    /** One part of a multipart body: its Content-Type and its content (JSON parsed). */
    data class Part(val contentType: String?, val content: Any)

    companion object {
        private const val MULTIPART = "multipart/related"

        /** [names] served in order by [server]. */
        fun enqueue(server: MockWebServer, vararg names: String): List<RecordedDrive> =
            names.map(::RecordedDrive).onEach { server.enqueue(it.response()) }

        /** Both Google hosts at [server]. */
        fun endpoints(server: MockWebServer): GoogleEndpoints = GoogleEndpoints(oauth = server.url("/"), api = server.url("/"))

        /** The parts of the multipart body [sent], read through the boundary its header names. */
        fun partsOf(sent: RecordedRequest): List<Part> =
            partsOf(sent.body!!, sent.headers["Content-Type"]!!.substringAfter("boundary="))

        private fun partsOf(body: ByteString, boundary: String): List<Part> = buildList {
            MultipartReader(Buffer().write(body), boundary).use { reader ->
                while (true) {
                    val part = reader.nextPart() ?: break
                    val type = part.headers["Content-Type"]
                    val bytes = part.body.readByteString()
                    add(Part(type, if (type.orEmpty().startsWith("application/json")) Json.parseToJsonElement(bytes.utf8()) else bytes))
                }
            }
        }

        private fun queryOf(url: HttpUrl): Map<String, List<String?>> = url.queryParameterNames.associateWith(url::queryParameterValues)

        private fun formOf(form: String): Map<String, String> = form.split('&').associate { field ->
            URLDecoder.decode(field.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(field.substringAfter('='), Charsets.UTF_8)
        }
    }
}
