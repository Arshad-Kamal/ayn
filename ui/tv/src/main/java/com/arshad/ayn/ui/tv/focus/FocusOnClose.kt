package com.arshad.ayn.ui.tv.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/** When [open] turns false (what it opened has closed), focus goes to [target] once it has laid out. */
@Composable
internal fun FocusOnClose(open: Boolean, target: FocusRequester) {
    FocusOnClose(open) { target.requestFocus() }
}

/** When [open] turns false, [focus] runs once the page has laid out. */
@Composable
internal fun FocusOnClose(open: Boolean, focus: () -> Unit) {
    val last = remember { OpenHolder() }
    val currentFocus by rememberUpdatedState(focus)
    LaunchedEffect(open) {
        val closed = last.open && !open
        last.open = open
        if (closed) {
            withFrameNanos { }
            currentFocus()
        }
    }
}

private class OpenHolder {
    var open: Boolean = false
}
