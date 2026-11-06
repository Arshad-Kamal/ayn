package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * In-memory [ForYouStore] for the layers above the store, one profile's worth: [hiddenVideoIds]
 * and [hiddenChannelUrls] (canonical, as the store keeps them) are what the hidden flows emit and
 * the hide calls edit; [impressionRows] is what [impressions] answers, as the test sets it (the store's own bookkeeping isn't
 * modelled), and every show and opening is kept in call order in [shown] and [opened].
 * [failReads] makes [impressions] throw. [hideGate] holds [hideVideo] until completed;
 * [hiddenChannelsGate] holds [hiddenChannels]' first answer until completed.
 */
class FakeForYouStore : ForYouStore {
    val hiddenVideoIds: MutableStateFlow<Set<VideoId>> = MutableStateFlow(emptySet())
    val hiddenChannelUrls: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    var impressionRows: List<Impression> = emptyList()
    val shown: MutableList<List<VideoItem>> = mutableListOf()
    val opened: MutableList<VideoItem> = mutableListOf()
    var failReads: Boolean = false
    var hideGate: CompletableDeferred<Unit>? = null
    var hiddenChannelsGate: CompletableDeferred<Unit>? = null

    override fun hiddenVideos(): Flow<Set<VideoId>> = hiddenVideoIds

    override fun hiddenChannels(): Flow<Set<String>> {
        val gate = hiddenChannelsGate ?: return hiddenChannelUrls
        return flow {
            gate.await()
            emitAll(hiddenChannelUrls)
        }
    }

    override suspend fun hideVideo(videoId: VideoId) {
        hideGate?.await()
        hiddenVideoIds.value += videoId
    }

    override suspend fun unhideVideo(videoId: VideoId) {
        hiddenVideoIds.value -= videoId
    }

    override suspend fun hideChannel(channelUrl: String) {
        hiddenChannelUrls.value += canonicalChannelUrl(channelUrl)
    }

    override suspend fun unhideChannel(channelUrl: String) {
        hiddenChannelUrls.value -= canonicalChannelUrl(channelUrl)
    }

    override suspend fun recordShown(cards: List<VideoItem>) {
        shown += cards
    }

    override suspend fun recordOpened(card: VideoItem) {
        opened += card
    }

    override suspend fun impressions(): List<Impression> {
        if (failReads) throw IllegalStateException("database closed")
        return impressionRows
    }
}

/** In-memory [FetchAttemptLog]: [attempts], oldest first, each [record] stamped by [clock]; a
 *  test may add attempts of its own. [failReads] makes [since] throw. */
class FakeFetchAttemptLog(private val clock: Clock) : FetchAttemptLog {
    val attempts: MutableList<FetchAttempt> = mutableListOf()
    var failReads: Boolean = false

    override suspend fun record(outcome: FetchOutcome) {
        attempts += FetchAttempt(clock.now(), outcome)
    }

    override suspend fun since(start: Instant): List<FetchAttempt> {
        if (failReads) throw IllegalStateException("database closed")
        return attempts.filter { it.at >= start }.sortedBy { it.at }
    }
}
