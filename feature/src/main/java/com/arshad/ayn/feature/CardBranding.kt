package com.arshad.ayn.feature

import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.feature.library.orFailed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/** What a surface shows for a video: DeArrow's title and picture where they replace YouTube's. */
data class CardFace(
    val title: String,
    val thumbnailUrl: String?,
    /** The title is DeArrow's; the video's own title is YouTube's (Details' credit). */
    val titleFromDeArrow: Boolean,
) {
    /** This face as [filter] lets it show: a DeArrow title the profile's keywords or patterns hide
     *  gives way to [video]'s own, which the video passed by (KP-D20); the picture stays. Before
     *  the filter is known (`null`) no DeArrow title shows. */
    fun checkedBy(filter: ContentFilter?, video: VideoItem): CardFace =
        if (titleFromDeArrow && (filter == null || !filter.allowsTitle(title))) copy(title = video.title, titleFromDeArrow = false) else this

    companion object {
        /** YouTube's own title and picture. */
        fun original(video: VideoItem): CardFace = CardFace(video.title, video.thumbnailUrl, titleFromDeArrow = false)
    }
}

/** The session profile's DeArrow switches. */
data class DeArrowToggles(val titles: Boolean, val thumbnails: Boolean) {
    val any: Boolean get() = titles || thumbnails

    companion object {
        val OFF = DeArrowToggles(titles = false, thumbnails = false)
    }
}

/**
 * The face each surface shows for a video (M7-D11): DeArrow's title and thumbnail per the session
 * profile's switches, YouTube's otherwise. Display only; a [VideoItem] is never changed.
 *
 * [known] answers without waiting when it can (both switches off, or DeArrow's answer already at
 * hand), so a card scrolled back draws its face at once; [face] waits for DeArrow at most [wait]
 * from the ask, then answers YouTube's (M7-D8: the clickbait version never shows first). Lookups
 * run in [scope], app-lived and supervised: one outliving its wait, or the card that asked, still
 * lands in the source's cache for the next ask. Asks for the same video and fallback picture share
 * one lookup; [lookAhead] asks before the picture is known, so a page asking at the same moment may
 * make a second request. [prefetch] starts a list's lookups before its cards ask. Settings that can't be read
 * count as both switches off, and so does a kids profile whatever its settings say (KP-D11).
 */
class CardBranding(
    private val source: BrandingSource,
    settingsStore: ProfileSettingsStore,
    /** The session profile ([com.arshad.ayn.core.store.ProfileSession.selectedId]). */
    selectedId: Flow<Long>,
    private val scope: CoroutineScope,
    private val wait: Duration = WAIT,
) {
    /**
     * `null` until the session profile's settings are read: at first, and after a profile switch
     * until the new profile's are (the previous profile's switches never decide its faces).
     */
    val toggles: StateFlow<DeArrowToggles?> = settingsStore.settings()
        .map<ProfileSettingsState, Pair<Long?, DeArrowToggles>> {
            it.profileId to if (it.kids) DeArrowToggles.OFF else DeArrowToggles(it.settings.dearrowTitles, it.settings.dearrowThumbnails)
        }
        // Unreadable settings are no profile's: off whoever is selected.
        .orFailed("profile settings read", null to DeArrowToggles.OFF)
        .combine(selectedId) { (readFor, toggles), sessionId -> toggles.takeIf { readFor == null || readFor == sessionId } }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    /** Lookups started and not yet answered. */
    private val inFlight = HashMap<Ask, Deferred<BrandingLookup>>()

    /** Prefetched lookups running at once, app-wide; a card's own ask never waits on these. */
    private val prefetches = Semaphore(PREFETCH_PARALLEL)

    /** [video]'s face when it needs no wait; `null` when only [face] can tell. */
    fun known(video: VideoItem): CardFace? {
        val toggles = toggles.value ?: return null
        if (!toggles.any) return CardFace.original(video)
        return source.cached(video.id, video.thumbnailUrl)?.let { faceOf(video, toggles, it) }
    }

    /** [video]'s face, waiting at most [wait] for the settings and DeArrow, then YouTube's. */
    suspend fun face(video: VideoItem): CardFace {
        known(video)?.let { return it }
        return withTimeoutOrNull(wait) {
            val toggles = toggles.filterNotNull().first()
            if (!toggles.any) return@withTimeoutOrNull CardFace.original(video)
            faceOf(video, toggles, lookup(Ask(video)).await())
        } ?: CardFace.original(video)
    }

    /**
     * Asks DeArrow about [id] for a video being opened, before its page (and so its picture) is
     * known (PAF-D13): returns once the answer is in the source's cache or [wait] has passed, and
     * [known] then reads the face with the page's picture. A lookup outliving the wait still lands
     * in the cache. Nothing is asked with both switches off, nor while the session's switches are
     * unread.
     */
    suspend fun lookAhead(id: VideoId) {
        withTimeoutOrNull(wait) {
            if (toggles.filterNotNull().first().any) lookup(Ask(id, thumbnailUrl = null)).await()
        }
    }

    /**
     * Starts the lookups of the first [PREFETCH_SCAN] of [videos], in order, before their cards ask
     * (a list showing): videos already known or being looked up are skipped, at most
     * [PREFETCH_LIMIT] are started per call and [PREFETCH_PARALLEL] run at once. Returns when the
     * last is started; cancelling it starts no more, and those started still land in the cache.
     * Nothing is asked with both switches off, nor while the session's switches are unread.
     */
    suspend fun prefetch(videos: List<VideoItem>) {
        if (!toggles.filterNotNull().first().any) return
        var started = 0
        for (video in videos.take(PREFETCH_SCAN)) {
            if (started == PREFETCH_LIMIT) return
            prefetches.acquire()
            // Checked with the permit held: the wait may outlast the switches, or the card's own ask.
            if (toggles.value?.any != true) {
                prefetches.release()
                return
            }
            if (known(video) != null || synchronized(inFlight) { Ask(video) in inFlight }) {
                prefetches.release()
                continue
            }
            lookup(Ask(video)).invokeOnCompletion { prefetches.release() }
            started++
        }
    }

    /** [ask]'s lookup: the one running, else a new one in [scope], not a child of the asker, so a
     *  wait that ends or a card that leaves lets it run on to the cache. */
    private fun lookup(ask: Ask): Deferred<BrandingLookup> {
        var created: Deferred<BrandingLookup>? = null
        val lookup = synchronized(inFlight) {
            inFlight.getOrPut(ask) {
                // Lazy: listed before it runs, so its end can't come before its listing.
                scope.async(start = CoroutineStart.LAZY) { source.branding(ask.id, ask.thumbnailUrl) }.also { created = it }
            }
        }
        created?.let { new ->
            new.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(ask, new) } }
            new.start()
        }
        return lookup
    }

    private fun faceOf(video: VideoItem, toggles: DeArrowToggles, lookup: BrandingLookup): CardFace {
        val branding = (lookup as? BrandingLookup.Found)?.branding
        val title = branding?.title?.takeIf { toggles.titles }
        val thumbnailUrl = branding?.thumbnailUrl?.takeIf { toggles.thumbnails }
        return CardFace(title ?: video.title, thumbnailUrl ?: video.thumbnailUrl, titleFromDeArrow = title != null)
    }

    /** One lookup: the source's answer depends on the video and the picture it falls back to. */
    private data class Ask(val id: VideoId, val thumbnailUrl: String?) {
        constructor(video: VideoItem) : this(video.id, video.thumbnailUrl)
    }

    private companion object {
        /** M7-D8 (Q7): how long a card holds its placeholders for DeArrow. */
        val WAIT = 1_500.milliseconds

        /** About a page of cards (a row, a page of Search or a channel). */
        const val PREFETCH_LIMIT = 30

        /** About four pages; a longer list (History) has the rest looked up as its cards show. */
        const val PREFETCH_SCAN = 120
        const val PREFETCH_PARALLEL = 4
    }
}
