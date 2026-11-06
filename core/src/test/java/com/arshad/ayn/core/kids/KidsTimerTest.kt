package com.arshad.ayn.core.kids

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.FilterRepository
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.KidsTimeRepository
import com.arshad.ayn.core.store.KidsTimeStore
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.ensureOwner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/** The kids timer over a real in-memory Room 3 database (KP-D8, D22); ticks are driven by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KidsTimerTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val profiles = ProfileRepository(database)
    private val filters = FilterRepository(database)
    private val ledger = KidsTimeRepository(database)
    private val session = ProfileSession()
    private val source = TestTimeSource()
    private val origin = source.markNow()
    private val clock = ServerClock(source)
    private var playbackEnds = 0
    private val timer = KidsTimer(
        session = session,
        filters = filters,
        ledger = ledger,
        clock = clock,
        elapsed = { origin.elapsedNow() },
        endPlayback = { playbackEnds++ },
        zone = { ZoneOffset.UTC },
    )

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun activeTimeIsCountedUntilTheLimitWhichEndsPlaybackOnce() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 30)))
        ledger.addUsed(kid, SATURDAY, 29 * 60L)

        repeat(4) { activeTick() }
        assertEquals(KidsTime.TimesUp, timer.time.value)
        assertEquals(1, playbackEnds)

        activeTick()
        assertEquals("time up counts nothing more", 30 * 60L, usedToday(kid))
        assertEquals(1, playbackEnds)
    }

    @Test
    fun idleTimeStopsCountingFiveMinutesAfterTheLastKey() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60)))
        timer.onUserInteraction()

        repeat(24) { tick() } // 6 minutes, no key

        assertEquals(5 * 60L, usedToday(kid))
    }

    @Test
    fun playbackCountsWithoutKeys() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60)))
        timer.onPlayingChanged(true)

        repeat(24) { tick() }

        assertEquals(6 * 60L, usedToday(kid))
        assertEquals(KidsTime.Left(54.minutes), timer.time.value)
    }

    /** The device slept between ticks: at most two ticks' worth counts. */
    @Test
    fun aLongGapBetweenTicksCountsAtMostTwoTicks() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60)))

        source += 10.minutes
        timer.onUserInteraction()
        timer.tick()

        assertEquals(30L, usedToday(kid))
    }

    @Test
    fun beforeTheClockIsKnownTheTimeIsUnknownAndItsCountWaits() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60)), knownClock = false)

        repeat(4) { activeTick() }
        assertEquals(KidsTime.Unknown, timer.time.value)
        assertEquals(KidsTime.Unknown, timer.current())
        assertEquals(emptyMap<LocalDate, DayTime>(), ledger.days(kid, SATURDAY, SATURDAY).first())

        clock.record(NOON)
        activeTick()

        assertEquals(75L, usedToday(kid))
    }

    @Test
    fun warningsComeAtFiveAndOneMinuteAndStayUntilConsumed() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 30)))
        ledger.addUsed(kid, SATURDAY, 24 * 60L) // 6 minutes left

        repeat(3) { activeTick() }
        assertNull(timer.warning.value)
        activeTick()
        assertEquals(5, timer.warning.value)
        timer.consumeWarning()
        assertNull(timer.warning.value)

        repeat(15) { activeTick() }
        assertNull(timer.warning.value)
        activeTick()
        assertEquals(1, timer.warning.value)
    }

    @Test
    fun moreTimeLiftsTimesUpAtOnce() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 30)))
        ledger.addUsed(kid, SATURDAY, 30 * 60L)
        timer.tick()
        assertEquals(KidsTime.TimesUp, timer.time.value)

        assertTrue(timer.addMoreTime(OwnerPass(), 15))

        assertEquals(KidsTime.Left(15.minutes), timer.time.value)
        assertEquals(KidsTime.Left(15.minutes), timer.current())
    }

    @Test
    fun tomorrowsLimitIsTheNextWeekdays() = scope.runTest {
        kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 30, DayOfWeek.SUNDAY to 90)))

        assertEquals(90, timer.limitTomorrow())
    }

    @Test
    fun noLimitTomorrowOrNoClockReadsNone() = scope.runTest {
        kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 30)), knownClock = false)
        assertNull("no clock", timer.limitTomorrow())

        clock.record(NOON)
        assertNull("no Sunday limit", timer.limitTomorrow())
    }

    @Test
    fun bedtimeStopsAndSaysWhenItEnds() = scope.runTest {
        kid(ProfileRestrictions(bedtime = Bedtime(startMinute = 19 * 60 + 30, endMinute = 7 * 60)))
        clock.record(Instant.parse("2026-10-10T20:00:00Z"))

        timer.tick()

        assertEquals(KidsTime.Bedtime(LocalTime.of(7, 0)), timer.time.value)
        assertEquals(1, playbackEnds)
    }

    @Test
    fun theStretchBeforeASwitchIsntTheNewProfiles() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 60)))
        session.select(profiles.ensureOwner().id)
        timer.tick()
        timer.onUserInteraction()
        source += KidsTimer.TICK_INTERVAL

        session.select(kid)
        timer.tick()

        assertEquals(0L, usedToday(kid))
    }

    @Test
    fun timeTheStoreCouldntWriteStillCountsTowardTheLimit() = scope.runTest {
        val kid = kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 1)))
        val unwritable = object : KidsTimeStore by ledger {
            override suspend fun addUsed(profileId: Long, day: LocalDate, seconds: Long): Boolean = false
        }
        val failing = KidsTimer(session, filters, unwritable, clock, { origin.elapsedNow() }, { playbackEnds++ }, { ZoneOffset.UTC })
        failing.tick()

        repeat(4) {
            failing.onUserInteraction()
            source += KidsTimer.TICK_INTERVAL
            failing.tick()
        }

        assertEquals(KidsTime.TimesUp, failing.time.value)
        assertEquals(0L, usedToday(kid))
    }

    @Test
    fun anotherProfileIsUnlimitedAndCountsNothing() = scope.runTest {
        kid(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SATURDAY to 0)))
        val endsBefore = playbackEnds
        val owner = profiles.ensureOwner().id
        session.select(owner)

        repeat(4) { activeTick() }

        assertEquals(KidsTime.Unlimited, timer.time.value)
        assertEquals(KidsTime.Unlimited, timer.current())
        assertEquals(emptyMap<LocalDate, DayTime>(), ledger.days(owner, SATURDAY, SATURDAY).first())
        assertEquals(endsBefore, playbackEnds)
    }

    private suspend fun activeTick() {
        timer.onUserInteraction()
        tick()
    }

    private suspend fun tick(step: Duration = KidsTimer.TICK_INTERVAL) {
        source += step
        timer.tick()
    }

    private suspend fun usedToday(kid: Long): Long = ledger.days(kid, SATURDAY, SATURDAY).first()[SATURDAY]?.usedSeconds ?: 0

    /** A kids profile with [restrictions], open in the session; the clock reads noon Saturday. */
    private suspend fun kid(restrictions: ProfileRestrictions, knownClock: Boolean = true): Long {
        val owner = profiles.ensureOwner()
        database.profileDao().setLock(owner.id, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
        filters.updateRestrictions(OwnerPass(), kid) { restrictions }
        if (knownClock) clock.record(NOON)
        session.select(kid)
        timer.tick() // as run() does on a profile switch: the stretch before it isn't the kid's
        return kid
    }

    private companion object {
        val SATURDAY: LocalDate = LocalDate.of(2026, 10, 10)
        val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")
    }
}
