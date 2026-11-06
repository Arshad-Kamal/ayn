package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.lock.OwnerPass
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Kids watch time per local day over a real in-memory Room 3 database (KP-D8, D18). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KidsTimeRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val profiles = ProfileRepository(database)
    private val store = KidsTimeRepository(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun usedTimeAddsUpPerDayAndMoreTimeIsKeptApart() = scope.runTest {
        val kid = kid()

        store.addUsed(kid, SATURDAY, 600)
        store.addUsed(kid, SATURDAY, 120)
        store.addMoreTime(OwnerPass(), kid, SATURDAY, 15)
        store.addUsed(kid, FRIDAY, 60)

        assertEquals(
            mapOf(
                FRIDAY to DayTime(usedSeconds = 60),
                SATURDAY to DayTime(usedSeconds = 720, moreTimeMinutes = 15, allowedUntilSeconds = 720 + 15 * 60),
            ),
            store.days(kid, FRIDAY, SATURDAY).first(),
        )
    }

    /** KP-D22: More time counts from now; a grant still running is extended, a spent one isn't. */
    @Test
    fun moreTimeRunsFromWhatIsUsedOrFromTheRunningGrantsEnd() = scope.runTest {
        val kid = kid()
        store.addUsed(kid, SATURDAY, 3_000)
        store.addMoreTime(OwnerPass(), kid, SATURDAY, 15) // until 3_900
        store.addMoreTime(OwnerPass(), kid, SATURDAY, 15) // still running: until 4_800
        store.addUsed(kid, SATURDAY, 2_000) // 5_000 used: the grant is spent

        store.addMoreTime(OwnerPass(), kid, SATURDAY, 30)

        assertEquals(
            DayTime(usedSeconds = 5_000, moreTimeMinutes = 60, allowedUntilSeconds = 5_000 + 30 * 60),
            store.days(kid, SATURDAY, SATURDAY).first()[SATURDAY],
        )
    }

    @Test
    fun daysOutsideTheAskedRangeAreLeftOut() = scope.runTest {
        val kid = kid()
        store.addUsed(kid, FRIDAY, 60)
        store.addUsed(kid, SATURDAY, 60)

        assertEquals(setOf(SATURDAY), store.days(kid, SATURDAY, SATURDAY.plusDays(6)).first().keys)
    }

    @Test
    fun countingADayDropsDaysOlderThanTheTwoWeeksKept() = scope.runTest {
        val kid = kid()
        val oldest = SATURDAY.minusDays(KEPT_DAYS - 1)
        store.addUsed(kid, oldest.minusDays(1), 60)
        store.addUsed(kid, oldest, 60)

        store.addUsed(kid, SATURDAY, 60)

        assertEquals(setOf(oldest, SATURDAY), store.days(kid, oldest.minusDays(7), SATURDAY).first().keys)
    }

    @Test
    fun moreTimeOutOfRangeIsRefusedAndNothingIsWritten() = scope.runTest {
        val kid = kid()

        assertFalse(store.addMoreTime(OwnerPass(), kid, SATURDAY, 0))
        assertFalse(store.addMoreTime(OwnerPass(), kid, SATURDAY, MINUTES_PER_DAY + 1))
        assertEquals(emptyMap<LocalDate, DayTime>(), store.days(kid, SATURDAY, SATURDAY).first())
    }

    @Test
    fun aWriteForARemovedProfileFailsAndRemovingItTakesItsTime() = scope.runTest {
        val kid = kid()
        store.addUsed(kid, SATURDAY, 60)

        assertTrue(profiles.remove(OwnerPass(), kid))

        assertEquals(emptyMap<LocalDate, DayTime>(), store.days(kid, SATURDAY, SATURDAY).first())
        assertFalse(store.addUsed(kid, SATURDAY, 60))
    }

    private suspend fun kid(): Long {
        val owner = profiles.ensureOwner()
        database.profileDao().setLock(owner.id, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        return (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
    }
}

private val SATURDAY: LocalDate = LocalDate.of(2026, 10, 10)
private val FRIDAY: LocalDate = SATURDAY.minusDays(1)
