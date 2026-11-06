package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.VideoRefusal
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

/**
 * The refusal a video page's [ContentNotAvailableException] names, if any. Only the base type
 * counts: its subclasses are the refusals the fork already matched by their reason (members,
 * region, paid, private, age, terminated). The status and reason come from the message the
 * fork's `checkPlayabilityStatus` builds, `Got error <STATUS>: "<reason>"` (pinned by the
 * `video-made-for-kids` and `video-upcoming` fixtures).
 */
internal fun videoRefusalOf(e: ContentNotAvailableException): VideoRefusal? {
    if (e.javaClass != ContentNotAvailableException::class.java) return null
    val match = PLAYABILITY_ERROR.matchEntire(e.message.orEmpty()) ?: return null
    val (status, reason) = match.destructured
    return when (status) {
        // VISIONOS answers a made-for-kids video UNPLAYABLE "This video is not available" (spec 12
        // derisk ledger); Restricted Mode's refusal is ERROR, so it never reads as made-for-kids.
        STATUS_UNPLAYABLE -> VideoRefusal.MadeForKids
        STATUS_NOT_STARTED -> VideoRefusal.NotStarted(shownReason(reason))
        else -> null
    }
}

/** YouTube's reason as one capped line; null when the fork had none (it prints "null"). */
private fun shownReason(reason: String): String? {
    val line = reason.replace(CONTROL_OR_SPACE_RUN, " ").trim()
    if (line.isEmpty() || line == "null") return null
    return if (line.length <= MAX_REASON_CHARS) line else line.take(MAX_REASON_CHARS - 1).trimEnd() + "…"
}

private val PLAYABILITY_ERROR = Regex("""Got error (\w+): "(.*)"""", RegexOption.DOT_MATCHES_ALL)
private val CONTROL_OR_SPACE_RUN = Regex("""[\s\p{Z}\p{Cc}\p{Cf}]+""")
private const val STATUS_UNPLAYABLE = "UNPLAYABLE"
private const val STATUS_NOT_STARTED = "LIVE_STREAM_OFFLINE"
private const val MAX_REASON_CHARS = 120
