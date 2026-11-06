package com.arshad.ayn.ui.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.CaptionCue
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.feature.player.CaptionCues
import com.arshad.ayn.feature.player.followCues
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Toast
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.theme.atWeight
import kotlinx.coroutines.delay

// `b3-player-panels` `.cues` (px ÷ 2): lines 46 dp above the screen's foot, 36 dp above the
// chrome's top while it shows, 75 dp higher while the bottom-right slot holds something.
private val CuesRest = 46.dp
private val CuesOverChrome = 36.dp

/** How far the cues rise over a skip prompt, a note or a toast (`.cues.lift-note`). */
internal val CuesOverSlot = 75.dp

private val CueMaxWidth = 720.dp
private val CueShape = RoundedCornerShape(4.dp)
private val CueInk = Color(0xFFF8F8F8)
private val CueWell = Color(0xFF030201).copy(alpha = 0.74f)

/** How long the ccfail note stays up once it shows. */
private const val FAILED_NOTE_MS = 5_000L

/** How far the cues rise while the chrome shows: above its top, [chromeHeight] plus [chromeLift]
 *  (the Related peek or row it rides on), from their resting place. */
internal fun cuesOverChrome(chromeHeight: Dp, chromeLift: Dp): Dp =
    (chromeHeight + chromeLift + CuesOverChrome - CuesRest).coerceAtLeast(0.dp)

/**
 * The chosen track's cue at [player]'s position (`b3-player-panels` cues), centred above the
 * bottom safe line, one well per line, risen by [lift] (the caller animates it: transform only).
 * Nothing shows while [cues] aren't [CaptionCues.Shown] or between cues. A cue appears and goes
 * with no fade: timed text keeps to the speech.
 */
@Composable
internal fun CaptionCuesLayer(player: Player, cues: CaptionCues?, lift: () -> Dp, modifier: Modifier = Modifier) {
    val cue = rememberCueAt(player, (cues as? CaptionCues.Shown)?.cues) ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = -lift().toPx() }
            .padding(bottom = CuesRest),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cue.text.lines().forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.titleLarge.atWeight(560).copy(fontSize = 23.sp, lineHeight = 1.24.em, letterSpacing = 0.002.em),
                color = CueInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = CueMaxWidth)
                    .background(CueWell, CueShape)
                    .padding(start = 9.dp, end = 9.dp, top = 1.dp, bottom = 2.5.dp),
            )
        }
    }
}

/** The cue at [player]'s position ([followCues]); it changes (and recomposes) only as one cue
 *  gives way to the next. */
@Composable
private fun rememberCueAt(player: Player, cues: List<CaptionCue>?): CaptionCue? {
    val cue by produceState<CaptionCue?>(null, player, cues) {
        value = null
        if (cues != null) player.followCues(cues) { value = it }
    }
    return cue
}

/** The ViewModel's captions calls (M7-D14): the resolve's tracks reported per turn, a pick, Try again. */
internal class CaptionCalls(
    val resolved: (turn: Int, tracks: List<CaptionTrack>) -> Unit,
    val choose: (CaptionTrack?) -> Unit,
    val retry: () -> Unit,
)

/**
 * One turn's ccfail notes: each failure ([CaptionCues.Failed.serial]) has one, which Try again,
 * BACK, another key or its time takes away for good; a later failure (after Try again or another
 * pick) has a note of its own. Held per turn, so the stage coming back within the turn (Up next's
 * Cancel, the player's Try again) doesn't bring back a note already taken away.
 */
@Stable
internal class FailedCaptionsNote(private val retry: () -> Unit) {
    private var dismissed: Int? by mutableStateOf(null)

    fun isDismissed(failure: CaptionCues.Failed): Boolean = dismissed == failure.serial

    fun dismiss(failure: CaptionCues.Failed) {
        dismissed = failure.serial
    }

    /**
     * A key while [failure]'s note shows: OK is Try again and is spent (true); BACK is the
     * screen's BackHandler's; any other key drops the note and goes on as usual.
     */
    fun onKey(key: Key, failure: CaptionCues.Failed): Boolean {
        if (key == Key.Back) return false
        dismiss(failure)
        if (key !in EnterKeys) return false
        retry()
        return true
    }
}

/** [failure]'s note goes [FAILED_NOTE_MS] after it starts [showing], counted afresh each time it
 *  comes back (once the chrome or a skip prompt gives the corner back). */
@Composable
internal fun FailedCaptionsNoteEffects(note: FailedCaptionsNote, failure: CaptionCues.Failed?, showing: Boolean) {
    LaunchedEffect(note, failure, showing) {
        if (showing && failure != null) {
            delay(FAILED_NOTE_MS)
            note.dismiss(failure)
        }
    }
}

/** ccfail: "English captions didn't load · the video plays on", Try again drawn focused. */
@Composable
internal fun FailedCaptionsToast(visible: Boolean, track: CaptionTrack, modifier: Modifier = Modifier) {
    Toast(
        visible = visible,
        icon = R.drawable.ic_cc,
        text = "${captionsLabel(track.languageTag)} captions didn’t load",
        muted = "the video plays on",
        modifier = modifier,
    ) { ToastAction(R.drawable.ic_retry, "Try again") }
}
