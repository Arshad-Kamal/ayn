package com.arshad.ayn.ui.tv.focus

/** Whether a region holds focus: written by its `onFocusChanged`, read by an effect; no
 *  composition reads it. */
internal class FocusHeld {
    var now: Boolean = false
}
