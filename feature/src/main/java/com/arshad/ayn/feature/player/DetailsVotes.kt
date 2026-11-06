package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.VoteLookup

/** Return YouTube Dislike's part of Details for the turn's video, asked once Details opens (M7-D5). */
sealed interface DetailsVotes {
    /** The profile has Dislikes off, or its settings couldn't be read: nothing was asked. */
    data object Off : DetailsVotes

    data object Loading : DetailsVotes

    data class Found(val dislikes: Long) : DetailsVotes

    /** No data, a failed lookup and a refused one (rate limit) read the same. */
    data object NoData : DetailsVotes

    companion object {
        fun of(lookup: VoteLookup): DetailsVotes = when (lookup) {
            is VoteLookup.Found -> Found(lookup.dislikes)
            VoteLookup.None, VoteLookup.Failed -> NoData
        }
    }
}

/**
 * Details' votes line: YouTube's [likes], RYD's [dislikes] (shown with RYD's credit), and the
 * ratio only when both are known; [noDislikeData] puts "No dislike data for this video" in the
 * dislikes' place.
 */
data class VoteRow(
    val likes: Long?,
    val dislikes: Long?,
    /** 0..100; `null` unless both counts are known and not both zero. */
    val likedPercent: Double?,
    val noDislikeData: Boolean = false,
)

/**
 * The votes line for [likes] (`null` when the uploader hides them) and [votes] (Q27): Dislikes
 * off, or still loading, shows the likes alone; hidden likes show the dislikes alone, with no
 * ratio; no RYD data says so beside the likes. `null` when there is nothing to show.
 */
fun voteRow(likes: Long?, votes: DetailsVotes): VoteRow? = when (votes) {
    DetailsVotes.Off, DetailsVotes.Loading -> likes?.let { VoteRow(it, dislikes = null, likedPercent = null) }
    is DetailsVotes.Found -> VoteRow(likes, votes.dislikes, likes?.let { likedPercent(it, votes.dislikes) })
    DetailsVotes.NoData -> VoteRow(likes, dislikes = null, likedPercent = null, noDislikeData = true)
}

private fun likedPercent(likes: Long, dislikes: Long): Double? {
    val total = likes + dislikes
    return if (total == 0L) null else likes * 100.0 / total
}
