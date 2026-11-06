package com.arshad.ayn.feature

import java.util.Locale

/**
 * Whether [tag] and [language] name one language whatever their regions or scripts ("en-GB" is
 * English, "zh-Hans" Chinese), a code ISO 639 withdrew reading as its successor ("iw" is Hebrew:
 * YouTube and older platforms still use them). A blank [language] matches nothing.
 */
fun sameLanguage(tag: String, language: String): Boolean =
    language.isNotBlank() && primaryLanguage(tag) == primaryLanguage(language)

private fun primaryLanguage(tag: String): String =
    tag.substringBefore('-').lowercase(Locale.ROOT).let { WITHDRAWN_CODES[it] ?: it }

private val WITHDRAWN_CODES: Map<String, String> = mapOf("iw" to "he", "in" to "id", "ji" to "yi")
