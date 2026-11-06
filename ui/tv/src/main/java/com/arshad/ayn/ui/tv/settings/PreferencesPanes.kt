package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.settings.DeviceSwitch
import com.arshad.ayn.feature.settings.PreferencesUiState
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfileSwitch
import com.arshad.ayn.feature.settings.SOURCE_CODE_SHOWN
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.interfaceSizeLabel
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.profiles.KMark
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.color

// Every row carries a focus key (ScreenFocus memory), which is how closing a sheet returns focus to
// the row that opened it.

// `.is-off`: what is off or out of reach (SponsorBlock's categories, a sample, the TV's rows for
// anyone but the owner, the owner's restrictions on Filters) dims to 38%.
internal const val OFF_ALPHA = 0.38f

/**
 * One of [me]'s preference pages ([category]: Playback, SponsorBlock, DeArrow, Dislikes or
 * Interface); it waits until the profile's and the TV's settings and who the owner is have
 * loaded.
 */
@Composable
internal fun PreferencesPane(category: SettingsCategory, me: Profile?, state: PreferencesUiState, preferences: PreferencesViewModel) {
    me ?: return
    val settings = state.profile?.settings ?: return
    val device = state.device ?: return
    val ownerName = state.ownerName ?: return
    val tv = TvRows(changes = state.changesTheTv, ownerName = ownerName)
    when (category) {
        SettingsCategory.Playback -> PlaybackPane(me, settings, device, tv, preferences)
        SettingsCategory.SponsorBlock -> SponsorBlockPane(me, settings, preferences)
        SettingsCategory.DeArrow -> DeArrowPane(me, settings, preferences)
        SettingsCategory.Dislikes -> DislikesPane(me, settings, preferences)
        SettingsCategory.Interface -> InterfacePane(me, settings, device, tv, preferences)
        SettingsCategory.Profiles, SettingsCategory.Filters, SettingsCategory.Sync, SettingsCategory.Backup, SettingsCategory.About -> Unit
    }
}

/** Whether this profile [changes] the TV's settings (the owner does, Q17), and who does ([ownerName]). */
private data class TvRows(val changes: Boolean, val ownerName: String)

/**
 * The "Everyone on this Shield" group (`.sub-h.scoped`): its heading, then its [rows]. For anyone
 * but the owner a line says who changes them, and the rows dim and focus passes over them
 * (`b3-settings` guestplayback, guestinterface); [rows] get whether they take focus.
 */
@Composable
private fun TvGroup(tv: TvRows, rows: @Composable (focusable: Boolean) -> Unit) {
    ScopeHeading(me = null, first = false)
    if (!tv.changes) OwnerOnlyLine(ownerOnly(tv.ownerName))
    Column(
        Modifier.graphicsLayer { alpha = if (tv.changes) 1f else OFF_ALPHA },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        rows(tv.changes)
    }
}

/** `.ownline`: a lock, then [text]: who may change the rows under it. */
@Composable
internal fun OwnerOnlyLine(text: String) {
    Row(
        modifier = Modifier.padding(start = RowInset, bottom = 4.dp).offset(y = (-3).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.5.sp),
            color = KeColors.Ink2,
        )
    }
}

/**
 * Settings › Playback (`b3-settings` playback, codec, livechat): [me]'s speed, resume, captions and
 * live chat, then the TV's quality, codec and frame-rate matching. Video codec is text while VP9 is
 * the only codec Ayn plays.
 */
@Composable
private fun PlaybackPane(me: Profile, settings: ProfileSettings, device: DeviceSettings, tv: TvRows, preferences: PreferencesViewModel) {
    SettingsPane("Playback", sub = null) {
        ScopeHeading(me, first = true)
        SettingRow(label = "Speed", value = speedLabel(settings.speedPercent), onClick = preferences::onOpenSpeed, modifier = Modifier.focusMemory("speed"))
        SwitchRow(
            label = "Resume where you left off",
            on = settings.resume,
            onSwitch = { preferences.onSwitch(ProfileSwitch.Resume, it) },
            focusKey = "resume",
        )
        SettingRow(
            label = "Captions",
            hint = "For every video that has them; change them for one video from the player",
            value = captionsLabel(settings.captionsLanguage),
            onClick = preferences::onOpenCaptions,
            modifier = Modifier.focusMemory("captions"),
        )
        SettingRow(
            label = "Live chat",
            hint = "Where a live stream’s chat shows when you open it; change it for one stream from the player",
            value = settings.chatPlacement.label,
            onClick = preferences::onOpenLiveChat,
            modifier = Modifier.focusMemory("live-chat"),
        )
        TvGroup(tv) { focusable ->
            SettingRow(
                label = "Quality",
                hint = "Steps down on its own when the connection can’t keep up",
                value = qualityLabel(device.maxVideoHeight),
                valueMaxWidth = 220.dp,
                focusable = focusable,
                onClick = preferences::onOpenQuality,
                modifier = Modifier.focusMemory("quality"),
            )
            SettingRow(
                label = "Video codec",
                hint = "The one codec Ayn plays for now; it carries 4K and 60 fps on the Shield",
                value = "VP9",
                chevron = false,
                focusable = false,
                onClick = {},
            )
            SwitchRow(
                label = "Match the TV to the video’s frame rate",
                on = device.frameRateMatching,
                onSwitch = { preferences.onSwitch(DeviceSwitch.FrameRateMatching, it) },
                focusKey = "frame-rate",
                hint = "Switches to 24, 25, 50 or 60 Hz while a video plays; restores on exit",
                focusable = focusable,
            )
        }
    }
}

/**
 * Settings › SponsorBlock (`b3-settings` sponsorblock, sboff): the switch, then the sample seek
 * bar, each category's action (OK opens its sheet) and the skip note. With SponsorBlock off they
 * dim and focus passes over them; the choices are kept.
 */
@Composable
private fun SponsorBlockPane(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val on = settings.sponsorBlock
    val categoriesAlpha by animateFloatAsState(if (on) 1f else OFF_ALPHA, KeMotion.base(), label = "sponsorBlockOn")
    SettingsPane("SponsorBlock", sub = null) {
        ScopeLine(me)
        SwitchRow(
            label = "Use SponsorBlock",
            on = on,
            onSwitch = { preferences.onSwitch(ProfileSwitch.SponsorBlock, it) },
            focusKey = "sponsorblock",
            hint = if (on) "Community-submitted segments from sponsor.ajay.app; Ayn sends only the video’s ID" else "Off · your category choices are kept",
        )
        Column(Modifier.graphicsLayer { alpha = categoriesAlpha }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SegmentsSample()
            PaneSubHeading("Categories")
            SegmentCategory.entries.forEach { category ->
                val text = category.text
                SettingRow(
                    label = text.name,
                    hint = text.about,
                    value = settings.segmentActions.getValue(category).label,
                    focusable = on,
                    onClick = { preferences.onOpenCategory(category) },
                    modifier = Modifier.focusMemory("segments-${category.key}"),
                    lead = { CategorySwatch(category.color) },
                )
            }
            SwitchRow(
                label = "Show a note after each skip",
                on = settings.skipNote,
                onSwitch = { preferences.onSwitch(ProfileSwitch.SkipNote, it) },
                focusKey = "skip-note",
                hint = "With Undo, for 6 seconds",
                focusable = on,
            )
        }
    }
}

/**
 * Settings › DeArrow (`b3-settings` dearrow, dearrowtitlesoff, dearrowoff): titles and
 * thumbnails, the credit, the sample, whose DeArrow side follows the switches (Q15).
 */
@Composable
private fun DeArrowPane(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    SettingsPane("DeArrow", sub = null) {
        ScopeLine(me)
        SwitchRow(
            label = "Use DeArrow titles",
            on = settings.dearrowTitles,
            onSwitch = { preferences.onSwitch(ProfileSwitch.DeArrowTitles, it) },
            focusKey = "dearrow-titles",
            hint = "Community-written titles without clickbait; the original is one press away in Details",
        )
        SwitchRow(
            label = "Use DeArrow thumbnails",
            on = settings.dearrowThumbnails,
            onSwitch = { preferences.onSwitch(ProfileSwitch.DeArrowThumbnails, it) },
            focusKey = "dearrow-thumbnails",
            hint = "A frame from the video instead of the uploaded thumbnail",
        )
        PaneNote(
            "Titles and thumbnails come from DeArrow (dearrow.ajay.app), written by its community and shared under " +
                "CC BY-NC-SA 4.0. Ayn sends only the video’s ID.",
            top = 2.dp,
        )
        DeArrowSample(titles = settings.dearrowTitles, thumbnails = settings.dearrowThumbnails)
    }
}

/**
 * Settings › Dislikes (`b3-settings` dislikes, dislikesoff): the switch, the sample counts (dim
 * while it is off, Q15), where they come from.
 */
@Composable
private fun DislikesPane(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val votesAlpha by animateFloatAsState(if (settings.dislikes) 1f else OFF_ALPHA, KeMotion.base(), label = "dislikesOn")
    SettingsPane("Dislikes", sub = null) {
        ScopeLine(me)
        SwitchRow(
            label = "Show dislike counts",
            on = settings.dislikes,
            onSwitch = { preferences.onSwitch(ProfileSwitch.Dislikes, it) },
            focusKey = "dislikes",
            hint = "In a video’s Details, beside the like count",
        )
        VotesSample(Modifier.graphicsLayer { alpha = votesAlpha })
        PaneNote(
            "Dislike counts are estimates from Return YouTube Dislike (returnyoutubedislike.com), built from archived " +
                "data and its users’ votes. Ayn sends only the video’s ID.",
        )
    }
}

/**
 * Settings › Interface (`b3-settings` interface, guestinterface, interface-fy): [me]'s Show Shorts
 * and For you (FY-D10), then the TV's interface size (OK opens its sheet), clock and hero.
 */
@Composable
private fun InterfacePane(me: Profile, settings: ProfileSettings, device: DeviceSettings, tv: TvRows, preferences: PreferencesViewModel) {
    SettingsPane("Interface", sub = null) {
        ScopeHeading(me, first = true)
        SwitchRow(
            label = "Show Shorts",
            on = settings.showShorts,
            onSwitch = { preferences.onSwitch(ProfileSwitch.ShowShorts, it) },
            focusKey = INTERFACE_SHORTS_KEY,
            hint = "Off: Shorts never appear. On: a Shorts tab on channels and a Shorts shelf in Subscriptions and in " +
                "search results; they play in a vertical player",
        )
        SwitchRow(
            label = "For you",
            on = settings.forYou,
            onSwitch = { preferences.onSwitch(ProfileSwitch.ForYou, it) },
            focusKey = "for-you",
            hint = "Picks on Home from what this profile watches and saves, worked out on this Shield. Off: no For you " +
                "on Home and nothing fetched for it",
        )
        TvGroup(tv) { focusable ->
            SettingRow(
                label = "Interface size",
                hint = "Scale everything up or down if text looks too small or too large on this TV",
                value = interfaceSizeLabel(device.interfaceScalePercent),
                focusable = focusable,
                onClick = preferences::onOpenInterfaceSize,
                modifier = Modifier.focusMemory("interface-size"),
            )
            SwitchRow(
                label = "Clock in the player",
                on = device.clockInPlayer,
                onSwitch = { preferences.onSwitch(DeviceSwitch.ClockInPlayer, it) },
                focusKey = "clock",
                focusable = focusable,
            )
            SwitchRow(
                label = "Hero on Home",
                on = device.heroOnHome,
                onSwitch = { preferences.onSwitch(DeviceSwitch.HeroOnHome, it) },
                focusKey = "hero",
                hint = "The large featured video at the top of Home",
                focusable = focusable,
            )
        }
    }
}

/**
 * Settings › About (`b3-settings` about): Ayn and its [version], its licence and the open-source
 * ones (both open the Licences sheet), its source code (a QR sheet), the community data credits (text focus passes over) and
 * that Ayn isn't YouTube's.
 */
@Composable
internal fun AboutPane(version: String?, preferences: PreferencesViewModel) {
    SettingsPane("About", sub = null) {
        SettingRow(
            label = "Ayn",
            hint = "A YouTube client for this Shield; everything it keeps stays here",
            value = version?.let { "Version $it" },
            chevron = false,
            onClick = {},
            modifier = Modifier.focusMemory("ayn"),
            lead = { tones ->
                KMark(
                    plate = remember(tones) { tones.fade(KeColors.Ink, KeColors.Ground) },
                    glyph = remember(tones) { tones.fade(KeColors.Ground, KeColors.Ink) },
                )
            },
        )
        SettingRow(
            label = "Ayn’s licence",
            hint = "Free software: you may use, study, share and change it",
            value = "GPL-3.0-or-later",
            onClick = preferences::onOpenLicences,
            modifier = Modifier.focusMemory("licence"),
        )
        SettingRow(
            label = "Open-source licences",
            hint = "The code, libraries and font Ayn is built from",
            chevron = true,
            onClick = preferences::onOpenLicences,
            modifier = Modifier.focusMemory("open-source"),
        )
        SettingRow(
            label = "Source code",
            hint = "$SOURCE_CODE_SHOWN · read, build and change Ayn",
            chevron = true,
            onClick = preferences::onOpenSourceCode,
            modifier = Modifier.focusMemory("source"),
        )
        PaneSubHeading("Community data")
        Credit("SponsorBlock", "Skip segments from sponsor.ajay.app · CC BY-NC-SA 4.0", R.drawable.ic_shield)
        Credit("DeArrow", "Titles and thumbnails from dearrow.ajay.app · CC BY-NC-SA 4.0", R.drawable.ic_edit)
        Credit("Return YouTube Dislike", "Dislike estimates from returnyoutubedislike.com", R.drawable.ic_thumb_down)
        PaneNote("Ayn isn’t made by, or affiliated with, YouTube or Google.")
    }
}

/** A community data credit: a row's look, but text focus passes over. */
@Composable
private fun Credit(label: String, hint: String, @DrawableRes icon: Int) {
    SettingRow(label = label, hint = hint, focusable = false, onClick = {}, lead = { RowIcon(icon, it) })
}
