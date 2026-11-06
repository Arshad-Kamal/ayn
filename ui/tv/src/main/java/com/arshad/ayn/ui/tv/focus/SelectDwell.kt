package com.arshad.ayn.ui.tv.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long focus rests on a select-on-focus item before it is selected (PERF-D6: ~150–200 ms). */
internal const val SelectDwellMs = 180L

/**
 * A row's one pending select-on-focus selection (the Subscriptions strip, a channel's tabs,
 * Settings' categories): the item focus rests on is selected after [SelectDwellMs], so items D-pad
 * only passes over load nothing. The row calls [cancel] when focus leaves it; OK selects at once through [now].
 */
internal class SelectDwell(private val scope: CoroutineScope) {
    private var pending: Job? = null

    /** Focus came to an item: select it unless focus moves on first. */
    fun rest(select: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(SelectDwellMs)
            select()
        }
    }

    fun now(select: () -> Unit) {
        cancel()
        select()
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
