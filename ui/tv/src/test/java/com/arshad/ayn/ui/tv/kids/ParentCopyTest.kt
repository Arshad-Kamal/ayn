package com.arshad.ayn.ui.tv.kids

import com.arshad.ayn.core.lock.LockKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Parent page's lock copy (`kp-kids` parentgate). */
class ParentCopyTest {

    @Test
    fun theGateNamesTheOwnersLockAndWhereBackGoes() {
        val copy = parentGateCopy("Zainab", "Arshad", LockKind.Sequence)

        assertEquals("The Parent page needs Arshad’s lock. Enter Arshad’s sequence, then press OK.", copy.line)
        assertEquals("BACK returns to Zainab’s Home", copy.hint)
        assertEquals("The Parent page needs Arshad’s lock. Enter Arshad’s PIN.", parentGateCopy("Zainab", "Arshad", LockKind.Pin).line)
    }
}
