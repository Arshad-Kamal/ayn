package com.arshad.ayn.core.store

import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keeps per kids profile, shared across instances and forgotten after two weeks (Q17). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesKidsReviewKeepsTest {
    private var now = 1_000_000_000_000L

    private fun newStore() = PreferencesKidsReviewKeeps(RuntimeEnvironment.getApplication(), { now }, UnconfinedTestDispatcher())

    @Test
    fun keepsAreHeldPerProfileAcrossInstances() = runTest {
        assertTrue(newStore().keep(OwnerPass(), 4, "aaaaaaaaaa1"))
        newStore().keep(OwnerPass(), 4, "aaaaaaaaaa2")
        newStore().keep(OwnerPass(), 5, "bbbbbbbbbb1")

        assertEquals(setOf("aaaaaaaaaa1", "aaaaaaaaaa2"), newStore().kept(4))
        assertEquals(setOf("bbbbbbbbbb1"), newStore().kept(5))
    }

    @Test
    fun aKeepIsForgottenAfterFourteenDaysAndKeepingAgainRestartsIt() = runTest {
        newStore().keep(OwnerPass(), 4, "aaaaaaaaaa1")
        newStore().keep(OwnerPass(), 4, "aaaaaaaaaa2")
        now += 10 * DAY
        newStore().keep(OwnerPass(), 4, "aaaaaaaaaa2")

        now += 5 * DAY

        assertEquals(setOf("aaaaaaaaaa2"), newStore().kept(4))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
