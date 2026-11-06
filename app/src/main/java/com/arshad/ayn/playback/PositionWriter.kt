package com.arshad.ayn.playback

import android.util.Log
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.WatchStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes the control plane's position saves to [store] one at a time, in the order they were
 * made, so an older place never lands after a newer one. Runs on the app's [scope], which
 * outlives the playback service, so the save made as the service goes away still lands. A failed
 * write is logged and dropped: a later save of that video, if any, supersedes it; a failed final
 * save leaves the row at the previous save's place.
 */
class PositionWriter(private val store: WatchStore, scope: CoroutineScope) {
    private val queue = Channel<Entry>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (entry in queue) {
                when (entry) {
                    is Entry.Save -> write(entry.save)
                    is Entry.Drain -> entry.done.complete(Unit)
                }
            }
        }
    }

    fun save(save: PositionSave) {
        queue.trySend(Entry.Save(save))
    }

    /** Returns once every save made before the call is written (or dropped): a sync reads the
     *  playing video's place after it (M8-D8). */
    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        queue.trySend(Entry.Drain(done))
        done.await()
    }

    private suspend fun write(save: PositionSave) {
        try {
            store.recordPosition(save)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "position save failed: ${e.javaClass.simpleName}")
        }
    }

    private sealed interface Entry {
        class Save(val save: PositionSave) : Entry

        class Drain(val done: CompletableDeferred<Unit>) : Entry
    }
}
