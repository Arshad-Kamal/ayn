package com.arshad.ayn.core.access

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * KT-JSON-02's one untrusted-boundary instance, for every JSON KE didn't write itself (backup
 * files, imports, third-party APIs) and the JSON KE stores that another build may read back (For
 * you's related lists): unknown keys are ignored, syntax stays strict, and no value
 * is silently replaced by a default.
 */
internal val boundaryJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    coerceInputValues = false
}

/** [text] as [T] through [boundaryJson], or `null` when it isn't one. Nothing is logged: the
 *  decoder's messages quote the input (KT-JSON-04). */
internal fun <T> decodeOrNull(text: String, deserializer: DeserializationStrategy<T>): T? =
    try {
        boundaryJson.decodeFromString(deserializer, text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * KT-JSON-03's depth guard (the parser has none): whether [text]'s objects and arrays nest at
 * most [maxDepth] deep; brackets in strings don't count, and a closer with nothing open fails
 * (no JSON has one).
 */
internal fun nestsWithin(text: String, maxDepth: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> if (++depth > maxDepth) return false
            c == '}' || c == ']' -> if (--depth < 0) return false
        }
    }
    return true
}
