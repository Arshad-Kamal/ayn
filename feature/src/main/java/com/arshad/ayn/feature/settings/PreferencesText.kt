package com.arshad.ayn.feature.settings

import androidx.annotation.RawRes
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.R
import java.text.Collator
import java.util.Locale

// Preference pages' text both UIs show (`b3-settings` v13, `m6-settings` v1).

/** Ayn's public repository (About › Source code), and its address as the row shows it. */
const val SOURCE_CODE_URL = "https://github.com/Arshad-Kamal/ayn"
val SOURCE_CODE_SHOWN: String = SOURCE_CODE_URL.removePrefix("https://")

/** An action's name in a category's row and sheet. */
val SegmentAction.label: String
    get() = when (this) {
        SegmentAction.Skip -> "Skip automatically"
        SegmentAction.Button -> "Show a skip button"
        SegmentAction.Bar -> "Show on the seek bar only"
        SegmentAction.Off -> "Off"
    }

/** Where chat shows, as the Live chat row and sheets name it (`b3-settings` v19, `m6-settings` v7). */
val ChatPlacement.label: String
    get() = when (this) {
        ChatPlacement.Beside -> "Beside the video"
        ChatPlacement.Side -> "Over the right side"
        ChatPlacement.TopLeft -> "Top-left corner"
        ChatPlacement.TopRight -> "Top-right corner"
        ChatPlacement.BottomLeft -> "Bottom-left corner"
        ChatPlacement.BottomRight -> "Bottom-right corner"
    }

/** Where chat sits in a choice's small drawing of the picture (`.vmini i`), as fractions of its
 *  width and height; [square] chat meets the picture's edges without rounded corners. */
class PlacementSketch(val left: Float, val top: Float, val width: Float, val height: Float, val square: Boolean = false)

val ChatPlacement.sketch: PlacementSketch
    get() = when (this) {
        ChatPlacement.Beside -> PlacementSketch(left = 0.60f, top = 0.08f, width = 0.36f, height = 0.84f)
        ChatPlacement.Side -> PlacementSketch(left = 0.68f, top = 0f, width = 0.32f, height = 1f, square = true)
        ChatPlacement.TopLeft -> PlacementSketch(left = 0.06f, top = 0.10f, width = 0.30f, height = 0.40f)
        ChatPlacement.TopRight -> PlacementSketch(left = 0.64f, top = 0.10f, width = 0.30f, height = 0.40f)
        ChatPlacement.BottomLeft -> PlacementSketch(left = 0.06f, top = 0.50f, width = 0.30f, height = 0.40f)
        ChatPlacement.BottomRight -> PlacementSketch(left = 0.64f, top = 0.50f, width = 0.30f, height = 0.40f)
    }

/** A choice in the Quality sheet: "Up to 2160p". */
fun qualityChoice(maxHeight: Int): String = "Up to ${maxHeight}p"

/** What a Quality choice is, under it, where it has a common name. */
fun qualityHint(maxHeight: Int): String? = if (maxHeight == 480) "Uses the least data" else heightName(maxHeight)

/** An Interface size choice (TV), and the value its row and a restore's skips show. */
fun interfaceSizeLabel(percent: Int): String = "$percent%"

/** An offered language's own name, under its English one. */
val CAPTIONS_HINTS: Map<String, String> = mapOf("ga" to "Gaeilge", "gd" to "Gàidhlig")

/** A group's heading in the captions sheet. */
val CaptionsGroup.heading: String
    get() = when (this) {
        CaptionsGroup.Gaelic -> "Gaelic"
        CaptionsGroup.Indigenous -> "Indigenous languages"
    }

/** A language in All languages: its tag and the platform's English name for it. */
class NamedLanguage(val tag: String, val name: String)

// Codes ISO 639 withdrew in 1989 for he, id and yi; a platform may still list them beside the new ones.
private val WITHDRAWN_CODES = setOf("iw", "in", "ji")

/**
 * Every ISO 639-1 language the platform names (Q16, Q18), in English, alphabetical by name: no
 * list ships with Ayn, so it is whatever this device's ICU knows. A code the platform can't name
 * is left out.
 */
fun allLanguages(): List<NamedLanguage> {
    val collator = Collator.getInstance(Locale.ENGLISH)
    return Locale.getISOLanguages()
        .filter { it.length == 2 && it !in WITHDRAWN_CODES }
        .map { NamedLanguage(it, Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)) }
        .filter { it.name.isNotEmpty() && it.name != it.tag }
        .sortedWith(compareBy(collator) { it.name })
}

/** A profile's settings, as the page's scope names them. */
fun profileScope(name: String): String = "$name’s profile"

/**
 * A licence as Ayn ships its full text (`res/raw`, verbatim): its SPDX id, its name, the text.
 * BSD, ISC, MIT and OFL texts carry their part's copyright line, so each is that part's own file;
 * Rhino's is the MPL followed by its NOTICE (the code it bundles under other licences).
 */
enum class Licence(val id: String, val title: String, @param:RawRes val text: Int) {
    Gpl3OrLater("GPL-3.0-or-later", "GNU General Public License v3.0", R.raw.licence_gpl_3_0),
    Apache2("Apache-2.0", "Apache License 2.0", R.raw.licence_apache_2_0),
    Mpl2("MPL-2.0", "Mozilla Public License 2.0", R.raw.licence_mpl_2_0),
    Mpl2Rhino("MPL-2.0", "Mozilla Public License 2.0", R.raw.licence_mpl_2_0_rhino),
    Bsd3Protobuf("BSD-3-Clause", "BSD 3-Clause License", R.raw.licence_bsd_3_clause_protobuf),
    Bsd3Re2j("BSD-3-Clause", "BSD 3-Clause License", R.raw.licence_bsd_3_clause_re2j),
    MitJsoup("MIT", "MIT License", R.raw.licence_mit_jsoup),
    Unlicense("Unlicense", "The Unlicense", R.raw.licence_unlicense),
    IscMeriyah("ISC", "ISC License", R.raw.licence_isc_meriyah),
    MitAstring("MIT", "MIT License", R.raw.licence_mit_astring),
    MitQrcodegen("MIT", "MIT License", R.raw.licence_mit_qrcodegen),
    MitLuanrt("MIT", "MIT License", R.raw.licence_mit_luanrt),
    OflArchivo("OFL-1.1", "SIL Open Font License 1.1", R.raw.licence_ofl_1_1_archivo),
}

/** One part of Ayn (or a group of them) in the Licences sheet: what it does and its licence. */
class LicensedPart(val name: String, val role: String, val licence: Licence)

/**
 * What Ayn is built from, grouped (`b3-settings` licences): the vendored code in THIRD-PARTY.md
 * and the libraries the release build ships (`gradle/libs.versions.toml`), each with its licence.
 */
val LICENSED_PARTS: List<LicensedPart> = listOf(
    LicensedPart("NewPipeExtractor (LibreTube fork)", "Reads YouTube’s pages", Licence.Gpl3OrLater),
    LicensedPart("LibreTube player code", "Streams the video", Licence.Gpl3OrLater),
    LicensedPart("AndroidX, Jetpack Compose, Media3, Guava", "Screens and the player", Licence.Apache2),
    LicensedPart("OkHttp, Okio, Coil, Accompanist, Kotlin libraries, nanojson", "Network, images, code", Licence.Apache2),
    LicensedPart("JSR 305, JSpecify, JetBrains annotations", "Code annotations", Licence.Apache2),
    LicensedPart("Rhino", "Runs YouTube’s player script", Licence.Mpl2Rhino),
    LicensedPart("Public Suffix List (in OkHttp)", "Web address rules", Licence.Mpl2),
    LicensedPart("yt-dlp EJS", "Plays made-for-kids videos", Licence.Unlicense),
    LicensedPart("meriyah (in EJS)", "Reads the player script", Licence.IscMeriyah),
    LicensedPart("astring (in EJS)", "Rewrites the player script", Licence.MitAstring),
    LicensedPart("BgUtils (in the token page)", "Playback tokens", Licence.MitLuanrt),
    LicensedPart("Protocol Buffers", "The stream’s format", Licence.Bsd3Protobuf),
    LicensedPart("googlevideo schemas", "The stream’s messages", Licence.MitLuanrt),
    LicensedPart("jsoup", "Video descriptions", Licence.MitJsoup),
    LicensedPart("RE2/J", "Title patterns in filters", Licence.Bsd3Re2j),
    LicensedPart("QR Code generator", "Sign-in codes on the TV", Licence.MitQrcodegen),
    LicensedPart("Archivo", "The typeface", Licence.OflArchivo),
)

// A line of only `=` or `-` underlines the heading above it (MPL's plain-text layout).
private val UNDERLINE = Regex("[=-]+")
private val BULLET = Regex("\\*\\s.*")
private val SPACES = Regex("\\s+")

// MPL boxes sections 6 and 7 in asterisks: a border line, then `*  text  *` lines.
private val BOX_BORDER = Regex("\\*{3,}")
private val BOXED = Regex("\\*\\s(.*?)\\s*\\*")

/** [line] (trimmed) without a box's asterisks: a border reads as a blank line, a boxed line as its text. */
private fun unboxed(line: String): String = when {
    BOX_BORDER.matches(line) -> ""
    else -> BOXED.matchEntire(line)?.groupValues?.get(1)?.trim() ?: line
}

/**
 * A licence's [text] as the sheet shows it: its words unchanged, its hard-wrapped lines joined
 * into paragraphs (a blank line ends one, a `*` bullet starts one); heading underlines and
 * asterisk boxes go.
 */
fun licenceParagraphs(text: String): List<String> {
    val paragraphs = mutableListOf<String>()
    val current = StringBuilder()
    fun end() {
        if (current.isNotEmpty()) paragraphs += current.toString()
        current.clear()
    }
    text.lines().map { unboxed(it.trim()) }.forEach { line ->
        when {
            line.isEmpty() -> end()
            UNDERLINE.matches(line) -> Unit
            else -> {
                if (BULLET.matches(line)) end()
                if (current.isNotEmpty()) current.append(' ')
                current.append(line.replace(SPACES, " "))
            }
        }
    }
    end()
    return paragraphs
}

/** A licence's text as the sheet shows it: its [title]'s lines (`.lhead`; empty when it has none), then its [paragraphs]. */
class LicenceText(val title: List<String>, val paragraphs: List<String>)

/**
 * [text] split as [LicenceText]: the opening block is the title when the plain text marks it as
 * one, centred (every line indented: GPL, Apache) or underlined (MPL); a text that opens with its
 * copyright line has none.
 */
fun licenceText(text: String): LicenceText {
    val paragraphs = licenceParagraphs(text)
    val opening = text.lines().dropWhile(String::isBlank).takeWhile(String::isNotBlank)
    val centred = opening.all { it.first().isWhitespace() }
    val underlined = opening.size == 2 && UNDERLINE.matches(opening[1].trim())
    if (opening.isEmpty() || !(centred || underlined)) return LicenceText(emptyList(), paragraphs)
    val title = opening.map { it.trim() }.filterNot { UNDERLINE.matches(it) }.map { it.replace(SPACES, " ") }
    return LicenceText(title, paragraphs.drop(1))
}

/** A licence name kept on one line (`‑` doesn't break). */
fun unbroken(licence: String): String = licence.replace('-', '\u2011')
