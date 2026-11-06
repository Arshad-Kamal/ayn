package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.filter.packs.IslamicGroup
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.FILTER_PACKS
import com.arshad.ayn.feature.settings.FilterPack
import com.arshad.ayn.feature.settings.NOT_NOW
import com.arshad.ayn.feature.settings.PackHeading
import com.arshad.ayn.feature.settings.PackLine
import com.arshad.ayn.feature.settings.PackOption
import com.arshad.ayn.feature.settings.PackSheetCopy
import com.arshad.ayn.feature.settings.SUGGESTIONS_KICKER
import com.arshad.ayn.feature.settings.SUGGESTIONS_SUB
import com.arshad.ayn.feature.settings.SUGGESTIONS_TITLE
import com.arshad.ayn.feature.settings.Suggestion
import com.arshad.ayn.feature.settings.SuggestionOffer
import com.arshad.ayn.feature.settings.USE_PACK
import com.arshad.ayn.feature.settings.enabledIslamicChannels
import com.arshad.ayn.feature.settings.heading
import com.arshad.ayn.feature.settings.hint
import com.arshad.ayn.feature.settings.isOn
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.packValue
import com.arshad.ayn.feature.settings.suggestionHint
import com.arshad.ayn.feature.settings.turnOnLabel
import com.arshad.ayn.feature.settings.usePackHint
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.menu.RowCheck
import com.arshad.ayn.ui.mobile.menu.RowSwitch
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan

/**
 * The Filter packs group (`m6-settings` P1, P6, P9, P10): a row per pack, its value On or Off
 * ([kids] picks the Islamic channel defaults it counts) and [hint]; a tap opens its sheet
 * ([onOpen]). Without [onOpen] the rows are text (the owner's, dimmed by their group).
 */
@Composable
internal fun PackRows(packs: FilterPacks, kids: Boolean, onOpen: ((FilterPack) -> Unit)?, hint: (FilterPack) -> String = { it.hint }) {
    Column {
        SubHeading(FILTER_PACKS)
        FilterPack.entries.forEach { pack ->
            SettingRow(
                pack.label,
                onOpen?.let { open -> { open(pack) } },
                hint = hint(pack),
                value = packValue(packs, pack, kids),
                chevron = onOpen != null,
            )
        }
    }
}

/** What a pack's sheet changes: the pack itself, its options, Islamic content only's channels. */
internal class PackActions(
    val onUse: (FilterPack, Boolean) -> Unit,
    val onSwitchOption: (PackOption, Boolean) -> Unit,
    val onSwitchChannel: (String, Boolean) -> Unit,
)

/**
 * A pack's sheet (`m6-settings` P2, P4, P5, P7, P8): Use this pack, how approvals meet it on a
 * kids profile's restrictions, then what the pack always hides or keeps and its options, or
 * Islamic content only's channels by group, each with its switch. [packs] are the setter's
 * choices: the session profile's own ([target] `null`) or the owner's on [target]; [kids] picks
 * the channel defaults.
 */
@Composable
internal fun PackSheet(kicker: String, pack: FilterPack, packs: FilterPacks, target: Profile?, kids: Boolean, actions: PackActions) {
    val copy = remember(pack, target) { PackSheetCopy(pack, target) }
    val use: @Composable () -> Unit = {
        SwitchSheetRow(USE_PACK, packs.isOn(pack), { actions.onUse(pack, it) }, hint = usePackHint(pack, packs))
        copy.approvals?.let { ApprovalsNote(it) }
    }
    if (pack == FilterPack.IslamicOnly) {
        IslamicSheet(kicker, copy, packs.enabledIslamicChannels(kids), actions.onSwitchChannel, use)
        return
    }
    SheetColumn(kicker, copy.title, copy.sub, choices = false, note = copy.note) {
        use()
        copy.lists.forEach { list ->
            GroupHeading(list.heading)
            PackLines(list.lines)
        }
        copy.optionsHeading?.let { GroupHeading(it) }
        PackOption.entries.filter { it.pack == pack }.forEach { option ->
            SwitchSheetRow(option.label, packs.isOn(option), { actions.onSwitchOption(option, it) }, hint = option.hint)
        }
    }
}

private val ChannelsByGroup = IslamicGroup.entries.associateWith { group -> IslamicOnly.channels.filter { it.group == group } }

/** Islamic content only's sheet: [use], then the listed channels by group, each [enabled] or not,
 *  composed as they scroll into view (41 rows). */
@Composable
private fun IslamicSheet(kicker: String, copy: PackSheetCopy, enabled: Set<String>, onSwitchChannel: (String, Boolean) -> Unit, use: @Composable () -> Unit) {
    LazyColumn(Modifier.padding(horizontal = 8.dp)) {
        item(key = "head", contentType = "head") {
            Column {
                SettingsSheetHead(kicker, copy.title, copy.sub)
                use()
            }
        }
        ChannelsByGroup.forEach { (group, channels) ->
            item(key = group.name, contentType = "heading") { GroupHeading(group.heading) }
            items(channels, key = { it.id }, contentType = { "channel" }) { channel ->
                SwitchSheetRow(
                    channel.name,
                    channel.id in enabled,
                    { onSwitchChannel(channel.id, it) },
                    hint = channel.note,
                    lead = { Box(Modifier.size(40.dp)) { ChannelAvatar(channel.name, url = null, monogramSize = 16f) } },
                )
            }
        }
        item(key = "note", contentType = "note") {
            Column {
                SettingsSheetNote(copy.note)
                SheetFoot()
            }
        }
    }
}

/** An on/off row in a sheet (`.mrow` with `.sw`): the whole row flips its switch. */
@Composable
private fun SwitchSheetRow(label: String, on: Boolean, onSwitch: (Boolean) -> Unit, hint: String? = null, lead: (@Composable () -> Unit)? = null) {
    SheetRow(
        label,
        Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onSwitch),
        hint = hint,
        lead = lead,
        trail = { RowSwitch(on) },
    )
}

/** A heading in a pack's sheet (`.mgrp`), its sub after it in a lighter weight. */
@Composable
private fun GroupHeading(heading: PackHeading) {
    Text(
        buildAnnotatedString {
            append(heading.text)
            heading.sub?.let { sub -> withStyle(weightSpan(500)) { append(" · $sub") } }
        },
        style = MaterialTheme.typography.labelSmall.atWeight(680).copy(fontSize = 12.5.sp, letterSpacing = 0.25.sp),
        color = AynColors.Ink3,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
    )
}

// `.mhides`: lines 16 dp in, a 6 dp dot 2 dp from the edge and 10 dp down.
private val LineIndent = 16.dp
private val DotRadius = 3.dp

/** What a pack always hides or keeps (`.mhides`): a dotted line each, its lead in bold. */
@Composable
private fun PackLines(lines: List<PackLine>) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
        lines.forEach { line ->
            Text(
                line.annotated(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.5.sp),
                color = AynColors.Ink2,
                modifier = Modifier
                    .drawBehind { drawCircle(AynColors.Ink3, DotRadius.toPx(), Offset(2.dp.toPx() + DotRadius.toPx(), 10.dp.toPx())) }
                    .padding(start = LineIndent, top = 3.dp, bottom = 3.dp),
            )
        }
    }
}

/** How approvals meet the pack on a kids profile (`.mpacknote`): an info mark, then [line]. */
@Composable
private fun ApprovalsNote(line: PackLine) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(line.annotated(), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.5.sp), color = AynColors.Ink2)
    }
}

private fun PackLine.annotated() = buildAnnotatedString {
    lead?.let {
        withStyle(weightSpan(650, AynColors.Ink)) { append(it) }
        append(' ')
    }
    append(text)
}

/**
 * Also turn these on? (`m6-settings` P3; FPK-D7), right after Harmful for kids goes on: each
 * [offer]ed setting with its tick, the foot [note], then Not now and Turn on N (only with one
 * ticked). [restriction] when the owner sets them on another profile.
 */
@Composable
internal fun SuggestionsSheet(
    offer: SuggestionOffer,
    restriction: Boolean,
    note: String,
    onTick: (Suggestion, Boolean) -> Unit,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetHead(SUGGESTIONS_KICKER, SUGGESTIONS_TITLE, SUGGESTIONS_SUB)
        offer.offered.forEach { suggestion ->
            val ticked = suggestion in offer.ticked
            SheetRow(
                suggestion.label,
                Modifier.toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onTick(suggestion, it) }),
                hint = suggestionHint(suggestion, restriction),
                lead = { RowCheck(ticked) },
            )
        }
        SettingsSheetNote(note)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            PillButton(NOT_NOW, onNotNow, Modifier.height(48.dp), kind = ButtonKind.Tonal)
            PillButton(turnOnLabel(offer.ticked.size), onTurnOn, Modifier.height(48.dp), enabled = offer.ticked.isNotEmpty())
        }
        SheetFoot()
    }
}
