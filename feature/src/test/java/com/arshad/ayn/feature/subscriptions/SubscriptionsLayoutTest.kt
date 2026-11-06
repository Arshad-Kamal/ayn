package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.core.store.Subscription
import java.time.DayOfWeek
import java.time.ZoneOffset
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Subscriptions' feed draws: a picked channel's body, the partial note, blocks taking cards
 *  out, and a filter naming a channel no longer subscribed. */
class SubscriptionsLayoutTest {

    private val a = Subscription(A, name = "A", avatarUrl = null, subscribedAtMillis = 0L, updatedAtMillis = 0L)
    private val b = a.copy(channelUrl = B, name = "B")
    private val video = VideoItem(
        id = VideoId("aaaaaaaaaaa"), title = "t", uploaderName = "A", uploaderUrl = A, durationSeconds = 1,
        viewCount = 1, textualUploadDate = null, uploadDate = NOW, thumbnailUrl = null, isLive = false,
    )

    private val short = video.copy(id = VideoId("sssssssssss"))

    private fun ready(failed: Set<String> = emptySet(), videos: List<VideoItem> = listOf(video), shorts: List<VideoItem> = emptyList()) = FeedState.Ready(
        entries = videos,
        videosByChannel = mapOf(A to videos),
        failedChannels = failed,
        newIds = emptySet(),
        loadedAt = NOW,
        shorts = shorts,
        shortsByChannel = mapOf(A to shorts),
    )

    private fun layout(state: SubscriptionsUiState, filter: ContentFilter? = null) =
        subscriptionsLayout(state, ZoneOffset.UTC, DayOfWeek.MONDAY, filter) as SubscriptionsLayout.Feed

    @Test
    fun everyChannelsFeedNamesTheChannelsThatDidntAnswerAndMarksThemInTheStrip() {
        val feed = layout(SubscriptionsUiState(listOf(a, b), feedState = ready(failed = setOf(B))))

        assertEquals(listOf("B"), feed.failedNames)
        assertEquals(listOf(StripChannel(a, failed = false), StripChannel(b, failed = true)), feed.channels)
    }

    @Test
    fun aSelectedChannelHidesThePartialNote() {
        val feed = layout(SubscriptionsUiState(listOf(a, b), filter = A, feedState = ready(failed = setOf(B))))

        assertEquals(a, feed.selected)
        assertTrue(feed.failedNames.isEmpty())
    }

    @Test
    fun aSelectedChannelThatDidntAnswerFailed() {
        assertEquals(FeedBody.ChannelFailed(b), layout(SubscriptionsUiState(listOf(a, b), filter = B, feedState = ready(failed = setOf(B)))).body)
    }

    @Test
    fun aPickedChannelWithOnlyShortsListsThemWhileShowShortsIsOn() {
        val shortsOnly = ready(videos = emptyList(), shorts = listOf(short))

        assertEquals(FeedBody.Latest(a, emptyList(), listOf(short)), layout(SubscriptionsUiState(listOf(a), filter = A, feedState = shortsOnly, showShorts = true)).body)
        assertEquals(FeedBody.ChannelEmpty(a), layout(SubscriptionsUiState(listOf(a), filter = A, feedState = shortsOnly)).body)
    }

    @Test
    fun aBlockedVideoOrShortLeavesEveryChannelsFeedAndAPickedChannels() {
        val other = video.copy(id = VideoId("bbbbbbbbbbb"))
        val feed = ready(videos = listOf(video, other), shorts = listOf(short))
        val blocked = blockingVideos(video.id.value, short.id.value)

        val days = layout(SubscriptionsUiState(listOf(a), feedState = feed, showShorts = true), blocked).body as FeedBody.Days
        assertEquals(listOf(listOf(other)), days.groups.map { it.videos })
        assertEquals(emptyList<VideoItem>(), days.shorts)
        val picked = layout(SubscriptionsUiState(listOf(a), filter = A, feedState = feed, showShorts = true), blocked)
        assertEquals(FeedBody.Latest(a, listOf(other), emptyList()), picked.body)
    }

    @Test
    fun aPickedChannelWhoseVideosAndShortsAreAllBlockedIsEmpty() {
        val feed = ready(videos = listOf(video), shorts = listOf(short))
        val state = SubscriptionsUiState(listOf(a), filter = A, feedState = feed, showShorts = true)

        assertEquals(FeedBody.ChannelEmpty(a), layout(state, blockingVideos(video.id.value, short.id.value)).body)
    }

    @Test
    fun aFilterNamingAChannelNoLongerSubscribedShowsEveryChannel() {
        val feed = layout(SubscriptionsUiState(listOf(a), filter = B, feedState = ready()))

        assertEquals(null, feed.selected)
        assertTrue(feed.body is FeedBody.Days)
    }

    private companion object {
        const val A = "https://www.youtube.com/channel/UCa"
        const val B = "https://www.youtube.com/channel/UCb"
        val NOW: Instant = Instant.parse("2026-06-28T16:00:00Z")
    }
}
