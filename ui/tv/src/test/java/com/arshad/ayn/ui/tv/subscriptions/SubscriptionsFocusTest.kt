package com.arshad.ayn.ui.tv.subscriptions

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.feature.subscriptions.FeedState
import com.arshad.ayn.feature.subscriptions.SubscriptionsLayout
import com.arshad.ayn.feature.subscriptions.SubscriptionsUiState
import com.arshad.ayn.feature.subscriptions.subscriptionsLayout
import java.time.DayOfWeek
import java.time.ZoneOffset
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where Subscriptions puts focus first. */
class SubscriptionsFocusTest {

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

    private fun layout(state: SubscriptionsUiState) =
        subscriptionsLayout(state, ZoneOffset.UTC, DayOfWeek.MONDAY) as SubscriptionsLayout.Feed

    @Test
    fun everyChannelsFeedFocusesItsFirstCard() {
        assertEquals(videoKey(video), layout(SubscriptionsUiState(listOf(a, b), feedState = ready())).initialFocusKey)
    }

    @Test
    fun aPartialFeedFocusesTheNotesTryAgain() {
        assertEquals(RetryFailedKey, layout(SubscriptionsUiState(listOf(a, b), feedState = ready(failed = setOf(B)))).initialFocusKey)
    }

    @Test
    fun aSelectedChannelFocusesItsAvatar() {
        assertEquals(avatarKey(A), layout(SubscriptionsUiState(listOf(a, b), filter = A, feedState = ready(failed = setOf(B)))).initialFocusKey)
    }

    @Test
    fun aFeedWithNoVideosFocusesAll() {
        assertEquals(AllKey, layout(SubscriptionsUiState(listOf(a), feedState = ready(videos = emptyList()))).initialFocusKey)
    }

    @Test
    fun aFeedOfOnlyShortsFocusesTheFirstShortWhileShowShortsIsOn() {
        val shortsOnly = ready(videos = emptyList(), shorts = listOf(short))

        assertEquals(shortKey(short.id), layout(SubscriptionsUiState(listOf(a), feedState = shortsOnly, showShorts = true)).initialFocusKey)
        assertEquals(AllKey, layout(SubscriptionsUiState(listOf(a), feedState = shortsOnly)).initialFocusKey)
    }

    private companion object {
        const val A = "https://www.youtube.com/channel/UCa"
        const val B = "https://www.youtube.com/channel/UCb"
        val NOW: Instant = Instant.parse("2026-06-28T16:00:00Z")
    }
}
