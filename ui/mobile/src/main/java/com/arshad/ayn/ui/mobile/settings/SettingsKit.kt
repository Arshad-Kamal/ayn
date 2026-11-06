package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.PageBar
import com.arshad.ayn.ui.mobile.common.rememberScrolled
import com.arshad.ayn.ui.mobile.menu.RowRadio
import com.arshad.ayn.ui.mobile.menu.RowSwitch
import com.arshad.ayn.ui.mobile.menu.SheetSub
import com.arshad.ayn.ui.mobile.menu.SheetTitle
import com.arshad.ayn.ui.mobile.profiles.ProfileTile
import com.arshad.ayn.ui.mobile.profiles.TileSize
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan

// `m6-settings` (px = dp): rows 20 dp in from the sides, 14 dp tall padding, at least 56 dp; a
// dimmed group (another profile's view of This phone, SponsorBlock off) shows at 38 %.
private val RowSide = 20.dp
private val ValueWidth = 150.dp
internal const val DIMMED_ALPHA = 0.38f

/** A profile's monogram beside a page's scope line (`.tile.xs`). */
internal val ScopeTile = TileSize(26.dp, 8.dp, 15.sp)

/**
 * A Settings page (`.setp`): the bar with back and [title] (a hairline once the rows scroll under
 * it) over the page's [rows].
 */
@Composable
internal fun SettingsFrame(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, rows: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    Column(modifier.fillMaxSize().background(AynColors.Ground)) {
        PageBar(title = title, solid = true, onBack = onBack, lifted = rememberScrolled(list))
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp), content = rows)
    }
}

/** A page's opening line (`.lede`), beside [profile]'s tile when it is about one. */
@Composable
internal fun PageLede(text: String, profile: Profile? = null) {
    Row(Modifier.fillMaxWidth().padding(start = RowSide, end = RowSide, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        if (profile != null) ProfileTile(profile.name, profile.tint, ScopeTile, Modifier.padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = AynColors.Ink2)
    }
}

/**
 * A Settings row (`.srow`): [lead] (an icon, a swatch, the Ayn mark), [label] (and [labelTrail], a
 * tag) over its [hint], then [value] (at most [valueWidth] wide) and a chevron when it opens
 * something ([chevronIcon]: another mark, e.g. a page the browser opens). Without [onClick] it is text, read out as one line; not [enabled], it takes no taps
 * (its group dims it).
 */
@Composable
internal fun SettingRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    value: String? = null,
    chevron: Boolean = true,
    enabled: Boolean = true,
    lead: (@Composable () -> Unit)? = null,
    labelTrail: (@Composable () -> Unit)? = null,
    valueWidth: Dp = ValueWidth,
    @DrawableRes chevronIcon: Int = R.drawable.ic_chevron,
) {
    val tap = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {}
    RowFrame(modifier.then(tap), label, hint, lead, labelTrail) {
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = AynColors.Ink2,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueWidth),
            )
        }
        if (chevron) {
            Icon(painterResource(chevronIcon), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.offset(x = (-4).dp).size(18.dp))
        }
    }
}

/** A row that is text only (`.srow.text`): a credit, nothing to tap. */
@Composable
internal fun TextRow(label: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = RowSide, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A Settings switch (`.srow` with `.sw`), after [lead] when it has one: the whole row toggles it;
 *  the switch slides (M3). */
@Composable
internal fun SwitchRow(
    label: String,
    on: Boolean,
    onSwitch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    lead: (@Composable () -> Unit)? = null,
) {
    RowFrame(modifier.toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onSwitch), label, hint, lead = lead, labelTrail = null) {
        RowSwitch(on)
    }
}

@Composable
private fun RowFrame(
    modifier: Modifier,
    label: String,
    hint: String?,
    lead: (@Composable () -> Unit)?,
    labelTrail: (@Composable () -> Unit)?,
    trail: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = RowSide, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                labelTrail?.invoke()
            }
            // A switch's line says what its state does; the new line fades in.
            Crossfade(hint, animationSpec = AynMotion.base(), label = "hint") { shown ->
                if (shown != null) Text(shown, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 3.dp))
            }
        }
        trail()
    }
}

/** A category's icon at the head of its row (`.ic.lead`). */
@Composable
internal fun RowIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
}

/** A SponsorBlock category's colour (`.swatch`). */
@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier, size: Int = 14) {
    Box(modifier.size(size.dp).background(color, RoundedCornerShape((size * 0.28f).dp)))
}

/** The scope over a profile's rows (`.scope`): its tile and "Arshad's profile", and when every
 *  profile keeps its own ([eachOwn]) a quiet " · each profile sets its own". */
@Composable
internal fun ProfileScope(profile: Profile, eachOwn: Boolean = false) {
    Scope(lead = { ProfileTile(profile.name, profile.tint, ScopeTile) }) {
        buildAnnotatedString {
            append(profileScope(profile.name))
            if (eachOwn) withStyle(weightSpan(520, AynColors.Ink3)) { append(EACH_PROFILE) }
        }
    }
}

/** A scope that names [profile] in its own words (`.scope`): "Set by Arshad, the owner". */
@Composable
internal fun ScopeLine(profile: Profile, text: String) {
    Scope(lead = { ProfileTile(profile.name, profile.tint, ScopeTile) }) { AnnotatedString(text) }
}

/** A small label after a row's name (`.tag`): "Owner only". */
@Composable
internal fun SettingsTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.atWeight(680).copy(fontSize = 11.sp),
        color = AynColors.Ink2,
        modifier = Modifier.background(AynColors.Tonal, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** A warning over a page's rows (`.notice`): the alert, then [lead] in ink and [text] after it. */
@Composable
internal fun SettingsNotice(lead: String, text: String) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().background(AynColors.Pool, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AynColors.Warn, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(
            buildAnnotatedString {
                withStyle(weightSpan(650, AynColors.Ink)) { append(lead) }
                if (text.isNotEmpty()) append(" $text")
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
            color = AynColors.Ink2,
        )
    }
}

/** The scope over This phone's rows (`.scope` with `.splate.ph`). */
@Composable
internal fun PhoneScope() {
    Scope(lead = {
        Box(Modifier.size(26.dp).background(AynColors.Surface3, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_phone), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(16.dp))
        }
    }) { buildAnnotatedString { append(THIS_PHONE) } }
}

@Composable
private fun Scope(lead: @Composable () -> Unit, text: () -> AnnotatedString) {
    Row(
        Modifier.fillMaxWidth().padding(start = RowSide, end = RowSide, top = 14.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        lead()
        Text(text(), style = MaterialTheme.typography.labelMedium.atWeight(680).copy(fontSize = 13.sp), color = AynColors.Ink2)
    }
}

/** Who may change the rows under it, for anyone but the owner (`.oline`). */
@Composable
internal fun OwnerOnlyLine(text: String) {
    Row(
        Modifier.padding(horizontal = RowSide, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2)
    }
}

/** Rows shown at [DIMMED_ALPHA] while not [lit] (the change fades, 190 ms). */
@Composable
internal fun Dimmed(lit: Boolean, modifier: Modifier = Modifier, rows: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (lit) 1f else DIMMED_ALPHA, AynMotion.base(), label = "dimmed")
    Column(modifier.graphicsLayer { this.alpha = alpha }) { rows() }
}

/** The hairline between a page's groups (`.sgroup + .sgroup`). */
@Composable
internal fun GroupLine() {
    Box(Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth().height(1.dp).background(AynColors.Line))
}

/** A heading inside a page (`.ssh`): "Categories", "Community data". */
@Composable
internal fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.atWeight(680).copy(fontSize = 13.sp, letterSpacing = 0.26.sp),
        color = AynColors.Ink3,
        modifier = Modifier.padding(start = RowSide, end = RowSide, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A page's foot (`.sfoot`): a hairline, then a quiet line. */
@Composable
internal fun PageFoot(text: String) {
    Column(Modifier.padding(start = RowSide, end = RowSide, top = 18.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AynColors.Line))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 14.dp))
    }
}

/** A Settings sheet's title (`.stl`), read out as a heading. */
@Composable
internal fun SettingsSheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SheetTitle, modifier = modifier.semantics { heading() })
}

/** A sheet's small line over its title (`.skick` on the Settings sheets: quieter than the menu's). */
@Composable
internal fun SettingsSheetKicker(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** A heading between a sheet's choices (`.chd`): "Gaelic". */
@Composable
internal fun ChoiceHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.atWeight(680).copy(fontSize = 12.5.sp),
        color = AynColors.Ink3,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

private val ChoiceShape = RoundedCornerShape(16.dp)

/** One of a sheet's choices (`.mrow.choice`): a radio, [label] over its [hint]; the chosen one
 *  rests on a lit fill (it fades, 190 ms). Not [enabled], it takes no taps (its caller dims it). */
@Composable
internal fun ChoiceRow(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    lead: (@Composable () -> Unit)? = null,
) {
    val fill by animateColorAsState(if (on) AynColors.RowLit else Color.Transparent, AynMotion.base(), label = "choice")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(fill, ChoiceShape)
            .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (lead != null) lead() else RowRadio(on)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp))
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** One choice of a set as chips (`.chips2`), wrapping; [chosen] is lit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> PickChips(choices: List<T>, chosen: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice -> PickChip(label(choice), choice == chosen) { onPick(choice) } }
    }
}

private val ChipShape = RoundedCornerShape(12.dp)

/** A chip choice (`.chips2 span`): outlined, or lit with a tick when chosen (the change fades). */
@Composable
private fun PickChip(label: String, on: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) AynColors.Indicator else Color.Transparent, AynMotion.base(), label = "chip")
    val outline by animateColorAsState(if (on) Color.Transparent else AynColors.Line, AynMotion.base(), label = "chipLine")
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(38.dp)
            .clip(ChipShape)
            .background(fill)
            .border(1.5.dp, outline, ChipShape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) AynColors.Ink else AynColors.Ink2)
    }
}

/** A Settings sheet (`.msheet`): its kicker, title and line (when it has one), then its [rows] (a
 *  group of choices where [choices] says so) and its [note], scrolling when long. */
@Composable
internal fun SheetColumn(
    kicker: String,
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    choices: Boolean = true,
    note: String? = null,
    rows: @Composable () -> Unit,
) {
    Column(modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
        SettingsSheetHead(kicker, title, sub)
        Column(if (choices) Modifier.selectableGroup() else Modifier) { rows() }
        if (note != null) SettingsSheetNote(note)
        SheetFoot()
    }
}

/** A Settings sheet's kicker, title and line (when it has one). */
@Composable
internal fun SettingsSheetHead(kicker: String, title: String, sub: String?) {
    SettingsSheetKicker(kicker)
    SettingsSheetTitle(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = if (sub != null) 4.dp else 10.dp))
    if (sub != null) SheetSub(sub)
}

/** A quiet line at a Settings sheet's foot (`.snote2`). */
@Composable
internal fun SettingsSheetNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp), color = AynColors.Ink3, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
}

/** Space at a sheet's foot, over the gesture bar. */
@Composable
internal fun SheetFoot() {
    Spacer(Modifier.height(20.dp))
}
