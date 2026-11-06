package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import kotlin.random.Random

/** The list Play all goes through. */
sealed interface PlayAll {
    val title: String

    /** -1 when unknown. */
    val videoCount: Long

    /** A YouTube playlist, paged from the extractor (ui-rebuild B8p). */
    data class YouTube(val playlist: PlaylistItem) : PlayAll {
        override val title: String get() = playlist.title
        override val videoCount: Long get() = playlist.videoCount
    }

    /**
     * A playlist saved in KE (`b3-library`), in its order, or shuffled by [shuffleSeed]: the seed
     * rides the route, so a recreated player screen deals the same order again.
     */
    data class Saved(
        val playlistId: Long,
        override val title: String,
        override val videoCount: Long,
        val shuffleSeed: Long? = null,
    ) : PlayAll
}

/** [videos] in the order Play all plays them: as listed, or dealt by [shuffleSeed]. The playlist
 *  page deals the same order to pick the video Shuffle opens with. */
fun playOrder(videos: List<VideoItem>, shuffleSeed: Long?): List<VideoItem> =
    if (shuffleSeed == null) videos else videos.shuffled(Random(shuffleSeed))
