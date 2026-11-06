package com.arshad.ayn.feature.library

import com.arshad.ayn.core.store.FakeWatchStore
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The History page: days from the store, Clear history and its confirm. */
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val compute = StandardTestDispatcher(dispatcher.scheduler, "compute")
    private val watch = FakeWatchStore()
    private val zone = ZoneId.of("UTC")
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-03T21:00:00Z")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

    private fun viewModel(): HistoryViewModel = HistoryViewModel(watch, clock, zone, compute)

    private fun ready(viewModel: HistoryViewModel): HistoryState.Ready = viewModel.uiState.value.history as HistoryState.Ready

    @Test
    fun historyIsReadOffTheMainThread() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(listOf(compute), watch.readOn)
    }

    @Test
    fun historyIsListedByDayNewestFirst() = runTest(dispatcher) {
        watch.entries.value = listOf(
            watchEntry("aaaaaaaaaaa", lastWatchedAtMillis = at("2026-10-03T20:12:00Z")),
            watchEntry("bbbbbbbbbbb", lastWatchedAtMillis = at("2026-10-03T19:40:00Z")),
            watchEntry("ccccccccccc", lastWatchedAtMillis = at("2026-10-02T22:05:00Z")),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        val history = ready(viewModel)
        assertEquals(3, history.count)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 3) to listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), LocalDate.of(2026, 10, 2) to listOf("ccccccccccc")),
            history.days.map { day -> day.date to day.videos.map { it.video.id.value } },
        )
    }

    @Test
    fun noHistoryIsTheEmptyPageAndClearAsksNothing() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onClearHistory()

        assertEquals(HistoryState.Ready(emptyList(), 0), viewModel.uiState.value.history)
        assertFalse(viewModel.uiState.value.confirmingClear)
    }

    @Test
    fun clearingAfterTheConfirmEmptiesHistory() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("aaaaaaaaaaa"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onClearHistory()
        assertTrue(viewModel.uiState.value.confirmingClear)
        viewModel.onConfirmClear()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmingClear)
        assertEquals(0, ready(viewModel).count)
        assertEquals(emptyList<Any>(), watch.entries.value)
    }

    @Test
    fun keepHistoryClosesTheConfirmAndClearsNothing() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("aaaaaaaaaaa"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onClearHistory()
        viewModel.onKeepHistory()
        viewModel.onConfirmClear()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmingClear)
        assertEquals(1, ready(viewModel).count)
    }

    @Test
    fun aFailedClearClosesTheConfirmAndKeepsTheList() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("aaaaaaaaaaa"))
        watch.failWrites = true
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onClearHistory()
        viewModel.onConfirmClear()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmingClear)
        assertEquals(1, ready(viewModel).count)
    }

    @Test
    fun aConfirmOpenWhenHistoryEmptiesElsewhereCloses() = runTest(dispatcher) {
        watch.entries.value = listOf(watchEntry("aaaaaaaaaaa"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onClearHistory()

        watch.entries.value = emptyList()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmingClear)
    }

    @Test
    fun aFailedReadSaysSoAndTryAgainReadsAgain() = runTest(dispatcher) {
        watch.failReads = true
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(HistoryState.Failed, viewModel.uiState.value.history)

        watch.failReads = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(HistoryState.Ready(emptyList(), 0), viewModel.uiState.value.history)
    }
}
