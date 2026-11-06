package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.settings.DeviceSwitch
import com.arshad.ayn.feature.settings.Licence
import com.arshad.ayn.feature.settings.unbroken
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfileSwitch
import com.arshad.ayn.feature.settings.SOURCE_CODE_SHOWN
import com.arshad.ayn.feature.settings.SOURCE_CODE_URL
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.qualityChoice
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.feature.settings.text
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.player.color
import com.arshad.ayn.ui.mobile.shell.SettingsPage

/**
 * One of [me]'s preference pages (`m6-settings` 2–16): Playback, SponsorBlock, DeArrow, Dislikes,
 * Interface or About, with their sheets over them. The rows wait until the profile's and this
 * phone's settings and who the owner is have loaded (About needs none of them).
 */
@Composable
internal fun PreferencesPage(page: SettingsPage, me: Profile, preferences: PreferencesViewModel, onBack: () -> Unit) {
    val state by preferences.uiState.collectAsStateWithLifecycle()
    SettingsFrame(page.title(me.isOwner), onBack) {
        if (page == SettingsPage.About) {
            aboutRows(preferences)
            return@SettingsFrame
        }
        val settings = state.profile?.settings ?: return@SettingsFrame
        val device = state.device ?: return@SettingsFrame
        val phone = PhoneRows(state.changesTheTv, state.ownerName ?: return@SettingsFrame)
        when (page) {
            SettingsPage.Playback -> playbackRows(me, settings, device, phone, preferences)
            SettingsPage.SponsorBlock -> sponsorBlockRows(me, settings, preferences)
            SettingsPage.DeArrow -> deArrowRows(me, settings, preferences)
            SettingsPage.Dislikes -> dislikesRows(me, settings, preferences)
            SettingsPage.Interface -> interfaceRows(me, settings, device, phone, preferences)
            SettingsPage.About, SettingsPage.Profiles, SettingsPage.Filters, SettingsPage.Sync, SettingsPage.Backup -> error("not a preference page: $page")
        }
    }
    PreferencesSheets(state, me, preferences)
}

/** Whether this profile [changes] This phone's settings (the owner does, Q17), and who does. */
private class PhoneRows(val changes: Boolean, val ownerName: String)

/** This phone's group: its scope, for anyone but the owner who changes it, then [rows] dimmed and
 *  out of reach unless this profile [PhoneRows.changes] them. */
private fun LazyListScope.phoneGroup(phone: PhoneRows, rows: @Composable (enabled: Boolean) -> Unit) {
    item(key = "phone") {
        GroupLine()
        PhoneScope()
        if (!phone.changes) OwnerOnlyLine(ownerOnly(phone.ownerName))
        Dimmed(lit = phone.changes) { rows(phone.changes) }
    }
}

/** Playback (states 2–4, live-chat): [me]'s speed, resume, captions and live chat, then This phone's quality and background audio. */
private fun LazyListScope.playbackRows(me: Profile, settings: ProfileSettings, device: DeviceSettings, phone: PhoneRows, preferences: PreferencesViewModel) {
    item(key = "scope") { ProfileScope(me) }
    item(key = "speed") { SettingRow("Speed", preferences::onOpenSpeed, value = speedLabel(settings.speedPercent)) }
    item(key = "resume") {
        SwitchRow("Resume where you left off", settings.resume, { preferences.onSwitch(ProfileSwitch.Resume, it) })
    }
    item(key = "captions") {
        SettingRow(
            "Captions",
            preferences::onOpenCaptions,
            hint = "For every video that has them; change them for one video from the player",
            value = captionsLabel(settings.captionsLanguage),
        )
    }
    item(key = "live-chat") {
        SettingRow(
            "Live chat",
            preferences::onOpenLiveChat,
            hint = "Where a live stream’s chat shows when you open it; change it for one stream from the player",
            value = settings.chatPlacement.label,
        )
    }
    phoneGroup(phone) { enabled ->
        SettingRow(
            "Quality",
            preferences::onOpenQuality,
            hint = "Steps down on its own when the connection can’t keep up",
            value = qualityChoice(device.maxVideoHeight),
            enabled = enabled,
        )
        SwitchRow(
            "Background audio",
            device.backgroundAudio,
            { preferences.onSwitch(DeviceSwitch.BackgroundAudio, it) },
            hint = if (device.backgroundAudio) {
                "Keeps playing when you leave Ayn or lock the phone, with controls on the lock screen and in the notification shade"
            } else {
                "Off: leaving Ayn or locking the phone pauses the video"
            },
            enabled = enabled,
        )
    }
    item(key = "foot") {
        PageFoot(
            if (device.backgroundAudio) {
                "This phone’s settings are the same for every profile here. Kids profiles always pause when Ayn leaves the screen."
            } else {
                "Kids profiles always pause when Ayn leaves the screen, so their daily limit counts only what they see."
            },
        )
    }
}

/** SponsorBlock (states 9–11): the switch, then the sample, each category's action and the skip
 *  note, dimmed and out of reach while it is off (the choices are kept). */
private fun LazyListScope.sponsorBlockRows(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    val on = settings.sponsorBlock
    item(key = "scope") { ProfileScope(me, eachOwn = true) }
    item(key = "switch") {
        SwitchRow(
            "Use SponsorBlock",
            on,
            { preferences.onSwitch(ProfileSwitch.SponsorBlock, it) },
            hint = if (on) "Community-submitted segments from sponsor.ajay.app; Ayn sends only the video’s ID" else "Off · your category choices are kept",
        )
    }
    item(key = "categories") {
        Dimmed(lit = on) {
            SegmentsSample()
            SubHeading("Categories")
            SegmentCategory.entries.forEach { category ->
                val text = category.text
                SettingRow(
                    label = text.name,
                    onClick = { preferences.onOpenCategory(category) },
                    hint = text.about,
                    value = settings.segmentActions.getValue(category).label,
                    chevron = false,
                    enabled = on,
                    lead = { Swatch(category.color) },
                )
            }
            SwitchRow(
                "Show a note after each skip",
                settings.skipNote,
                { preferences.onSwitch(ProfileSwitch.SkipNote, it) },
                hint = "With Undo, for 6 seconds",
                enabled = on,
            )
        }
    }
}

/** DeArrow (state 12): titles and thumbnails, the sample following them, the credit. */
private fun LazyListScope.deArrowRows(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    item(key = "scope") { ProfileScope(me, eachOwn = true) }
    item(key = "titles") {
        SwitchRow(
            "Use DeArrow titles",
            settings.dearrowTitles,
            { preferences.onSwitch(ProfileSwitch.DeArrowTitles, it) },
            hint = "Community-written titles without clickbait; the original is a tap away in Details",
        )
    }
    item(key = "thumbnails") {
        SwitchRow(
            "Use DeArrow thumbnails",
            settings.dearrowThumbnails,
            { preferences.onSwitch(ProfileSwitch.DeArrowThumbnails, it) },
            hint = "A frame from the video instead of the uploaded thumbnail",
        )
    }
    item(key = "sample") { DeArrowSample(titles = settings.dearrowTitles, thumbnails = settings.dearrowThumbnails) }
    item(key = "foot") {
        PageFoot(
            "Titles and thumbnails come from DeArrow (dearrow.ajay.app), written by its community and shared under CC BY-NC-SA 4.0. " +
                "Ayn sends only the video’s ID.",
        )
    }
}

/** Dislikes (state 13): the switch, the sample counts (dim while it is off), the credit. */
private fun LazyListScope.dislikesRows(me: Profile, settings: ProfileSettings, preferences: PreferencesViewModel) {
    item(key = "scope") { ProfileScope(me, eachOwn = true) }
    item(key = "switch") {
        SwitchRow("Show dislike counts", settings.dislikes, { preferences.onSwitch(ProfileSwitch.Dislikes, it) }, hint = "In a video’s Details, beside the like count")
    }
    item(key = "sample") { Dimmed(lit = settings.dislikes) { VotesSample() } }
    item(key = "foot") {
        PageFoot(
            "Dislike counts are estimates from Return YouTube Dislike (returnyoutubedislike.com), built from archived data and its users’ " +
                "votes. Ayn sends only the video’s ID.",
        )
    }
}

/** Interface (state 8): [me]'s Show Shorts and For you, then This phone's Hero on Home and clock
 *  (no Interface size: text size is the phone's, Q48). */
private fun LazyListScope.interfaceRows(me: Profile, settings: ProfileSettings, device: DeviceSettings, phone: PhoneRows, preferences: PreferencesViewModel) {
    item(key = "scope") { ProfileScope(me) }
    item(key = "shorts") {
        SwitchRow(
            "Show Shorts",
            settings.showShorts,
            { preferences.onSwitch(ProfileSwitch.ShowShorts, it) },
            hint = "Off: Shorts never appear. On: a Shorts tab on channels and a Shorts shelf in Subscriptions and in search results; " +
                "they play in a vertical player",
        )
    }
    item(key = "foryou") {
        SwitchRow(
            "For you",
            settings.forYou,
            { preferences.onSwitch(ProfileSwitch.ForYou, it) },
            hint = "Picks on Home from what this profile watches and saves, worked out on this phone. Off: no For you on Home and " +
                "nothing fetched for it",
        )
    }
    phoneGroup(phone) { enabled ->
        SwitchRow(
            "Hero on Home",
            device.heroOnHome,
            { preferences.onSwitch(DeviceSwitch.HeroOnHome, it) },
            hint = "The large featured video at the top of Home. Off: Home opens on its rows, and the newest upload leads New from " +
                "your subscriptions",
            enabled = enabled,
        )
        SwitchRow(
            "Clock in the player",
            device.clockInPlayer,
            { preferences.onSwitch(DeviceSwitch.ClockInPlayer, it) },
            hint = "The time beside the quality badge when the phone is sideways",
            enabled = enabled,
        )
    }
    item(key = "foot") { PageFoot("Text and display size follow the phone’s own settings (Settings › Display).") }
}

/** About (state 14): Ayn and its version, its licence and the open-source ones (both open
 *  Licences), its source code (the browser opens the repo), the community data's credits and that Ayn isn't YouTube's. */
private fun LazyListScope.aboutRows(preferences: PreferencesViewModel) {
    item(key = "ayn") {
        val version = rememberVersionName()
        SettingRow(
            "Ayn",
            onClick = null,
            hint = "A YouTube client for this phone; everything it keeps stays here",
            value = version?.let { "Version $it" },
            chevron = false,
            lead = { AboutMark() },
        )
    }
    item(key = "licence") {
        SettingRow("Ayn’s licence", preferences::onOpenLicences, hint = "Free software: you may use, study, share and change it", value = unbroken(Licence.Gpl3OrLater.id))
    }
    item(key = "open") { SettingRow("Open-source licences", preferences::onOpenLicences, hint = "The code, libraries and font Ayn is built from") }
    item(key = "source") {
        val browser = LocalUriHandler.current
        SettingRow(
            "Source code",
            { browser.tryOpen(SOURCE_CODE_URL) },
            hint = "$SOURCE_CODE_SHOWN · read, build and change Ayn",
            chevronIcon = R.drawable.ic_open,
        )
    }
    item(key = "community") {
        SubHeading("Community data")
        TextRow("SponsorBlock", "Skip segments from sponsor.ajay.app · CC BY-NC-SA 4.0")
        TextRow("DeArrow", "Titles and thumbnails from dearrow.ajay.app · CC BY-NC-SA 4.0")
        TextRow("Return YouTube Dislike", "Dislike estimates from returnyoutubedislike.com")
    }
    item(key = "foot") { PageFoot("Ayn isn’t made by, or affiliated with, YouTube or Google.") }
}
