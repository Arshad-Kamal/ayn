package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Filters and restrictions values and lines (`kp-filters` v2). */
class FiltersTextTest {

    @Test
    fun lengthsReadAsTheSheetNamesThem() {
        assertEquals(listOf("1 min", "60 min", "2 hours"), listOf(1, 60, 120).map(::minutesLabel))
    }

    @Test
    fun videoLengthSaysWhichBoundsHide() {
        assertEquals("Any length", lengthLabel(LengthLimits()))
        assertEquals("Under 3 min hidden", lengthLabel(LengthLimits(minMinutes = 3)))
        assertEquals("Over 30 min hidden", lengthLabel(LengthLimits(maxMinutes = 30)))
        assertEquals("Under 3 min, over 2 hours hidden", lengthLabel(LengthLimits(3, 120)))
    }

    @Test
    fun countsReadNoneForNothing() {
        assertEquals(listOf("None", "1 word", "4 words"), listOf(0, 1, 4).map(::wordsLabel))
        assertEquals(listOf("None", "3"), listOf(0, 3).map(::countLabel))
    }

    @Test
    fun aWordAlreadyThereIsNamedAsSaved() {
        assertEquals("“prank” is already on the list.", keywordProblemText(RuleProblem.AlreadyThere, " prank "))
    }

    @Test
    fun theRestrictionsHoleNamesEachUnlockedProfileAndTheOwnersAsYours() {
        val owner = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val guest = Profile(2, "Guests", ProfileTint.Teal, isOwner = false, lock = null)

        assertEquals("", restrictionsHole("Mira", emptyList()))
        assertEquals(
            "They hold only while every other profile is locked: Guests has no lock, so Mira could switch to it.",
            restrictionsHole("Mira", listOf(guest)),
        )
        assertEquals(
            "They hold only while every other profile is locked: your profile and Guests have no lock, so Mira could switch to them.",
            restrictionsHole("Mira", listOf(owner, guest)),
        )
    }

    /** KP.15: the blocked row's tail and the Unblock where? sheet (`kp-filters` unblockall). */
    @Test
    fun unblockWhereNamesTheOtherProfiles() {
        val owner = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val mira = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val yuri = Profile(3, "Yuri", ProfileTint.Amber, isOwner = false, lock = null)
        assertEquals("", alsoInTail(emptyList()))
        assertEquals(" · also in Mira and Yuri", alsoInTail(listOf(mira, yuri)))

        val copy = UnblockWhereCopy(BlockedChannel("https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw", "Linus Tech Tips"), owner, listOf(mira, yuri))
        assertEquals("Blocked channels", copy.kicker)
        assertEquals("Unblock Linus Tech Tips?", copy.title)
        assertEquals("You blocked it in Mira and Yuri too.", copy.sub)
        assertEquals("Arshad, Mira and Yuri", copy.everywhereHint)
        assertEquals("Mira and Yuri keep it blocked", copy.hereOnlyHint)
        val video = UnblockWhereCopy(BlockedVideo("EkLmxmUfgBU", null, null), owner, listOf(mira))
        assertEquals("Blocked videos", video.kicker)
        assertEquals("Unblock EkLmxmUfgBU?", video.title)
        assertEquals("Mira keeps it blocked", video.hereOnlyHint)
    }
}
