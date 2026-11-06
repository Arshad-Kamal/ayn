package com.arshad.ayn.core.store

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The last-used profile id is shared across instances and reads `null` until one is set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesLastUsedProfileTest {
    private fun newStore() = PreferencesLastUsedProfile(RuntimeEnvironment.getApplication(), UnconfinedTestDispatcher())

    @Test
    fun nothingIsKeptBeforeAProfileOpens() = runTest {
        assertNull(newStore().get())
    }

    @Test
    fun theLastIdSetIsWhatTheNextStartReads() = runTest {
        newStore().set(7)
        newStore().set(3)

        assertEquals(3L, newStore().get())
    }
}
