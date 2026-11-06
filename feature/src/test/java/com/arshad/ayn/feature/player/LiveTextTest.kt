package com.arshad.ayn.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTextTest {
    @Test
    fun `over the picture a live stream's Live chat view names no view after the title`() {
        assertNull(chatViewName(replay = false, liveView = true))
    }

    @Test
    fun `the Top chat view and a replay's views are named after the title`() {
        assertEquals("Top chat", chatViewName(replay = false, liveView = false))
        assertEquals("Top chat", chatViewName(replay = true, liveView = false))
        assertEquals("Live chat", chatViewName(replay = true, liveView = true))
    }
}
