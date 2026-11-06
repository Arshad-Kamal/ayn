package com.arshad.ayn.core.store

import androidx.room3.withWriteTransaction
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.boundaryJson
import com.arshad.ayn.core.access.decodeOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * [RelatedListStore] over Room for the [session]'s profile. Each write trims the profile's lists
 * to [RelatedListStore.MAX_LISTS] in the same transaction. Rows are tagged with the profile's
 * [restrictedMode] when written, and a row written under another level reads as absent, so its
 * list is fetched again under the current one (KP-D15).
 */
class RelatedListRepository(
    private val database: KeDatabase,
    private val session: ProfileSession,
    /** The session profile's Restricted Mode now. */
    private val restrictedMode: suspend () -> RestrictedMode,
    private val clock: Clock = Clock.System,
) : RelatedListStore {
    private val dao: RelatedListDao = database.relatedListDao()

    override suspend fun record(videoId: VideoId, items: List<VideoItem>) {
        if (items.isEmpty()) return
        val profileId = session.currentId()
        val level = restrictedMode().key
        val text = encodeRelatedItems(items)
        database.withWriteTransaction {
            val now = clock.now().toEpochMilliseconds()
            dao.upsert(RelatedListEntity(profileId, videoId.value, text, fetchedAtMillis = now, failedAtMillis = null, restrictedMode = level))
            dao.trim(profileId, RelatedListStore.MAX_LISTS)
        }
    }

    override suspend fun recordFailure(videoId: VideoId) {
        val profileId = session.currentId()
        val level = restrictedMode().key
        database.withWriteTransaction {
            val now = clock.now().toEpochMilliseconds()
            dao.insertIfAbsent(
                RelatedListEntity(profileId, videoId.value, NO_ITEMS, fetchedAtMillis = null, failedAtMillis = now, restrictedMode = level),
            )
            dao.markFailed(profileId, videoId.value, now)
            dao.trim(profileId, RelatedListStore.MAX_LISTS)
        }
    }

    /** A row whose list can't be read back (a damaged write) counts as no list, so it is fetched again. */
    override suspend fun lists(videoIds: Collection<VideoId>): Map<VideoId, RelatedList> {
        if (videoIds.isEmpty()) return emptyMap()
        val level = restrictedMode()
        return dao.lists(session.currentId(), videoIds.map { it.value }.distinct())
            .filter { RestrictedMode.of(it.restrictedMode) == level }
            .mapNotNull { row ->
                val items = decodeRelatedItems(row.items) ?: return@mapNotNull null
                VideoId(row.videoId) to RelatedList(
                    items = items,
                    fetchedAt = row.fetchedAtMillis?.let(Instant::fromEpochMilliseconds),
                    failedAt = row.failedAtMillis?.let(Instant::fromEpochMilliseconds),
                )
            }
            .toMap()
    }

    private companion object {
        const val NO_ITEMS = "[]"
    }
}

/** One [VideoItem] as stored in [RelatedListEntity.items] and [ChannelFeedEntity]'s lists. */
@Serializable
private data class StoredVideo(
    val id: String,
    val title: String,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val durationSeconds: Long,
    val viewCount: Long,
    val textualUploadDate: String? = null,
    val uploadDateMillis: Long? = null,
    val thumbnailUrl: String? = null,
    val isLive: Boolean = false,
    val membersOnly: Boolean = false,
    val upcoming: Boolean = false,
)

private val StoredVideos = ListSerializer(StoredVideo.serializer())

internal fun encodeRelatedItems(items: List<VideoItem>): String =
    boundaryJson.encodeToString(
        StoredVideos,
        items.map {
            StoredVideo(
                id = it.id.value,
                title = it.title,
                uploaderName = it.uploaderName,
                uploaderUrl = it.uploaderUrl,
                durationSeconds = it.durationSeconds,
                viewCount = it.viewCount,
                textualUploadDate = it.textualUploadDate,
                uploadDateMillis = it.uploadDate?.toEpochMilliseconds(),
                thumbnailUrl = it.thumbnailUrl,
                isLive = it.isLive,
                membersOnly = it.membersOnly,
                upcoming = it.upcoming,
            )
        },
    )

/** [text]'s videos, an entry with an id no [VideoId] accepts dropped; `null` when [text] isn't a list. */
internal fun decodeRelatedItems(text: String): List<VideoItem>? =
    decodeOrNull(text, StoredVideos)?.mapNotNull { stored ->
        if (!VideoId.isValid(stored.id)) return@mapNotNull null
        VideoItem(
            id = VideoId(stored.id),
            title = stored.title,
            uploaderName = stored.uploaderName,
            uploaderUrl = stored.uploaderUrl,
            durationSeconds = stored.durationSeconds,
            viewCount = stored.viewCount,
            textualUploadDate = stored.textualUploadDate,
            uploadDate = stored.uploadDateMillis?.let(Instant::fromEpochMilliseconds),
            thumbnailUrl = stored.thumbnailUrl,
            isLive = stored.isLive,
            membersOnly = stored.membersOnly,
            upcoming = stored.upcoming,
        )
    }
