package com.arshad.ayn.ui.tv.shell

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.ApproveNotice
import com.arshad.ayn.ui.tv.library.approveNoticeLine
import com.arshad.ayn.ui.tv.library.approveRowHint
import com.arshad.ayn.ui.tv.search.SearchCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A kids profile's shell copy (`kp-kids` kidsearch, kidsearchnone, approvemenu, approved). */
class KidsShellCopyTest {

    @Test
    fun kidsSearchSaysItLooksOnlyThroughWhatIsApproved() {
        val kids = SearchCopy("Zainab")

        assertEquals("Search · only what’s approved for Zainab", kids.label)
        assertEquals("Nothing approved matches “trucks”", kids.noResultsTitle("trucks"))
        assertEquals("Ask a grown-up to approve more.", kids.noResultsBody)
        assertEquals("Search", SearchCopy(null).label)
        assertEquals("Nothing matched that search", SearchCopy(null).noResultsTitle("trucks"))
    }

    @Test
    fun approveOffersTheChannelOnlyWhenTheVideoNamesOne() {
        val video = VideoItem(VideoId("aaaaaaaaaaa"), "Arctic", "BBC Earth", "https://www.youtube.com/channel/UCa", 60, 1, null, null, null, isLive = false)

        assertEquals("This video, or all of BBC Earth", approveRowHint(video))
        assertEquals("This video", approveRowHint(video.copy(uploaderUrl = null)))
    }

    @Test
    fun theApprovedNoticeNamesTheKidAndWhatWasApproved() {
        val line = approveNoticeLine(ApproveNotice("Zainab", "Extraordinary Wildlife", done = true))
        assertEquals("Approved for Zainab", line.text)
        assertEquals("Extraordinary Wildlife", line.muted)

        assertFalse(approveNoticeLine(ApproveNotice("Zainab", "Arctic", done = false)).undo)
    }
}
