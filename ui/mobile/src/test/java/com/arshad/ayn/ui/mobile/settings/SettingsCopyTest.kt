package com.arshad.ayn.ui.mobile.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SegmentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The index's lines (state 1; Q53: undrawn cases follow the drawn pattern, nothing to say is no line). */
class SettingsCopyTest {

    @Test
    fun profilesSayHowManyForTheOwnerAndWhoOwnsAynForAnyoneElse() {
        assertEquals("4 profiles · you’re the owner", profilesSummary(owner = true, profiles = 4, ownerName = "Arshad"))
        assertEquals("1 profile · you’re the owner", profilesSummary(owner = true, profiles = 1, ownerName = "Arshad"))
        assertEquals("Arshad is the owner", profilesSummary(owner = false, profiles = 1, ownerName = "Arshad"))
        assertNull(profilesSummary(owner = false, profiles = 1, ownerName = null))
    }

    @Test
    fun filtersNameRestrictedModeAndKeywordsOnlyWhenThereAreAny() {
        assertEquals("Restricted Mode off", filtersSummary(ProfileSettings(), keywords = 0))
        assertEquals("Restricted Mode strict · 1 keyword", filtersSummary(ProfileSettings(restrictedMode = RestrictedMode.Strict), keywords = 1))
    }

    @Test
    fun playbackNamesSpeedCaptionsAndBackgroundAudio() {
        assertEquals("1.5× speed · English captions · background audio off", playbackSummary(ProfileSettings(speedPercent = 150, captionsLanguage = "en"), DeviceSettings(backgroundAudio = false)))
    }

    @Test
    fun sponsorBlockSaysWhatSponsorsGet() {
        assertEquals("Off", sponsorBlockSummary(ProfileSettings(sponsorBlock = false)))
        val actions = SegmentAction.entries.map { sponsorBlockSummary(ProfileSettings().let { base -> base.copy(segmentActions = base.segmentActions + (SegmentCategory.Sponsor to it)) }) }
        assertEquals(
            listOf("On · sponsors are skipped", "On · sponsors get a skip button", "On · sponsors show on the seek bar", "On · sponsors aren’t shown"),
            actions,
        )
    }

    @Test
    fun deArrowDislikesAndInterfaceNameWhatIsOn() {
        assertEquals("Titles", deArrowSummary(ProfileSettings(dearrowThumbnails = false)))
        assertEquals("Off", deArrowSummary(ProfileSettings(dearrowTitles = false, dearrowThumbnails = false)))
        assertEquals("Off", dislikesSummary(ProfileSettings(dislikes = false)))
        assertEquals("Shorts on · For you off", interfaceSummary(ProfileSettings(showShorts = true, forYou = false), DeviceSettings(heroOnHome = false)))
    }

    @Test
    fun noBackupsIsNoLine() {
        assertNull(backupSummary(0))
        assertEquals("1 backup on this phone", backupSummary(1))
    }
}
