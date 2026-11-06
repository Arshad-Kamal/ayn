package com.arshad.ayn.ui.tv.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A key's release belongs to the screen its press reached: a release whose press came before
 * [screen] last changed is dropped. A lock entry submits on OK's key-down and a tv-material click
 * acts on key-up, so without this the OK that opens the next screen would also click whatever
 * that screen focuses first.
 */
@Composable
internal fun Modifier.releasesStayWith(screen: Any?): Modifier {
    val pressedHere = remember(screen) { mutableSetOf<Key>() }
    return onPreviewKeyEvent { event ->
        when (event.type) {
            KeyEventType.KeyDown -> {
                // A key held since the last screen repeats here; its release stays the old screen's.
                if (event.nativeKeyEvent.repeatCount == 0) pressedHere += event.key
                false
            }
            KeyEventType.KeyUp -> !pressedHere.remove(event.key)
            else -> false
        }
    }
}
