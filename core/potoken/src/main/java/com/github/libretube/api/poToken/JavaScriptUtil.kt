// MODIFIED (YouTube KE, 2026-10-23): the BotGuard challenge comes from YouTube's homepage
// (parseHomepageChallenge) instead of the jnn `Create` endpoint (parseChallengeData and
// descramble removed): GVS refuses tokens minted from the Create challenge. See THIRD-PARTY.md
// entry 3.
package com.github.libretube.api.poToken

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * ADDED: the attestation challenge YouTube's homepage runs for itself, and the page's
 * `EVENT_ID`, which BotGuard reads from `yt.config_`. GVS accepts tokens minted from this pair
 * only: the Create challenge, or this one without the page's EVENT_ID, gets 403s (2026-10-23).
 */
class HomepageChallenge(
    val eventId: String,
    val program: String,
    val globalName: String,
    /** https URL of the BotGuard interpreter script, on [INTERPRETER_HOST] only. */
    val interpreterUrl: String,
) {
    init {
        // The interpreter runs as code in the minting WebView: only Google's BotGuard host serves it.
        val url = interpreterUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.host != INTERPRETER_HOST) {
            throw PoTokenException("BotGuard interpreter off its host")
        }
    }

    /** The `yt.config_` value BotGuard reads, as a JavaScript literal. */
    val ytConfig: String
        get() = JsonObject(mapOf("EVENT_ID" to JsonPrimitive(eventId))).toString()

    /** The `runBotGuard` argument carrying the fetched [interpreterJavascript], as a JavaScript literal. */
    fun runBotGuardArgument(interpreterJavascript: String): String =
        JsonObject(
            mapOf(
                "interpreterJavascript" to JsonObject(
                    mapOf("privateDoNotAccessOrElseSafeScriptWrappedValue" to JsonPrimitive(interpreterJavascript))
                ),
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
            )
        ).toString()
}

/**
 * ADDED: reads [HomepageChallenge] from the homepage HTML: `"EVENT_ID":"…"` in its ytcfg, and
 * the `window.ytAtN({'R': '<JS-escaped JSON>', …})` call whose JSON carries `bgChallenge`.
 * Every failure (neither shape, malformed JSON or escapes, a missing or mistyped field, an
 * off-host interpreter) throws [PoTokenException].
 */
fun parseHomepageChallenge(html: String): HomepageChallenge {
    val eventId = EVENT_ID_PATTERN.find(html)?.groupValues?.get(1)
        ?: throw PoTokenException("homepage has no EVENT_ID")
    val escaped = attestationStringBody(html)
        ?: throw PoTokenException("homepage has no attestation challenge")
    val root = try {
        Json.parseToJsonElement(unescapeJavaScript(escaped)) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) { // a bad escape
        null
    } ?: throw PoTokenException(MALFORMED)
    val bg = root.child("bgChallenge")
    val url = bg.child("interpreterUrl").text("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
    return HomepageChallenge(
        eventId = eventId,
        program = bg.text("program"),
        globalName = bg.text("globalName"),
        interpreterUrl = if (url.startsWith("//")) "https:$url" else url,
    )
}

private const val MALFORMED = "malformed attestation challenge"

private fun JsonObject.child(key: String): JsonObject =
    get(key) as? JsonObject ?: throw PoTokenException(MALFORMED)

private fun JsonObject.text(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PoTokenException(MALFORMED)

/** The host that serves the BotGuard interpreter (`//www.google.com/js/th/<hash>.js`). */
private const val INTERPRETER_HOST = "www.google.com"

private val EVENT_ID_PATTERN = Regex("\"EVENT_ID\":\"([^\"\\\\]+)\"")

private val ATTESTATION_PREFIX = Regex("""window\.ytAtN\(\{\s*'R'\s*:\s*'""")

/**
 * The raw body of the `'R'` string literal, or null. Scanned, not matched: a regex alternation
 * over a ~40 KB literal overflows the JVM regex engine's stack.
 */
private fun attestationStringBody(html: String): String? {
    val start = ATTESTATION_PREFIX.find(html)?.range?.last?.plus(1) ?: return null
    var i = start
    while (i < html.length) {
        when (html[i]) {
            '\\' -> i += 2
            '\'' -> return html.substring(start, i)
            else -> i++
        }
    }
    return null
}

/**
 * Decodes a single-quoted JavaScript string body: `\xHH`, `\uHHHH`, `\n`-style and `\c` escapes.
 * A truncated or non-hex escape throws [IllegalArgumentException].
 */
private fun unescapeJavaScript(body: String): String = buildString(body.length) {
    fun hex(from: Int, digits: Int): Char =
        body.substring(from, minOf(from + digits, body.length)).takeIf { it.length == digits }
            ?.toIntOrNull(16)?.toChar()
            ?: throw IllegalArgumentException("bad escape")
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\') {
            append(c)
            i++
            continue
        }
        when (val next = body.getOrNull(i + 1) ?: throw IllegalArgumentException("bad escape")) {
            'x' -> { append(hex(i + 2, 2)); i += 4 }
            'u' -> { append(hex(i + 2, 4)); i += 6 }
            'n' -> { append('\n'); i += 2 }
            't' -> { append('\t'); i += 2 }
            'r' -> { append('\r'); i += 2 }
            else -> { append(next); i += 2 }
        }
    }
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and an [Int] representing the
 * duration of this token in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    return base64ToU8(integrityTokenData[0].jsonPrimitive.content) to integrityTokenData[1].jsonPrimitive.long
}

/**
 * Converts a string (usually the identifier used as input to `obtainPoToken`) to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code.
 */
fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
 * (e.g. "97,98,99" would be "abc"), which is the output of `Uint8Array::toString()` in JavaScript,
 * and converts it to the specific base64 representation for poTokens.
 */
fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
 * returns a JavaScript `Uint8Array` that can be embedded directly in JavaScript code.
 */
private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteString(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube.
 */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}