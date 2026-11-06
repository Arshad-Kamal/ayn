package com.arshad.ayn.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.compactCount
import com.arshad.ayn.feature.format.liveWatchingText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.startedStreamingText
import com.arshad.ayn.feature.format.streamedForText
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.player.PlaylistPosition
import com.arshad.ayn.feature.player.playlistPlaceText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.search.RowInset
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlin.time.Instant

/** What leads the info: the chapter playing, Play all's place, a live stream's LIVE tag
 *  (`live-phone-player` 1), or nothing (`.wchap`, `.wpl`). */
internal sealed interface Lead {
    data class Chapter(val number: Int, val title: String) : Lead

    data class Playlist(val position: PlaylistPosition) : Lead

    data object Live : Lead
}

/** The chips under the channel: Save, Comments (YouTube's [comments] count once fetched; none
 *  without [onComments]: a kids profile, KP-D11, or a live stream), a live stream's Chat in its
 *  place ([onChat]; dimmed and inert unless [chatOffered], LIVE-D27), an ended stream's kept chat
 *  right after Comments (LIVE-D28) and, with [chapters], Chapters. */
internal class WatchChips(
    val onSave: () -> Unit,
    val comments: String?,
    val onComments: (() -> Unit)?,
    val onChat: (() -> Unit)?,
    val chatOffered: Boolean,
    val chapters: Int,
    val onChapters: () -> Unit,
)

/**
 * The info under the picture (`m6-player` 2, 12): [lead], the title (its DeArrow face), [meta]
 * ("more" on it opens Video details, [onMore]), then, once [channel] is known, the channel with
 * Subscribe and the [chips]. Before that (the video starting, or a statement) only the title and
 * the channel's name show.
 */
@Composable
internal fun WatchHeader(video: VideoItem, lead: Lead?, meta: String?, channel: ChannelLine?, chips: WatchChips?, onMore: (() -> Unit)? = null) {
    val face = rememberCardFace(video)
    Column(Modifier.padding(start = RowInset, end = RowInset, top = 14.dp)) {
        when (lead) {
            is Lead.Chapter -> Text(
                "Chapter ${lead.number} · ${lead.title}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp).atWeight(620),
                color = AynColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is Lead.Playlist -> PlaylistLead(lead.position)
            Lead.Live -> LiveTag(lit = true)
            null -> Unit
        }
        Text(
            face?.title ?: video.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 1.0.em, letterSpacing = (-0.004).em),
            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
        )
        meta?.let { MetaLine(it, onMore) }
        if (channel != null) {
            ChannelRow(channel)
            if (chips != null) {
                // 8 dp: each chip's touch target reaches 6 dp above its 36 dp fill.
                Row(
                    Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(R.drawable.ic_list, "Save", onClick = chips.onSave)
                    chips.onComments?.let { Chip(R.drawable.ic_comment, "Comments", chips.comments, it) }
                    chips.onChat?.let { Chip(R.drawable.ic_chat, "Chat", onClick = it, enabled = chips.chatOffered) }
                    if (chips.chapters > 0) Chip(R.drawable.ic_chapters, "Chapters", chips.chapters.toString(), chips.onChapters)
                }
            }
        }
    }
}

/** "≡ **Engineering deep dives** · 3 of 8". */
@Composable
private fun PlaylistLead(position: PlaylistPosition) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(16.dp))
        Text(
            buildAnnotatedString {
                withStyle(weightSpan(650, AynColors.Ink)) { append(position.title) }
                withStyle(SpanStyle(color = AynColors.Ink3)) { append("  ·  ") }
                append(playlistPlaceText(position))
            },
            style = MaterialTheme.typography.bodySmall,
            color = AynColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The channel under the title: its picture and name open it, Subscribe toggles (none for a kids
 *  profile: [onToggleSubscribe] `null`, KP-D11). */
internal class ChannelLine(
    val name: String,
    val avatarUrl: String?,
    val subscribers: Long?,
    val subscribed: Boolean,
    val onOpen: (() -> Unit)?,
    val onToggleSubscribe: (() -> Unit)?,
)

@Composable
private fun ChannelRow(channel: ChannelLine) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .then(if (channel.onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the channel", onClick = channel.onOpen) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(38.dp)) { ChannelAvatar(channel.name, channel.avatarUrl, monogramSize = 15f) }
            Column {
                Text(channel.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp).atWeight(650), maxLines = 1, overflow = TextOverflow.Ellipsis)
                channel.subscribers?.let(::subscribersText)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3)
                }
            }
        }
        channel.onToggleSubscribe?.let { toggle ->
            Spacer(Modifier.width(12.dp))
            if (channel.subscribed) {
                PillButton("Subscribed", toggle, Modifier.height(36.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_check)
            } else {
                PillButton("Subscribe", toggle, Modifier.height(36.dp))
            }
        }
    }
}

/** The meta line; with [onMore], "more" after it and a tap anywhere on it opens Video details. */
@Composable
private fun MetaLine(meta: String, onMore: (() -> Unit)?) {
    val style = MaterialTheme.typography.bodySmall
    if (onMore == null) {
        Text(meta, style = style, color = AynColors.Ink3, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val interaction = remember { MutableInteractionSource() }
    Text(
        buildAnnotatedString {
            append(meta)
            withStyle(weightSpan(650, AynColors.Ink)) { append("\u2002more") }
        },
        style = style,
        color = AynColors.Ink3,
        modifier = Modifier
            .padding(top = 8.dp)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Open video details", onClick = onMore),
    )
}

/** A chip under the channel (`.chip`): an icon, a word and its [count]; it sinks while pressed,
 *  and dims while not [enabled]. */
@Composable
private fun Chip(icon: Int, label: String, count: String? = null, onClick: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val dim by animateFloatAsState(if (enabled) 1f else DIMMED, AynMotion.base(), label = "chipDim")
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .graphicsLayer { alpha = dim }
            .pressScale(interaction)
            .clip(CircleShape)
            .background(AynColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 11.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp))
        count?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp, fontFeatureSettings = "tnum").atWeight(560), color = AynColors.Ink3)
        }
    }
}

private const val DIMMED = 0.4f

/** The "Up next" heading over the related videos (`.unhead`). */
@Composable
internal fun UpNextHeading() {
    Text(
        "Up next",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = RowInset, top = 20.dp, bottom = 12.dp).semantics { heading() },
    )
}

/** Views, YouTube's likes when it shows them, then the age: "15M views · 121K likes · 12 days ago". */
internal fun watchMeta(video: VideoItem, likes: Long?): String? = metaLine(
    viewsText(video.viewCount),
    likes?.let { "${compactCount(it)} likes" },
    video.textualUploadDate,
).ifEmpty { null }

/** A live page's meta at [now] (`live-phone-player` 1, LIVE-D24): how many watch
 *  ([liveWatchingText]) and how long it has run once the resolve says ([startedAt]):
 *  "1.2K watching · Started streaming 1 hour ago". */
internal fun liveMeta(video: VideoItem, startedAt: Instant?, now: Instant): String? = metaLine(
    video.liveWatchingText(),
    startedAt?.let { startedStreamingText(it, now) },
).ifEmpty { null }

/** An ended stream's meta (`live-phone-player` 10): the channel and how long it streamed, from
 *  [startedAt] to [endedAt]: "NASA · streamed for 3 hours". */
internal fun endedMeta(channel: String?, startedAt: Instant?, endedAt: Instant?): String? = metaLine(
    channel,
    if (startedAt != null && endedAt != null) streamedForText(startedAt, endedAt) else null,
).ifEmpty { null }
