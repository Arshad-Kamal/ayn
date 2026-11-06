package com.arshad.ayn.ui.tv.common

import androidx.compose.runtime.compositionLocalOf
import com.arshad.ayn.core.filter.ContentFilter

/**
 * The open profile's content filter (KP-D1) for what a screen already shows: a block made from a
 * card's menu takes matching cards out at once, and its Undo brings them back (KP-D19). What is
 * fetched or read later is filtered at the data layer; this only catches lists already on screen.
 * `null` (not read yet, previews, tests) hides nothing.
 */
internal val LocalContentFilter = compositionLocalOf<ContentFilter?> { null }

