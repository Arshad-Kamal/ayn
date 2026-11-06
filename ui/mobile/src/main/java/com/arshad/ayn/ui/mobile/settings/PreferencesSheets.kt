package com.arshad.ayn.ui.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.MAX_VIDEO_HEIGHTS
import com.arshad.ayn.core.store.Profile
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
import com.arshad.ayn.feature.settings.allLanguages
import com.arshad.ayn.feature.settings.captionsChoices
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.heading
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.licenceText
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.feature.settings.qualityChoice
import com.arshad.ayn.feature.settings.qualityHint
import com.arshad.ayn.feature.settings.sketch
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.menu.RowRadio
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.SheetSub
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.player.color
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What shows over a preference page (`m6-settings` 5–7, 10, 15–16): a bottom sheet for the
 * choices (a pick saves and closes; All languages opens in the same sheet, ← back), Licences as a
 * sheet, and a licence's text as a page over it that Back returns from.
 */
@Composable
internal fun PreferencesSheets(state: PreferencesUiState, me: Profile, preferences: PreferencesViewModel) {
    val sheet = state.sheet
    StageSheet(sheet?.takeIf { it !is PreferencesSheet.LicenceText }, onDismiss = preferences::onDismiss, onBack = preferences::onBack, stageKey = { it::class }) { shown ->
        val settings = state.profile?.settings
        val device = state.device
        when (shown) {
            PreferencesSheet.Speed -> if (settings != null) {
                SheetColumn("Playback · ${profileScope(me.name)}", "Speed", "Every video starts at this speed; change it for one video from the player. Pitch stays natural at every speed.") {
                    SPEED_PERCENTS.forEach { percent ->
                        ChoiceRow(speedLabel(percent), percent == settings.speedPercent, { preferences.onSpeedPicked(percent) })
                    }
                }
            }
            PreferencesSheet.Quality -> if (device != null) {
                SheetColumn("Playback · $THIS_PHONE", "Quality", "The most Ayn plays on this phone. Below it, playback still steps down on its own when the connection can’t keep up.") {
                    MAX_VIDEO_HEIGHTS.forEach { height ->
                        ChoiceRow(qualityChoice(height), height == device.maxVideoHeight, { preferences.onQualityPicked(height) }, hint = qualityHint(height))
                    }
                }
            }
            PreferencesSheet.Captions -> if (settings != null) CaptionsChoices(me, settings.captionsLanguage, preferences)
            PreferencesSheet.AllLanguages -> if (settings != null) AllLanguages(settings.captionsLanguage, preferences)
            PreferencesSheet.LiveChat -> if (settings != null) LiveChatChoices(me, settings.chatPlacement, preferences)
            is PreferencesSheet.Category -> if (settings != null) CategoryChoices(shown.category, settings.segmentActions.getValue(shown.category), preferences)
            PreferencesSheet.Licences -> Licences(preferences)
            PreferencesSheet.InterfaceSize, PreferencesSheet.SourceCode, is PreferencesSheet.LicenceText -> Unit
        }
    }
    if (sheet is PreferencesSheet.LicenceText) {
        LICENSED_PARTS.firstOrNull { it.name == sheet.part }?.let { part -> LicencePage(part, onBack = preferences::onBack) }
    }
}

/** Live chat (`m6-settings` live-chat 2): where chat shows, each choice drawn small after its radio. */
@Composable
private fun LiveChatChoices(me: Profile, chosen: ChatPlacement, preferences: PreferencesViewModel) {
    SheetColumn(
        "Playback · ${profileScope(me.name)}",
        "Live chat",
        "Where chat shows when you open it on a live stream; the player’s Chat sheet changes it for one stream. Over the picture applies sideways.",
    ) {
        ChatPlacement.entries.forEach { placement ->
            val on = placement == chosen
            ChoiceRow(
                placement.label,
                on,
                { preferences.onChatPlacementPicked(placement) },
                hint = placement.consequence,
                lead = {
                    RowRadio(on)
                    PlacementMini(placement, Modifier.size(MiniSize))
                },
            )
        }
    }
}

// `.vmini`: a 48 × 27 dp picture with chat drawn where it sits.
private val MiniSize = DpSize(48.dp, 27.dp)
private val MiniShape = RoundedCornerShape(4.dp)
private val MiniChatCorner = 2.dp
internal val MiniPicture = Color.White.copy(alpha = 0.14f)
internal val MiniChat = AynColors.Ink.copy(alpha = 0.6f)

/** [placement] drawn small (`m6-settings` live-chat; the player's Chat sheet sizes it to its card):
 *  the [picture], and [chat] where it sits in it. */
@Composable
internal fun PlacementMini(placement: ChatPlacement, modifier: Modifier = Modifier, picture: Color = MiniPicture, chat: Color = MiniChat) {
    val sketch = placement.sketch
    Box(
        modifier.clip(MiniShape).drawBehind {
            drawRect(picture)
            drawRoundRect(
                chat,
                topLeft = Offset(sketch.left * size.width, sketch.top * size.height),
                size = Size(sketch.width * size.width, sketch.height * size.height),
                cornerRadius = CornerRadius(if (sketch.square) 0f else MiniChatCorner.toPx()),
            )
        },
    )
}

/** Captions (state 7): Off, this phone's language, the offered languages under their groups,
 *  then All languages…. */
@Composable
private fun CaptionsChoices(me: Profile, chosen: String?, preferences: PreferencesViewModel) {
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    val count = remember { allLanguages().size }
    SheetColumn(
        "Playback · ${profileScope(me.name)}",
        "Captions",
        "Turned on for every video that has them in this language: written captions first, YouTube’s automatic ones when there are none.",
        Modifier.fillMaxHeight(),
    ) {
        ChoiceRow("Off", chosen == null, { preferences.onCaptionsPicked(null) })
        captionsChoices(deviceLanguage, chosen).forEach { section ->
            section.group?.let { ChoiceHeading(it.heading) }
            section.tags.forEach { tag ->
                ChoiceRow(
                    captionsLabel(tag),
                    tag == chosen,
                    { preferences.onCaptionsPicked(tag) },
                    hint = if (sameLanguage(tag, deviceLanguage)) "This phone’s language" else CAPTIONS_HINTS[tag],
                )
            }
        }
        SheetRow(
            "All languages…",
            tap(preferences::onOpenAllLanguages),
            hint = "$count languages, by the names this phone gives them",
            icon = R.drawable.ic_nav_search,
            chevron = true,
        )
    }
}

/** All languages (Captions' second stage): every language this phone names, alphabetical; it opens
 *  on the chosen one; ← returns to Captions. */
@Composable
private fun AllLanguages(chosen: String?, preferences: PreferencesViewModel) {
    val languages = remember { allLanguages() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = languages.indexOfFirst { it.tag == chosen }.coerceAtLeast(0))
    Column(Modifier.fillMaxHeight().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = preferences::onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Back to Captions", tint = AynColors.Ink)
            }
            SettingsSheetTitle("All languages")
        }
        SheetSub("A video shows captions only when it has them in the language you pick.")
        LazyColumn(state = list, modifier = Modifier.weight(1f).selectableGroup()) {
            items(languages, key = { it.tag }) { language ->
                ChoiceRow(language.name, language.tag == chosen, { preferences.onCaptionsPicked(language.tag) })
            }
        }
    }
}

/** A SponsorBlock category (state 10): the four actions, each with what it does. */
@Composable
private fun CategoryChoices(category: SegmentCategory, chosen: SegmentAction, preferences: PreferencesViewModel) {
    val text = category.text
    Column(Modifier.padding(horizontal = 8.dp)) {
        SettingsSheetKicker("SponsorBlock category")
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(category.color)
            SettingsSheetTitle(text.name)
        }
        SheetSub("${text.about}.")
        Column(Modifier.selectableGroup()) {
            SegmentAction.entries.forEach { action ->
                ChoiceRow(action.label, action == chosen, { preferences.onActionPicked(action) }, hint = action.consequence)
            }
        }
        SheetFoot()
    }
}

/** Licences (state 15): what Ayn is built from, each with its licence; a tap opens its text. */
@Composable
private fun Licences(preferences: PreferencesViewModel) {
    SheetColumn(
        "About",
        "Licences",
        "Ayn is free software under the GNU General Public License, version 3 or later. These are the parts it’s built from; tap one for its full text.",
        Modifier.fillMaxHeight(),
        choices = false,
    ) {
        LICENSED_PARTS.forEach { part ->
            SheetRow(part.name, tap { preferences.onOpenLicenceText(part.name) }, hint = "${part.role} · ${part.licence.id}", icon = R.drawable.ic_doc, chevron = true)
        }
    }
}

/** A licence's text (state 16), verbatim, as a page that scrolls; Back returns to Licences. */
@Composable
private fun LicencePage(part: LicensedPart, onBack: () -> Unit) {
    // Read here, not in the list's item builder: under load the builder's read raced its measure
    // (IndexOutOfBounds in the item list as the text arrived).
    val text = rememberLicenceText(part.licence).value
    FullCover {
        BackHandler(onBack = onBack)
        SettingsFrame(part.licence.title, onBack) {
            item(key = "kicker") {
                Text("Licences · ${part.name}", style = MaterialTheme.typography.labelSmall.atWeight(650).copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(horizontal = 24.dp))
            }
            val shown = text ?: return@SettingsFrame
            if (shown.title.isNotEmpty()) {
                item(key = "title") {
                    Text(
                        shown.title.joinToString("\n"),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp),
                    )
                }
            }
            items(shown.paragraphs) { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                    color = AynColors.Ink2,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                )
            }
        }
    }
}

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
