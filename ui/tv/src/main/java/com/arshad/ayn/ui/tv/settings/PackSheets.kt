package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.weightSpan

/** A pack row's focus key, which its sheet returns focus to. */
internal fun packKey(pack: FilterPack): String = "pk-${pack.name.lowercase()}"

/**
 * The Filter packs group (`kp-filters` fpk-filters, fpk-zainab, fpk-mira): a row per pack, its
 * value On or Off ([kids] picks the Islamic channel defaults it counts) and [hint]; OK opens its
 * sheet ([onOpen]). Without [onOpen] the rows are text focus passes over (the owner's, dimmed).
 * [keyPrefix] keeps their focus keys apart from another set's.
 */
@Composable
internal fun PackRows(
    packs: FilterPacks,
    kids: Boolean,
    onOpen: ((FilterPack) -> Unit)?,
    keyPrefix: String = "",
    hint: (FilterPack) -> String = { it.hint },
) {
    val live = onOpen != null
    PaneSubHeading(FILTER_PACKS)
    FilterPack.entries.forEach { pack ->
        SettingRow(
            label = pack.label,
            hint = hint(pack),
            value = packValue(packs, pack, kids),
            chevron = live,
            focusable = live,
            onClick = { onOpen?.invoke(pack) },
            modifier = if (live) Modifier.focusMemory(keyPrefix + packKey(pack)) else Modifier,
        )
    }
}

/** What a pack's sheet changes: the pack itself, its options, Islamic content only's channels. */
internal class PackActions(
    val onUse: (FilterPack, Boolean) -> Unit,
    val onSwitchOption: (PackOption, Boolean) -> Unit,
    val onSwitchChannel: (String, Boolean) -> Unit,
)

private const val USE_KEY = "use"

/**
 * A pack's sheet (`kp-filters` fpk-harmful, fpk-halal, fpk-islamic, fpk-z-harmful,
 * fpk-z-islamic): Use this pack, focused as it opens, then what the pack always hides or keeps
 * and its options, or Islamic content only's channels by group, each with its switch. [packs]
 * are the setter's choices: the session profile's own ([target] `null`) or the owner's on
 * [target]; [kids] picks the channel defaults.
 */
@Composable
internal fun PackSheet(kicker: String, pack: FilterPack, packs: FilterPacks, target: Profile?, kids: Boolean, actions: PackActions) {
    val copy = remember(pack, target) { PackSheetCopy(pack, target) }
    val focus = rememberSheetFocus(USE_KEY)
    val use = @Composable {
        SheetSwitchRow(
            label = USE_PACK,
            on = packs.isOn(pack),
            onSwitch = { actions.onUse(pack, it) },
            modifier = focus.on(USE_KEY),
            hint = usePackHint(pack, packs),
        )
    }
    if (pack == FilterPack.IslamicOnly) {
        IslamicSheet(kicker, copy, packs.enabledIslamicChannels(kids), focus, actions.onSwitchChannel, use)
        return
    }
    SettingsSheet(kicker = kicker, title = { SheetTitle(copy.title) }, sub = copy.sub, note = copy.note, scroll = true) {
        use()
        copy.lists.forEach { list ->
            SheetHeading(list.heading)
            PackLines(list.lines)
        }
        copy.optionsHeading?.let { SheetHeading(it) }
        PackOption.entries.filter { it.pack == pack }.forEach { option ->
            SheetSwitchRow(
                label = option.label,
                on = packs.isOn(option),
                onSwitch = { actions.onSwitchOption(option, it) },
                modifier = focus.on(option.name),
                hint = option.hint,
            )
        }
        copy.approvals?.let { ApprovalsNote(it) }
    }
}

private val ChannelsByGroup = IslamicGroup.entries.associateWith { group -> IslamicOnly.channels.filter { it.group == group } }

/** Islamic content only's sheet: [use], then the listed channels by group, each [enabled] or not,
 *  composed as they scroll into view (41 rows). */
@Composable
private fun IslamicSheet(
    kicker: String,
    copy: PackSheetCopy,
    enabled: Set<String>,
    focus: SheetFocus,
    onSwitchChannel: (String, Boolean) -> Unit,
    use: @Composable () -> Unit,
) {
    SettingsListSheet(kicker = kicker, title = { SheetTitle(copy.title) }, sub = copy.sub, initialItem = 0, note = copy.note) {
        item(key = USE_KEY, contentType = "use") { use() }
        copy.approvals?.let { line -> item(key = "approvals", contentType = "note") { ApprovalsNote(line) } }
        ChannelsByGroup.forEach { (group, channels) ->
            item(key = group.name, contentType = "heading") { SheetHeading(group.heading) }
            items(channels, key = { it.id }, contentType = { "channel" }) { channel ->
                val on = channel.id in enabled
                SettingRow(
                    label = channel.name,
                    hint = channel.note,
                    labelMaxLines = 2,
                    minHeight = SheetRowMinHeight,
                    onClick = { onSwitchChannel(channel.id, !on) },
                    modifier = focus.on(channel.id),
                    lead = { ChannelMonogramLead(channel.name) },
                    control = { RowSwitch(on, it) },
                )
            }
        }
    }
}

/** An on/off row in a sheet: its switch at the row's end; OK flips it ([onSwitch] gets the new value). */
@Composable
private fun SheetSwitchRow(label: String, on: Boolean, onSwitch: (Boolean) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    SettingRow(
        label = label,
        hint = hint,
        minHeight = SheetRowMinHeight,
        onClick = { onSwitch(!on) },
        modifier = modifier,
        control = { RowSwitch(on, it) },
    )
}

@Composable
private fun SheetHeading(heading: PackHeading) {
    SheetGroupHeading(heading.text, heading.sub)
}

// `.sheet .hides` (px ÷ 2): lines 13 dp in from the text, a 4.5 dp dot 8 dp down.
private val LineIndent = 13.dp
private val DotRadius = 2.25.dp

/** What a pack always hides or keeps (`.sheet .hides`): a dotted line each, its lead in bold. */
@Composable
private fun PackLines(lines: List<PackLine>) {
    Column(Modifier.padding(start = RowInset, top = 2.dp, bottom = 3.dp)) {
        lines.forEach { line ->
            Text(
                text = line.annotated(),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.5.sp, lineHeight = 1.32.em),
                color = KeColors.Ink2,
                modifier = Modifier
                    .drawBehind { drawCircle(KeColors.Ink3, DotRadius.toPx(), Offset(DotRadius.toPx() + 1.dp.toPx(), 8.dp.toPx())) }
                    .padding(start = LineIndent, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** How approvals meet the pack on a kids profile (`.packnote`): an info mark, then [line]. */
@Composable
private fun ApprovalsNote(line: PackLine) {
    Row(Modifier.padding(start = 1.dp, top = 11.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.padding(top = 1.5.dp).size(14.dp))
        Text(
            text = line.annotated(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 1.38.em),
            color = KeColors.Ink2,
        )
    }
}

private fun PackLine.annotated() = buildAnnotatedString {
    lead?.let {
        withStyle(weightSpan(600, KeColors.Ink)) { append(it) }
        append(' ')
    }
    append(text)
}

private const val TURN_ON_KEY = "turn-on"
private const val NOT_NOW_KEY = "not-now"

/**
 * Also turn these on? (`kp-filters` fpk-suggest; FPK-D7), right after Harmful for kids goes on:
 * each [offer]ed setting with its tick, Turn on N (focused as it opens; only with one ticked) and
 * Not now. [restriction] when the owner sets them on another profile; [note] at the foot.
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
    val focus = rememberSheetFocus(TURN_ON_KEY)
    SettingsSheet(kicker = SUGGESTIONS_KICKER, title = { SheetTitle(SUGGESTIONS_TITLE) }, sub = SUGGESTIONS_SUB, note = note) {
        offer.offered.forEach { suggestion ->
            val ticked = suggestion in offer.ticked
            SettingRow(
                label = suggestion.label,
                hint = suggestionHint(suggestion, restriction),
                minHeight = SheetRowMinHeight,
                onClick = { onTick(suggestion, !ticked) },
                modifier = focus.on(suggestion.name),
                lead = { RowCheck(ticked, it) },
            )
        }
        SettingRow(
            label = turnOnLabel(offer.ticked.size),
            minHeight = SheetRowMinHeight,
            focusable = offer.ticked.isNotEmpty(),
            onClick = onTurnOn,
            modifier = Modifier.padding(top = 9.dp).then(focus.on(TURN_ON_KEY)),
            lead = { RowIcon(R.drawable.ic_check, it) },
        )
        SettingRow(label = NOT_NOW, minHeight = SheetRowMinHeight, onClick = onNotNow, modifier = focus.on(NOT_NOW_KEY))
    }
}
