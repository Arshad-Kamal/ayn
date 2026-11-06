package com.arshad.ayn.core.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VideoId.isValid]: eleven characters of `[A-Za-z0-9_-]`, nothing else. */
class VideoIdTest {

    @Test
    fun aWatchIdOfElevenAsciiCharactersIsValid() {
        assertTrue(VideoId.isValid("dQw4w9WgX-_"))
    }

    @Test
    fun aLengthOtherThanElevenIsInvalid() {
        assertFalse(VideoId.isValid("dQw4w9WgXc"))
        assertFalse(VideoId.isValid("dQw4w9WgXcQQ"))
    }

    @Test
    fun nonAsciiLettersAndDigitsAreInvalid() {
        assertFalse(VideoId.isValid("dQw4w9WgXcé"))
        assertFalse(VideoId.isValid("dQw4w9WgXc٣"))
    }
}
