package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The store-read decorators (KP-D1, D20) over in-memory stores and the real [ContentFilters]. */
class FilteredStoresTest {
    private val session = ProfileSession().apply { select(PROFILE) }
    private val rules = FakeFilterStore()
    private val settings = FakeProfileSettingsStore(selectedId = PROFILE)
    private val filters = ContentFilters(session, settings, rules)

    @Test
    fun historyAndContinueWatchingDropABlockedChannelsEntriesInOrder() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER), entry("aaaaaaaaaa2", BLOCKED), entry("aaaaaaaaaa3", OTHER)) }
        block(BlockedChannel(BLOCKED, null))
        val store = FilteredWatchStore(watch, filters)

        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa3"), store.history().first().map { it.videoId.value })
        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa3"), store.resumePoints().first().map { it.videoId.value })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRuleAddedWhileHistoryShowsDropsItsEntryAtOnce() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER, title = "prank war"), entry("aaaaaaaaaa2", OTHER)) }
        val seen = mutableListOf<List<String>>()
        val showing = launch(UnconfinedTestDispatcher(testScheduler)) {
            FilteredWatchStore(watch, filters).history().collect { list -> seen += list.map { it.videoId.value } }
        }

        block(Keyword("prank"))
        showing.cancel()

        assertEquals(listOf(listOf("aaaaaaaaaa1", "aaaaaaaaaa2"), listOf("aaaaaaaaaa2")), seen)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun entriesPlayedAsShortsShowOnlyWhileShowShortsIsOn() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER, isShort = true), entry("aaaaaaaaaa2", OTHER)) }
        val store = FilteredWatchStore(watch, filters)
        val seen = mutableListOf<List<String>>()
        val showing = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.history().collect { list -> seen += list.map { it.videoId.value } }
        }

        assertEquals(listOf("aaaaaaaaaa2"), store.resumePoints().first().map { it.videoId.value })
        settings.update(PROFILE) { it.copy(showShorts = true) }
        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa2"), store.resumePoints().first().map { it.videoId.value })
        settings.update(PROFILE) { it.copy(showShorts = false) }
        showing.cancel()

        assertEquals(listOf(listOf("aaaaaaaaaa2"), listOf("aaaaaaaaaa1", "aaaaaaaaaa2"), listOf("aaaaaaaaaa2")), seen)
    }

    @Test
    fun theOwnersHideShortsHidesEntriesPlayedAsShortsWhateverShowShortsSays() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER, isShort = true), entry("aaaaaaaaaa2", OTHER)) }
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(showShorts = true))
        rules.rulesById.value = mapOf(PROFILE to ProfileRules(restrictions = ProfileRestrictions(hideShorts = true)))

        assertEquals(listOf("aaaaaaaaaa2"), FilteredWatchStore(watch, filters).history().first().map { it.videoId.value })
        assertFalse(filters.shortsShownNow())
    }

    /** PAF-D6: Show Shorts off alone (no filter hides them) refuses a Short too. */
    @Test
    fun shortsShowOnlyWithShowShortsOnAndNoneWithNoProfileOpen() = runTest {
        assertFalse(filters.shortsShownNow())

        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(showShorts = true))

        assertTrue(filters.shortsShownNow())
        assertFalse(ContentFilters(ProfileSession(), settings, rules).shortsShownNow())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSwitchToAProfileThatShowsShortsShowsTheEntriesPlayedAsShorts() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER, isShort = true), entry("aaaaaaaaaa2", OTHER)) }
        settings.byProfile.value = mapOf(OTHER_PROFILE to ProfileSettings(showShorts = true))
        val seen = mutableListOf<List<String>>()
        val showing = launch(UnconfinedTestDispatcher(testScheduler)) {
            FilteredWatchStore(watch, filters).history().collect { list -> seen += list.map { it.videoId.value } }
        }

        session.select(OTHER_PROFILE)
        settings.selected.value = OTHER_PROFILE
        showing.cancel()

        assertEquals(listOf(listOf("aaaaaaaaaa2"), listOf("aaaaaaaaaa1", "aaaaaaaaaa2")), seen)
    }

    @Test
    fun aPlaylistWatchLaterAndEveryPlaylistsVideosDropAFilteredVideo() = runTest {
        val playlists = FakePlaylistStore()
        val id = playlists.seed("Mix", video(id = "aaaaaaaaaa2", channelUrl = BLOCKED).copy(thumbnailUrl = "blocked.jpg"), video(id = "aaaaaaaaaa1", channelUrl = OTHER).copy(thumbnailUrl = "shown.jpg"))
        playlists.addToWatchLater(video(id = "aaaaaaaaaa3", channelUrl = BLOCKED))
        playlists.addToWatchLater(video(id = "aaaaaaaaaa4", channelUrl = OTHER))
        block(BlockedChannel(BLOCKED, null))
        val store = FilteredPlaylistStore(playlists, filters)

        assertEquals(listOf("aaaaaaaaaa1"), store.videos(id).first().map { it.video.id.value })
        assertEquals(listOf("aaaaaaaaaa1"), store.playlistVideos().first().map { it.video.id.value })
        assertEquals(listOf("aaaaaaaaaa4"), store.watchLater().first().map { it.video.id.value })
        assertEquals("counts still include the hidden one", 2, store.playlists().first().single().videoCount)
        assertEquals("the cover is the first video shown", "shown.jpg", store.playlists().first().single().coverThumbnailUrl)
    }

    @Test
    fun keptRelatedListsLoseFilteredItemsAndKeepTheirDates() = runTest {
        val kept = RelatedList(listOf(video(id = "aaaaaaaaaa1", channelUrl = BLOCKED), video(id = "aaaaaaaaaa2", channelUrl = OTHER)), fetchedAt = null, failedAt = null)
        val related = FakeRelatedListStore().apply { this.kept[SOURCE] = kept }
        block(BlockedChannel(BLOCKED, null))

        val lists = FilteredRelatedListStore(related, filters).lists(listOf(SOURCE))

        assertEquals(mapOf(SOURCE to kept.copy(items = listOf(video(id = "aaaaaaaaaa2", channelUrl = OTHER)))), lists)
    }

    @Test
    fun relatedListsReadWithNoProfileOpenKeepNothing() = runTest {
        val related = FakeRelatedListStore().apply { kept[SOURCE] = RelatedList(listOf(video(id = "aaaaaaaaaa2", channelUrl = OTHER)), fetchedAt = null, failedAt = null) }
        val noProfile = ContentFilters(ProfileSession(), FakeProfileSettingsStore(), rules)

        val lists = FilteredRelatedListStore(related, noProfile).lists(listOf(SOURCE))

        assertEquals(emptyList<VideoItem>(), lists.getValue(SOURCE).items)
    }

    @Test
    fun aBlockedChannelLeavesTheSubscriptionsList() = runTest {
        val subscriptions = Subscriptions(listOf(subscription(OTHER), subscription(BLOCKED)))
        block(BlockedChannel(BLOCKED, null))

        val listed = FilteredSubscriptionStore(subscriptions, filters).subscriptions().first()

        assertEquals(listOf(OTHER), listed.map { it.channelUrl })
    }

    /** FPK-D11: a pack's channel-name rule reaches history entries and subscriptions by name. */
    @Test
    fun aPacksChannelNameRuleDropsHistoryEntriesAndSubscriptions() = runTest {
        val watch = FakeWatchStore().apply { entries.value = listOf(entry("aaaaaaaaaa1", OTHER).copy(uploaderName = "Kids Roma Show"), entry("aaaaaaaaaa2", OTHER)) }
        val subscriptions = Subscriptions(listOf(subscription(BLOCKED).copy(name = "Kids Roma Show"), subscription(OTHER)))
        settings.byProfile.value = mapOf(PROFILE to ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)))

        assertEquals(listOf("aaaaaaaaaa2"), FilteredWatchStore(watch, filters).history().first().map { it.videoId.value })
        assertEquals(listOf(OTHER), FilteredSubscriptionStore(subscriptions, filters).subscriptions().first().map { it.channelUrl })
    }

    private fun block(vararg blocked: ContentRule) {
        rules.rulesById.value = mapOf(PROFILE to ProfileRules(rules = blocked.map { StoredRule(it, byOwner = false, addedAtMillis = 0) }))
    }

    private class Subscriptions(rows: List<Subscription>) : SubscriptionStore {
        private val rows = MutableStateFlow(rows)

        override fun subscriptions(): Flow<List<Subscription>> = rows

        override fun subscription(channelUrl: String): Flow<Subscription?> = rows.map { list -> list.firstOrNull { it.channelUrl == channelUrl } }

        override suspend fun subscribe(channel: ChannelItem) = Unit

        override suspend fun unsubscribe(channelUrl: String) = Unit
    }

    private companion object {
        const val PROFILE = 1L
        const val OTHER_PROFILE = 2L
        const val BLOCKED = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
        val SOURCE = VideoId("sssssssssss")

        fun entry(id: String, channelUrl: String, title: String = "A video", isShort: Boolean = false): WatchEntry = WatchEntry(
            videoId = VideoId(id),
            title = title,
            uploaderName = "Someone",
            uploaderUrl = channelUrl,
            thumbnailUrl = null,
            positionMs = 60_000,
            durationMs = 600_000,
            watched = false,
            lastWatchedAtMillis = 1,
            updatedAtMillis = 1,
            isShort = isShort,
        )

        fun subscription(channelUrl: String): Subscription = Subscription(channelUrl, "Someone", avatarUrl = null, subscribedAtMillis = 1, updatedAtMillis = 1)
    }
}
