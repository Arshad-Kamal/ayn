package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.SegmentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The captions language contract (tag-shaped values pass, anything else reads as Off and can't
 *  be written) and which segment categories the player fetches. */
class ProfileSettingsTest {

    @Test
    fun languageTagsYouTubeUsesAreKept() {
        listOf("en", "pt-BR", "es-419", "zh-Hans", "fil").forEach { assertEquals(it, captionsLanguageOrOff(it)) }
    }

    @Test
    fun aValueThatIsNotATagReadsAsOff() {
        listOf("en_US", "not a tag", "e", "", "en-").forEach { assertNull(it, captionsLanguageOrOff(it)) }
    }

    @Test
    fun aTagShapedValueOverThirtyFiveCharactersReadsAsOff() {
        val long = "en-aaaaaaaa-bbbbbbbb-cccccccc-dddddddd"

        assertEquals(38, long.length)
        assertNull(captionsLanguageOrOff(long))
    }

    @Test(expected = IllegalArgumentException::class)
    fun settingsCantHoldACaptionsLanguageThatWouldReadAsOff() {
        ProfileSettings(captionsLanguage = "en_US")
    }

    @Test
    fun everyCategoryNotOffIsFetched() {
        val defaults = ProfileSettings()

        assertEquals(SegmentCategory.entries.toSet() - SegmentCategory.Filler, defaults.segmentCategoriesToFetch())
    }

    @Test
    fun nothingIsFetchedWithSponsorBlockOff() {
        assertEquals(emptySet<SegmentCategory>(), ProfileSettings(sponsorBlock = false).segmentCategoriesToFetch())
    }

    @Test
    fun aCategorySetOffIsNotFetched() {
        val sponsorOff = ProfileSettings().let { it.copy(segmentActions = it.segmentActions + (SegmentCategory.Sponsor to SegmentAction.Off)) }

        assertEquals(
            SegmentCategory.entries.toSet() - SegmentCategory.Filler - SegmentCategory.Sponsor,
            sponsorOff.segmentCategoriesToFetch(),
        )
    }

    @Test
    fun nothingIsFetchedWithEveryCategoryOff() {
        val allOff = ProfileSettings(segmentActions = SegmentCategory.entries.associateWith { SegmentAction.Off })

        assertEquals(emptySet<SegmentCategory>(), allOff.segmentCategoriesToFetch())
    }
}
