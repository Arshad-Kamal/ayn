package com.arshad.ayn.ui.mobile.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** How long the card a page's Shorts player came back to stays lit. */
private const val LIT_MS = 1_200L

/**
 * A page's Shorts and where its Shorts player comes back to (`m6-shorts` 8, M7 Q37): [play]
 * opens a Short in the Shorts player with the page's list as its run; once it closes, [lit] names
 * the Short last played there for a moment, for the page to scroll to its card and light it.
 */
@Stable
internal class ShortsReturn(private val awaiting: MutableState<Boolean>, private val playShort: (VideoItem) -> Unit) {
    var lit: VideoId? by mutableStateOf(null)
        private set

    fun play(short: VideoItem) {
        awaiting.value = true
        playShort(short)
    }

    /** Lights [lastPlayed] for a while each time the Shorts player this page opened closes. */
    suspend fun follow(shortOpen: () -> Boolean, lastPlayed: () -> VideoId?, returned: () -> Unit) {
        snapshotFlow(shortOpen).collectLatest { open ->
            if (open || !awaiting.value) return@collectLatest
            awaiting.value = false
            lit = lastPlayed()
            returned()
            try {
                delay(LIT_MS)
            } finally {
                lit = null
            }
        }
    }
}

/**
 * A page's [ShortsReturn] on [chrome]'s Shorts player: a Short plays with [openShorts]' list
 * through [onPlayShort]; as the player closes, [lastPlayed] is read, then the page's ViewModel lets
 * it go ([returned]). Saved, so a page restored with the player open still hears the close.
 */
@Composable
internal fun rememberShortsReturn(
    chrome: ShellChrome,
    openShorts: () -> ShortsList,
    onPlayShort: (VideoItem, ShortsList) -> Unit,
    lastPlayed: () -> VideoId?,
    returned: () -> Unit,
): ShortsReturn {
    val awaiting = rememberSaveable { mutableStateOf(false) }
    val currentOpenShorts by rememberUpdatedState(openShorts)
    val currentOnPlayShort by rememberUpdatedState(onPlayShort)
    val back = remember(awaiting) { ShortsReturn(awaiting) { short -> currentOnPlayShort(short, currentOpenShorts()) } }
    val currentLastPlayed by rememberUpdatedState(lastPlayed)
    val currentReturned by rememberUpdatedState(returned)
    LaunchedEffect(back, chrome) { back.follow(chrome.shortOpen, { currentLastPlayed() }, { currentReturned() }) }
    return back
}
