package com.arshad.ayn.core.filter

import com.google.re2j.PatternSyntaxException
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern as JavaPattern
import com.google.re2j.Pattern as Re2Pattern

/**
 * Title rules of one profile (F5, F6): keywords matched as whole words on the folded title, and
 * patterns run by RE2/J, which runs in time linear in the title (KP-D7). Built once per rules
 * snapshot; [matches] is pure.
 */
internal class TitleMatcher(keywords: Collection<String>, patterns: Collection<String>) {
    /** One alternation of every keyword, each quoted: the words are literal text, never syntax. */
    private val words: JavaPattern? = keywords.map(::foldTitle).filter { it.isNotBlank() }.distinct()
        .takeIf { it.isNotEmpty() }
        ?.let { folded -> JavaPattern.compile("$WORD_START(?:${folded.joinToString("|") { JavaPattern.quote(it) }})$WORD_END") }

    /** A stored pattern that no longer compiles (one written by a later build) matches nothing. */
    private val regexes: List<Re2Pattern> = patterns.mapNotNull(::compiledOrNull)

    val isEmpty: Boolean get() = words == null && regexes.isEmpty()

    fun matches(title: String): Boolean {
        if (isEmpty) return false
        if (words?.matcher(foldTitle(title))?.find() == true) return true
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
        return regexes.any { it.matcher(normalized).find() }
    }
}

/**
 * Whether [title] answers a kids profile's local search for [query] (KP-D10): every word of the
 * query starts a word of the title, both folded as keywords are ("dino" finds "Dinosaurs"). A
 * query with no letters or digits matches nothing.
 */
fun titleMatchesQuery(query: String, title: String): Boolean = titleQuery(query)(title)

/** [titleMatchesQuery] for [query] over many titles: the query is folded and split once. */
fun titleQuery(query: String): (title: String) -> Boolean {
    val wanted = words(query)
    return { title ->
        val titleWords = words(title)
        wanted.isNotEmpty() && wanted.all { word -> titleWords.any { it.startsWith(word) } }
    }
}

private fun words(text: String): List<String> = foldTitle(text).split(NOT_WORD).filter { it.isNotEmpty() }

private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

private fun compiledOrNull(pattern: String): Re2Pattern? = try {
    Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE)
} catch (_: PatternSyntaxException) {
    null
}

/**
 * [text] as keywords compare it (F5): NFKC, lower case in [Locale.ROOT] (never the device's: a
 * Turkish locale would fold "I" to a dotless i), curly and typographic quotes as plain ones.
 */
internal fun foldTitle(text: String): String {
    val lower = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return buildString(lower.length) {
        lower.forEach { c ->
            append(
                when (c) {
                    in SINGLE_QUOTES -> '\''
                    in DOUBLE_QUOTES -> '"'
                    else -> c
                },
            )
        }
    }
}

private const val SINGLE_QUOTES = "\u2018\u2019\u201A\u201B\u02BC\u0060\u00B4"
private const val DOUBLE_QUOTES = "\u201C\u201D\u201E\u201F\u00AB\u00BB"

/** A keyword is a whole word: no letter or digit right before or after it ("prank" hides
 *  "PRANK WAR", not "pranksters"). */
private const val WORD_START = "(?<![\\p{L}\\p{N}])"
private const val WORD_END = "(?![\\p{L}\\p{N}])"
