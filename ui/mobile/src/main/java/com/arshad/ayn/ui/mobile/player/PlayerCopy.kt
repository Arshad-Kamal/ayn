package com.arshad.ayn.ui.mobile.player

import androidx.annotation.DrawableRes
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.LiveStop
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.liveStopText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.home.UnreadableReply

/** What a stopped player says in the picture's box (`m6-player` 26–28); no [retryLabel] = ⌄ only.
 *  A live statement's kicker leads with the LIVE tag ([liveMark]) in place of [icon]. */
internal data class StopCopy(
    @param:DrawableRes val icon: Int,
    val kicker: String,
    val title: String,
    val body: String,
    val retryLabel: String?,
    val liveMark: LiveMark? = null,
)

/** The LIVE tag on a live statement: lit while the stream is on, outlined once it has ended. */
internal enum class LiveMark { Lit, Outlined }

/**
 * The statement for a stop [reason] (`PlayerPolicy.TERMINAL_*`), as the TV says it with the
 * phone's words. A kept place ([positionMs] > 0) is where Try again resumes. A refusal (YouTube's,
 * the profile's filters, a kids profile's time) offers no Try again; a not-started video's
 * [detail] is YouTube's own sentence. Under Restricted Mode Strict ([restricted], the profile's
 * own or the owner's) YouTube's refusal names that as a cause too.
 */
internal fun stopCopy(reason: String, positionMs: Long, detail: String? = null, restricted: Boolean = false): StopCopy {
    val place = positionMs.takeIf { it > 0 }?.let(::timeText)
    val saved = if (place != null) " Your place is saved." else ""
    val retry = place?.let { "Try again from $it" } ?: "Try again"
    return when (reason) {
        PlayerPolicy.TERMINAL_EXHAUSTED -> StopCopy(
            R.drawable.ic_alert,
            "Stopped after ${PlayerPolicy.RECOVERABLE_RETRY_BUDGET} tries",
            "This video stopped playing",
            "YouTube kept dropping the connection.$saved",
            retry,
        )
        PlayerPolicy.TERMINAL_ATTESTATION -> StopCopy(
            R.drawable.ic_alert,
            "Stopped after ${PlayerPolicy.ATTESTATION_RETRY_BUDGET} refreshes",
            "This video stopped playing",
            "YouTube didn’t accept Ayn’s playback session.$saved",
            retry,
        )
        PlayerPolicy.TERMINAL_UNAVAILABLE, PlayerPolicy.TERMINAL_PROTOCOL -> StopCopy(
            R.drawable.ic_block,
            "YouTube refused to play it",
            "This video can’t play here",
            refusedBody(restricted),
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_MADE_FOR_KIDS -> StopCopy(
            R.drawable.ic_block,
            "YouTube refused to play it",
            "This made-for-kids video can’t play in Ayn",
            "YouTube wouldn’t send it to Ayn, through either of the players Ayn uses.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_NOT_STARTED -> StopCopy(
            R.drawable.ic_later,
            "Not started yet",
            "This video hasn’t started yet",
            detail ?: "It’s a premiere or a scheduled stream. Come back when it begins.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_FILTERED -> StopCopy(
            R.drawable.ic_block,
            "Hidden in this profile",
            "This video can’t play here",
            "This profile’s filters keep it from playing.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_TIME_UP -> StopCopy(
            R.drawable.ic_later,
            "Watch time",
            "Time’s up for now",
            "This profile’s watch time has run out.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_RESOLVE_OFFLINE -> StopCopy(
            R.drawable.ic_offline,
            "No connection to YouTube",
            "This video couldn’t start",
            "Check that the phone is online, then try again.",
            retry,
        )
        // TERMINAL_RESOLVE_UNREADABLE, and any reason this screen predates.
        else -> StopCopy(R.drawable.ic_alert, "YouTube changed something", "This video couldn’t start", UnreadableReply, retry)
    }
}

private fun refusedBody(restricted: Boolean): String =
    if (restricted) {
        "It may be private, removed, age-restricted, members-only, blocked in your region, or hidden by Restricted Mode."
    } else {
        "It may be private, removed, age-restricted, members-only, or blocked in your region."
    }

/** The playback service didn't answer; Try again binds again. */
internal val PlayerDownCopy = StopCopy(
    R.drawable.ic_alert,
    "The player didn’t answer",
    "This video couldn’t start",
    "Ayn’s player stopped responding. Try again; if it keeps happening, restart Ayn.",
    "Try again",
)

/** A live stream's statement (`live-phone-player` states 10–11; the words [liveStopText]'s): only
 *  YouTube offering nothing playable can be tried again. */
internal fun liveStopCopy(stop: LiveStop, card: VideoItem): StopCopy {
    val text = liveStopText(stop, card)
    return StopCopy(
        R.drawable.ic_live,
        text.kicker,
        text.title,
        text.body,
        retryLabel = "Try again".takeIf { stop == LiveStop.Unavailable },
        liveMark = if (stop == LiveStop.Ended) LiveMark.Outlined else LiveMark.Lit,
    )
}

/** The resolve refused a live page ([PlayerPolicy.TERMINAL_LIVE]): a [kids] profile's refusal
 *  (LIVE-D13), else YouTube offered nothing playable. */
internal fun liveRefusalCopy(kids: Boolean, card: VideoItem): StopCopy =
    liveStopCopy(if (kids) LiveStop.Kids else LiveStop.Unavailable, card)

/** A stop as the Shorts player says it (`m6-shorts` 11–12): about the Short, a swipe named as the
 *  way on when it can be tried again and the run has another Short ([canMove]); a refusal keeps
 *  the phone's "can't play here". */
internal fun StopCopy.forShort(canMove: Boolean): StopCopy =
    if (retryLabel == null) {
        copy(title = "This Short can’t play here")
    } else {
        val wayOn = if (canMove) " Swipe up or down for another Short." else ""
        copy(title = "This Short couldn’t play", body = "$body$wayOn", retryLabel = "Try again")
    }
