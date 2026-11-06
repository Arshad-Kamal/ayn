package com.arshad.ayn.ui.tv.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The query field's text and selection. The field owns them (an async round trip through the
 * ViewModel would fight the keyboard). [selectAll] is Edit search: the whole query selected so
 * typing replaces it (B-D6). A TV keyboard collapses that selection as its session starts (Gboard
 * for TV sends `setSelection(end, end)`, then `finishComposingText`), so until
 * the user types or moves the cursor ([onCursorKey]: the keyboard's arrows reach the field as
 * keys) a change that leaves the text alone puts the selection back.
 */
internal class QueryFieldState(query: String) {
    var value: TextFieldValue by mutableStateOf(TextFieldValue(query, TextRange(query.length)))
        private set

    private var holdingSelection = false

    fun selectAll(query: String) {
        value = TextFieldValue(query, TextRange(0, query.length))
        holdingSelection = true
    }

    fun onValueChange(new: TextFieldValue) {
        // Keeping the selected value is enough: the field recomposes after every change it reports
        // and resets its editing buffer, and the keyboard, to the value it is given.
        if (holdingSelection && new.text == value.text) return
        holdingSelection = false
        value = new
    }

    fun onCursorKey() {
        holdingSelection = false
    }
}
