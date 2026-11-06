package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.foryou.SeedKind
import com.arshad.ayn.feature.library.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The TV card menu's own lines (`b3-library` menu). */
class LibraryCopyTest {
    /** FY-D8: a For you pick's menu line, worded by its seed's kind. */
    @Test
    fun aForYouReasonSaysWhatThePickCameFrom() {
        val seed = VideoId("seed0000001")
        assertEquals("Because you watched Enigma", reasonText(ForYouReason(seed, SeedKind.Watched, "Enigma")).text)
        assertEquals("Because you saved Enigma", reasonText(ForYouReason(seed, SeedKind.Saved, "Enigma")).text)
        assertEquals("Because you follow Veritasium", reasonText(ForYouReason(seed, SeedKind.Followed, "Veritasium")).text)
    }

    @Test
    fun theMenuResumesFromThePlaceAsTheBadgeWritesIt() {
        assertEquals("Resume from 34:51", resumeText(2_091_000))
        assertEquals("Resume from 1:02:03", resumeText(3_723_000))
    }

    @Test
    fun theMenuRemovesOnlyFromWhereTheVideoWasListed() {
        assertEquals("Remove from history", removeText(VideoSource.History))
        assertEquals("Remove from Watch later", removeText(VideoSource.WatchLater))
        assertEquals("Remove from this playlist", removeText(VideoSource.Playlist(4)))
        assertNull(removeText(VideoSource.Elsewhere))
    }
}
