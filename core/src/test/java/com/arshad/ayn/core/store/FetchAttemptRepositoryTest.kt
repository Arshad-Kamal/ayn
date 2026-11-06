package com.arshad.ayn.core.store

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * For you's device-wide request log (FY-D5) on a database file, so a restart is a real reopen:
 * attempts survive it, order and outcomes read back, and a record drops what is a day old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FetchAttemptRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clock = MutableClock(T0)
    private var database: KeDatabase = open()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun attemptsSurviveARestart() = runTest {
        val log = FetchAttemptRepository(database, clock)
        log.record(FetchOutcome.Fetched)
        clock.nowMillis = T0 + 1_000
        log.record(FetchOutcome.Failed)
        database.close()

        database = open()

        assertEquals(
            listOf(FetchAttempt(at(T0), FetchOutcome.Fetched), FetchAttempt(at(T0 + 1_000), FetchOutcome.Failed)),
            FetchAttemptRepository(database, clock).since(at(T0)),
        )
    }

    @Test
    fun sinceCountsFromItsStartInclusive() = runTest {
        val log = FetchAttemptRepository(database, clock)
        listOf(FetchOutcome.Fetched, FetchOutcome.RateLimited, FetchOutcome.Failed).forEachIndexed { i, outcome ->
            clock.nowMillis = T0 + i * 1_000L
            log.record(outcome)
        }

        assertEquals(
            listOf(FetchAttempt(at(T0 + 1_000), FetchOutcome.RateLimited), FetchAttempt(at(T0 + 2_000), FetchOutcome.Failed)),
            log.since(at(T0 + 1_000)),
        )
    }

    @Test
    fun aRecordDropsAttemptsADayOld() = runTest {
        val log = FetchAttemptRepository(database, clock)
        log.record(FetchOutcome.Fetched)
        clock.nowMillis = T0 + 1
        log.record(FetchOutcome.Fetched)
        val dayLater = at(T0) + 1.days

        clock.nowMillis = dayLater.toEpochMilliseconds()
        log.record(FetchOutcome.Failed)

        assertEquals(
            listOf(FetchAttempt(at(T0 + 1), FetchOutcome.Fetched), FetchAttempt(dayLater, FetchOutcome.Failed)),
            log.since(at(0)),
        )
    }

    @Test
    fun anOutcomeThisBuildDoesntKnowCountsAsAFailure() = runTest {
        database.fetchAttemptDao().insert(FetchAttemptEntity(atMillis = T0, outcome = "blocked"))

        assertEquals(listOf(FetchAttempt(at(T0), FetchOutcome.Failed)), FetchAttemptRepository(database, clock).since(at(T0) - 1.milliseconds))
    }

    private fun open(): KeDatabase =
        Room.databaseBuilder<KeDatabase>(context, DATABASE)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*KeMigrations.all)
            .build()

    private companion object {
        const val DATABASE = "fetch-attempts-test.db"
        const val T0 = 1_700_000_000_000L

        fun at(millis: Long): Instant = Instant.fromEpochMilliseconds(millis)
    }
}
