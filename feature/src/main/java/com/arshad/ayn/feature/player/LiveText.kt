package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.dateText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.liveWatchingText

/** The chat panel's title: a live stream's chat or an ended stream's kept replay (LIVE-D7). */
fun chatTitle(replay: Boolean): String = if (replay) "Chat replay" else "Live chat"

/** The view named after [chatTitle] over the picture ("Live chat · Top chat"); `null` where it would
 *  repeat the title, a live stream's Live chat view (PAF-D7). */
fun chatViewName(replay: Boolean, liveView: Boolean): String? = when {
    !liveView -> "Top chat"
    replay -> "Live chat"
    else -> null
}

/** Over an ended stream's chat replay: the channel, its views and when it streamed, as YouTube
 *  words it ("NASA · 412K views · Streamed 2 days ago"). */
fun chatReplayKicker(card: VideoItem): String = metaLine(card.uploaderName, viewsText(card.viewCount), card.dateText())

/** Why a live stream stopped (`live-tv-player`, `live-phone-player`: unavailable, kids, ended). */
enum class LiveStop { Unavailable, Kids, Ended }

/** A live statement's words: the [kicker] after its LIVE tag, the [title] and the [body]. */
data class LiveStopText(val kicker: String, val title: String, val body: String)

/**
 * What both players say for [stop] on [card]: YouTube offered nothing Ayn can play, a kids
 * profile's refusal (LIVE-D13), or the stream ended while watched. The kicker names the channel,
 * then how many watch ([liveWatchingText]) or that it ended just now.
 */
fun liveStopText(stop: LiveStop, card: VideoItem): LiveStopText {
    val channel = card.uploaderName
    val kicker = metaLine(channel, if (stop == LiveStop.Ended) "ended just now" else card.liveWatchingText())
    return when (stop) {
        LiveStop.Unavailable -> LiveStopText(
            kicker,
            "This live stream can’t play right now",
            "YouTube didn’t offer Ayn a version of this stream it can play. A stream that has just started can need a minute.",
        )
        LiveStop.Kids -> LiveStopText(
            kicker,
            "Live streams are off for kids",
            "A live broadcast can’t be checked before it airs, so kids profiles don’t play them.",
        )
        LiveStop.Ended -> LiveStopText(
            kicker,
            "This stream has ended",
            "${channel?.let { "$it’s" } ?: "The"} recording usually appears on the channel a few hours after the stream ends.",
        )
    }
}
