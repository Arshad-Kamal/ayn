package com.arshad.ayn.ui.tv.home

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.feature.home.HomeLayout
import com.arshad.ayn.feature.home.HomeTop

internal const val PlayKey = "hero-play"
internal const val WatchLaterKey = "hero-later"
internal const val MoreKey = "hero-more"
internal const val SearchKey = "blank-search"
internal const val RetryKey = "retry"
internal const val OpenLibraryKey = "open-library"
internal const val UndoKey = "hidden-undo"

/** A For you card's focus key. */
internal fun forYouKey(id: VideoId): String = "foryou:${id.value}"

/** [com.arshad.ayn.ui.tv.focus.ScreenFocus]'s entry key: a new kind of content takes focus again;
 *  `null` while loading (focus waits for content). */
internal val HomeLayout.entryKey: String?
    get() = when (this) {
        HomeLayout.Loading -> null
        is HomeLayout.Failed -> "failed"
        is HomeLayout.Browse -> when (top) {
            is HomeTop.HeroTop -> "hero"
            HomeTop.Blank -> "blank"
            is HomeTop.FeedsFailed -> "feeds-failed"
            HomeTop.None -> "rows"
            HomeTop.HeroOff -> "hero-off"
            HomeTop.NothingApproved -> "nothing-approved"
        }
    }

internal val HomeLayout.initialFocusKey: String?
    get() = when (this) {
        HomeLayout.Loading -> null
        is HomeLayout.Failed -> RetryKey
        is HomeLayout.Browse -> when (top) {
            is HomeTop.HeroTop -> PlayKey
            HomeTop.Blank -> SearchKey
            is HomeTop.FeedsFailed -> RetryKey
            HomeTop.None, HomeTop.HeroOff, HomeTop.NothingApproved -> null
        }
    }
