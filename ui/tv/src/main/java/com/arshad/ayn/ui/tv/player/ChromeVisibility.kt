package com.arshad.ayn.ui.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.arshad.ayn.feature.player.PlayerChromePolicy
import kotlinx.coroutines.delay

/**
 * Whether the stage's chrome shows. The hidden chrome's keys but a seek [show] it and BACK
 * [hide]s it; [ChromeVisibilityEffects] applies the rest (pause, end, panels, the idle wait).
 * Everything that sits in the chrome's place reads [shown] here.
 */
@Stable
internal class ChromeVisibility(shown: Boolean) {
    var shown: Boolean by mutableStateOf(shown)
        private set

    /** Key presses so far: each one restarts the idle wait. */
    var interactions: Int by mutableIntStateOf(0)
        private set

    fun show() {
        shown = true
    }

    fun hide() {
        shown = false
    }

    fun interacted() {
        interactions++
    }
}

/**
 * [chrome]'s rules: a pause or the end shows it (unless a panel is open); a panel opening hides
 * it, and closing brings it back when the panel came from one of its controls; shown while
 * playing with the Related row closed, it hides after [PlayerChromePolicy.HIDE_AFTER_IDLE_MS]
 * without a key, but never while the scrub holds a target ([scrubPending]: hiding would drop it).
 * [reopensOnClose] is read each time no panel shows, the first composition
 * included, so it must stay false until a panel has closed (else a chrome the resumed toast
 * keeps hidden would show at once).
 */
@Composable
internal fun ChromeVisibilityEffects(
    chrome: ChromeVisibility,
    paused: Boolean,
    ended: Boolean,
    panelOpen: Boolean,
    rowOpen: Boolean,
    reopensOnClose: () -> Boolean,
    scrubPending: () -> Boolean,
) {
    val currentPanelOpen by rememberUpdatedState(panelOpen)
    val currentReopens by rememberUpdatedState(reopensOnClose)
    // Only a target appearing or clearing recomposes, and only these effects; one moving doesn't.
    val holding by remember(scrubPending) { derivedStateOf(scrubPending) }
    LaunchedEffect(paused, ended) { if ((paused || ended) && !currentPanelOpen) chrome.show() }
    LaunchedEffect(panelOpen) {
        if (panelOpen) chrome.hide() else if (currentReopens()) chrome.show()
    }
    LaunchedEffect(chrome.shown, paused, ended, chrome.interactions, rowOpen, holding) {
        if (chrome.shown && !paused && !ended && !rowOpen && !holding) {
            delay(PlayerChromePolicy.HIDE_AFTER_IDLE_MS)
            chrome.hide()
        }
    }
}
