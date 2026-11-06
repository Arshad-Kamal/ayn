package com.arshad.ayn.ui.tv.player

import com.arshad.ayn.core.store.ChatPlacement
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.Player
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.store.SPEED_PERCENTS
import com.arshad.ayn.feature.format.liveWatchingText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.player.AUTO_CAPTIONS_HINT
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerCaptions
import com.arshad.ayn.feature.player.PlayerUiState
import com.arshad.ayn.feature.player.QUALITY_SHEET_SUB
import com.arshad.ayn.feature.player.QualityOption
import com.arshad.ayn.feature.player.SPEED_SHEET_SUB
import com.arshad.ayn.feature.player.VideoQuality
import com.arshad.ayn.feature.player.autoQualityHint
import com.arshad.ayn.feature.player.captionsSheetSub
import com.arshad.ayn.feature.player.chatReplayKicker
import com.arshad.ayn.feature.player.followChat
import com.arshad.ayn.feature.player.holdQuality
import com.arshad.ayn.feature.player.missingCaptionsText
import com.arshad.ayn.feature.player.pictureLines
import com.arshad.ayn.feature.player.qualityValue
import com.arshad.ayn.feature.player.sponsorBlockText
import com.arshad.ayn.feature.settings.heightName
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.settings.Choice
import com.arshad.ayn.ui.tv.settings.RowIcon
import com.arshad.ayn.ui.tv.settings.SettingRow
import com.arshad.ayn.ui.tv.settings.SettingsSheet
import com.arshad.ayn.ui.tv.settings.SheetRowMinHeight
import com.arshad.ayn.ui.tv.settings.SheetTitle
import com.arshad.ayn.ui.tv.settings.rememberSheetFocus
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlin.math.roundToInt

/** What can show over the playing video (`b3-player-panels` settings, speed, quality, captions,
 *  details, cmt-*; `live-tv-player` chat-*); Comments also over a Short. */
internal enum class PlayerPanel { Playback, Speed, Quality, Captions, Details, Comments, Chat, ChatSheet }

/** Details, Comments and Chat move the picture into a frame beside them; the rest are sheets over it. */
internal val PlayerPanel.framesPicture: Boolean
    get() = this == PlayerPanel.Details || this == PlayerPanel.Comments || this == PlayerPanel.Chat

/**
 * The player's open panels, the latest on top: ⋯ opens Playback, Speed opens Speed, HD opens
 * Quality and CC opens Captions (from the chrome or the Playback sheet), Video details opens
 * Details, Comments opens Comments, Chat opens Chat or the Chat sheet. BACK closes the top one; a pick that finishes the errand (a
 * speed, a quality, captions, a chapter) closes them all.
 */
@Stable
internal class PanelStack {
    private var panels: List<PlayerPanel> by mutableStateOf(emptyList())

    /** The panel BACK closed last, while the one under it shows: the Playback sheet opens on its row. */
    var closed: PlayerPanel? by mutableStateOf(null)
        private set

    val top: PlayerPanel? get() = panels.lastOrNull()

    fun open(panel: PlayerPanel) {
        closed = null
        panels = panels + panel
    }

    fun back() {
        closed = panels.lastOrNull()
        panels = panels.dropLast(1)
    }

    fun closeAll() {
        closed = null
        panels = emptyList()
    }

    /** [panels] in place of what shows, the last on top (the Chat sheet moving chat in or out of
     *  the panel under it). */
    fun replace(panels: List<PlayerPanel>) {
        closed = null
        this.panels = panels
    }

    operator fun contains(panel: PlayerPanel): Boolean = panel in panels
}

/**
 * The open panel over the stage: the Playback and Speed sheets at the right edge over the dimmed
 * picture, Details with the picture in its corner, or Comments or a [live] stream's chat with the
 * picture beside it (Q7, LIVE-D15), or the Chat sheet over the picture (LIVE-D21); an ended
 * stream's kept chat ([replay], LIVE-D28) as a live stream's, following the play position. The
 * chat polls only while its panel is open (under the Chat sheet too) or its view over the picture
 * shows ([ChatShownEffect]; the view itself is the stage's, [ChatOverlay]).
 * BACK closes the top panel (registered after the stage's, whose handlers all stand down while a
 * panel shows; the comments panel's own, for its replies, after this one). Details asks for its
 * votes as it opens ([onOpenDetails]); a captions pick goes to [onChooseCaptions] (`null`: Off).
 * A [live] stream's Playback sheet has no Speed (it plays at 1×, LIVE-D23).
 */
@Composable
internal fun PlayerPanels(
    stack: PanelStack,
    player: Player,
    view: PlaybackView,
    state: PlayerUiState,
    dressing: PlaybackDressing?,
    actions: ChromeActions,
    commentsPlace: CommentsPlace,
    commentsCalls: CommentsCalls,
    chatCalls: ChatCalls,
    onOpenDetails: () -> Unit,
    onChooseCaptions: (CaptionTrack?) -> Unit,
    live: Boolean = false,
    replay: Boolean = false,
) {
    val top = stack.top
    val place = state.chatPlace
    BackHandler(enabled = top != null) {
        // BACK in Scroll chat's panel puts chat back over the picture (LIVE-D22).
        if (top == PlayerPanel.Chat && place.scrolling) chatCalls.endScroll()
        stack.back()
    }
    Overlays<PlayerPanel, Unit>(
        sheet = top?.takeUnless { it.framesPicture },
        cover = null,
        onRailShade = {},
        sheetContent = { panel ->
            when (panel) {
                PlayerPanel.Playback -> PlaybackSheet(
                    quality = qualityValue(view.videoSize.width, view.videoSize.height, view.codec),
                    qualityHeld = view.quality.held != null,
                    speed = speedLabel(speedPercent(view.speed)).takeUnless { live },
                    segments = dressing?.segments,
                    captions = state.captions,
                    closed = stack.closed,
                    onOpenQuality = { stack.open(PlayerPanel.Quality) },
                    onOpenSpeed = { stack.open(PlayerPanel.Speed) },
                    onOpenCaptions = { stack.open(PlayerPanel.Captions) },
                    onOpenDetails = {
                        stack.open(PlayerPanel.Details)
                        onOpenDetails()
                    },
                )
                PlayerPanel.Speed -> SpeedSheet(
                    chosen = speedPercent(view.speed),
                    onPick = { percent ->
                        player.setPlaybackSpeed(percent / 100f)
                        stack.closeAll()
                    },
                )
                PlayerPanel.Quality -> QualitySheet(view.quality, nowLines = pictureLines(view.videoSize.width, view.videoSize.height)) { option ->
                    player.holdQuality(option)
                    stack.closeAll()
                }
                PlayerPanel.Captions -> state.captions?.let { captions ->
                    CaptionsSheet(captions) { track ->
                        onChooseCaptions(track)
                        stack.closeAll()
                    }
                }
                PlayerPanel.ChatSheet -> ChatSheet(
                    kicker = if (replay) chatReplayKicker(state.video) else metaLine(state.video.uploaderName, state.video.liveWatchingText()),
                    chat = state.chat,
                    place = place,
                    calls = chatSheetCalls(stack, chatCalls, scrollable = place.overlay != null),
                )
                PlayerPanel.Details, PlayerPanel.Comments, PlayerPanel.Chat -> Unit
            }
        },
        coverContent = {},
        page = {},
    )
    val commentsOpen = top == PlayerPanel.Comments
    val chatOpen = top == PlayerPanel.Chat
    AnimatedVisibility(commentsOpen || chatOpen, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
        CommentsBeside(state.video, dressing?.uploaderAvatarUrl, dressing?.chapters.orEmpty(), player, live)
    }
    CommentsPanel(
        open = commentsOpen,
        comments = state.comments,
        channel = CommentsChannel(state.video.uploaderName, dressing?.uploaderAvatarUrl),
        place = commentsPlace,
        calls = commentsCalls,
    )
    if (live || replay) {
        // Polls while its panel is up, under the Chat sheet too, or chat is over the picture, stepped
        // aside or not: neither a press nor the sheet restarts it.
        val chatShown = PlayerPanel.Chat in stack || place.overlay != null
        ChatShownEffect(chatShown, state.turn, dressing, chatCalls)
        if (replay && chatShown) {
            val turn = state.turn
            LaunchedEffect(player, turn, chatCalls) { player.followChat { at, playing -> chatCalls.tick(turn, at, playing) } }
        }
        ChatPanel(
            open = chatOpen,
            chat = state.chat,
            kicker = if (replay) chatReplayKicker(state.video) else state.video.liveWatchingText(),
            replay = replay,
            calls = chatCalls,
            onView = { stack.open(PlayerPanel.ChatSheet) },
            scrolling = place.scrolling,
            fromSheet = stack.closed == PlayerPanel.ChatSheet,
        )
    }
    AnimatedVisibility(top == PlayerPanel.Details, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
        PlayerDetails(
            video = state.video,
            dressing = dressing,
            votes = state.votes,
            isSubscribed = state.isSubscribed,
            player = player,
            onToggleSubscribe = actions.onToggleSubscribe,
            onChapter = { chapter ->
                actions.onSeekTo(chapter.startSeconds * 1000)
                stack.closeAll()
            },
        )
    }
}

/** The Chat sheet's calls on [stack]: a pick re-stacks what waits under it (the panel beside the
 *  picture, or nothing over it), Scroll chat ([scrollable] while chat is over the picture) leaves
 *  the panel alone on top, Hide chat closes everything. */
private fun chatSheetCalls(stack: PanelStack, calls: ChatCalls, scrollable: Boolean): ChatSheetCalls = ChatSheetCalls(
    chooseView = calls.chooseView,
    choosePlacement = { placement ->
        calls.choosePlacement(placement)
        stack.replace(if (placement == ChatPlacement.Beside) listOf(PlayerPanel.Chat, PlayerPanel.ChatSheet) else listOf(PlayerPanel.ChatSheet))
    },
    scroll = if (scrollable) {
        {
            calls.scroll()
            stack.replace(listOf(PlayerPanel.Chat))
        }
    } else {
        null
    },
    hide = {
        calls.hide()
        stack.closeAll()
    },
)

private fun speedPercent(speed: Float): Int = (speed * 100).roundToInt()

private const val QUALITY_KEY = "quality"
private const val SPEED_KEY = "speed"
private const val CAPTIONS_KEY = "captions"
private const val DETAILS_KEY = "details"
private const val OFF_KEY = "off"

/**
 * The Playback sheet (`b3-player-panels` settings, qualityheld, sbfail, ccnone, ccmissing; Q24,
 * Q25, Q51, Q60): Quality (the picture playing, and whether a height is [qualityHeld]), Speed
 * (none without a [speed]), Captions, what SponsorBlock found for this video, and Video details.
 * It opens on the row of the panel BACK just [closed], else on Quality.
 */
@Composable
private fun PlaybackSheet(
    quality: String?,
    qualityHeld: Boolean,
    speed: String?,
    segments: SegmentLookup?,
    captions: PlayerCaptions?,
    closed: PlayerPanel?,
    onOpenQuality: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenCaptions: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val focus = rememberSheetFocus(
        when (closed) {
            PlayerPanel.Speed -> SPEED_KEY
            PlayerPanel.Captions -> CAPTIONS_KEY
            PlayerPanel.Details -> DETAILS_KEY
            PlayerPanel.Quality, PlayerPanel.Playback, PlayerPanel.Comments, PlayerPanel.Chat, PlayerPanel.ChatSheet, null -> QUALITY_KEY
        },
    )
    SettingsSheet(kicker = "Playing now", title = { SheetTitle("Playback") }, sub = null) {
        SettingRow(
            label = "Quality",
            onClick = onOpenQuality,
            value = quality,
            hint = QUALITY_HELD_HINT.takeIf { qualityHeld },
            chevron = true,
            minHeight = SheetRowMinHeight,
            modifier = focus.on(QUALITY_KEY),
            lead = { RowIcon(R.drawable.ic_hq, it) },
        )
        if (speed != null) {
            SettingRow(
                label = "Speed",
                onClick = onOpenSpeed,
                value = speed,
                minHeight = SheetRowMinHeight,
                modifier = focus.on(SPEED_KEY),
                lead = { RowIcon(R.drawable.ic_speed, it) },
            )
        }
        CaptionsRow(captions, onOpenCaptions, focus.on(CAPTIONS_KEY))
        SettingRow(
            label = "SponsorBlock",
            onClick = {},
            hint = sponsorBlockText(segments),
            minHeight = SheetRowMinHeight,
            lead = { RowIcon(R.drawable.ic_shield, it) },
        )
        SettingRow(
            label = "Video details",
            onClick = onOpenDetails,
            chevron = true,
            minHeight = SheetRowMinHeight,
            modifier = focus.on(DETAILS_KEY),
            lead = { RowIcon(R.drawable.ic_info, it) },
        )
    }
}

/**
 * Captions for this video: the chosen track's name or Off, with the Q51 line while the profile's
 * default has no track here and none is chosen; OK opens the Captions sheet. A video without
 * tracks says so, dimmed, and focus passes over it (ccnone).
 */
@Composable
private fun CaptionsRow(captions: PlayerCaptions?, onOpen: () -> Unit, modifier: Modifier) {
    val none = captions == null || captions.tracks.isEmpty()
    val chosen = captions?.chosen
    SettingRow(
        label = "Captions",
        onClick = onOpen,
        value = if (none) "None for this video" else chosen?.name ?: "Off",
        hint = captions?.missingLanguage?.takeIf { !none && chosen == null }?.let(::missingCaptionsText),
        chevron = !none,
        focusable = !none,
        minHeight = SheetRowMinHeight,
        modifier = if (none) modifier.graphicsLayer { alpha = GapAlpha } else modifier,
        lead = { RowIcon(R.drawable.ic_cc, it) },
    )
}

/**
 * Captions for this video (`b3-player-panels` ccchoice, ccmissingchoice): Off, then the video's
 * tracks by YouTube's names, YouTube's own marked as such. It opens on the chosen one; OK picks
 * ([onPick]) for this video only, the profile's default unchanged.
 */
@Composable
private fun CaptionsSheet(captions: PlayerCaptions, onPick: (CaptionTrack?) -> Unit) {
    val chosen = captions.chosen
    // Keyed by place: a video can list two tracks with one name.
    val focus = rememberSheetFocus(chosen?.let { "${captions.tracks.indexOf(it)}" } ?: OFF_KEY)
    SettingsSheet(kicker = "Playback", title = { SheetTitle("Captions") }, sub = captionsSheetSub(captions), scroll = true) {
        Choice("Off", on = chosen == null, onClick = { onPick(null) }, modifier = focus.on(OFF_KEY))
        captions.tracks.forEachIndexed { index, track ->
            Choice(
                label = track.name,
                on = track == chosen,
                onClick = { onPick(track) },
                modifier = focus.on("$index"),
                hint = AUTO_CAPTIONS_HINT.takeIf { track.autoGenerated },
            )
        }
    }
}

/**
 * This video's quality (`b3-player-panels` quality, qualityheldchoice; Q60): Auto, adapting up to
 * the TV's cap (the picture's [nowLines] beside it while nothing is held), then each height the
 * video has. It opens on the held one, else Auto; OK picks ([onPick], `null` for Auto) for this
 * video only.
 */
@Composable
private fun QualitySheet(quality: VideoQuality, nowLines: Int?, onPick: (QualityOption?) -> Unit) {
    val held = quality.held
    val focus = rememberSheetFocus(held?.let { "${it.lines}" } ?: AUTO_KEY)
    SettingsSheet(
        kicker = "Playback",
        title = { SheetTitle("Quality") },
        sub = QUALITY_SHEET_SUB,
        scroll = true,
    ) {
        Choice(
            label = "Auto",
            on = held == null,
            onClick = { onPick(null) },
            modifier = focus.on(AUTO_KEY),
            hint = autoQualityHint(held, nowLines),
        )
        quality.options.forEach { option ->
            Choice(
                label = "${option.lines}p",
                on = option == held,
                onClick = { onPick(option) },
                modifier = focus.on("${option.lines}"),
                hint = heightName(option.lines),
            )
        }
    }
}

private const val AUTO_KEY = "auto"
private const val QUALITY_HELD_HINT = "Held for this video"

/** This video's speed (`b3-player-panels` speed): it opens on the [chosen] one, and OK picks. */
@Composable
private fun SpeedSheet(chosen: Int, onPick: (Int) -> Unit) {
    val focus = rememberSheetFocus("${if (chosen in SPEED_PERCENTS) chosen else 100}")
    SettingsSheet(
        kicker = "Playback",
        title = { SheetTitle("Speed") },
        sub = SPEED_SHEET_SUB,
        scroll = true,
    ) {
        SPEED_PERCENTS.forEach { percent ->
            Choice(speedLabel(percent), on = percent == chosen, onClick = { onPick(percent) }, modifier = focus.on("$percent"))
        }
    }
}
