package com.arshad.ayn.core.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A kids profile's local search match (KP-D10): word starts, folded as keywords are. */
class TitleMatchTest {

    @Test
    fun eachQueryWordMustStartAWordOfTheTitle() {
        assertTrue(titleMatchesQuery("dino", "Dinosaurs for Kids | Learn Dinosaur Names"))
        assertTrue(titleMatchesQuery("baby shark", "Baby Shark Dance | Sing and Dance!"))
        assertFalse(titleMatchesQuery("baby truck", "Baby Shark Dance | Sing and Dance!"))
        assertFalse("inside a word is no start", titleMatchesQuery("saur", "Dinosaurs for Kids"))
    }

    @Test
    fun caseFormsAndQuotesFoldAsKeywordsDo() {
        assertTrue(titleMatchesQuery("PEPPA", "peppa pig"))
        assertTrue(titleMatchesQuery("ｂｌｕｅｙ", "Bluey Full Episodes"))
        assertTrue(titleMatchesQuery("bingo's", "Bingo\u2019s Birthday"))
    }

    @Test
    fun aQueryWithNoLettersOrDigitsMatchesNothing() {
        assertFalse(titleMatchesQuery("  !? ", "Anything at all"))
    }
}
