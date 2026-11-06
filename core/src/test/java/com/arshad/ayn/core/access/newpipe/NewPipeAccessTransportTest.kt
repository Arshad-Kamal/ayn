package com.arshad.ayn.core.access.newpipe

import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/**
 * Pins the transport-install semantics the fixture/live separation relies on (M3.6): every
 * [NewPipeAccess] construction installs its own downloader over the extractor's process-global
 * transport, so test order can never leak one suite's downloader into another.
 */
class NewPipeAccessTransportTest {

    @Test
    fun mostRecentConstructionOwnsTheProcessTransport() {
        val first = FixtureDownloader("video")
        NewPipeAccess(downloader = first)
        val second = FixtureDownloader("video")
        NewPipeAccess(downloader = second)

        assertTrue("the extractor must run on the most recent transport", NewPipe.getDownloader() === second)
    }
}
