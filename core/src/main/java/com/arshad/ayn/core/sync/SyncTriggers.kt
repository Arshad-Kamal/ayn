package com.arshad.ayn.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * When this device syncs (M8-D8), on [scope] for the process's life: at start, after a profile
 * switch, on [request] (the activity's start and stop, Sync now, the phone's pull to refresh), and
 * while Ayn [isOpen] (its screen is up, or the player holds a video) within [pushDelay] of a local
 * change and every [checkEvery]. A change is
 * pushed [pushDelay] after the first one, not after the last: playback saves its place every 10 s,
 * which would hold off a debounce for as long as it plays. Requests made while a round runs make
 * one more round after it.
 */
class SyncTriggers(
    private val sync: suspend () -> Unit,
    private val scope: CoroutineScope,
    /** An emission per write to a synced table ([SYNCED_TABLES]). */
    private val localChanges: Flow<Any>,
    private val profileSwitches: Flow<Long>,
    private val isOpen: suspend () -> Boolean,
    private val pushDelay: Duration = 1.minutes,
    private val checkEvery: Duration = 5.minutes,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            requests.consumeEach { sync() }
        }
        scope.launch {
            localChanges.conflate().collect {
                delay(pushDelay)
                if (isOpen()) request()
            }
        }
        scope.launch {
            profileSwitches.distinctUntilChanged().drop(1).collect { request() }
        }
        scope.launch {
            while (true) {
                delay(checkEvery)
                if (isOpen()) request()
            }
        }
        request()
    }

    fun request() {
        requests.trySend(Unit)
    }
}
