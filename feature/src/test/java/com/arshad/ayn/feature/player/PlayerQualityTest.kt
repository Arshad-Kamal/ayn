package com.arshad.ayn.feature.player

import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The heights a video offers and the one held (Q60), against hand-laid track groups. */
class PlayerQualityTest {

    @Test
    fun `a video offers one choice per height, best first, by its short side`() {
        val group = TrackGroup(
            vp9(1080, 1920, frameRate = 30f),
            vp9(720, 1280, bitrate = 2_000_000),
            vp9(1080, 1920, frameRate = 60f),
            vp9(360, 640),
            vp9(720, 1280, bitrate = 1_000_000),
        )
        val tracks = selected(group, supported = booleanArrayOf(true, true, true, false, true))

        val quality = videoQuality(tracks, TrackSelectionParameters.DEFAULT)

        assertEquals(listOf(1080 to 2, 720 to 1), quality.options.map { it.lines to it.track })
        assertNull(quality.held)
    }

    @Test
    fun `the held height is the override on the playing group, and another group's holds nothing`() {
        val tracks = selected(vp9Ladder)
        val holding = { group: TrackGroup, track: Int ->
            TrackSelectionParameters.DEFAULT.buildUpon().setOverrideForType(TrackSelectionOverride(group, track)).build()
        }

        assertEquals(1080, videoQuality(tracks, holding(vp9Ladder, 2)).held?.lines)
        assertNull(videoQuality(tracks, holding(TrackGroup(vp9(1920, 1080)), 0)).held)
        assertEquals(VideoQuality.None, videoQuality(Tracks.EMPTY, holding(vp9Ladder, 2)))
    }
}
