package com.arshad.ayn.ui.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.store.SPEED_PERCENTS
import com.arshad.ayn.feature.player.AUTO_CAPTIONS_HINT
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerCaptions
import com.arshad.ayn.feature.player.QUALITY_SHEET_SUB
import com.arshad.ayn.feature.player.QualityOption
import com.arshad.ayn.feature.player.SPEED_SHEET_SUB
import com.arshad.ayn.feature.player.VideoQuality
import com.arshad.ayn.feature.player.autoQualityHint
import com.arshad.ayn.feature.player.captionsSheetSub
import com.arshad.ayn.feature.player.holdQuality
import com.arshad.ayn.feature.player.missingCaptionsText
import com.arshad.ayn.feature.player.pictureLines
import com.arshad.ayn.feature.player.sponsorBlockText
import com.arshad.ayn.feature.settings.heightName
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.menu.RowRadio
import com.arshad.ayn.ui.mobile.menu.SheetKicker
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.SheetSub
import com.arshad.ayn.ui.mobile.menu.SheetTitle
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.theme.AynColors
import kotlin.math.roundToInt

/** The player's sheets (`m6-player` 13–16): ⚙ opens Playback, CC opens Captions; a live stream's
 *  Chat (`live-phone-player` 19–20) opens from the chrome's Chat or the chat panel's View. */
internal enum class PlayerSheet : Pane { Playback, Quality, Speed, Captions, Chat }

/**
 * The player's open sheets, the latest on top: Playback's rows open Quality, Speed and Captions
 * over it. Back closes the top one; a pick, a swipe or the dim closes them all.
 */
@Stable
internal class SheetStack {
    private var sheets: List<PlayerSheet> by mutableStateOf(emptyList())

    val top: PlayerSheet? get() = sheets.lastOrNull()

    /** A sheet lies under the top one (its stage gets ← beside the picture). */
    val deep: Boolean get() = sheets.size > 1

    fun open(sheet: PlayerSheet) {
        sheets = sheets + sheet
    }

    fun back() {
        sheets = sheets.dropLast(1)
    }

    fun closeAll() {
        sheets = emptyList()
    }
}

/** What a sheet's stages act on: Quality and Speed on [player] for this video only (a [live]
 *  stream has no Speed: it plays at 1×, LIVE-D23), a captions pick through [onChooseCaptions]
 *  (`null`: Off); Video details closes the sheets for [onOpenDetails]; Chat's on [chat] (none
 *  off a live stream). */
internal class SheetCalls(
    val player: Player,
    val view: PlaybackView,
    val captions: PlayerCaptions?,
    val segments: SegmentLookup?,
    val live: Boolean,
    val onChooseCaptions: (CaptionTrack?) -> Unit,
    val chat: ChatSheetCalls?,
    val onOpenDetails: () -> Unit,
)

/** The top sheet of [stack] as a Material 3 bottom sheet over the watch page, its stages cross-fading. */
@Composable
internal fun PlayerSheets(stack: SheetStack, calls: SheetCalls) {
    StageSheet(stack.top, onDismiss = stack::closeAll, onBack = stack::back, stageKey = { it }) { sheet ->
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 20.dp)) {
            SheetKicker(icon = null, text = kickerOf(sheet, calls))
            Text(sheet.title, style = SheetTitle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
            SheetStage(sheet, stack, calls)
        }
    }
}

/**
 * [sheet]'s stage beside the sideways picture (`m6-player` 33): its name over its rows, ← back to
 * the sheet under it, ✕ or a swipe right closing them all; Back steps back a stage.
 */
@Composable
internal fun SheetBeside(sheet: PlayerSheet, stack: SheetStack, calls: SheetCalls) {
    BackHandler(enabled = stack.top == sheet, onBack = stack::back)
    val deep = stack.deep
    PanelFrame(
        sheet.title,
        onClose = stack::closeAll,
        beside = true,
        head = {
            if (deep) PanelIconButton(R.drawable.ic_back, "Back", stack::back, Modifier.offset(x = (-10).dp))
            PanelHead(sheet.title, kicker = kickerOf(sheet, calls))
        },
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp)) { SheetStage(sheet, stack, calls) }
    }
}

private fun kickerOf(sheet: PlayerSheet, calls: SheetCalls): String = when (sheet) {
    PlayerSheet.Playback -> "Playing now"
    PlayerSheet.Chat -> calls.chat?.kicker.orEmpty()
    PlayerSheet.Quality, PlayerSheet.Speed, PlayerSheet.Captions -> "Playback"
}

private val PlayerSheet.title: String
    get() = when (this) {
        PlayerSheet.Playback -> "Playback"
        PlayerSheet.Quality -> "Quality"
        PlayerSheet.Speed -> "Speed"
        PlayerSheet.Captions -> "Captions"
        PlayerSheet.Chat -> "Chat"
    }

/** [sheet]'s rows: a pick closes every sheet (Chat's Show switch excepted: it reloads the list). */
@Composable
private fun ColumnScope.SheetStage(sheet: PlayerSheet, stack: SheetStack, calls: SheetCalls) {
    val view = calls.view
    val nowLines = pictureLines(view.videoSize.width, view.videoSize.height)
    val speedPercent = (view.speed * 100).roundToInt()
    when (sheet) {
        PlayerSheet.Playback -> PlaybackSheet(view.quality.held, nowLines, speedPercent.takeUnless { calls.live }, calls.captions, calls.segments, onOpen = stack::open) {
            stack.closeAll()
            calls.onOpenDetails()
        }
        PlayerSheet.Quality -> QualitySheet(view.quality, nowLines) { option ->
            calls.player.holdQuality(option)
            stack.closeAll()
        }
        PlayerSheet.Speed -> SpeedSheet(speedPercent) { percent ->
            calls.player.setPlaybackSpeed(percent / 100f)
            stack.closeAll()
        }
        PlayerSheet.Captions -> calls.captions?.let {
            CaptionsSheet(it) { track ->
                calls.onChooseCaptions(track)
                stack.closeAll()
            }
        }
        PlayerSheet.Chat -> calls.chat?.let { ChatSheet(it, onDone = stack::closeAll) }
    }
}

/**
 * Playback (`m6-player` 13): Quality (Auto and the height playing, or the height held), Speed
 * (none without a [speedPercent]), Captions (dimmed when the video has none), what SponsorBlock
 * found (a line with no action), and Video details.
 */
@Composable
private fun ColumnScope.PlaybackSheet(
    held: QualityOption?,
    nowLines: Int?,
    speedPercent: Int?,
    captions: PlayerCaptions?,
    segments: SegmentLookup?,
    onOpen: (PlayerSheet) -> Unit,
    onOpenDetails: () -> Unit,
) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        SheetRow(
            "Quality",
            tap { onOpen(PlayerSheet.Quality) },
            hint = qualityLine(held, nowLines),
            icon = R.drawable.ic_hq,
            chevron = true,
        )
        if (speedPercent != null) {
            SheetRow("Speed", tap { onOpen(PlayerSheet.Speed) }, hint = speedLabel(speedPercent), icon = R.drawable.ic_speed, chevron = true)
        }
        val none = captions == null || captions.tracks.isEmpty()
        val chosen = captions?.chosen
        SheetRow(
            "Captions",
            if (none) Modifier else tap { onOpen(PlayerSheet.Captions) },
            hint = when {
                none -> "None for this video"
                chosen != null -> chosen.name
                else -> captions?.missingLanguage?.let(::missingCaptionsText) ?: "Off"
            },
            icon = R.drawable.ic_cc,
            chevron = !none,
            dimmed = none,
        )
        SheetRow("SponsorBlock", Modifier, hint = sponsorBlockText(segments), icon = R.drawable.ic_shield)
        SheetRow("Video details", tap(onOpenDetails), icon = R.drawable.ic_info, chevron = true)
    }
}

/** The Quality row's line: "Auto · now 2160p" (the picture's lines once known), else the held height. */
private fun qualityLine(held: QualityOption?, nowLines: Int?): String = when {
    held != null -> "${held.lines}p · held for this video"
    nowLines != null -> "Auto · now ${nowLines}p"
    else -> "Auto"
}

/**
 * Quality (`m6-player` 14): Auto first (the height playing beside it while nothing is held), then
 * each height the video has up to the phone's cap; a pick ([onPick], `null` for Auto) holds for
 * this video only.
 */
@Composable
private fun ColumnScope.QualitySheet(quality: VideoQuality, nowLines: Int?, onPick: (QualityOption?) -> Unit) {
    val held = quality.held
    SheetSub(QUALITY_SHEET_SUB)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
        Choice("Auto", on = held == null, hint = autoQualityHint(held, nowLines)) { onPick(null) }
        quality.options.forEach { option ->
            Choice("${option.lines}p", on = option == held, hint = heightName(option.lines)) { onPick(option) }
        }
    }
}

/** Speed (`m6-player` 15): the seven speeds, the one playing marked; a pick holds for this video. */
@Composable
private fun ColumnScope.SpeedSheet(chosen: Int, onPick: (Int) -> Unit) {
    SheetSub(SPEED_SHEET_SUB)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
        SPEED_PERCENTS.forEach { percent -> Choice(speedLabel(percent), on = percent == chosen) { onPick(percent) } }
    }
}

/** Captions (`m6-player` 16): Off, then the video's tracks by YouTube's names, its own marked; a
 *  pick ([onPick]) is for this video only, the profile's default unchanged. */
@Composable
private fun ColumnScope.CaptionsSheet(captions: PlayerCaptions, onPick: (CaptionTrack?) -> Unit) {
    val chosen = captions.chosen
    SheetSub(captionsSheetSub(captions))
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
        Choice("Off", on = chosen == null) { onPick(null) }
        captions.tracks.forEach { track ->
            Choice(track.name, on = track == chosen, hint = AUTO_CAPTIONS_HINT.takeIf { track.autoGenerated }) { onPick(track) }
        }
    }
}

private val ChosenFill = AynColors.Ink.copy(alpha = 0.08f)

/** A radio row (`.mrow.choice`), lit while [on]. */
@Composable
private fun Choice(label: String, on: Boolean, hint: String? = null, onClick: () -> Unit) {
    SheetRow(
        label,
        (if (on) Modifier.background(ChosenFill) else Modifier).selectable(on, role = Role.RadioButton, onClick = onClick),
        hint = hint,
        lead = { RowRadio(on) },
    )
}
