package com.arshad.ayn.ui.mobile.search

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.upcomingText
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** A result row's meta lines. */
class SearchRowsTest {

    private val premiere = VideoItem(
        id = VideoId("34GOO-dOZNU"),
        title = "Forest Retreat",
        uploaderName = "Chillhop Music",
        uploaderUrl = null,
        durationSeconds = -1,
        viewCount = -1,
        textualUploadDate = "2026-11-03 09:00",
        uploadDate = Instant.parse("2026-11-03T16:00:00Z"),
        thumbnailUrl = null,
        isLive = false,
        upcoming = true,
    )

    /** Search and channel rows pass no clock; they still say when it starts, not the raw schedule. */
    @Test
    fun anUpcomingRowSaysWhenItStartsWithOrWithoutAClock() {
        val expected = "Chillhop Music\n" + premiere.upcomingText()

        assertEquals(expected, rowMeta(premiere))
        assertEquals(expected, rowMeta(premiere, now = Instant.parse("2026-11-02T10:00:00Z")))
    }

    @Test
    fun aStartedRowKeepsYouTubesText() {
        assertEquals("Chillhop Music\n2026-11-03 09:00", rowMeta(premiere.copy(upcoming = false)))
    }
}
