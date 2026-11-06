package com.arshad.ayn.ui.tv.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * A destination registered through this enters via [ScreenFocus], so returning to it with
 * BACK puts focus on the item it last held (CMP-FOCUS-05).
 */
internal inline fun <reified T : Any> NavGraphBuilder.keScreen(
    noinline onNothingToFocus: () -> Unit,
    initialFocusKey: String? = null,
    noinline content: @Composable (entry: NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        ScreenFocus(onNothingToFocus = onNothingToFocus, initialFocusKey = initialFocusKey) {
            content(entry)
        }
    }
}

/**
 * Focus entry for one screen, its single restoration mechanism (CMP-FOCUS-03). Whenever focus
 * enters the screen (on arrival, after BACK, from the rail) it goes to the item that last held
 * it, else (that item gone while an overlay held focus, [LocalOverlayHoldsFocus]) the item now
 * nearest its place, else (gone from a list that gave its [FocusSlot]) the item now in its slot,
 * else the item keyed [initialFocusKey], else the first focusable; items opt in with
 * [focusMemory].
 *
 * Entry runs on arrival and again whenever [entryKey] changes, so a screen whose content lands
 * after it arrives gets focus then (TVF-7); `null` means the content is still loading, so focus
 * waits for it. An arrival while loading stays pending: the first content takes focus as the
 * arrival would have, unless [focusHeldElsewhere] changed since (the user moved to or from the
 * rail meanwhile). Any other later entry takes focus only when the screen lost it, and neither
 * [focusHeldElsewhere] nor [LocalOverlayHoldsFocus] says something outside the screen holds it. An
 * entry that finds nothing focusable calls [onNothingToFocus], so focus never idles on nothing
 * once content is there (CMP-FOCUS-01). So does a LEFT that finds nothing further left: the
 * closed rail's item reaches into the page's first column, where spatial search doesn't count
 * it as left of a narrow control (Channel's Subscribe), so the shell is asked directly.
 *
 * A [returnKey] (a one-shot: BACK from a Shorts run, Q37) outranks the remembered item on the
 * next entry that takes focus; [onReturned] then consumes it, landed or not.
 */
@Composable
internal fun ScreenFocus(
    onNothingToFocus: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusKey: String? = null,
    entryKey: Any? = Unit,
    focusHeldElsewhere: () -> Boolean = { false },
    returnKey: String? = null,
    onReturned: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val lastFocused = rememberSaveable { mutableStateOf<String?>(null) }
    val lastSlot = rememberSaveable { mutableStateOf<FocusSlot?>(null) }
    val currentReturnKey = rememberUpdatedState(returnKey)
    val currentOnReturned by rememberUpdatedState(onReturned)
    val screen = remember { FocusRequester() }
    val holder = remember { FocusHolder() }
    val currentInitialFocusKey by rememberUpdatedState(initialFocusKey)
    val currentOnNothingToFocus by rememberUpdatedState(onNothingToFocus)
    val memory = remember {
        FocusMemory(lastFocused, currentReturnKey, lastSlot) {
            screen.requestFocus()
            if (!holder.hasFocus) currentOnNothingToFocus()
        }
    }
    val currentFocusHeldElsewhere by rememberUpdatedState(focusHeldElsewhere)
    val overlayHoldsFocus by rememberUpdatedState(LocalOverlayHoldsFocus.current)
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(screen)
            .onFocusChanged {
                memory.regionFocusChanged(it.hasFocus, overlayHoldsFocus)
                holder.hasFocus = it.hasFocus
            }
            .focusProperties { onEnter = { memory.restore(currentInitialFocusKey) } }
            .focusGroup()
            .onKeyEvent { event ->
                val leftward = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft
                if (leftward && !focusManager.moveFocus(FocusDirection.Left)) currentOnNothingToFocus()
                leftward
            },
    ) {
        CompositionLocalProvider(LocalFocusMemory provides memory, content = content)
    }

    LaunchedEffect(entryKey) {
        val arrival = !holder.arrived
        holder.arrived = true
        if (entryKey == null) {
            if (arrival) holder.heldElsewhereAtArrival = currentFocusHeldElsewhere()
            return@LaunchedEffect
        }
        val pendingArrival = holder.heldElsewhereAtArrival?.let { it == currentFocusHeldElsewhere() } == true
        holder.heldElsewhereAtArrival = null
        // An overlay holding focus (a card menu's notice) keeps it; its close brings focus back.
        val takeFocus = arrival || pendingArrival || (!holder.hasFocus && !currentFocusHeldElsewhere() && !overlayHoldsFocus)
        if (!takeFocus) return@LaunchedEffect
        // Lazy rows compose their items during the first layout; wait for it.
        withFrameNanos { }
        screen.requestFocus()
        if (currentReturnKey.value != null) currentOnReturned()
        if (!holder.hasFocus) currentOnNothingToFocus()
    }
}

// Written by onFocusChanged and the entry effect, read by the entry effect and FocusMemory.enter:
// no composition reads it.
private class FocusHolder {
    var hasFocus: Boolean = false
    // The first entry run is the arrival, even while loading (then pending, see below).
    var arrived: Boolean = false
    // Set by an arrival while loading (whether focus was outside the screen then); cleared by
    // the first content.
    var heldElsewhereAtArrival: Boolean? = null
}
