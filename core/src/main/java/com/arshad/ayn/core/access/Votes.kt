package com.arshad.ayn.core.access

/**
 * Return YouTube Dislike seam (M7-D1/D5): a video's estimated dislike count; likes come from
 * YouTube's own count ([VideoMetadata.likeCount]). Main-safe; never throws for a network or server
 * problem, which comes back as [VoteLookup.Failed].
 */
fun interface VoteSource {
    suspend fun votes(videoId: VideoId): VoteLookup
}

sealed interface VoteLookup {
    data class Found(val dislikes: Long) : VoteLookup {
        init {
            require(dislikes >= 0) { "a dislike count is never negative" }
        }
    }

    /** Return YouTube Dislike has no data for the video. */
    data object None : VoteLookup

    /** Return YouTube Dislike couldn't be reached, refused (rate limit) or answered unreadably. */
    data object Failed : VoteLookup
}
