package com.arshad.ayn.ui.tv.subscriptions

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which row each card and the shelf join on (four columns). */
class FeedRowStepsTest {

    private fun videos(vararg ids: Int) = ids.map {
        VideoItem(VideoId("v%010d".format(it)), "t", null, null, 1, 1, null, null, null, isLive = false)
    }

    private fun steps(groups: List<FeedGroup>, shelf: FeedShelf?): Pair<List<Int>, Int> {
        val steps = rowSteps(groups, shelf)
        return groups.flatMap { it.videos }.map { steps.card.getValue(videoKey(it)) } to steps.shelf
    }

    @Test
    fun eachGroupStartsARowAndTheShelfTakesOneAfterTheFirstGroupsSplit() {
        val today = FeedGroup("Today", null, videos(1, 2, 3, 4, 5, 6))
        val june = FeedGroup("June", null, videos(7))
        val shelf = FeedShelf("From your channels", videos(90), afterVideos = 4, channelUrl = null)

        // Today: 1–4 row 0, the shelf row 1, 5–6 row 2; June row 3.
        assertEquals(listOf(0, 0, 0, 0, 2, 2, 3) to 1, steps(listOf(today, june), shelf))
    }

    @Test
    fun withoutAShelfRowsFollowTheGroups() {
        val first = FeedGroup("Last week", null, videos(1, 2, 3))
        val second = FeedGroup("June", null, videos(4, 5, 6, 7, 8))

        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 2), steps(listOf(first, second), null).first)
    }

    @Test
    fun aShelfAloneIsTheFirstRow() {
        assertEquals(emptyList<Int>() to 0, steps(emptyList(), FeedShelf("From A", videos(90), afterVideos = 4, channelUrl = "a")))
    }
}
