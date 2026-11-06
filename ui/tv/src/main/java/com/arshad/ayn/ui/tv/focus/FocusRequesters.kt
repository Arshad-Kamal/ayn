package com.arshad.ayn.ui.tv.focus

import androidx.compose.ui.focus.FocusRequester

/** Focus requesters for a changing list's items by key, each made on its first [of]; remember
 *  one per list. [get] finds an item's to move focus to, `null` before it was composed. */
internal class FocusRequesters {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun of(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    operator fun get(key: String): FocusRequester? = requesters[key]
}
