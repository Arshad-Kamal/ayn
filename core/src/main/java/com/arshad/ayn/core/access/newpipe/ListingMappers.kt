package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import java.util.Base64
import kotlin.time.toKotlinInstant
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Vendored InfoItem → seam model mapping. Internal so same-module tests can drive
 * the mapping synthetically; the Shorts filter and the live marker live here, at the seam.
 */

/** Shorts never cross the seam as listing items (charter D5; [toShortOrNull] lists them apart);
 *  playlists list only in channel tabs ([toChannelTabItem]); comments page through their own
 *  call ([toCommentOrNull]). */
internal fun InfoItem.toListingItem(): ListingItem? = when (this) {
    is StreamInfoItem -> toVideoItemOrNull()
    is ChannelInfoItem -> toChannelItem()
    else -> null
}

/**
 * A comment or reply as the panel shows it (CMT-D2); `null` without an id, an author or any text
 * (dropped like any unreadable item). YouTube writes an edit into the age ("7 days ago (edited)"),
 * which the vendored item reads as [CommentsInfoItem.isEdited]; the note leaves the age.
 */
internal fun CommentsInfoItem.toCommentOrNull(): Comment? {
    val id = commentId?.ifBlank { null } ?: return null
    val authorName = uploaderName?.ifBlank { null } ?: return null
    val text = commentText.toPlainText() ?: return null
    return Comment(
        id = id,
        authorName = authorName,
        authorUrl = uploaderUrl?.ifBlank { null },
        authorAvatarUrl = bestImage(uploaderAvatars),
        text = text,
        age = ageWithoutEditNote(),
        edited = isEdited,
        likes = textualLikeCount?.trim()?.ifEmpty { null },
        replyCount = replyCount.coerceAtLeast(0), // the vendored item's -1 means unknown
        repliesToken = replies.toPageToken(),
        pinned = isPinned,
        hearted = isHeartedByUploader,
        byChannelOwner = isChannelOwner,
    )
}

private fun CommentsInfoItem.ageWithoutEditNote(): String? {
    val age = textualUploadDate ?: return null
    return (if (isEdited) age.substringBefore(EDITED_NOTE_START) else age).trim().ifEmpty { null }
}

private const val EDITED_NOTE_START = " ("

/** A channel tab lists playlists too (its Playlists, Releases, Podcasts and Courses tabs).
 *  Search and kiosks keep dropping them: no surface opens a YouTube playlist yet. */
internal fun InfoItem.toChannelTabItem(): ListingItem? =
    if (this is PlaylistInfoItem) toPlaylistItem() else toListingItem()

internal fun PlaylistInfoItem.toPlaylistItem(): PlaylistItem? {
    val title = name ?: return null
    val url = url ?: return null
    return PlaylistItem(
        title = title,
        url = url,
        thumbnailUrl = bestImage(thumbnails),
        // The vendored item reports an unparsed count as 0 and a mix as -2.
        videoCount = streamCount.takeIf { it > 0 } ?: -1,
        uploaderName = uploaderName,
    )
}

/** The header of a fetched playlist: a missing name fails the page; the rest degrades to unknown. */
internal fun PlaylistExtractor.toPlaylistDetails(): PlaylistDetails = PlaylistDetails(
    title = name,
    uploaderName = catchingNull { uploaderName.ifBlank { null } },
    uploaderUrl = catchingNull { uploaderUrl.ifBlank { null } },
    uploaderAvatarUrl = catchingNull { bestImage(uploaderAvatars) },
    // The vendored extractor reports an unknown count as a negative sentinel.
    videoCount = catchingNull { streamCount }?.takeIf { it >= 0 } ?: -1,
    thumbnailUrl = catchingNull { bestImage(thumbnails) },
)

internal fun StreamInfoItem.toVideoItemOrNull(): VideoItem? {
    if (isShort()) return null // charter D5
    return toVideoItem(uploaderUrl) { bestImage(thumbnails) }
}

/**
 * A Short as its own vertical card (M7-D12); `null` for anything else. Its picture is the
 * listing's own where YouTube marks the item a Short and sends a portrait picture (search shelves,
 * a Shorts tab: 405×720); an RSS entry's is landscape (`mqdefault`, which the vendored feed
 * extractor labels 180×320, so a size check alone would take it), so it is asked for by id.
 * The vendored tab item's channel URL ends in `/shorts`; it is trimmed back to the channel's own.
 */
internal fun StreamInfoItem.toShortOrNull(): VideoItem? {
    if (!isShort()) return null
    val portrait = if (isShortFormContent) thumbnails.filter { it.height > it.width } else emptyList()
    return toVideoItem(uploaderUrl?.removeSuffix("/shorts")) { id -> bestImage(portrait) ?: portraitPictureUrl(id) }
}

/**
 * Two signals, either enough: the extractor's computed flag (InnerTube listings) and the URL
 * itself — RSS feed items carry no flag, and a channel feed is largely Shorts (the recorded
 * corpus proves it: 11 of 15 entries link /shorts/).
 */
private fun StreamInfoItem.isShort(): Boolean = isShortFormContent || url.isShortsLink()

private fun StreamInfoItem.toVideoItem(channelUrl: String?, picture: (VideoId) -> String?): VideoItem? {
    val title = name ?: return null
    return catchingNull {
        val id = VideoId(YoutubeStreamLinkHandlerFactory.getInstance().getId(url))
        VideoItem(
            id = id,
            title = title,
            uploaderName = uploaderName,
            uploaderUrl = channelUrl,
            durationSeconds = duration,
            viewCount = viewCount,
            textualUploadDate = textualUploadDate,
            uploadDate = uploadDate?.instant?.toKotlinInstant(),
            thumbnailUrl = picture(id),
            isLive = streamType.isLive(),
            membersOnly = contentAvailability == ContentAvailability.MEMBERSHIP,
            upcoming = contentAvailability == ContentAvailability.UPCOMING,
        )
    }
}

/** A video's own picture at its own aspect: 1080×1920 for a Short (verified 2026-10-07). */
private fun portraitPictureUrl(id: VideoId): String = "https://i.ytimg.com/vi/${id.value}/oar2.jpg"

/**
 * A video page's related item as a video; playlists and mixes are dropped. Related lockups carry
 * no Shorts marker (the vendored extractor documents a Short there as one without a duration
 * badge, length -1), so a video with no length is dropped unless it is live (charter D5); that
 * drops upcoming premieres too, which can't play yet.
 */
internal fun InfoItem.toRelatedVideoOrNull(): VideoItem? =
    (this as? StreamInfoItem)?.toVideoItemOrNull()?.takeIf { it.isLive || it.durationSeconds > 0 }

/** D11: active live streams carry the marker; post-live replays play as VODs. */
internal fun StreamType?.isLive(): Boolean = this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

internal fun ChannelInfoItem.toChannelItem(): ChannelItem? {
    val name = name ?: return null
    val url = url ?: return null // a channel without an identity key is dropped, never ""-coerced
    return ChannelItem(
        name = name,
        url = url,
        avatarUrl = bestImage(thumbnails),
        subscriberCount = subscriberCount,
        description = description,
        verified = isVerified,
    )
}

/** Channel header: the full identity block, banners and tabs included (listings never have
 *  them). A tab kind the seam doesn't know is dropped. */
internal fun ChannelInfo.toChannelItem(): ChannelItem = ChannelItem(
    name = name,
    url = url,
    avatarUrl = bestImage(avatars),
    bannerUrl = bestImage(banners)?.let(::uncroppedBanner),
    subscriberCount = subscriberCount,
    description = description,
    verified = isVerified,
    tabs = tabs.mapNotNull { handler ->
        val tabName = handler.contentFilters.firstOrNull()
        ChannelTab.entries.firstOrNull { it.tabName == tabName }
    },
)

/**
 * The banner's whole uploaded art (16:9) at TV width, from one of its desktop crops: a yt3 image
 * URL ends in an options suffix (`=w2560-fcrop64=…`), and replacing it with `=w1920` asks for
 * the uncropped upload scaled to 1920 px (yt-dlp's `banner_uncropped` uses `=s0`; verified
 * 2026-10-04). A URL without that suffix is kept as it is.
 */
internal fun uncroppedBanner(url: String): String {
    // The options themselves hold '=' (`fcrop64=1,…`): they start at the last segment's first one.
    val options = url.indexOf('=', startIndex = url.lastIndexOf('/'))
    if (!url.contains("googleusercontent.com/") || options < 0) return url
    return url.substring(0, options) + "=w$BANNER_WIDTH"
}

private const val BANNER_WIDTH = 1920

/** The highest-resolution image of a set; unknown dimensions (the vendored -1 sentinels)
 * never outrank a known size. */
internal fun bestImage(images: List<Image>): String? =
    images.maxByOrNull { image ->
        if (image.width <= 0 || image.height <= 0) Long.MIN_VALUE else image.width.toLong() * image.height
    }?.url

/** True for canonical Shorts links (`youtube.com/shorts/<id>`). */
internal fun String?.isShortsLink(): Boolean {
    val path = this?.substringBefore('?')?.substringAfter("://")?.substringAfter('/', "") ?: return false
    return path.startsWith("shorts/")
}

internal fun Page?.toPageToken(): PageToken? {
    val page = this ?: return null
    if (!Page.isValid(page)) return null
    // The vendored Page's convenience constructors leave ids/cookies null (Java side).
    return PageToken(
        url = page.url,
        id = page.id,
        ids = page.ids?.toList().orEmpty(),
        cookies = page.cookies?.toMap().orEmpty(),
        bodyBase64 = page.body?.let { Base64.getEncoder().encodeToString(it) },
    )
}

internal fun PageToken.toVendoredPage(): Page = Page(
    url,
    id,
    ids,
    cookies,
    bodyBase64?.let { Base64.getDecoder().decode(it) },
)

/** Per-item resilience: a weird item drops to null; cancellation still flows (KT-CONC-03). */
internal inline fun <T> catchingNull(block: () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
