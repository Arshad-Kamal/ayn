package com.arshad.ayn.ui.mobile.kids

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.kids.ParentSheet
import com.arshad.ayn.feature.kids.ParentUiState
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.kids.bedtimeSub
import com.arshad.ayn.feature.kids.clockText
import com.arshad.ayn.feature.kids.dayLimitText
import com.arshad.ayn.feature.kids.long
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.settings.DAY_STEPS_NOTE
import com.arshad.ayn.ui.mobile.settings.RemoveRow
import com.arshad.ayn.ui.mobile.settings.RowIcon
import com.arshad.ayn.ui.mobile.settings.SettingRow
import com.arshad.ayn.ui.mobile.settings.SheetColumn
import com.arshad.ayn.ui.mobile.settings.SwitchRow
import com.arshad.ayn.ui.mobile.settings.TimeSheetContent
import com.arshad.ayn.ui.mobile.settings.rememberRuleDates
import com.arshad.ayn.ui.mobile.shell.ShellTime
import java.time.DayOfWeek

/** Under a day's choices from the Parent page (state 19): unlike adding a profile, today is under way. */
private const val PARENT_DAY_NOTE = "$DAY_STEPS_NOTE A change counts from today."

/**
 * The sheets over the Parent page (`m6-kids` 17–20): the approved channels and videos with Remove,
 * a row per weekday's limit, bedtime, and a day's or bound's choices. A swipe closes the sheet;
 * Back from a day's or bound's choices returns to its list.
 */
@Composable
internal fun ParentSheets(sheet: ParentSheet?, state: ParentUiState, parent: ParentViewModel, time: ShellTime) {
    StageSheet(sheet, onDismiss = parent::onDismiss, onBack = { parent.onBack() }, stageKey = { it }) { shown ->
        val kid = state.kid ?: return@StageSheet
        val restrictions = state.rules?.restrictions ?: return@StageSheet
        val kicker = "Parent · ${kid.name}"
        when (shown) {
            ParentSheet.ApprovedChannels -> ApprovedChannels(kicker, kid, state, parent, time)
            ParentSheet.ApprovedVideos -> ApprovedVideos(kicker, kid, state, parent, time)
            ParentSheet.DailyLimits -> DailyLimits(kicker, kid, restrictions.dailyLimitMinutes, parent)
            ParentSheet.Bedtime -> BedtimeSheet(kicker, kid, restrictions.bedtime, state.bedtimeOff, parent)
            is ParentSheet.Time -> TimeSheetContent(
                sheet = shown.sheet,
                name = kid.name,
                limits = restrictions.dailyLimitMinutes,
                bedtime = restrictions.bedtime ?: state.bedtimeOff,
                onDayLimit = parent::onDayLimitPicked,
                onBedtime = parent::onBedtimePicked,
                dayNote = PARENT_DAY_NOTE,
            )
        }
    }
}

/** The approved channels, most recently approved first (state 17); Remove takes one away at once. */
@Composable
private fun ApprovedChannels(kicker: String, kid: Profile, state: ParentUiState, parent: ParentViewModel, time: ShellTime) {
    val dates = rememberRuleDates(time)
    SheetColumn(
        kicker,
        "Approved channels",
        "${kid.name} sees these channels’ videos, new ones too. Remove takes one away.",
        choices = false,
        note = "To approve a channel, open your profile and choose Approve for ${kid.name} on any of its videos.",
    ) {
        state.channels.forEach { channel ->
            RemoveRow(
                label = channel.name,
                hint = dates.approved(channel.subscribedAtMillis),
                action = "Remove",
                onClick = { parent.onRemoveChannel(channel.channelUrl) },
                icon = R.drawable.ic_trash,
                lead = { Box(Modifier.size(40.dp)) { ChannelAvatar(channel.name, channel.avatarUrl, monogramSize = 14f) } },
            )
        }
    }
}

/** The approved single videos, newest first, read as the channels are (state 17's caption). */
@Composable
private fun ApprovedVideos(kicker: String, kid: Profile, state: ParentUiState, parent: ParentViewModel, time: ShellTime) {
    val dates = rememberRuleDates(time)
    SheetColumn(
        kicker,
        "Approved videos",
        "${kid.name} can watch these without their channels approved. Remove takes one away.",
        choices = false,
        note = "To approve a video, open your profile and choose Approve for ${kid.name} on it.",
    ) {
        state.videos.forEach { listed ->
            RemoveRow(
                label = listed.rule.title,
                hint = metaLine(listed.rule.channelName, dates.approved(listed.addedAtMillis)),
                action = "Remove",
                onClick = { parent.onRemoveVideo(listed.rule) },
                icon = R.drawable.ic_trash,
            )
        }
    }
}

/** A row per weekday with its limit (state 18); a tap opens the day's choices. */
@Composable
private fun DailyLimits(kicker: String, kid: Profile, limits: Map<DayOfWeek, Int>, parent: ParentViewModel) {
    SheetColumn(
        kicker,
        "Daily limits",
        "Time counts while ${kid.name}’s profile is in use. Tap a day to change it in 15-minute steps.",
        choices = false,
    ) {
        DayOfWeek.entries.forEach { day ->
            SettingRow(
                label = day.long(),
                onClick = { parent.onDayLimit(day) },
                hint = dayLimitText(limits[day]),
                lead = { RowIcon(R.drawable.ic_later) },
            )
        }
    }
}

/** Bedtime on or off, and its start and end (state 20); a tap on a bound opens its choices. */
@Composable
private fun BedtimeSheet(kicker: String, kid: Profile, bedtime: Bedtime?, bedtimeOff: Bedtime, parent: ParentViewModel) {
    val shown = bedtime ?: bedtimeOff
    SheetColumn(kicker, "Bedtime", bedtimeSub(kid.name, bedtime), choices = false) {
        SwitchRow("Bedtime", on = bedtime != null, onSwitch = { parent.onBedtimeToggle() }, lead = { RowIcon(R.drawable.ic_moon) })
        SettingRow("Starts", onClick = parent::onBedtimeStart, hint = clockText(shown.startMinute), lead = { RowIcon(R.drawable.ic_later) })
        SettingRow("Ends", onClick = parent::onBedtimeEnd, hint = clockText(shown.endMinute), lead = { RowIcon(R.drawable.ic_later) })
    }
}
