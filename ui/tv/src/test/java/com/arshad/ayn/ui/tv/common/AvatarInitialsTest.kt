package com.arshad.ayn.ui.tv.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** A monogram's initials: words that don't start with a letter or digit are passed over. */
class AvatarInitialsTest {

    @Test
    fun twoWordsGiveTwoInitials() {
        assertEquals("LT", initials("Linus Tech Tips"))
    }

    @Test
    fun aWordStartingWithPunctuationIsSkipped() {
        assertEquals("OH", initials("Omar & Hana"))
        assertEquals("O", initials("One4kids (Zaky)"))
    }

    @Test
    fun aNameWithoutALetteredWordKeepsItsFirstMarks() {
        assertEquals("&", initials("&"))
    }
}
