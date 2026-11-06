package com.arshad.ayn.ui.mobile.kids

import com.arshad.ayn.core.lock.LockKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Parent page's lock (`m6-kids` 13): the lock's words, where Back goes. */
class ParentGateCopyTest {

    @Test
    fun aSequenceIsTappedThenOkAndBackReturnsToTheKidsHome() {
        val copy = parentGateCopy("Zainab", "Arshad", LockKind.Sequence)

        assertEquals("Parent", copy.label)
        assertEquals("The Parent page needs Arshad’s lock. Enter Arshad’s sequence, then tap OK.", copy.line)
        assertEquals("⌫ erases a press · Back returns to Zainab’s Home", copy.hint)
        assertEquals(", or go back to Zainab’s Home.", copy.waitTail)
    }
}
