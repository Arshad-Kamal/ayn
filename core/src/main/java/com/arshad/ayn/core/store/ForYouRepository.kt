package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * [ForYouStore] over Room for the [session]'s profile; a profile switch re-emits the hidden sets.
 * Impressions are written as a skeleton insert-or-ignore plus one update, in one transaction; a
 * show also drops every profile's impressions [ForYouStore.IMPRESSIONS_KEPT] old, so a profile
 * that stopped using For you keeps none past the window, and reads leave older ones out. An
 * unhide leaves a removal record for sync (M8-D6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForYouRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    private val clock: Clock = Clock.System,
) : ForYouStore {
    private val dao: ForYouDao = database.forYouDao()

    override fun hiddenVideos(): Flow<Set<VideoId>> =
        session.selectedId.flatMapLatest { profileId -> dao.hiddenVideoIds(profileId).map { ids -> ids.mapTo(HashSet(), ::VideoId) } }

    override fun hiddenChannels(): Flow<Set<String>> =
        session.selectedId.flatMapLatest { profileId -> dao.hiddenChannelUrls(profileId).map { it.toSet() } }

    override suspend fun hideVideo(videoId: VideoId) {
        dao.hideVideo(HiddenVideoEntity(session.currentId(), videoId.value, nowMillis()))
    }

    override suspend fun unhideVideo(videoId: VideoId) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            database.removalDao().hiddenVideo(profileId, videoId.value, nowMillis())
            dao.unhideVideo(profileId, videoId.value)
        }
    }

    override suspend fun hideChannel(channelUrl: String) {
        dao.hideChannel(HiddenChannelEntity(session.currentId(), canonicalChannelUrl(channelUrl), nowMillis()))
    }

    override suspend fun unhideChannel(channelUrl: String) {
        val profileId = session.currentId()
        val key = canonicalChannelUrl(channelUrl)
        database.withWriteTransaction {
            database.removalDao().hiddenChannel(profileId, key, nowMillis())
            dao.unhideChannel(profileId, key)
        }
    }

    override suspend fun recordShown(cards: List<VideoItem>) {
        if (cards.isEmpty()) return
        val profileId = session.currentId()
        database.withWriteTransaction {
            val now = nowMillis()
            cards.distinctBy { it.id }.forEach { card ->
                dao.insertImpressionIfAbsent(skeleton(profileId, card, now))
                dao.markShown(profileId, card.id.value, card.channelKey(), now)
            }
            dao.pruneImpressions(now - ForYouStore.IMPRESSIONS_KEPT.inWholeMilliseconds)
        }
    }

    override suspend fun recordOpened(card: VideoItem) {
        val profileId = session.currentId()
        database.withWriteTransaction {
            val now = nowMillis()
            dao.insertImpressionIfAbsent(skeleton(profileId, card, now))
            dao.markOpened(profileId, card.id.value, card.channelKey(), now)
        }
    }

    override suspend fun impressions(): List<Impression> =
        dao.impressions(session.currentId(), nowMillis() - ForYouStore.IMPRESSIONS_KEPT.inWholeMilliseconds).map { row ->
            Impression(
                videoId = VideoId(row.videoId),
                channelUrl = row.channelUrl,
                shownCount = row.shownCount,
                lastShownAt = Instant.fromEpochMilliseconds(row.lastShownAtMillis).takeIf { row.shownCount > 0 },
                openedAt = row.openedAtMillis?.let(Instant::fromEpochMilliseconds),
            )
        }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()
}

/** No shows yet; the first show or opening fills it in. The row's last-shown time starts at its
 *  creation, so an opened-only row is pruned on the same clock. */
private fun skeleton(profileId: Long, card: VideoItem, nowMillis: Long): ImpressionEntity =
    ImpressionEntity(profileId, card.id.value, card.channelKey(), shownCount = 0, lastShownAtMillis = nowMillis, openedAtMillis = null)
