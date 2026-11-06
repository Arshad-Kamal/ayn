package com.arshad.ayn.ui.tv.playlist

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.feature.playlist.PlaylistLayout

internal const val PlayAllKey = "play-all"
internal const val RetryKey = "retry"
internal const val BackKey = "back"

/** A row's focus key: rows key by position (a playlist can hold one video twice). */
internal fun rowKey(position: Int): String = "row:$position"

/** [com.arshad.ayn.ui.tv.focus.ScreenFocus]'s entry key: each kind of content takes focus as it
 *  lands; `null` while loading (focus waits for the rows). */
internal val PlaylistLayout.entryKey: String?
    get() = when (this) {
        is PlaylistLayout.Loading -> null
        is PlaylistLayout.Videos -> "videos"
        is PlaylistLayout.Empty -> "empty"
        is PlaylistLayout.Failed -> "failed"
    }

internal val PlaylistLayout.initialFocusKey: String?
    get() = when (this) {
        is PlaylistLayout.Loading -> null
        is PlaylistLayout.Videos -> PlayAllKey
        is PlaylistLayout.Empty -> BackKey
        is PlaylistLayout.Failed -> if (failure == AccessFailure.UNAVAILABLE) BackKey else RetryKey
    }
