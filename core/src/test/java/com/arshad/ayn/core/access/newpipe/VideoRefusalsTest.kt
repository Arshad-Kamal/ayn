package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.VideoRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException

/** The fork's refusal message as `checkPlayabilityStatus` builds it; the recorded payloads drive the
 *  fixture tests, these pin the edges no recording holds. */
class VideoRefusalsTest {

    @Test
    fun restrictedModesErrorStatus_namesNoRefusal() {
        assertNull(videoRefusalOf(refusal("ERROR", "This video is unavailable")))
    }

    @Test
    fun aRefusalTheForkAlreadyNamed_isLeftToItsOwnType() {
        assertNull(videoRefusalOf(GeographicRestrictionException("Got error UNPLAYABLE: \"not in your country\"")))
    }

    @Test
    fun aMessageOfAnotherShape_namesNoRefusal() {
        assertNull(videoRefusalOf(ContentNotAvailableException("Got error: \"This playlist does not exist\"")))
    }

    @Test
    fun notStartedWithoutAReason_carriesNoSentence() {
        assertEquals(VideoRefusal.NotStarted(null), videoRefusalOf(refusal("LIVE_STREAM_OFFLINE", "null")))
    }

    @Test
    fun notStartedReason_isOneLineWithoutControlCharacters() {
        val refusal = videoRefusalOf(refusal("LIVE_STREAM_OFFLINE", "  Premieres\n\tin\u20283\u2029hours\u200b.\u0007 "))

        assertEquals(VideoRefusal.NotStarted("Premieres in 3 hours ."), refusal)
    }

    @Test
    fun notStartedReason_longerThanTheCap_endsInAnEllipsisAtTheCap() {
        val message = (videoRefusalOf(refusal("LIVE_STREAM_OFFLINE", "a".repeat(200))) as VideoRefusal.NotStarted).message

        assertEquals("a".repeat(119) + "…", message)
    }

    private fun refusal(status: String, reason: String) =
        ContentNotAvailableException("Got error $status: \"$reason\"")
}
