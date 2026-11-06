package com.arshad.ayn.ui.tv.playlist

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row's line under its title. */
class PlaylistRowsTest {

    private val video = VideoItem(
        id = VideoId("aaaaaaaaaaa"),
        title = "T",
        uploaderName = "NASA",
        uploaderUrl = null,
        durationSeconds = 0,
        viewCount = 110,
        textualUploadDate = "2 days ago",
        uploadDate = null,
        thumbnailUrl = null,
        isLive = false,
    )

    @Test
    fun aVideosLineIsItsUploaderViewsAndAge() {
        assertEquals("NASA · 110 views · 2 days ago", rowLine(video))
    }

    @Test
    fun aLiveStreamsLineIsItsAudienceInPlaceOfViewsAndAge() {
        assertEquals("NASA · 110 watching", rowLine(video.copy(isLive = true)))
    }

    /** The listing gave no start here, so YouTube's own date text follows the label. */
    @Test
    fun anUpcomingVideosLineSaysItIsUpcoming() {
        val premiere = video.copy(upcoming = true, viewCount = -1, textualUploadDate = "03/11/2026, 16:00")

        assertEquals("NASA · Upcoming · 03/11/2026, 16:00", rowLine(premiere))
    }

    @Test
    fun aVideoWithNothingKnownHasNoLine() {
        assertNull(rowLine(video.copy(uploaderName = null, viewCount = -1, textualUploadDate = null)))
    }
}
