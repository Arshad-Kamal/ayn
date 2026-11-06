package com.arshad.ayn.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.timecode
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.text
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A playback time the way the chrome shows it: "0:07", "34:51", "1:02:03" ([timecode]'s form). */
fun timeText(ms: Long): String = timecode(ms)

/** What is left, after a real minus sign: "−25:45"; empty while the length is unknown. */
fun remainingText(positionMs: Long, durationMs: Long): String =
    if (durationMs <= 0) "" else "\u2212" + timeText((durationMs - positionMs).coerceAtLeast(0))

/** How far behind live the picture is, after a real minus sign: "−24:00" (the scrub bubble's
 *  and the nudge's form). */
fun behindLiveText(behindMs: Long): String = "\u2212" + timeText(behindMs.coerceAtLeast(0))

/** A live window's length as the chrome names it (LIVE-D23), to the nearest minute or hour:
 *  "Last 25 minutes", "Last hour", "Last 3 hours"; `null` while unknown. */
fun liveWindowText(durationMs: Long): String? {
    if (durationMs <= 0) return null
    val minutes = ((durationMs + 30_000) / 60_000).coerceAtLeast(1)
    if (minutes < 60) return if (minutes == 1L) "Last minute" else "Last $minutes minutes"
    val hours = (minutes + 30) / 60
    return if (hours == 1L) "Last hour" else "Last $hours hours"
}

/** The format badge, "2160p · 24 fps"; the rate is left out until known, the badge until the
 *  picture's size is. A portrait picture is named by its short side, as YouTube does. */
fun formatBadgeText(width: Int, height: Int, frameRate: Float?): String? {
    val lines = pictureLines(width, height) ?: return null
    val rate = frameRate?.takeIf { it > 0f }?.roundToInt()
    return if (rate == null) "${lines}p" else "${lines}p · $rate fps"
}

/** A picture's lines, as the badge and the Quality sheet name it: its short side; `null` until
 *  its size is known. */
fun pictureLines(width: Int, height: Int): Int? = minOf(width, height).takeIf { it > 0 }

/** The Playback sheet's Quality value, "2160p · VP9": the picture's lines (its short side, as the
 *  badge) and the codec once known; `null` until the picture's size is. */
fun qualityValue(width: Int, height: Int, codec: String?): String? =
    formatBadgeText(width, height, frameRate = null)?.let { metaLine(it, codec) }

/** The selected video track's codec by its common name ("VP9"); `null` while none is selected. */
@OptIn(UnstableApi::class)
fun videoCodec(tracks: Tracks): String? {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return null
    val track = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return null
    return when (group.getTrackFormat(track).sampleMimeType) {
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_H264 -> "H.264"
        else -> null
    }
}

/** The day [video] went up in [locale]'s long form ("September 21, 2026"), else YouTube's own
 *  text ("12 days ago"); `null` when neither is known. */
fun uploadedText(video: VideoItem, locale: Locale): String? {
    val uploaded = video.uploadDate ?: return video.textualUploadDate
    return DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(uploaded.toEpochMilliseconds()))
}

/** Details' ratio label, "98.9% liked". */
fun likedText(percent: Double, locale: Locale): String = "${percentText(percent, locale)} liked"

/** A share to a tenth of a percent, "98.9%". */
fun percentText(percent: Double, locale: Locale): String = String.format(locale, "%.1f%%", percent)

/** A comment count as YouTube states it, as a whole number: "2,458,514". */
fun commentCountText(count: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(count)

/** A comment's replies: "1 reply", "1,234 replies"; "Replies" when YouTube gave no count. */
fun repliesText(count: Int, locale: Locale): String = when {
    count <= 0 -> "Replies"
    count == 1 -> "1 reply"
    else -> "${NumberFormat.getIntegerInstance(locale).format(count)} replies"
}

/** The chapter playing at [positionMs]; `null` when the video has none (YouTube's first starts at 0). */
fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? = chapters.getOrNull(chapterIndexAt(chapters, positionMs))

/** [chapterAt]'s place in [chapters]; -1 when none has started. */
fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startSeconds * 1000 <= positionMs }

/** "3 of 22" in Play all; the count is left out while YouTube hasn't said it. */
fun playlistPlaceText(position: PlaylistPosition): String =
    if (position.videoCount > 0) "${position.index + 1} of ${position.videoCount}" else "${position.index + 1}"

/** How long a skip skipped, rounded to the second: "11 seconds", "1 minute 5 seconds", "2 minutes". */
fun skippedLengthText(lengthMs: Long): String {
    val seconds = ((lengthMs + 500) / 1000).coerceAtLeast(1)
    if (seconds < 60) return count(seconds, "second")
    val rest = seconds % 60
    return count(seconds / 60, "minute") + if (rest > 0) " " + count(rest, "second") else ""
}

private fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

/** The share of [prompt]'s lifetime ([SkipPrompt.shownAtMs] to [SkipPrompt.untilMs]) left at
 *  [positionMs]: 1 as it shows, 0 at its end. */
fun promptLeft(prompt: SkipPrompt, positionMs: Long): Float {
    val lifetime = (prompt.untilMs - prompt.shownAtMs).coerceAtLeast(1)
    return ((prompt.untilMs - positionMs).toFloat() / lifetime).coerceIn(0f, 1f)
}

/** One storyboard frame: the page it sits on and its cell, in frames from the page's top left. */
data class StoryboardFrame(val page: Int, val column: Int, val row: Int)

/** The frame covering [positionMs] (the last one past the end); `null` when the board has none. */
fun storyboardFrame(board: Storyboard, positionMs: Long): StoryboardFrame? {
    val perPage = board.framesPerPageX * board.framesPerPageY
    if (board.frameCount <= 0 || perPage <= 0 || board.pageUrls.isEmpty()) return null
    val index = if (board.frameDurationMs > 0) {
        (positionMs.coerceAtLeast(0) / board.frameDurationMs).toInt().coerceAtMost(board.frameCount - 1)
    } else {
        0
    }
    val page = (index / perPage).coerceAtMost(board.pageUrls.lastIndex)
    val cell = index - page * perPage
    return StoryboardFrame(page, column = cell % board.framesPerPageX, row = cell / board.framesPerPageX)
}

/** The storyboard pages held for a frame on [page]: with [prefetch] its neighbours too, so at most
 *  three, however long the video. */
fun pageWindow(page: Int, lastPage: Int, prefetch: Boolean): IntRange =
    if (prefetch) (page - 1).coerceAtLeast(0)..(page + 1).coerceAtMost(lastPage) else page..page

/** A frame's cut from its page image, in the page's pixels. */
data class FrameCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/** [frame]'s cut from its page image of [pageWidth]×[pageHeight] px. A page can be a few pixels
 *  short of the grid (NewPipe#11596): the cut is clamped to it, `null` when nothing is left. */
fun storyboardCrop(board: Storyboard, frame: StoryboardFrame, pageWidth: Int, pageHeight: Int): FrameCrop? {
    val x = (frame.column * board.frameWidth).coerceAtMost(pageWidth - 1)
    val y = (frame.row * board.frameHeight).coerceAtMost(pageHeight - 1)
    val w = board.frameWidth.coerceAtMost(pageWidth - x)
    val h = board.frameHeight.coerceAtMost(pageHeight - y)
    return if (w <= 0 || h <= 0) null else FrameCrop(x, y, w, h)
}

/** The content frame rate: the highest among the selected video tracks (an adaptive selection
 *  can mix a video's 60 fps renditions with its 30 fps low-resolution ones). */
fun contentFrameRate(tracks: Tracks): Float? =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it).frameRate } }
        .maxOrNull()
        ?.takeIf { it > 0f }

/**
 * The Playback sheet's SponsorBlock line (Q25): each segment's category and start in time order
 * ("Self-promotion 5:09 · Sponsor 23:14"), that there are none, that they didn't load, or "Off"
 * when no lookup ran (SponsorBlock off, every category Off, or a Short).
 */
fun sponsorBlockText(lookup: SegmentLookup?): String = when (lookup) {
    null -> "Off"
    SegmentLookup.None -> "No segments for this video"
    SegmentLookup.Failed -> "Segments didn’t load for this video; it plays without them"
    is SegmentLookup.Found -> lookup.segments.sortedBy { it.startMs }.joinToString(MetaSeparator) { "${it.category.text.name} ${timeText(it.startMs)}" }
}

/** The skip note's line: "Skipped self-promotion", "Skipped sponsor, intro animation and
 *  self-promotion" (the categories in start order, by their Settings names). */
/** The player controls' Skip, read out (PAF-D12): "Skip sponsor". */
fun skipControlText(category: SegmentCategory): String = "Skip ${category.text.name.replaceFirstChar { it.lowercase() }}"

fun skippedText(categories: List<SegmentCategory>): String {
    val names = categories.map { category -> category.text.name.replaceFirstChar { it.lowercase() } }
    val joined = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
    return "Skipped $joined"
}

/** Q51: the Playback sheet's line under Captions when the profile's default has no track here. */
fun missingCaptionsText(language: String): String = "No ${captionsLabel(language)} captions for this video"

/** The Captions sheet's line (`b3-player-panels` ccchoice, ccmissingchoice): a pick is for this
 *  video, and where the profile's default lives. */
fun captionsSheetSub(captions: PlayerCaptions): String {
    val missing = captions.missingLanguage
    return if (missing != null) {
        "Just for this video. It has no ${captionsLabel(missing)} captions, your default in $PLAYBACK_SETTINGS."
    } else {
        "Just for this video; your default (${captionsLabel(captions.language)}) is in $PLAYBACK_SETTINGS."
    }
}

/** D8 in progress: what the player is doing ([text]) and how far it has got ([count], "2 of 6"). */
data class RetryLine(val text: String, val count: String)

/** [retry]'s line while the player retries or refreshes its session; `null` otherwise. */
fun retryLine(retry: PlayerErrorUiState): RetryLine? = when (retry) {
    is PlayerErrorUiState.Retrying -> RetryLine("Connection dropped · retrying", "${retry.attempt} of ${retry.max}")
    is PlayerErrorUiState.Refreshing -> RetryLine("Refreshing the playback session", "${retry.attempt} of ${retry.max}")
    PlayerErrorUiState.None, is PlayerErrorUiState.Terminal -> null
}

/** Where a player sheet's device-wide or profile default lives, as its line names it. */
const val PLAYBACK_SETTINGS = "Settings\u00A0›\u00A0Playback"

/** The Quality sheet's line: a pick holds for this video, under the device's most. */
const val QUALITY_SHEET_SUB = "Just for this video. $PLAYBACK_SETTINGS sets the most Ayn plays."

/** The Speed sheet's line. */
const val SPEED_SHEET_SUB = "Pitch stays natural at every speed."

/** Under the Quality sheet's Auto: the lines playing while nothing is [held] and they are known. */
fun autoQualityHint(held: QualityOption?, nowLines: Int?): String =
    if (held == null && nowLines != null) "Now ${nowLines}p · adapts to the connection" else "Adapts to the connection"

/** Under a track YouTube made itself. */
const val AUTO_CAPTIONS_HINT = "Made by YouTube’s speech recognition"
