package com.arshad.ayn.ui.tv.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay

/** [clock]'s now, turning over on each minute. */
@Composable
internal fun rememberMinuteNow(clock: Clock): Instant {
    val now by produceState(clock.now(), clock) {
        while (true) {
            delay(MinuteMs - clock.now().toEpochMilliseconds() % MinuteMs)
            value = clock.now()
        }
    }
    return now
}

private const val MinuteMs = 60_000L
