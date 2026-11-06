package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import kotlin.time.Clock
import kotlin.time.Instant

/** [FetchAttemptLog] over Room; each record drops the attempts [FetchAttemptLog.KEPT] old or older. */
class FetchAttemptRepository(
    private val database: KeDatabase,
    private val clock: Clock = Clock.System,
) : FetchAttemptLog {
    private val dao: FetchAttemptDao = database.fetchAttemptDao()

    override suspend fun record(outcome: FetchOutcome) {
        database.withWriteTransaction {
            val now = clock.now()
            dao.insert(FetchAttemptEntity(atMillis = now.toEpochMilliseconds(), outcome = outcome.key))
            dao.deleteThrough((now - FetchAttemptLog.KEPT).toEpochMilliseconds())
        }
    }

    /** An outcome this build doesn't know (a later build's) reads as [FetchOutcome.Failed]: it still counts. */
    override suspend fun since(start: Instant): List<FetchAttempt> =
        dao.since(start.toEpochMilliseconds()).map { row ->
            FetchAttempt(
                at = Instant.fromEpochMilliseconds(row.atMillis),
                outcome = FetchOutcome.entries.firstOrNull { it.key == row.outcome } ?: FetchOutcome.Failed,
            )
        }
}
