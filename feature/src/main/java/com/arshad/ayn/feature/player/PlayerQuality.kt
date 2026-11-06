package com.arshad.ayn.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/** One height the Quality sheet offers: the picture's short side ([lines]) and the track that plays it. */
data class QualityOption(val lines: Int, val group: TrackGroup, val track: Int)

/** The current video's heights, best first, and the one held for it (`null`: Auto, adapting). */
data class VideoQuality(val options: List<QualityOption>, val held: QualityOption?) {
    companion object {
        val None = VideoQuality(emptyList(), held = null)
    }
}

/**
 * The heights the playing video group offers (Q60): one per short side among the tracks the
 * device can play, the highest frame rate (then bitrate) where a height comes twice. The resolve
 * has already dropped what lies over the device's cap (M7-D15). [VideoQuality.held] is the group's
 * video override in [parameters], if it names one of them.
 */
@OptIn(UnstableApi::class)
fun videoQuality(tracks: Tracks, parameters: TrackSelectionParameters): VideoQuality {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return VideoQuality.None
    val media = group.mediaTrackGroup
    val options = (0 until group.length)
        .filter(group::isTrackSupported)
        .mapNotNull { index -> media.getFormat(index).lines()?.let { lines -> lines to index } }
        .groupBy({ it.first }, { it.second })
        .map { (lines, indices) ->
            val best = indices.maxWith(compareBy({ media.getFormat(it).frameRate }, { media.getFormat(it).bitrate }))
            QualityOption(lines, media, best)
        }
        .sortedByDescending { it.lines }
    val heldLines = parameters.overrides[media]?.trackIndices?.singleOrNull()?.let { media.getFormat(it).lines() }
    return VideoQuality(options, options.firstOrNull { it.lines == heldLines })
}

private fun Format.lines(): Int? = pictureLines(width, height)

/**
 * Holds the playing video at [option] (a track override on its group), or lets it adapt again
 * (`null`, Auto). The override outlives the video, and another video whose tracks look alike
 * would match it, so a player screen calls this with `null` as it asks for another video. Auto with
 * nothing held asks the player for nothing.
 */
fun Player.holdQuality(option: QualityOption?) {
    val parameters = trackSelectionParameters
    if (option == null && parameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }) return
    trackSelectionParameters = parameters.buildUpon().apply {
        if (option != null) setOverrideForType(TrackSelectionOverride(option.group, option.track)) else clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    }.build()
}
