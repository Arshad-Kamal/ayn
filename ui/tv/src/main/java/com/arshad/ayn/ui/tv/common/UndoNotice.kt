package com.arshad.ayn.ui.tv.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import kotlinx.coroutines.delay

/** What an [UndoNotice] says: [text], then [muted] after a dot; without [undo] it reports a
 *  failure (a warning toast with nothing to undo). */
internal class NoticeLine(val text: String, val muted: String?, val undo: Boolean = true)

/**
 * A notice with Undo (`b3-home` fy-hidden, `kp-filters` blocked) while [notice] is set: Undo
 * (placed by [undoModifier]; it takes focus as the notice shows when [focusOnShow]) calls [onUndo];
 * any other key, BACK, or [ACTION_TOAST_MS] going by calls [onClose] (the key is spent). A line
 * without Undo leaves focus alone and closes after [TOAST_MS]. It rises in and sinks away (D8);
 * sinking, Undo takes no focus.
 */
@Composable
internal fun <T : Any> UndoNotice(
    notice: T?,
    line: (T) -> NoticeLine,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    undoModifier: Modifier = Modifier,
    focusOnShow: Boolean = false,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val last = remember { LastNotice<T>() }
    if (notice != null) last.notice = notice
    val shown = notice ?: last.notice ?: return
    val live = notice != null
    val words = line(shown)
    val undoable = live && words.undo
    BackHandler(enabled = undoable) { currentOnClose() }
    val undo = remember { FocusRequester() }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        if (focusOnShow && words.undo) {
            withFrameNanos { }
            undo.requestFocus()
        }
        delay(if (words.undo) ACTION_TOAST_MS else TOAST_MS)
        currentOnClose()
    }
    if (!words.undo) {
        Toast(visible = live, icon = R.drawable.ic_alert, text = words.text, muted = words.muted, modifier = modifier, warn = true)
        return
    }
    Toast(
        visible = live,
        icon = null,
        text = words.text,
        muted = words.muted,
        modifier = modifier,
        action = {
            ControlSurface(
                onClick = { if (undoable) onUndo() },
                modifier = undoModifier
                    .focusRequester(undo)
                    .focusProperties { canFocus = undoable }
                    .onPreviewKeyEvent { event ->
                        val other = event.type == KeyEventType.KeyDown && event.key !in EnterKeys && event.key != Key.Back
                        if (other && undoable) currentOnClose()
                        other
                    }
                    .height(28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TintedIcon(painterResource(R.drawable.ic_undo), contentDescription = null, modifier = Modifier.size(14.dp))
                    TintedText("Undo", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp))
                }
            }
        },
    )
}

// The notice that sinks away after it closes; written as one shows, never read for invalidation.
private class LastNotice<T : Any> {
    var notice: T? = null
}
