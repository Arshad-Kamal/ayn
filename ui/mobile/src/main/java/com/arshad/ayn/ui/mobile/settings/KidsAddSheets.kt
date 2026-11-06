package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.kids.clockText
import com.arshad.ayn.feature.kids.dayLimitText
import com.arshad.ayn.feature.kids.long
import com.arshad.ayn.feature.settings.FindChannel
import com.arshad.ayn.feature.settings.FindResults
import com.arshad.ayn.feature.settings.KidDraft
import com.arshad.ayn.feature.settings.KidsAddViewModel
import com.arshad.ayn.feature.settings.TimeSheet
import com.arshad.ayn.feature.settings.bedtimeChoices
import com.arshad.ayn.feature.settings.dayLimitChoices
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.theme.AynColors
import java.time.DayOfWeek

/**
 * Find a channel (`m6-kids` 25): the name typed with the keyboard up, whose search key asks
 * YouTube; the channels it finds are rows, approved ones marked, and a tap approves one and closes
 * the sheet. Searching, no match and offline say so in the sheet.
 */
@Composable
internal fun FindChannelSheet(find: FindChannel, draft: KidDraft, kidsAdd: KidsAddViewModel) {
    val results = find.results
    SheetColumn(kicker = "Approve for ${draft.name.trim()}", title = "Find a channel", sub = null, modifier = Modifier.imePadding(), choices = false) {
        FindField(find.query, searching = results == FindResults.Searching, kidsAdd)
        when (results) {
            FindResults.Idle -> Unit
            FindResults.Searching -> FindLine("Searching YouTube…")
            is FindResults.Found -> {
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { ChoiceHeading("Channels for “${results.query}”") }
                results.channels.forEach { channel -> FoundChannelRow(channel, draft.approves(channel.url)) { kidsAdd.onFindPick(channel) } }
            }
            is FindResults.NoMatch -> FindLine("No channel matches “${results.query}”. Try its name as YouTube shows it.")
            FindResults.Failed -> FindLine("Ayn couldn’t reach YouTube. Check the connection, then tap the search key again.")
        }
    }
}

@Composable
private fun FindField(query: String, searching: Boolean, kidsAdd: KidsAddViewModel) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    TextField(
        value = query,
        onValueChange = kidsAdd::onFindQueryChange,
        label = { Text("Channel name") },
        singleLine = true,
        readOnly = searching,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { kidsAdd.onFindSearch() }),
        colors = aynFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).focusRequester(field),
    )
}

/** What a search is doing, or why it found nothing; read out as it changes. */
@Composable
private fun FindLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = AynColors.Ink2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A channel the search found: its picture, name and subscribers, "Approved" when it is. */
@Composable
private fun FoundChannelRow(channel: ChannelItem, approved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp)) { ChannelAvatar(channel.name, channel.avatarUrl, monogramSize = 14f) }
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium)
            subscribersText(channel.subscriberCount)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (approved) Text("Approved", style = MaterialTheme.typography.labelMedium, color = AynColors.Ink2)
    }
}

/**
 * A day's limit from [limits] (`m6-kids` 19) or a bound of [bedtime] for [name]: the choices as
 * chips, the current one lit; a pick goes to [onDayLimit] or [onBedtime]. [dayNote] is under a
 * day's choices.
 */
@Composable
internal fun TimeSheetContent(
    sheet: TimeSheet,
    name: String,
    limits: Map<DayOfWeek, Int>,
    bedtime: Bedtime,
    onDayLimit: (Int?) -> Unit,
    onBedtime: (Int) -> Unit,
    dayNote: String = DAY_STEPS_NOTE,
) {
    when (sheet) {
        is TimeSheet.DayLimit -> {
            val day = sheet.day.long()
            SheetColumn(kicker = "Daily limit", title = day, sub = "How long $name can watch on ${day}s.", choices = false, note = dayNote) {
                PickChips(dayLimitChoices(), limits[sheet.day], ::dayLimitText, onDayLimit)
            }
        }
        TimeSheet.BedtimeStart, TimeSheet.BedtimeEnd -> {
            val starts = sheet == TimeSheet.BedtimeStart
            val current = if (starts) bedtime.startMinute else bedtime.endMinute
            val other = if (starts) bedtime.endMinute else bedtime.startMinute
            SheetColumn(
                kicker = "Bedtime",
                title = if (starts) "Starts" else "Ends",
                sub = "Ayn doesn’t open for $name from ${clockText(bedtime.startMinute)} to ${clockText(bedtime.endMinute)}.",
                choices = false,
            ) {
                PickChips(bedtimeChoices(other), current, ::clockText, onBedtime)
            }
        }
    }
}

/** Under a day's limit choices. */
internal const val DAY_STEPS_NOTE = "15-minute steps up to 4 h."
