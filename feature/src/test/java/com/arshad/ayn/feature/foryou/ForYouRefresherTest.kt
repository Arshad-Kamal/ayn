package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FetchAttempt
import com.arshad.ayn.core.store.FetchOutcome
import com.arshad.ayn.core.store.FetchOutcome.Failed
import com.arshad.ayn.core.store.FetchOutcome.Fetched
import com.arshad.ayn.core.store.FetchOutcome.RateLimited
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.home.FakeHomeExtractor
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FY-D4/FY-D5's top-up against a fake seam, store, settings, attempt log and clock: which seeds
 * are fetched, in what order, and when the budget or the For you switch ends a run. Seeds A–D
 * answer [LIST] unless a test says otherwise.
 */
class ForYouRefresherTest {
    private val clock = TestClock(NOW)
    private val extractor = FakeHomeExtractor().apply { listOf(A, B, C, D).forEach { relatedAnswers[it] = Result.success(LIST) } }
    private val lists = FakeRelatedListStore()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private val log = FakeFetchAttemptLog(clock)
    private val refresher = ForYouRefresher(extractor, lists, settings, FetchBudget(log, clock), clock)

    @Test
    fun seedsWithoutAListAreFetchedHeaviestFirstOnceEachAndKept() = runTest {
        refresher.topUp(ids(B, A, B))

        assertEquals(listOf(B, A), extractor.relatedAsked)
        assertEquals(listOf(VideoId(B) to LIST, VideoId(A) to LIST), lists.recorded)
        assertEquals(listOf(Fetched, Fetched), outcomes())
    }

    @Test
    fun aFreshListIsNotFetched() = runTest {
        lists.kept[VideoId(A)] = RelatedList(LIST, fetchedAt = NOW - 2.days, failedAt = null)

        refresher.topUp(ids(A))

        assertEquals(emptyList<String>(), extractor.relatedAsked)
    }

    @Test
    fun aListThreeDaysOldIsFetchedAgain() = runTest {
        lists.kept[VideoId(A)] = RelatedList(LIST, fetchedAt = NOW - 3.days, failedAt = null)

        refresher.topUp(ids(A))

        assertEquals(listOf(A), extractor.relatedAsked)
    }

    @Test
    fun aSeedWhoseFetchFailedWaitsADay() = runTest {
        lists.kept[VideoId(A)] = RelatedList(emptyList(), fetchedAt = null, failedAt = NOW - 23.hours)
        lists.kept[VideoId(B)] = RelatedList(emptyList(), fetchedAt = null, failedAt = NOW - 24.hours)

        refresher.topUp(ids(A, B))

        assertEquals(listOf(B), extractor.relatedAsked)
    }

    /** The store keeps a stale list when its refetch fails, and marks the failure. */
    @Test
    fun aStaleListWhoseRefetchFailedWaitsADay() = runTest {
        lists.kept[VideoId(A)] = RelatedList(LIST, fetchedAt = NOW - 4.days, failedAt = NOW - 1.hours)

        refresher.topUp(ids(A))

        assertEquals(emptyList<String>(), extractor.relatedAsked)
    }

    /** Playback may keep a seed's list while the run fetches the ones before it. */
    @Test
    fun aListKeptDuringTheRunIsNotFetched() = runTest {
        val gate = CompletableDeferred<Unit>()
        extractor.relatedGate = gate

        val run = launch { refresher.topUp(ids(A, B)) }
        advanceUntilIdle()
        lists.kept[VideoId(B)] = RelatedList(LIST, fetchedAt = NOW, failedAt = null)
        gate.complete(Unit)
        run.join()

        assertEquals(listOf(A), extractor.relatedAsked)
    }

    @Test
    fun turningForYouOffEndsTheRun() = runTest {
        val gate = CompletableDeferred<Unit>()
        extractor.relatedGate = gate

        val run = launch { refresher.topUp(ids(A, B)) }
        advanceUntilIdle()
        settings.byProfile.value = mapOf(1L to ProfileSettings(forYou = false))
        gate.complete(Unit)
        run.join()

        assertEquals(listOf(A), extractor.relatedAsked)
        assertEquals(listOf(Fetched), outcomes())
    }

    @Test
    fun aFailedFetchMarksTheSeedAndCountsAsAFailure() = runTest {
        extractor.relatedAnswers[A] = Result.failure(AccessException("related down", IOException("offline")))

        refresher.topUp(ids(A, B))

        assertEquals(listOf(VideoId(A)), lists.failures)
        assertEquals(listOf(VideoId(B) to LIST), lists.recorded)
        assertEquals(listOf(Failed, Fetched), outcomes())
    }

    @Test
    fun aRateLimitAnswerEndsTheRunAndMarksNoSeed() = runTest {
        extractor.relatedAnswers[A] = Result.failure(AccessException("over the limit", IllegalStateException(), AccessFailure.RATE_LIMITED))

        refresher.topUp(ids(A, B))

        assertEquals(listOf(A), extractor.relatedAsked)
        assertEquals(emptyList<VideoId>(), lists.failures)
        assertEquals(listOf(RateLimited), outcomes())
    }

    @Test
    fun threeFailuresInARowEndTheRun() = runTest {
        listOf(A, B, C, D).forEach { extractor.relatedAnswers[it] = Result.failure(AccessException("related down", IllegalStateException())) }

        refresher.topUp(ids(A, B, C, D))

        assertEquals(listOf(A, B, C), extractor.relatedAsked)
        assertEquals(listOf(Failed, Failed, Failed), outcomes())
    }

    /** The log outlives the process: a new refresher on it sees the hour's attempts. */
    @Test
    fun aSpentBudgetFetchesNothingAfterARestart() = runTest {
        repeat(FetchBudget.PER_HOUR) { log.attempts += FetchAttempt(NOW - 30.minutes, Fetched) }
        val restarted = ForYouRefresher(extractor, lists, settings, FetchBudget(log, clock), clock)

        restarted.topUp(ids(A))

        assertEquals(emptyList<String>(), extractor.relatedAsked)
    }

    @Test
    fun aStoppedBudgetFetchesNothing() = runTest {
        log.attempts += FetchAttempt(NOW - 59.minutes, RateLimited)

        refresher.topUp(ids(A))

        assertEquals(emptyList<String>(), extractor.relatedAsked)
    }

    @Test
    fun aRequestCutOffInFlightCountsAsAFailure() = runTest {
        extractor.relatedGate = CompletableDeferred()

        val run = launch { refresher.topUp(ids(A)) }
        advanceUntilIdle()
        run.cancel()
        advanceUntilIdle()

        assertEquals(listOf(Failed), outcomes())
        assertEquals(emptyList<VideoId>(), lists.failures)
    }

    private fun ids(vararg ids: String): List<VideoId> = ids.map(::VideoId)

    private fun outcomes(): List<FetchOutcome> = log.attempts.map { it.outcome }

    private companion object {
        const val A = "seedaaaaaa1"
        const val B = "seedbbbbbb1"
        const val C = "seedcccccc1"
        const val D = "seeddddddd1"
        val LIST: List<VideoItem> = listOf(testVideo("fy000000001"), testVideo("fy000000002"))
    }
}
