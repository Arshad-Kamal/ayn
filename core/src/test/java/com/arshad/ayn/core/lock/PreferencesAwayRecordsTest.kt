package com.arshad.ayn.core.lock

import com.arshad.ayn.core.store.PreferencesLastUsedProfile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The away record is shared across instances, clears, and leaves the last-used profile it
 *  shares the `device` preferences with alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesAwayRecordsTest {
    private fun newStore() = PreferencesAwayRecords(RuntimeEnvironment.getApplication(), UnconfinedTestDispatcher())

    @Test
    fun nothingIsKeptBeforeKeIsLeft() = runTest {
        assertNull(newStore().read())
    }

    @Test
    fun theLastRecordWrittenIsWhatAnotherInstanceReads() = runTest {
        newStore().write(AwayRecord(7, 1_000.milliseconds))
        newStore().write(AwayRecord(3, 123_456_789.milliseconds))

        assertEquals(AwayRecord(3, 123_456_789.milliseconds), newStore().read())
    }

    @Test
    fun leavingWithNoShellOpenClearsItAndKeepsTheLastUsedProfile() = runTest {
        val lastUsed = PreferencesLastUsedProfile(RuntimeEnvironment.getApplication(), UnconfinedTestDispatcher())
        lastUsed.set(5)
        newStore().write(AwayRecord(7, 1_000.milliseconds))

        newStore().write(null)

        assertNull(newStore().read())
        assertEquals(5L, lastUsed.get())
    }
}
