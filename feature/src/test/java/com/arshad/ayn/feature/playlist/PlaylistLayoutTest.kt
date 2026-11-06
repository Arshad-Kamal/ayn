package com.arshad.ayn.feature.playlist

import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.blockingVideos
import org.junit.Assert.assertEquals
import org.junit.Test

/** A playlist page's rows under the open profile's filter (KP-D19). */
class PlaylistLayoutTest {

    private val details = PlaylistDetails("P", null, null, null, videoCount = 2, thumbnailUrl = null)
    private val first = VideoItem(VideoId("aaaaaaaaaaa"), "a", null, null, 60, 1, null, null, null, isLive = false)
    private val second = first.copy(id = VideoId("bbbbbbbbbbb"), title = "b")

    private fun ready(vararg videos: VideoItem) = PlaylistUiState(details, PlaylistVideosState.Ready(videos.toList(), hasMore = true))

    @Test
    fun aBlockedVideosRowLeavesAndTheRowsAfterItMoveUp() {
        val layout = playlistLayout(ready(first, second), blockingVideos(first.id.value))

        assertEquals(PlaylistLayout.Videos(details, isAlbum = false, videos = listOf(second), hasMore = true), layout)
    }

    @Test
    fun aBlockOfEveryRowLeavesThePageEmpty() {
        assertEquals(PlaylistLayout.Empty(details, isAlbum = false), playlistLayout(ready(first), blockingVideos(first.id.value)))
    }
}
