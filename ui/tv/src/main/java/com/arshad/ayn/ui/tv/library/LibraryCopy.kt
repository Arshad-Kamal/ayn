package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.feature.format.timecode
import com.arshad.ayn.feature.library.VideoSource

/** The card menu's first row for a video with a resume point (`b3-library` menu): "Resume from 34:51". */
internal fun resumeText(positionMs: Long): String = "Resume from ${timecode(positionMs)}"

/** The card menu's remove row (M5-D17): it takes the video out of where it was listed; none elsewhere. */
internal fun removeText(source: VideoSource): String? = when (source) {
    VideoSource.History -> "Remove from history"
    VideoSource.WatchLater -> "Remove from Watch later"
    is VideoSource.Playlist -> "Remove from this playlist"
    VideoSource.Elsewhere, is VideoSource.ForYou -> null
}
