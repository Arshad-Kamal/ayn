package com.arshad.ayn.playback

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.WatchStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saves reach the store one at a time in the order they were made; a failed write doesn't stop the
 *  rest; a drain waits for the saves before it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PositionWriterTest {

    // Unconfined: a write runs inline until the store suspends, so a held write is observable.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fake = FakeWatchStore()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aLaterSaveWaitsForTheEarlierWriteToFinish() {
        val firstWrite = CompletableDeferred<Unit>()
        val writer = PositionWriter(HoldsFirstWrite(fake, firstWrite), scope)

        writer.save(save(10_000))
        writer.save(save(20_000))
        assertEquals("the second save waits behind the held first", emptyList<Long>(), fake.saves.map { it.positionMs })
        firstWrite.complete(Unit)

        assertEquals(listOf(10_000L, 20_000L), fake.saves.map { it.positionMs })
    }

    /** A sync reads the database after [PositionWriter.drain]: every save made before it has landed. */
    @Test
    fun drainReturnsOnceTheEarlierSavesAreWritten() {
        val firstWrite = CompletableDeferred<Unit>()
        val writer = PositionWriter(HoldsFirstWrite(fake, firstWrite), scope)
        writer.save(save(10_000))
        writer.save(save(20_000))

        var landedAtDrain: List<Long>? = null
        scope.launch {
            writer.drain()
            landedAtDrain = fake.saves.map { it.positionMs }
        }
        assertNull(landedAtDrain)
        firstWrite.complete(Unit)

        assertEquals(listOf(10_000L, 20_000L), landedAtDrain)
    }

    @Test
    fun aFailedWriteIsDroppedAndTheNextOneLands() {
        val writer = PositionWriter(FailsFirstWrite(fake), scope)

        writer.save(save(10_000))
        writer.save(save(20_000))

        assertEquals(listOf(20_000L), fake.saves.map { it.positionMs })
    }

    private fun save(positionMs: Long) = PositionSave(1L, VideoId("aaaaaaaaaaa"), positionMs, 600_000L, ended = false)

    /** Holds the first write until [release] completes, then writes it to [fake]. */
    private class HoldsFirstWrite(private val fake: FakeWatchStore, private val release: CompletableDeferred<Unit>) : WatchStore by fake {
        private var held = false

        override suspend fun recordPosition(save: PositionSave) {
            if (!held) {
                held = true
                release.await()
            }
            fake.recordPosition(save)
        }
    }

    private class FailsFirstWrite(private val fake: FakeWatchStore) : WatchStore by fake {
        private var failed = false

        override suspend fun recordPosition(save: PositionSave) {
            if (!failed) {
                failed = true
                throw IllegalStateException("disk full")
            }
            fake.recordPosition(save)
        }
    }
}
