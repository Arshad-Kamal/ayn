package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A playlist page through the seam: details (an owner-less album too), playlist order,
 *  continuation, and YouTube's "doesn't exist" answer (goldens read from the raw payloads into `RecordedCorpus.kt`). */
class NewPipeAccessPlaylistFixtureTest {

    @Test
    fun theDetailsCarryOwnerCountAvatarAndCover() {
        val page = playlist(PLAYLIST_FLOW, PLAYLIST_URL)

        assertEquals(
            PlaylistDetails(
                title = RecordedPlaylist.TITLE,
                uploaderName = RecordedPlaylist.UPLOADER_NAME,
                uploaderUrl = RECORDED_CHANNEL_URL,
                uploaderAvatarUrl = RecordedPlaylist.UPLOADER_AVATAR_URL,
                videoCount = RecordedPlaylist.VIDEO_COUNT,
                thumbnailUrl = RecordedPlaylist.THUMBNAIL_URL,
            ),
            page.details,
        )
    }

    @Test
    fun videosArriveInPlaylistOrderInOnePage() {
        val videos = playlist(PLAYLIST_FLOW, PLAYLIST_URL).videos

        val ids = videos.items.map { (it as VideoItem).id.value }
        assertEquals(RecordedPlaylist.VIDEO_COUNT.toInt(), ids.size)
        assertEquals(RecordedPlaylist.FIRST_IDS, ids.take(3))
        assertEquals(RecordedPlaylist.LAST_ID, ids.last())
        assertEquals(
            "a repeated video stays where the playlist has it",
            RecordedPlaylist.REPEATED_POSITIONS,
            ids.indices.filter { ids[it] == RecordedPlaylist.REPEATED_ID },
        )
        assertNull("the whole playlist fits the first page", videos.next)
    }

    /** The row line of the mockup: length, views and age come with every playlist video. */
    @Test
    fun aPlaylistVideoCarriesLengthViewsAndAge() {
        val first = playlist(PLAYLIST_FLOW, PLAYLIST_URL).videos.items.first() as VideoItem

        assertEquals(RecordedPlaylist.FIRST_DURATION_SECONDS, first.durationSeconds)
        assertEquals(RecordedPlaylist.FIRST_VIEW_COUNT, first.viewCount)
        assertEquals(RecordedPlaylist.FIRST_AGE, first.textualUploadDate)
        assertNotNull("the age parses to an instant", first.uploadDate)
        assertEquals(RecordedPlaylist.UPLOADER_NAME, first.uploaderName)
    }

    @Test
    fun aLongPlaylistContinuesWithItsNextPage() {
        val access = NewPipeAccess(downloader = FixtureDownloader(PAGED_PLAYLIST_FLOW))

        val first = runBlocking { access.playlist(PAGED_PLAYLIST_URL) }
        val second = runBlocking { access.playlistVideos(PAGED_PLAYLIST_URL, first.videos.next!!) }

        assertEquals(RecordedPlaylist.PAGED_VIDEO_COUNT, first.details.videoCount)
        assertEquals(RecordedPlaylist.PAGE_SIZE, first.videos.items.size)
        assertEquals(RecordedPlaylist.PAGE_1_FIRST_ID, (first.videos.items.first() as VideoItem).id.value)
        assertEquals(RecordedPlaylist.PAGE_SIZE, second.items.size)
        assertEquals(RecordedPlaylist.PAGE_2_FIRST_ID, (second.items.first() as VideoItem).id.value)
        assertTrue("438 videos continue past page 2", second.next != null)
    }

    /** A Releases-tab album opens as a playlist; its header names no owner. */
    @Test
    fun anAlbumHasNoOwnerAndASquareCover() {
        val page = playlist(ALBUM_PLAYLIST_FLOW, ALBUM_PLAYLIST_URL)

        assertEquals(
            PlaylistDetails(
                title = RecordedAlbumPlaylist.TITLE,
                uploaderName = null,
                uploaderUrl = null,
                uploaderAvatarUrl = null,
                videoCount = RecordedAlbumPlaylist.VIDEO_COUNT,
                thumbnailUrl = RecordedAlbumPlaylist.THUMBNAIL_URL,
            ),
            page.details,
        )
        assertEquals(RecordedAlbumPlaylist.IDS, page.videos.items.map { (it as VideoItem).id.value })
    }

    @Test
    fun aPlaylistThatDoesNotExistFailsAsUnavailable() {
        val access = NewPipeAccess(downloader = FixtureDownloader(PLAYLIST_UNAVAILABLE_FLOW))

        try {
            runBlocking { access.playlist(UNAVAILABLE_PLAYLIST_URL) }
            fail("a missing playlist must fail")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.UNAVAILABLE, e.failure)
            assertNull("a playlist refusal names no video refusal", e.refusal)
        }
    }

    /** On the playlist calls an InnerTube 404 reads as "not found" too. */
    @Test
    fun aPlaylistAnswering404FailsAsUnavailable() {
        val access = NewPipeAccess(downloader = FixtureDownloader(PLAYLIST_FLOW, responseCode = 404))

        try {
            runBlocking { access.playlist(PLAYLIST_URL) }
            fail("a 404 must fail")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.UNAVAILABLE, e.failure)
        }
    }

    private fun playlist(flow: String, url: String): PlaylistPage {
        val access = NewPipeAccess(downloader = FixtureDownloader(flow))
        return runBlocking { access.playlist(url) }
    }
}
