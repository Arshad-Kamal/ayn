package com.arshad.ayn.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.arshad.ayn.core.access.LivePlayable
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.SegmentSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.sabr.sabrPlayable
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.kids.PlayTimeUp
import com.arshad.ayn.core.kids.allowsPlay
import com.arshad.ayn.core.kids.stopped
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RelatedListStore
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.livePlayableOrRefuse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What extraction hands a resolve: the page, and the playable its item's source is built from
 *  ([playableFor]). */
internal class ExtractedVideo(val metadata: VideoMetadata, val playable: Any)

/**
 * The playable [metadata]'s item is built from: a live page's HLS manifest ([LivePlayable],
 * LIVE-D2; refused without one or when [kidsProfile] says a kids profile is open), else its SABR
 * manifest; either capped at [maxVideoHeight] lines (M7-D15, LIVE-D5). [kidsProfile] is asked only
 * for a live page. A post-live replay isn't live and takes SABR (LIVE-D18).
 */
internal suspend fun playableFor(metadata: VideoMetadata, kidsProfile: suspend () -> Boolean, maxVideoHeight: suspend () -> Int): Any =
    if (metadata.isLive) {
        livePlayableOrRefuse(metadata, kidsProfile(), maxVideoHeight())
    } else {
        sabrPlayable(metadata, maxVideoHeight())
    }

/**
 * One resolve (M7-D3): extraction and the SponsorBlock lookup run side by side, and the lookup's
 * outcome rides the item's dressing. The lookup never fails the resolve: a settings read or
 * SponsorBlock call that throws, or a SponsorBlock call still running after [segmentWait], counts
 * as [SegmentLookup.Failed]. A failed extraction cancels the lookup. No lookup runs for a Short
 * (Q40: SponsorBlock has no Shorts data) or when the profile fetches no category. Each extracted
 * page except a Short's goes to [keepRelated] (For you's related lists, FY-D4), which must not wait.
 * A Short asked for while the session profile doesn't show Shorts ([shortsHidden]: its Show Shorts
 * off, the owner's Hide Shorts, or a kids profile) is refused with [VideoFiltered] before any request
 * (KP-D15, PAF-D6).
 * A kids profile's stopped time ([playTime]) is refused with [PlayTimeUp] before any request, and
 * any time that doesn't allow play after extraction, whose own InnerTube answer sets the clock a
 * fresh start lacks (KP-D22).
 * The system controls show the video's DeArrow face as its pages do (PAF-D13): [branding]'s lookup
 * runs beside extraction too (not for a Short) and is waited for no longer than its own wait from
 * the start; its title is checked by the session profile's [contentFilter]. A face that can't be
 * told in time, or a lookup that throws, keeps YouTube's title and picture; a filter read that
 * throws keeps YouTube's title only.
 */
internal class ItemResolver(
    private val extract: suspend (VideoId) -> ExtractedVideo,
    private val segments: SegmentSource,
    /** The session profile's settings, read once per resolve. */
    private val settings: suspend () -> ProfileSettings,
    private val keepRelated: (VideoMetadata) -> Unit,
    private val shortsHidden: suspend () -> Boolean,
    private val playTime: suspend () -> KidsTime,
    private val branding: CardBranding,
    private val contentFilter: suspend () -> ContentFilter,
    private val segmentWait: Duration = SEGMENT_WAIT,
) {
    suspend fun resolve(videoId: VideoId, short: Boolean = false): MediaItem = coroutineScope {
        if (short && shortsHidden()) throw VideoFiltered()
        if (playTime().stopped) throw PlayTimeUp()
        val segmentLookup = if (short) null else async { lookUpSegments(videoId) }
        val brandingLookAhead = if (short) null else async { lookAhead(videoId) }
        val video = extract(videoId)
        if (!playTime().allowsPlay) throw PlayTimeUp()
        if (!short) keepRelated(video.metadata)
        // The look-ahead answers nothing itself: awaiting it only orders the cache read after it.
        val face = brandingLookAhead?.await()?.let { faceOf(video.metadata) }
        resolvedItem(video.metadata, video.playable, segmentLookup?.await(), face)
    }

    private suspend fun lookAhead(videoId: VideoId) {
        try {
            branding.lookAhead(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "branding lookup threw: ${e.javaClass.simpleName}")
        }
    }

    /** [metadata]'s face from what [lookAhead] left in the cache, a DeArrow title checked by the
     *  profile's filter; `null` keeps YouTube's. */
    private suspend fun faceOf(metadata: VideoMetadata): CardFace? {
        val video = metadata.asCard()
        val face = branding.known(video) ?: return null
        return if (face.titleFromDeArrow) face.checkedBy(filterOrNull(), video) else face
    }

    /** The profile's filter; `null` when it can't be read, which [CardFace.checkedBy] reads as
     *  hiding DeArrow's title. */
    private suspend fun filterOrNull(): ContentFilter? =
        try {
            contentFilter()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "content filter read threw: ${e.javaClass.simpleName}")
            null
        }

    private suspend fun lookUpSegments(videoId: VideoId): SegmentLookup? =
        try {
            val categories = settings().segmentCategoriesToFetch()
            if (categories.isEmpty()) {
                null
            } else {
                withTimeoutOrNull(segmentWait) { segments.segments(videoId, categories) }
                    ?: SegmentLookup.Failed.also { Log.w(PLAYBACK_LOG_TAG, "segments lookup timed out") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "segments lookup threw: ${e.javaClass.simpleName}")
            SegmentLookup.Failed
        }

    private companion object {
        /** SponsorBlock answers in about 0.6 s (2026-10-06); a stalled server must not hold the video. */
        val SEGMENT_WAIT: Duration = 3.seconds
    }
}

/**
 * Keeps [metadata]'s related list for For you (FY-D4) under the session profile: starts the write
 * on this scope and returns. On a `Main.immediate` scope the profile read and the list's encoding
 * (well under a millisecond) run before the caller continues, which pins the write to the profile
 * the resolve ran for; the database write itself never holds the caller. A failed write is logged
 * and dropped.
 */
internal fun CoroutineScope.keepRelatedList(store: RelatedListStore, metadata: VideoMetadata) {
    launch {
        try {
            store.record(metadata.id, metadata.relatedVideos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "related list save failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * The item a resolve hands Media3: the video id as media id, the [playable] in the tag (the
 * player-wide source factory builds its source from it), and the title, channel, picture and chrome
 * dressing in its metadata (system controllers read the title, artist and artwork, the title and
 * picture from [face] when given; the screen reads [PlaybackDressing], the SponsorBlock lookup
 * included). A live item's URI is its HLS manifest, and it plays [PlayerPolicy.LIVE_OFFSET_MS]
 * behind the live edge (LIVE-D3).
 */
internal fun resolvedItem(
    metadata: VideoMetadata,
    playable: Any,
    segments: SegmentLookup? = null,
    face: CardFace? = null,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(metadata.id.value)
        .apply {
            if (playable is LivePlayable) {
                setUri(playable.hlsUrl)
                setMimeType(MimeTypes.APPLICATION_M3U8)
                // No speeds set: Media3's HLS source then leaves the playback speed at 1x
                // unless the playlist itself asks for a hold-back (LIVE-D3).
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(PlayerPolicy.LIVE_OFFSET_MS).build())
            } else {
                // Inert placeholder: the SABR source fetches via the manifest endpoint, never
                // through the item URI; the URI only gives the item its LocalConfiguration.
                setUri("ke://watch/${metadata.id.value}")
            }
        }
        .setTag(playable)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(face?.title ?: metadata.title)
                .setArtist(metadata.uploaderName)
                .setArtworkUri((face?.thumbnailUrl ?: metadata.thumbnailUrl)?.let(Uri::parse))
                .setExtras(PlaybackDressing.from(metadata, segments).toBundle())
                .build(),
        )
        .build()

/** [this] page as the card [CardBranding] reads a face for: only its id, title and picture count
 *  there; the page carries no view count or upload date. */
private fun VideoMetadata.asCard(): VideoItem = VideoItem(
    id = id,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    durationSeconds = durationSeconds,
    viewCount = -1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = thumbnailUrl,
    isLive = isLive,
)
