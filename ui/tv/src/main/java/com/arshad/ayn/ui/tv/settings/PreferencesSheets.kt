package com.arshad.ayn.ui.tv.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.INTERFACE_SCALE_CHOICES
import com.arshad.ayn.core.store.MAX_VIDEO_HEIGHTS
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.SPEED_PERCENTS
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.sameLanguage
import com.arshad.ayn.feature.settings.CAPTIONS_HINTS
import com.arshad.ayn.feature.settings.LICENSED_PARTS
import com.arshad.ayn.feature.settings.Licence
import com.arshad.ayn.feature.settings.LicenceText
import com.arshad.ayn.feature.settings.LicensedPart
import com.arshad.ayn.feature.settings.PreferencesSheet
import com.arshad.ayn.feature.settings.PreferencesUiState
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.SOURCE_CODE_SHOWN
import com.arshad.ayn.feature.settings.SOURCE_CODE_URL
import com.arshad.ayn.feature.settings.allLanguages
import com.arshad.ayn.feature.settings.captionsChoices
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.heading
import com.arshad.ayn.feature.settings.interfaceSizeLabel
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.licenceText
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.feature.settings.qualityChoice
import com.arshad.ayn.feature.settings.qualityHint
import com.arshad.ayn.feature.settings.sketch
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.feature.settings.unbroken
import com.arshad.ayn.ui.tv.common.bottomFade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OFF_KEY = "off"
private const val ALL_LANGUAGES_KEY = "all-languages"
private const val TEXT_KEY = "text"

/**
 * The sheet over a preference page; [deviceLanguage] is the TV's (an ISO 639 code). [from] is the
 * sheet before it, which picks the row it opens on (back from All languages lands on its row,
 * back from a licence's text on that licence's part).
 */
@Composable
internal fun PreferencesSheetContent(
    shown: PreferencesSheet,
    from: PreferencesSheet?,
    state: PreferencesUiState,
    me: Profile?,
    deviceLanguage: String,
    preferences: PreferencesViewModel,
) {
    val settings = state.profile?.settings
    val device = state.device
    when (shown) {
        PreferencesSheet.Speed -> if (settings != null && me != null) SpeedSheet(me, settings, preferences)
        PreferencesSheet.Quality -> if (device != null) QualitySheet(device, preferences)
        PreferencesSheet.InterfaceSize -> if (device != null) InterfaceSizeSheet(device, preferences)
        PreferencesSheet.Captions -> if (settings != null && me != null) {
            CaptionsSheet(me, settings, deviceLanguage, fromAllLanguages = from == PreferencesSheet.AllLanguages, preferences)
        }
        PreferencesSheet.AllLanguages -> if (settings != null && me != null) AllLanguagesSheet(me, settings, preferences)
        PreferencesSheet.LiveChat -> if (settings != null && me != null) LiveChatSheet(me, settings, preferences)
        is PreferencesSheet.Category -> if (settings != null) CategorySheet(shown.category, settings, preferences)
        PreferencesSheet.Licences -> LicencesSheet(fromPart = (from as? PreferencesSheet.LicenceText)?.part, preferences)
        is PreferencesSheet.LicenceText -> LICENSED_PARTS.firstOrNull { it.name == shown.part }?.let { LicenceTextSheet(it) }
        PreferencesSheet.SourceCode -> SourceCodeSheet()
    }
}

/** Playback › Speed (`b3-settings` speed): the player's speeds; it opens on the one chosen, and OK picks and closes. */
@Composable
private fun SpeedSheet(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val chosen = settings.speedPercent
    val focus = rememberSheetFocus("$chosen")
    SettingsSheet(
        kicker = "Playback · ${profileScope(me.name)}",
        title = { SheetTitle("Speed") },
        sub = "Every video starts at this speed; change it for one video from the player. Pitch stays natural at every speed.",
        scroll = true,
    ) {
        SPEED_PERCENTS.forEach { percent ->
            Choice(speedLabel(percent), on = percent == chosen, onClick = { preferences.onSpeedPicked(percent) }, modifier = focus.on("$percent"))
        }
    }
}

/** Playback › Quality (`b3-settings` quality): the highest the TV plays, for everyone on it. */
@Composable
private fun QualitySheet(device: DeviceSettings, preferences: PreferencesViewModel) {
    val chosen = device.maxVideoHeight
    val focus = rememberSheetFocus("$chosen")
    SettingsSheet(
        kicker = "Playback · $EVERYONE_SCOPE",
        title = { SheetTitle("Quality") },
        sub = "The most Ayn plays on this Shield. Below it, playback still steps down on its own when the connection can’t keep up.",
        scroll = true,
    ) {
        MAX_VIDEO_HEIGHTS.forEach { height ->
            Choice(
                label = qualityChoice(height),
                hint = qualityHint(height),
                on = height == chosen,
                onClick = { preferences.onQualityPicked(height) },
                modifier = focus.on("$height"),
            )
        }
    }
}

/** Interface › Interface size (`b3-settings` ifsize): how large everything shows; a pick applies at once (Q59). */
@Composable
private fun InterfaceSizeSheet(device: DeviceSettings, preferences: PreferencesViewModel) {
    val chosen = device.interfaceScalePercent
    val focus = rememberSheetFocus("$chosen")
    SettingsSheet(
        kicker = "Interface · $EVERYONE_SCOPE",
        title = { SheetTitle("Interface size") },
        sub = "Everything Ayn shows grows or shrinks together. A pick applies at once.",
        scroll = true,
    ) {
        INTERFACE_SCALE_CHOICES.forEach { percent ->
            Choice(
                label = interfaceSizeLabel(percent),
                hint = interfaceSizeHint(percent),
                on = percent == chosen,
                onClick = { preferences.onInterfaceSizePicked(percent) },
                modifier = focus.on("$percent"),
            )
        }
    }
}

/**
 * Playback › Captions (`b3-settings` capdefault, capall): Off, the TV's language, the offered
 * languages under their groups' headings (Q16), then All languages…. It opens on the one chosen
 * (on All languages… when back from there), and OK picks and closes.
 */
@Composable
private fun CaptionsSheet(
    me: Profile,
    settings: ProfileSettings,
    deviceLanguage: String,
    fromAllLanguages: Boolean,
    preferences: PreferencesViewModel,
) {
    val chosen = settings.captionsLanguage
    val focus = rememberSheetFocus(if (fromAllLanguages) ALL_LANGUAGES_KEY else chosen ?: OFF_KEY)
    val languageCount = remember { allLanguages().size }
    SettingsSheet(
        kicker = "Playback · ${profileScope(me.name)}",
        title = { SheetTitle("Captions") },
        sub = "Turned on for every video that has them in this language: written captions first, YouTube’s automatic " +
            "ones when there are none.",
        scroll = true,
    ) {
        Choice("Off", on = chosen == null, onClick = { preferences.onCaptionsPicked(null) }, modifier = focus.on(OFF_KEY))
        captionsChoices(deviceLanguage, chosen).forEach { section ->
            section.group?.let { SheetGroupHeading(it.heading) }
            section.tags.forEach { tag ->
                Choice(
                    label = captionsLabel(tag),
                    hint = if (sameLanguage(tag, deviceLanguage)) "This Shield’s language" else CAPTIONS_HINTS[tag],
                    on = tag == chosen,
                    onClick = { preferences.onCaptionsPicked(tag) },
                    modifier = focus.on(tag),
                )
            }
        }
        SettingRow(
            label = "All languages…",
            hint = "$languageCount languages, by the names this Shield gives them",
            chevron = true,
            minHeight = SheetRowMinHeight,
            onClick = preferences::onOpenAllLanguages,
            modifier = focus.on(ALL_LANGUAGES_KEY),
        )
    }
}

/**
 * Playback › Captions › All languages (`b3-settings` alllangs): every language the platform
 * names, alphabetical (Q23); it opens on the one chosen, and OK picks and closes both sheets.
 */
@Composable
private fun AllLanguagesSheet(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val languages = remember { allLanguages() }
    val chosen = settings.captionsLanguage
    val initial = languages.indexOfFirst { it.tag == chosen }.coerceAtLeast(0)
    val focus = rememberSheetFocus(languages.getOrNull(initial)?.tag.orEmpty())
    SettingsListSheet(
        kicker = "Playback · ${profileScope(me.name)} · Captions",
        title = { SheetTitle("All languages") },
        sub = "A video shows captions only when it has them in the language you pick.",
        initialItem = initial,
    ) {
        items(languages, key = { it.tag }) { language ->
            Choice(
                label = language.name,
                on = language.tag == chosen,
                onClick = { preferences.onCaptionsPicked(language.tag) },
                modifier = focus.on(language.tag),
            )
        }
    }
}

/**
 * Playback › Live chat (`b3-settings` livechatview): where chat shows, each choice drawn small
 * before its radio; it opens on the one chosen, and OK picks and closes.
 */
@Composable
private fun LiveChatSheet(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val chosen = settings.chatPlacement
    val focus = rememberSheetFocus(chosen.key)
    SettingsSheet(
        kicker = "Playback · ${profileScope(me.name)}",
        title = { SheetTitle("Live chat") },
        sub = "Where chat shows when you open it on a live stream. The player’s Chat sheet changes it for one stream.",
        scroll = true,
    ) {
        ChatPlacement.entries.forEach { placement ->
            SettingRow(
                label = placement.label,
                hint = placement.consequence,
                minHeight = SheetRowMinHeight,
                onClick = { preferences.onChatPlacementPicked(placement) },
                modifier = focus.on(placement.key),
                lead = { tones ->
                    PlacementMini(placement, tones)
                    RowRadio(placement == chosen, tones)
                },
            )
        }
    }
}

// `.vmini` (px ÷ 2): a 32 × 18 dp picture with chat drawn where it sits; focus darkens both.
private val MiniSize = DpSize(32.dp, 18.dp)
private val MiniShape = RoundedCornerShape(3.dp)
private val MiniChatCorner = 1.dp
private val MiniPicture = Color.White.copy(alpha = 0.14f)
private val MiniPictureFocused = KeColors.Ground.copy(alpha = 0.16f)
private val MiniChat = KeColors.Ink.copy(alpha = 0.6f)
private val MiniChatFocused = KeColors.Ground.copy(alpha = 0.7f)

/** [placement] drawn small: the picture, and chat where it sits in it; a row's 32 × 18 dp unless
 *  [modifier] sizes it (the player's Chat sheet). */
@Composable
internal fun PlacementMini(placement: ChatPlacement, tones: RowTones, modifier: Modifier = Modifier.size(MiniSize)) {
    val picture = remember(tones) { tones.fade(MiniPicture, MiniPictureFocused) }
    val chat = remember(tones) { tones.fade(MiniChat, MiniChatFocused) }
    val sketch = placement.sketch
    Box(
        modifier.clip(MiniShape).drawBehind {
            drawRect(picture())
            val corner = if (sketch.square) 0f else MiniChatCorner.toPx()
            drawRoundRect(
                chat(),
                topLeft = Offset(sketch.left * size.width, sketch.top * size.height),
                size = Size(sketch.width * size.width, sketch.height * size.height),
                cornerRadius = CornerRadius(corner),
            )
        },
    )
}

/** SponsorBlock › a category (`b3-settings` sbchoice): the four actions, each with what it does. */
@Composable
private fun CategorySheet(category: SegmentCategory, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val chosen = settings.segmentActions.getValue(category)
    val focus = rememberSheetFocus(chosen.key)
    val text = category.text
    SettingsSheet(
        kicker = "SponsorBlock category",
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetSwatch(category)
                SheetTitle(text.name)
            }
        },
        sub = "${text.about}.",
    ) {
        SegmentAction.entries.forEach { action ->
            Choice(
                label = action.label,
                hint = action.consequence,
                on = action == chosen,
                onClick = { preferences.onActionPicked(action) },
                modifier = focus.on(action.key),
            )
        }
    }
}

// `.sw.big` (px ÷ 2): 15 dp, 4.5 dp corners, 8 dp before the name.
@Composable
private fun SheetSwatch(category: SegmentCategory) {
    CategorySwatch(category.color, Modifier.padding(end = 8.dp), size = 15.dp, corner = 4.5.dp)
}

/**
 * About › Licences (`b3-settings` licences): Ayn's licence, then the parts it is built from,
 * each with what it does and its licence; OK opens that licence's text. It opens on [fromPart]
 * when back from its text, else on the first.
 */
@Composable
private fun LicencesSheet(fromPart: String?, preferences: PreferencesViewModel) {
    val focus = rememberSheetFocus(fromPart ?: LICENSED_PARTS.first().name)
    SettingsSheet(
        kicker = "About",
        title = { SheetTitle("Licences") },
        sub = "Ayn is free software under the GNU General Public License, version 3 or later. These are the parts it’s " +
            "built from; OK opens a licence’s full text.",
        scroll = true,
    ) {
        LICENSED_PARTS.forEach { part ->
            SettingRow(
                label = part.name,
                hint = "${part.role} · ${unbroken(part.licence.id)}",
                chevron = true,
                minHeight = SheetRowMinHeight,
                labelMaxLines = 2,
                onClick = { preferences.onOpenLicenceText(part.name) },
                modifier = focus.on(part.name),
            )
        }
    }
}

/**
 * About › Licences › a part (`b3-settings` lictext): its licence's text, verbatim. The text is
 * one focus; up and down scroll it a page at a time, and the bar beside it shows where it is.
 */
@Composable
private fun LicenceTextSheet(part: LicensedPart) {
    val text by rememberLicenceText(part.licence)
    val focus = rememberSheetFocus(TEXT_KEY)
    SheetFrame {
        SheetHead(kicker = "Licences · ${part.name}", title = { SheetTitle(part.licence.title) })
        LicenceBody(text ?: LicenceText(emptyList(), emptyList()), Modifier.weight(1f).padding(top = 14.dp), focus.on(TEXT_KEY))
        Text(
            "Up and down scroll a page · BACK returns to the list",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = KeColors.Ink3,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

/**
 * About › Source code (`b3-settings` source): the public repository as a QR code to scan with a
 * phone. The code is the sheet's one focus, so BACK returns to the row.
 */
@Composable
private fun SourceCodeSheet() {
    val focus = rememberSheetFocus(SOURCE_KEY)
    SettingsSheet(
        kicker = "About",
        title = { SheetTitle("Source code") },
        sub = "Ayn’s code, and where every part it borrows comes from. Scan with your phone to open it.",
    ) {
        AddressQr(
            SOURCE_CODE_URL,
            SOURCE_CODE_SHOWN,
            focus.on(SOURCE_KEY).focusable().align(Alignment.CenterHorizontally).padding(top = 5.dp),
        )
    }
}

private const val SOURCE_KEY = "source"

/** [licence]'s text, read off the main thread; `null` until it is read. */
@Composable
private fun rememberLicenceText(licence: Licence): State<LicenceText?> {
    val resources = LocalContext.current.resources
    return produceState<LicenceText?>(initialValue = null, licence) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(licence.text).bufferedReader().use { licenceText(it.readText()) }
        }
    }
}

// A page keeps a line or two of the last in view; the text's foot fades out (`.lclip`).
private const val PAGE_FRACTION = 0.85f
private val LicenceFade = 40.dp
private val BarWidth = 3.dp
private val BarTrack = Color.White.copy(alpha = 0.12f)

// `.lhead`: 13 dp under the title, less the paragraphs' 9 dp gap.
private val LicenceTitleGap = 4.dp

/** The licence's [text] in a scroller that is one focus ([focusModifier]); `.ltext` in the mockup. */
@Composable
private fun LicenceBody(text: LicenceText, modifier: Modifier, focusModifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    // The running page turn and where it ends, so a press during it turns a whole page from there.
    var turn by remember { mutableStateOf<Job?>(null) }
    var target by remember { mutableIntStateOf(0) }
    val focus by animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "licenceFocus")
    Box(
        modifier
            .fillMaxWidth()
            .then(focusModifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    val from = if (turn?.isActive == true) target else scroll.value
                    val to = (from + direction * (scroll.viewportSize * PAGE_FRACTION).toInt()).coerceIn(0, scroll.maxValue)
                    target = to
                    turn = scope.launch { scroll.animateScrollTo(to, KeMotion.move()) }
                }
                true
            }
            .focusable(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(end = 17.dp).bottomFade(LicenceFade).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.5.sp, lineHeight = 1.5.em)
            if (text.title.isNotEmpty()) {
                Text(
                    text.title.joinToString("\n"),
                    style = style.atWeight(600),
                    color = KeColors.Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = LicenceTitleGap),
                )
            }
            text.paragraphs.forEach { paragraph ->
                Text(
                    paragraph,
                    style = style,
                    color = KeColors.Ink2,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(BarWidth)
                .padding(vertical = 3.dp)
                .drawBehind {
                    val radius = CornerRadius(size.width / 2)
                    drawRoundRect(BarTrack, cornerRadius = radius)
                    val content = scroll.viewportSize + scroll.maxValue
                    if (content <= 0) return@drawBehind
                    val thumb = size.height * scroll.viewportSize / content
                    val top = if (scroll.maxValue == 0) 0f else (size.height - thumb) * scroll.value / scroll.maxValue
                    drawRoundRect(lerp(KeColors.Ink3, KeColors.Ink, focus), Offset(0f, top), Size(size.width, thumb), radius)
                },
        )
    }
}

/** One choice in a sheet: its radio, marked when [on], then the label and [hint]. */
@Composable
internal fun Choice(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier, hint: String? = null) {
    SettingRow(
        label = label,
        hint = hint,
        minHeight = SheetRowMinHeight,
        onClick = onClick,
        modifier = modifier,
        lead = { RowRadio(on, it) },
    )
}
