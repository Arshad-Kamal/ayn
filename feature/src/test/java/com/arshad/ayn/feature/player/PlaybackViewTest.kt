package com.arshad.ayn.feature.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import com.arshad.ayn.core.access.SegmentLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The frame rate AFR reads and the codec (only from the requested, resolved video), and the D8
 *  extras parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackViewTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the rate and codec come only from the requested video once it has resolved`() {
        val player = ScriptedPlayer().apply { frameRate = 24f }
        player.setMediaItem(dressedItem("previous0000"))
        player.prepare()
        player.ready()
        idle()
        val view = PlaybackView(player).also(player::addListener)

        view.request("requested00")
        assertNull("the last screen's video, still loaded, sets no rate", view.frameRate)
        assertNull(view.codec)

        player.frameRate = 30f
        player.setMediaItem(dressedItem("requested00"))
        player.prepare()
        player.ready()
        idle()
        assertEquals(30f, view.frameRate)
        assertEquals("VP9", view.codec)

        // The next video, still masked (no dressing): its tracks aren't trusted, the last rate holds.
        view.request("next0000000")
        player.frameRate = 24f
        player.setMediaItem(MediaItem.Builder().setMediaId("next0000000").build())
        player.prepare()
        player.ready()
        idle()
        assertEquals(30f, view.frameRate)
    }

    @Test
    fun `the dressing is parsed again only for a new extras object`() {
        val player = ScriptedPlayer()
        player.setMediaItem(dressedItem("requested00"))
        player.prepare()
        player.ready()
        idle()
        val view = PlaybackView(player).also(player::addListener)
        view.request("requested00")
        val dressing = checkNotNull(view.dressing)

        // Emptied behind the same object, then an event batch with no new item: nothing re-reads it.
        checkNotNull(player.currentMediaItem?.mediaMetadata?.extras).clear()
        player.play()
        idle()
        assertTrue("the batch was synced", view.playWhenReady)
        assertSame(dressing, view.dressing)

        player.setMediaItem(dressedItem("requested00", segments = SegmentLookup.None))
        player.prepare()
        player.ready()
        idle()
        assertEquals(SegmentLookup.None, view.dressing?.segments)
    }

    @Test
    fun `a controller's masked turn change carries no dressing until the new item's own lands`() {
        val service = FakePlaybackService(RuntimeEnvironment.getApplication())
        val controller = MediaController.Builder(RuntimeEnvironment.getApplication(), service.session.token).buildAsync()
        idle()
        val player = controller.get()
        try {
            val view = PlaybackView(player).also(player::addListener)
            service.page = RecordedEnigmaPage.copy(uploaderName = "Previous channel")
            player.setMediaItem(MediaItem.Builder().setMediaId("previous0000").build())
            player.prepare()
            idle()
            assertEquals("Previous channel", view.dressing?.uploaderName)

            service.page = RecordedEnigmaPage.copy(uploaderName = "Next channel")
            player.setMediaItem(MediaItem.Builder().setMediaId("next0000000").build())
            view.sync()
            assertEquals("next0000000", view.mediaId)
            assertFalse("the masked item wears the previous video's dressing", view.carries("next0000000"))

            idle()
            assertTrue(view.carries("next0000000"))
            val dressing = checkNotNull(view.dressing)
            assertEquals("Next channel", dressing.uploaderName)

            // Session updates that keep the item don't hand over a new extras object to parse.
            service.player.ready()
            player.play()
            idle()
            assertTrue("the batches were synced", view.playWhenReady)
            assertSame(dressing, view.dressing)
        } finally {
            player.release()
            service.release()
        }
    }

    @Test
    fun `the content rate is the highest selected video track's`() {
        assertEquals(24f, contentFrameRate(videoTracks(24f)))
        val ladder = Tracks.Group(
            TrackGroup(videoFormat(30f), videoFormat(60f), videoFormat(null)),
            true,
            IntArray(3) { C.FORMAT_HANDLED },
            booleanArrayOf(true, true, true),
        )
        assertEquals(60f, contentFrameRate(Tracks(listOf(ladder))))
        val noRate = Tracks.Group(TrackGroup(videoFormat(null)), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        assertNull(contentFrameRate(Tracks(listOf(noRate))))
        assertNull(contentFrameRate(Tracks.EMPTY))
        val unselected = videoTracks(30f).groups.single().let { Tracks.Group(it.mediaTrackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)) }
        assertNull(contentFrameRate(Tracks(listOf(unselected))))
    }

    private fun videoFormat(rate: Float?): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).apply { rate?.let(::setFrameRate) }.build()

    @Test
    fun `retry extras read as the D8 retry they name`() {
        fun extras(kind: String?) = Bundle().apply {
            putString(PlaybackContract.EXTRA_RETRY_KIND, kind)
            putInt(PlaybackContract.EXTRA_ATTEMPT, 2)
            putInt(PlaybackContract.EXTRA_MAX, 6)
        }
        assertEquals(PlayerErrorUiState.Retrying(2, 6), retryFrom(extras(PlaybackContract.RETRY_RECOVERABLE)))
        assertEquals(PlayerErrorUiState.Refreshing(2, 6), retryFrom(extras(PlaybackContract.RETRY_ATTESTATION)))
        assertEquals(PlayerErrorUiState.None, retryFrom(Bundle.EMPTY))
    }
}
