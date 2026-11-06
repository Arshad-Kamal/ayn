package com.arshad.ayn.feature.player

import androidx.media3.common.Player
import com.arshad.ayn.core.access.CaptionCue
import kotlinx.coroutines.delay

/** M7-D14: the cues follow the player's position on their own tick, finer than the chrome's. */
private const val CUE_TICK_MS = 100L

/** The skips' position tick, quicker than the chrome's so a jump lands close to a segment's start. */
private const val SKIP_TICK_MS = 200L

/** A chat replay's position tick (LIVE-D7): lines are timed to the millisecond, shown a quarter
 *  second late at most. */
private const val CHAT_TICK_MS = 250L

/** Hands the player's position, and whether the picture moves, to a chat replay's [tick]
 *  ([PlayerViewModel.chatTick]) every [CHAT_TICK_MS], until cancelled. */
suspend fun Player.followChat(tick: (positionMs: Long, playing: Boolean) -> Unit): Nothing {
    while (true) {
        tick(currentPosition, isPlaying)
        delay(CHAT_TICK_MS)
    }
}

/** Shows the cue at the player's position (`null` between cues) every [CUE_TICK_MS], until cancelled. */
suspend fun Player.followCues(cues: List<CaptionCue>, show: (CaptionCue?) -> Unit): Nothing {
    while (true) {
        show(cues.cueAt(currentPosition))
        delay(CUE_TICK_MS)
    }
}

/** Hands the player's position to [tick] every [SKIP_TICK_MS] and seeks where it answers
 *  (M7-D10's skips, [PlayerViewModel.segmentTick]), until cancelled. */
suspend fun Player.followSkips(tick: (positionMs: Long) -> Long?): Nothing {
    while (true) {
        tick(currentPosition)?.let(::seekTo)
        delay(SKIP_TICK_MS)
    }
}
