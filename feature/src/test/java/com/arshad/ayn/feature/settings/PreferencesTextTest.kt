package com.arshad.ayn.feature.settings

import com.arshad.ayn.feature.R
import java.io.File
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preference pages' text that is computed: language lists, licence names and texts. */
class PreferencesTextTest {

    @Test
    fun allLanguagesAreNamedOnceInAlphabeticalOrder() {
        val languages = allLanguages()
        val names = languages.map { it.name }

        assertEquals(names.sortedWith(Collator.getInstance(Locale.ENGLISH)), names)
        assertEquals(languages.size, languages.map { it.tag }.toSet().size)
        assertTrue(languages.none { it.tag in setOf("iw", "in", "ji") || it.name == it.tag })
        assertTrue(languages.any { it.tag == "he" && it.name == "Hebrew" })
    }

    @Test
    fun licenceNamesDoNotBreakAtTheirHyphens() {
        assertEquals("GPL\u20113.0\u2011or\u2011later", unbroken("GPL-3.0-or-later"))
    }

    @Test
    fun aLicenceReflowsIntoParagraphsWithItsWordsUnchanged() {
        val text = "Title\n=====\n\n  A wrapped\n    line.\n\n    * First,\n  continued.\n    * Second.\n"

        assertEquals(listOf("Title", "A wrapped line.", "* First, continued.", "* Second."), licenceParagraphs(text))
    }

    @Test
    fun aBoxedSectionReadsAsItsHeadingAndOneParagraph() {
        val text = "****\n*        *\n*  6. Heading  *\n*  ----------  *\n*        *\n*  Boxed and   *\n*  wrapped.    *\n*        *\n****\n"

        assertEquals(listOf("6. Heading", "Boxed and wrapped."), licenceParagraphs(text))
    }

    @Test
    fun everyShippedLicenceKeepsItsWordsAndLosesOnlyItsLayout() {
        val layout = Regex("[*=-]+")
        Licence.entries.forEach { licence ->
            val text = File("src/main/res/raw/${rawName(licence)}.txt").readText()
            val paragraphs = licenceParagraphs(text)

            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() && !layout.matches(it) }
            assertEquals(licence.name, words, paragraphs.flatMap { it.split(' ') }.filter { !layout.matches(it) })
            assertTrue(licence.name, paragraphs.none { it.startsWith("*") && it.endsWith("*") })
        }
        val mpl = licenceParagraphs(File("src/main/res/raw/licence_mpl_2_0.txt").readText())
        assertTrue(mpl.any { it.startsWith("Covered Software is provided under this License") && it.endsWith("except under this disclaimer.") })
    }

    @Test
    fun aLicencesTitleIsItsCentredOrUnderlinedOpeningOnly() {
        val titles = mapOf(
            Licence.Gpl3OrLater to listOf("GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007"),
            Licence.Apache2 to listOf("Apache License", "Version 2.0, January 2004", "http://www.apache.org/licenses/"),
            Licence.Mpl2 to listOf("Mozilla Public License Version 2.0"),
            Licence.Mpl2Rhino to listOf("Mozilla Public License Version 2.0"),
            Licence.Bsd3Protobuf to emptyList(),
            Licence.Bsd3Re2j to emptyList(),
            Licence.MitJsoup to emptyList(),
            Licence.Unlicense to emptyList(),
            Licence.IscMeriyah to emptyList(),
            Licence.MitAstring to emptyList(),
            Licence.MitQrcodegen to emptyList(),
            Licence.MitLuanrt to emptyList(),
            Licence.OflArchivo to emptyList(),
        )
        assertEquals(Licence.entries.toSet(), titles.keys)
        titles.forEach { (licence, title) ->
            val text = File("src/main/res/raw/${rawName(licence)}.txt").readText()
            val split = licenceText(text)

            assertEquals(licence.name, title, split.title)
            assertEquals(licence.name, licenceParagraphs(text).drop(if (title.isEmpty()) 0 else 1), split.paragraphs)
        }
    }

    private fun rawName(licence: Licence): String = when (licence) {
        Licence.Gpl3OrLater -> "licence_gpl_3_0"
        Licence.Apache2 -> "licence_apache_2_0"
        Licence.Mpl2 -> "licence_mpl_2_0"
        Licence.Mpl2Rhino -> "licence_mpl_2_0_rhino"
        Licence.Bsd3Protobuf -> "licence_bsd_3_clause_protobuf"
        Licence.Bsd3Re2j -> "licence_bsd_3_clause_re2j"
        Licence.MitJsoup -> "licence_mit_jsoup"
        Licence.Unlicense -> "licence_unlicense"
        Licence.IscMeriyah -> "licence_isc_meriyah"
        Licence.MitAstring -> "licence_mit_astring"
        Licence.MitQrcodegen -> "licence_mit_qrcodegen"
        Licence.MitLuanrt -> "licence_mit_luanrt"
        Licence.OflArchivo -> "licence_ofl_1_1_archivo"
    }

    @Test
    fun eachLicenceLoadsTheFileItsTestNames() {
        Licence.entries.forEach { licence ->
            assertEquals(licence.name, R.raw::class.java.getField(rawName(licence)).getInt(null), licence.text)
        }
    }

    @Test
    fun eachPartOpensTheTextThatNamesItsAuthors() {
        val holders = mapOf(
            "Rhino" to "Raffaello Giulietti",
            "meriyah (in EJS)" to "KFlash",
            "astring (in EJS)" to "David Bonnet",
            "BgUtils (in the token page)" to "LuanRT",
            "Protocol Buffers" to "Copyright 2008 Google Inc.",
            "googlevideo schemas" to "LuanRT",
            "jsoup" to "Jonathan Hedley",
            "RE2/J" to "The Go Authors",
            "QR Code generator" to "Project Nayuki",
            "Archivo" to "The Archivo Project Authors",
        )
        holders.forEach { (name, holder) ->
            val part = LICENSED_PARTS.single { it.name == name }
            val text = File("src/main/res/raw/${rawName(part.licence)}.txt").readText()
            assertTrue(name, text.contains(holder))
        }
    }

    @Test
    fun rhinosTextIsTheMplThenItsNoticeForTheCodeItBundles() {
        val mpl = File("src/main/res/raw/licence_mpl_2_0.txt").readText()
        val rhino = File("src/main/res/raw/licence_mpl_2_0_rhino.txt").readText()

        assertTrue(rhino.startsWith(mpl))
        assertTrue(rhino.contains("Copyright 2010-2015 the V8 project authors."))
        assertTrue(rhino.contains("Copyright 2018-2020 Raffaello Giulietti"))
    }

    @Test
    fun theShippedGplTextIsAynsLicenceFile() {
        // Gradle runs unit tests in the module's directory.
        assertArrayEquals(File("../LICENSE").readBytes(), File("src/main/res/raw/licence_gpl_3_0.txt").readBytes())
    }
}
