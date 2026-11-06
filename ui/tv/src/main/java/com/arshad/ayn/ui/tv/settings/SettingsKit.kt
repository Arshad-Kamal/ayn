package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.arshad.ayn.ui.tv.common.PrivateTextInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.bottomFade
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.fadeTint
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.profiles.PlateSize
import com.arshad.ayn.ui.tv.profiles.ProfilePlate
import com.arshad.ayn.ui.tv.profiles.rememberInitialFocus
import com.arshad.ayn.ui.tv.profiles.color
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// `.srow` (px ÷ 2): at least 46 dp tall (42 in a sheet), 15 dp in from its ends, 10 dp corners.
internal val RowMinHeight = 46.dp
internal val SheetRowMinHeight = 42.dp
internal val RowInset = 15.dp

/** `.srow.nest`: a profile to tick sits this far in, under Choose profiles. */
internal val NestInset = 28.dp
private val RowShape = RoundedCornerShape(10.dp)
private val RowGap = 11.dp
private val RowIconSize = 16.dp
private val ChevronSize = 13.dp

/** A value longer than this ends in an ellipsis, so the label keeps its room (a playlist's name);
 *  a row whose values are known may allow more. */
private val ValueMaxWidth = 150.dp

/** A row's plate (`.pav.sm`) and a sheet title's (`.pav.xs`). */
internal val RowPlate = PlateSize(26.dp, 7.dp, 15.5.sp, 1.dp)
internal val SheetTitlePlate = PlateSize(22.dp, 6.dp, 13.5.sp, 1.dp)

/**
 * A row's secondary tones while focus fills it: [soft] for a value or a lead icon (ink 72% at
 * rest), [faint] for a hint (ink 52%); both turn to the fill's ink at 72% (80% on danger red).
 * Read them, and [focus], when drawing: a focus fade then redraws the row without recomposing it.
 */
internal class RowTones(private val progress: State<Float>, danger: Boolean) {
    private val focused = if (danger) KeColors.OnDanger.copy(alpha = 0.8f) else KeColors.OnInk.copy(alpha = 0.72f)
    val focus: Float get() = progress.value
    val soft: ColorProducer = fadeTint(KeColors.Ink2, focused, progress)
    val faint: ColorProducer = fadeTint(KeColors.Ink3, focused, progress)

    /** [soft] at 60% of its alpha (a row's chevron). */
    val softer = ColorProducer { soft().let { it.copy(alpha = it.alpha * 0.6f) } }

    /** From [rest] at rest to [focused] as focus fills the row. */
    fun fade(rest: Color, focused: Color): ColorProducer = fadeTint(rest, focused, progress)
}

/**
 * A settings row (`.srow`, control focus family): [lead], the [label] with its [hint] under it,
 * [control] (a switch), then [value]; a row that opens something more ends in a chevron
 * ([chevron]). [danger] fills it red when focused (removing). Its right end sits at the safe line,
 * so focus grows it leftward; its label takes up to [labelMaxLines] lines, in [labelFont] when
 * it is code (a pattern). A row that isn't
 * [focusable] is text focus passes over (a credit,
 * a setting that waits for another).
 */
@Composable
internal fun SettingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    value: String? = null,
    chevron: Boolean = value != null,
    danger: Boolean = false,
    focusable: Boolean = true,
    minHeight: Dp = RowMinHeight,
    labelMaxLines: Int = 1,
    labelFont: FontFamily? = null,
    valueMaxWidth: Dp = ValueMaxWidth,
    lead: (@Composable (RowTones) -> Unit)? = null,
    valueLead: (@Composable (RowTones) -> Unit)? = null,
    labelTrail: (@Composable (RowTones) -> Unit)? = null,
    control: (@Composable (RowTones) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "rowTones")
    val tones = remember(progress, danger) { RowTones(progress, danger) }
    ControlSurface(
        onClick = onClick,
        style = if (danger) ControlStyle.DangerRow else ControlStyle.Row,
        shape = RowShape,
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = RowInset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            lead?.invoke(tones)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TintedText(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = labelFont,
                        maxLines = labelMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    labelTrail?.invoke(tones)
                }
                hint?.let {
                    TintedText(it, style = MaterialTheme.typography.bodySmall, tint = tones.faint, modifier = Modifier.padding(top = 2.5.dp))
                }
            }
            control?.invoke(tones)
            if (value != null || chevron) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    valueLead?.invoke(tones)
                    if (!value.isNullOrEmpty()) {
                        TintedText(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium.atWeight(500),
                            tint = tones.soft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = valueMaxWidth),
                        )
                    }
                    if (chevron) {
                        TintedIcon(
                            painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            tint = tones.softer,
                            modifier = Modifier.size(ChevronSize),
                        )
                    }
                }
            }
        }
    }
}

// `.srow.na`: a choice or control that can't act (no room, too short a passphrase, a blank field).
internal const val UnavailableAlpha = 0.42f

// `.srow.editing` (px ÷ 2): a well with a 1.5 dp ink rim, the value becomes the field.
private val EditRim = 1.5.dp
private val Arrows = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * A row edited in place (`.srow.editing`, `b3-profiles` rename, `b3-library` plrename): [leadIcon]
 * (resting) and [label], then the value as a field at the row's end (without a label, the field
 * takes the label's place: `kp-filters` keywordadd; in [fieldFont] when it is code), typed with the system keyboard, which comes up as the field
 * takes focus (it does as the row appears); its Done key calls [onDone]. Focus stays in the field
 * while it is edited (its arrows move the cursor); the keyboard neither corrects nor learns it.
 * A [secret] (a password) shows as dots and asks for the keyboard's password layout; [keyboardType]
 * asks for another (an address).
 */
@Composable
internal fun SettingEditRow(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    minHeight: Dp = RowMinHeight,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    fieldFont: FontFamily? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    @DrawableRes leadIcon: Int? = null,
) {
    val field = rememberInitialFocus()
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .drawBehind {
                drawInflated(RowShape, 0.dp, KeColors.Well, 1f)
                drawKeyline(RowShape, EditRim, KeColors.Ink, 1f)
            }
            .padding(horizontal = RowInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        leadIcon?.let { Icon(painterResource(it), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(RowIconSize)) }
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        PrivateTextInput {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .onFocusChanged { if (it.isFocused) keyboard?.show() }
                    // The arrows the field doesn't use for its cursor stay with it.
                    .onKeyEvent { it.type == KeyEventType.KeyDown && it.key in Arrows },
                textStyle = MaterialTheme.typography.labelMedium.atWeight(600)
                    .copy(color = KeColors.Ink, textAlign = if (label == null) TextAlign.Start else TextAlign.End, fontFamily = fieldFont),
                singleLine = true,
                cursorBrush = SolidColor(KeColors.Ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = capitalization,
                    autoCorrectEnabled = false,
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onDone()
                }),
            )
        }
    }
}

private val PaneFade = 15.dp

/**
 * A category's pane (`.spane`): its [title] (after [who]'s plate when it is someone's: `.sh-who`),
 * [sub] under it when it has one, then its [rows]. The
 * scroller (`.sv-scroll`) reaches a row's inset past the text and 15 dp above it (its fade), so a
 * focused row's fill isn't clipped.
 */
@Composable
internal fun SettingsPane(title: String, sub: String?, who: Profile? = null, rows: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .widenStart(RowInset)
            .offset(y = -PaneFade)
            .topFade(PaneFade)
            .verticalScroll(rememberScrollState())
            .padding(top = PaneFade, bottom = 120.dp),
    ) {
        Column(Modifier.padding(start = RowInset)) {
            Row(Modifier.padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                who?.let { ProfilePlate(it.name, it.tint, SheetTitlePlate, Modifier.padding(end = 9.dp)) }
                Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp))
            }
            sub?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, lineHeight = 1.4.em),
                    color = KeColors.Ink2,
                    modifier = Modifier.padding(bottom = 11.dp).widthIn(max = 450.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = rows)
    }
}

/** A heading between a pane's rows (`.sub-h`: 17 dp above, 4 below, less the rows' 3 dp gap). */
@Composable
internal fun PaneSubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.atWeight(640).copy(fontSize = 14.sp),
        color = KeColors.Ink2,
        modifier = Modifier.padding(start = RowInset, top = 14.dp, bottom = 1.dp),
    )
}

/** A row's lead icon (`.srow .lead`). */
@Composable
internal fun RowIcon(@DrawableRes icon: Int, tones: RowTones) {
    TintedIcon(painterResource(icon), contentDescription = null, tint = tones.soft, modifier = Modifier.size(RowIconSize))
}

/** A row's lead icon in the danger red, turning to the red fill's ink as focus fills it
 *  (`.srow.dz .lead`: Delete playlist). */
@Composable
internal fun DangerRowIcon(@DrawableRes icon: Int, tones: RowTones) {
    val tint = remember(tones) { tones.fade(KeColors.Danger, KeColors.OnDanger) }
    TintedIcon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(RowIconSize))
}

// `.srow .radio` (px ÷ 2): 17 dp, a 1.5 dp ring; the chosen one's dot sits 4.5 dp in.
private val RadioSize = 17.dp

/** A choice's radio (`.srow .radio`): a ring, filled with a dot when [on]; focus inverts it. */
@Composable
internal fun RowRadio(on: Boolean, tones: RowTones) {
    Box(
        Modifier.size(RadioSize).drawBehind {
            val ring = 1.5.dp.toPx()
            val r = size.minDimension / 2
            val rest = if (on) KeColors.Ink else KeColors.Ink3
            val focused = if (on) KeColors.Ground else KeColors.OnInk.copy(alpha = 0.5f)
            drawCircle(lerp(rest, focused, tones.focus), r - ring / 2, style = Stroke(ring))
            if (on) drawCircle(lerp(KeColors.Ink, KeColors.Ground, tones.focus), r - 4.5.dp.toPx())
        },
    )
}

// `.cbox` (px ÷ 2): 17 dp, 4.5 dp corners, a 1.5 dp ring; ticked, an ink fill whose check grows in.
private val CheckSize = 17.dp
private val CheckShape = RoundedCornerShape(4.5.dp)

/** A row's checkbox (`.cbox`): a ring, or ticked an ink square with a ground check; focus inverts
 *  it, and the check grows in as it ticks (D8). Its row reads as ticked or not. */
@Composable
internal fun RowCheck(on: Boolean, tones: RowTones) {
    val ticked = animateFloatAsState(if (on) 1f else 0f, KeMotion.fast(), label = "check")
    val checkInk = remember(tones) { tones.fade(KeColors.Ground, KeColors.Ink) }
    Box(
        modifier = Modifier
            .size(CheckSize)
            .semantics { toggleableState = ToggleableState(on) }
            .drawBehind {
                val ring = lerp(KeColors.Ink3, KeColors.OnInk.copy(alpha = 0.5f), tones.focus)
                drawKeyline(CheckShape, 1.5.dp, ring, 1f - ticked.value)
                drawInflated(CheckShape, 0.dp, lerp(KeColors.Ink, KeColors.Ground, tones.focus), ticked.value)
            },
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = checkInk,
            modifier = Modifier.size(13.dp).graphicsLayer {
                val scale = 0.6f + 0.4f * ticked.value
                scaleX = scale
                scaleY = scale
                alpha = ticked.value
            },
        )
    }
}

// `.switch` (px ÷ 2): a 38 × 22 dp track, its 17 dp knob 2.5 dp in, travelling 16 dp when on.
private val SwitchWidth = 38.dp
private val SwitchHeight = 22.dp
private val SwitchInset = 2.5.dp
private val SwitchTrackOff = Color.White.copy(alpha = 0.16f)

/**
 * A row's switch (`.srow .switch`): off, a faint track and a grey knob at the start; on, an ink
 * track and a ground knob at the end. Focus inverts both, and the knob slides (D8). Its row reads
 * as on or off (semantics merge into the row).
 */
@Composable
internal fun RowSwitch(on: Boolean, tones: RowTones) {
    val onness = animateFloatAsState(if (on) 1f else 0f, KeMotion.base(), label = "switch")
    Box(
        Modifier.size(SwitchWidth, SwitchHeight).semantics { toggleableState = ToggleableState(on) }.drawBehind {
            val focus = tones.focus
            val progress = onness.value
            val trackOff = lerp(SwitchTrackOff, KeColors.OnInk.copy(alpha = 0.2f), focus)
            val trackOn = lerp(KeColors.Ink, KeColors.Ground, focus)
            val knobOff = lerp(KeColors.Ink2, KeColors.OnInk.copy(alpha = 0.55f), focus)
            val knobOn = lerp(KeColors.Ground, KeColors.Ink, focus)
            drawRoundRect(lerp(trackOff, trackOn, progress), cornerRadius = CornerRadius(size.height / 2))
            val inset = SwitchInset.toPx()
            val radius = size.height / 2 - inset
            val travel = size.width - size.height
            drawCircle(lerp(knobOff, knobOn, progress), radius, center = Offset(inset + radius + travel * progress, size.height / 2))
        },
    )
}

/** A tint's swatch beside its name (`.tdot`). */
@Composable
internal fun TintDot(tint: ProfileTint, modifier: Modifier = Modifier) {
    Box(modifier.size(11.dp).background(tint.color, RoundedCornerShape(3.5.dp)))
}

private val TagShape = RoundedCornerShape(3.5.dp)
private val TagKeyline = Color.White.copy(alpha = 0.3f)

/** A profile's tags after its name: Owner, Kids (`em.tag`). */
@Composable
internal fun ProfileTags(owner: Boolean, kids: Boolean, tones: RowTones) {
    if (owner) RowTag("Owner", tones)
    if (kids) RowTag("Kids", tones)
}

/** A keyline label after a row's name (`em.tag`). */
@Composable
internal fun RowTag(text: String, tones: RowTones) {
    TintedText(
        text = text,
        style = MaterialTheme.typography.labelSmall.atWeight(650),
        modifier = Modifier
            .padding(start = 7.dp)
            .drawBehind { drawKeyline(TagShape, 1.dp, lerp(TagKeyline, KeColors.OnInk.copy(alpha = 0.4f), tones.focus), 1f) }
            .padding(start = 5.dp, end = 5.dp, top = 1.5.dp, bottom = 2.dp),
    )
}

// `.notice` (px ÷ 2): warn at 10% with a 32% keyline, 11 × 13 dp in, 10 dp corners.
private val NoticeShape = RoundedCornerShape(10.dp)

/** A standing warning above a page's rows (`.notice`): [lead] in bold ink, then [text]. */
@Composable
internal fun Notice(lead: String, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawInflated(NoticeShape, 0.dp, KeColors.Warn.copy(alpha = 0.1f), 1f)
                drawKeyline(NoticeShape, 1.dp, KeColors.Warn.copy(alpha = 0.32f), 1f)
            }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = KeColors.Warn, modifier = Modifier.padding(top = 1.dp).size(15.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(620, KeColors.Ink)) { append(lead) }
                append(" $text")
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.4.em),
            color = KeColors.Ink2,
        )
    }
}

/**
 * A sheet's rows by key, and the one focused as it opens (`initial`): the row that leads back
 * to where the person came from, else its first.
 */
internal class SheetFocus(private val initial: String) {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun on(key: String): Modifier = Modifier.focusRequester(requester(key))

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Focuses [key]'s row, when it is in the sheet. */
    fun focus(key: String) {
        requesters[key]?.requestFocus()
    }

    suspend fun start() {
        withFrameNanos { }
        requesters[initial]?.requestFocus()
    }
}

@Composable
internal fun rememberSheetFocus(initial: String): SheetFocus {
    val focus = remember { SheetFocus(initial) }
    LaunchedEffect(focus) { focus.start() }
    return focus
}

// `.sheet` (px ÷ 2): 350 dp wide at the right edge; 32 dp from the top, 28 from its left side.
internal val SheetWidth = 350.dp
private val SheetTopFill = Color(0xFF1C1814)
private val SheetEdge = Color.White.copy(alpha = 0.1f)

// `.sheet .opts`: the rows start 17 dp under the copy above them.
internal val SheetRowsTop = 17.dp

private val SheetRowsFade = 40.dp

/**
 * A side sheet (`.sheet`): [kicker] (what this is), [title], [sub] (one line about it), its
 * [rows], and [note] at the foot. It fills the screen's height at the right edge; the caller
 * places it and dims the page. Rows that may not fit [scroll] under the copy and fade out at the
 * sheet's foot (`.opts.fade`).
 */
@Composable
internal fun SettingsSheet(
    kicker: String,
    title: @Composable () -> Unit,
    sub: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    scroll: Boolean = false,
    rows: @Composable ColumnScope.() -> Unit,
) {
    SheetFrame(modifier, note) {
        SheetHead(kicker, title, sub)
        if (scroll) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .widenStart(RowInset)
                    .bottomFade(SheetRowsFade)
                    .verticalScroll(rememberScrollState())
                    .padding(top = SheetRowsTop, bottom = SheetRowsFade),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                content = rows,
            )
        } else {
            SheetRows(top = SheetRowsTop, rows = rows)
        }
    }
}

/**
 * A side sheet over a long list (`.opts.sopts`): [kicker], [title], [sub], then [rows], composed
 * as they scroll into view and fading out above [note] at the sheet's foot. It opens scrolled to
 * [initialItem], so the row focused as it opens is composed.
 */
@Composable
internal fun SettingsListSheet(
    kicker: String,
    title: @Composable () -> Unit,
    sub: String?,
    initialItem: Int,
    note: String? = null,
    rows: LazyListScope.() -> Unit,
) {
    SheetFrame(note = note) {
        SheetHead(kicker, title, sub)
        LazyColumn(
            modifier = Modifier.weight(1f).widenStart(RowInset).bottomFade(SheetRowsFade),
            state = rememberLazyListState(initialFirstVisibleItemIndex = initialItem),
            contentPadding = PaddingValues(top = SheetRowsTop, bottom = SheetRowsFade),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            content = rows,
        )
    }
}

/** What heads a sheet: [kicker] (what this is), [title], [sub] (one line about it). */
@Composable
internal fun SheetHead(kicker: String, title: @Composable () -> Unit, sub: String? = null) {
    Text(kicker, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = KeColors.Ink3)
    Box(Modifier.padding(top = 5.dp)) { title() }
    sub?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.5.sp, lineHeight = 1.35.em),
            color = KeColors.Ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A heading between a sheet's rows (`.sheet .grp`: 13 dp above, 4 below, less the rows' 3 dp gap),
 *  [sub] after it in a lighter weight (`.gsub`). */
@Composable
internal fun SheetGroupHeading(text: String, sub: String? = null) {
    Text(
        text = buildAnnotatedString {
            append(text)
            sub?.let { withStyle(weightSpan(450)) { append(" · $it") } }
        },
        style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 11.5.sp),
        color = KeColors.Ink3,
        modifier = Modifier.padding(start = RowInset, top = 10.dp, bottom = 1.dp),
    )
}

/** A sheet's rows (`.sheet .opts`), [top] under what heads them, their fills reaching past the copy. */
@Composable
internal fun SheetRows(top: Dp, modifier: Modifier = Modifier, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(top = top).widenStart(RowInset), verticalArrangement = Arrangement.spacedBy(3.dp), content = rows)
}

/**
 * A side sheet's frame (`.sheet`, [width] wide) around [content], with [note] at its foot: the
 * screen's height at the right edge, a warm top fading into the surface, a hairline at its left side.
 */
@Composable
internal fun SheetFrame(modifier: Modifier = Modifier, note: String? = null, width: Dp = SheetWidth, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .drawWithCache {
                val fill = Brush.verticalGradient(0f to SheetTopFill, 0.4f to KeColors.Surface1)
                onDrawBehind {
                    drawRect(fill)
                    drawRect(SheetEdge, size = size.copy(width = 1.dp.toPx()))
                }
            }
            .padding(start = 28.dp, end = 58.dp, top = 32.dp, bottom = 28.dp),
    ) {
        if (note == null) {
            content()
        } else {
            // The content takes all the height above the note, so rows that scroll in it may use
            // all of it (beside a spacer's weight they would get half).
            Column(Modifier.weight(1f), content = content)
            SheetNote(note)
        }
    }
}

/** A sheet's small print at its foot (`.sheet .snote`), apart from rows fading out above it. */
@Composable
private fun SheetNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 1.4.em),
        color = KeColors.Ink3,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** A sheet's title (`.sheet h2`, 24 sp condensed), on up to [maxLines] lines (a question). */
@Composable
internal fun SheetTitle(text: String, maxLines: Int = 1) {
    Text(text, style = MaterialTheme.typography.headlineSmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * Lays its content [by] wider, reaching past its start, so rows' text lines up with the copy
 * above while their focus fill reaches out (`.sheet .opts`, `.spane .srow`: margin-left −30 px).
 */
internal fun Modifier.widenStart(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
