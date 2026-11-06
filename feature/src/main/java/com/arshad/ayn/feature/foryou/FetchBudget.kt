package com.arshad.ayn.feature.foryou

import android.util.Log
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.store.FetchAttempt
import com.arshad.ayn.core.store.FetchAttemptLog
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.feature.library.attempt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * FY-D5's budget for For you's requests to YouTube, read from the device-wide [log], so failures
 * count and a restart forgets nothing. Every request For you makes (the hero's dressing, its own
 * fetches) asks [allows] first and [record]s its outcome.
 */
class FetchBudget(
    private val log: FetchAttemptLog,
    private val clock: Clock = Clock.System,
) {
    /** Whether For you may make a request now ([allowsAt]); a log that can't be read throws. */
    suspend fun allows(): Boolean {
        val now = clock.now()
        return allowsAt(log.since(now - FetchAttemptLog.KEPT), now)
    }

    /** Saved even when the caller is being cancelled: the request was made. Also logged with the
     *  seam's [failure] when known (no video id: the attempt log keeps none either), so a device
     *  pass can count a session's attempts in logcat and tell a network drop from a throttle. */
    suspend fun record(outcome: FetchOutcome, failure: AccessFailure? = null) {
        Log.i(FOR_YOU_LOG_TAG, "request ${outcome.key}" + failure?.let { " ($it)" }.orEmpty())
        withContext(NonCancellable) { log.record(outcome) }
    }

    /** A request cut off in flight may have reached YouTube: it counts, as a failure (its answer
     *  is unknown). A log that can't be written is logged and dropped. */
    suspend fun recordCutOff() {
        attempt("fetch attempt save") { record(FetchOutcome.Failed) }
    }

    companion object {
        /** Attempts in any [WINDOW]: ≈3 % of the guest limit (FY-D5). */
        const val PER_HOUR = 30

        /** The span [PER_HOUR] counts over. */
        val WINDOW: Duration = 1.hours

        /** Failed attempts in a row that stop For you's requests. */
        const val FAILURES_TO_STOP = 3

        /** How long a rate-limit answer, or [FAILURES_TO_STOP] failures in a row, stop them. */
        val STOP: Duration = 1.hours
    }
}

/** For you's logcat tag: its requests ([FetchBudget.record]) and its rankings' durations. */
internal const val FOR_YOU_LOG_TAG = "KeForYou"

/**
 * FY-D5 on [attempts] (oldest first): fewer than [FetchBudget.PER_HOUR] in the [FetchBudget.WINDOW] before [now],
 * none rate-limited within [FetchBudget.STOP], and the attempts' trailing run of failures (a
 * rate-limit answer is one) shorter than [FetchBudget.FAILURES_TO_STOP] or its last over
 * [FetchBudget.STOP] old. A run only ends with a success, so after a stop one attempt is tried,
 * and its failure stops requests for another [FetchBudget.STOP].
 */
internal fun allowsAt(attempts: List<FetchAttempt>, now: Instant): Boolean {
    if (attempts.count { now - it.at < FetchBudget.WINDOW } >= FetchBudget.PER_HOUR) return false
    if (attempts.any { it.outcome == FetchOutcome.RateLimited && now - it.at < FetchBudget.STOP }) return false
    val failuresInARow = attempts.takeLastWhile { it.outcome != FetchOutcome.Fetched }
    return failuresInARow.size < FetchBudget.FAILURES_TO_STOP || now - failuresInARow.last().at >= FetchBudget.STOP
}
