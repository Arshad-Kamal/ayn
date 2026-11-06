package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.VoteLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Details' votes line by Q27. Counts are `b3-player-panels` details' real
 * ones (fetched 2026-10-03): 120,903 likes, 1,293 dislikes, drawn as "98.9% liked".
 */
class VoteRowTest {

    @Test
    fun likesAndDislikesShowTheRatio() {
        val row = voteRow(LIKES, DetailsVotes.Found(DISLIKES))!!

        assertEquals(LIKES, row.likes)
        assertEquals(DISLIKES, row.dislikes)
        assertEquals(98.9, row.likedPercent!!, 0.05)
    }

    @Test
    fun dislikesOffShowsTheLikesAlone() {
        assertEquals(VoteRow(LIKES, dislikes = null, likedPercent = null), voteRow(LIKES, DetailsVotes.Off))
    }

    @Test
    fun theLikesShowAloneWhileTheDislikesLoad() {
        assertEquals(VoteRow(LIKES, dislikes = null, likedPercent = null), voteRow(LIKES, DetailsVotes.Loading))
    }

    @Test
    fun hiddenLikesShowTheDislikesWithoutARatio() {
        assertEquals(VoteRow(likes = null, dislikes = DISLIKES, likedPercent = null), voteRow(null, DetailsVotes.Found(DISLIKES)))
    }

    @Test
    fun noDislikeDataSaysSoBesideTheLikes() {
        assertEquals(VoteRow(LIKES, dislikes = null, likedPercent = null, noDislikeData = true), voteRow(LIKES, DetailsVotes.NoData))
    }

    @Test
    fun noDislikeDataSaysSoEvenWithTheLikesHidden() {
        assertEquals(VoteRow(likes = null, dislikes = null, likedPercent = null, noDislikeData = true), voteRow(null, DetailsVotes.NoData))
    }

    @Test
    fun hiddenLikesWithDislikesOffShowNothing() {
        assertNull(voteRow(null, DetailsVotes.Off))
        assertNull(voteRow(null, DetailsVotes.Loading))
    }

    @Test
    fun noVotesEitherWayHaveNoRatio() {
        assertNull(voteRow(0, DetailsVotes.Found(0))!!.likedPercent)
    }

    @Test
    fun noDataAFailureAndARefusalReadTheSame() {
        assertEquals(DetailsVotes.NoData, DetailsVotes.of(VoteLookup.None))
        assertEquals(DetailsVotes.NoData, DetailsVotes.of(VoteLookup.Failed))
        assertEquals(DetailsVotes.Found(DISLIKES), DetailsVotes.of(VoteLookup.Found(DISLIKES)))
    }
}

private const val LIKES = 120_903L
private const val DISLIKES = 1_293L
