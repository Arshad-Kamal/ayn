package com.arshad.ayn.ui.mobile.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.plural
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.ui.mobile.shell.SettingsPage

// The phone's Settings copy (`m6-settings` v1); text both UIs show: feature/settings/*Text.kt.

internal const val THIS_PHONE = "This phone"
internal const val EACH_PROFILE = " · each profile sets its own"

/** Who changes This phone's rows, for anyone else (Q17). */
internal fun ownerOnly(ownerName: String): String = "Only the owner, $ownerName, can change these"

/** What an action does, under its name in a category's sheet. */
internal val SegmentAction.consequence: String
    get() = when (this) {
        SegmentAction.Skip -> "Jumps past it and shows a note with Undo"
        SegmentAction.Button -> "You choose, with one tap"
        SegmentAction.Bar -> "Marked in colour, never skipped"
        SegmentAction.Off -> "Not fetched or shown"
    }

/** What a Live chat choice does, under its name in the sheet, where it says something: over the
 *  picture applies sideways (LIVE-D22, Q14). */
internal val ChatPlacement.consequence: String?
    get() = when (this) {
        ChatPlacement.Beside -> "Upright it’s always under the picture; chat can be scrolled back"
        ChatPlacement.Side -> "Sideways: the video stays full screen; chat floats over it"
        ChatPlacement.TopLeft, ChatPlacement.TopRight, ChatPlacement.BottomLeft, ChatPlacement.BottomRight -> null
    }

/** A category's name, its row's and its page's; [owner] picks Backup & restore over Import from YouTube. */
internal fun SettingsPage.title(owner: Boolean): String = when (this) {
    SettingsPage.Profiles -> "Profiles"
    SettingsPage.Filters -> "Filters"
    SettingsPage.Playback -> "Playback"
    SettingsPage.SponsorBlock -> "SponsorBlock"
    SettingsPage.DeArrow -> "DeArrow"
    SettingsPage.Dislikes -> "Dislikes"
    SettingsPage.Interface -> "Interface"
    SettingsPage.Sync -> "Sync"
    SettingsPage.Backup -> if (owner) "Backup & restore" else "Import from YouTube"
    SettingsPage.About -> "About"
}

/** Where filters apply (KP-D1), at the foot of Filters; the owner's adds where restrictions are set. */
internal fun filtersReach(owner: Boolean): String =
    "Filters apply to Home, Search, channels, playlists, Up next and your history. A video they hide won’t play, however you reach it." +
        if (owner) " To set filters another profile can’t turn off, choose it in Profiles." else ""

/** Whose list a blocked sheet keeps, on the profile's own Filters. */
internal const val YOUR_PROFILE = "your profile"

/** The Keywords sheet, its [note] naming whose list it is. */
internal fun keywordsCopy(note: String): RuleListCopy =
    RuleListCopy("Keywords", "A video is hidden when its title uses one of these words. Tap Remove to take one off.", "Add a word", note)

internal fun setByOwner(ownerName: String): String = "Set by $ownerName, the owner"

internal fun onlyOwnerChanges(ownerName: String): String = "Only $ownerName can change these. They apply on top of yours."

/** Restricted Mode's hint while the owner's Strict is on over the profile's own Off. */
internal fun ownerStrictHint(ownerName: String): String = "Strict applies while $ownerName’s restriction is on"

/** Show Shorts' hint on Filters: [ownerName] when the owner keeps Shorts off for this profile. */
internal fun shortsLinkHint(ownerName: String?): String =
    "Shorts have their own switch, in Interface" + (ownerName?.let { "; $it keeps them off for you" } ?: "")

internal fun restrictionsTitle(name: String): String = "$name’s restrictions"

internal fun restrictionsLede(name: String): String = "They add to $name’s own filters: a video either one hides stays hidden."

internal fun restrictionsNoticeLead(name: String): String = "Set by you, the owner. $name can’t turn these off."

/** The owner's lock before restrictions open (state 32). */
internal fun restrictionsGateHint(kind: LockKind): String = "Only the owner sets restrictions · ${erasesHint(kind)} · Back cancels"

/** The header's line under the profile's name (state 1). */
internal fun versionLine(version: String?): String = listOfNotNull(version?.let { "Ayn $it" }, "on this phone").joinToString(" · ")

// Each index row's line says what its page holds now (state 1, Q53: undrawn cases follow the drawn
// pattern; nothing to say, no line).

internal fun profilesSummary(owner: Boolean, profiles: Int, ownerName: String?): String? = when {
    owner -> "${plural(profiles, "profile")} · you’re the owner"
    else -> ownerName?.let { "$it is the owner" }
}

internal fun filtersSummary(settings: ProfileSettings, keywords: Int): String =
    listOfNotNull("Restricted Mode ${settings.restrictedMode.label.lowercase()}", keywords.takeIf { it > 0 }?.let { plural(it, "keyword") })
        .joinToString(" · ")

internal fun playbackSummary(settings: ProfileSettings, device: DeviceSettings): String {
    val captions = settings.captionsLanguage?.let { "${captionsLabel(it)} captions" } ?: "captions off"
    return "${speedLabel(settings.speedPercent)} speed · $captions · background audio ${if (device.backgroundAudio) "on" else "off"}"
}

internal fun sponsorBlockSummary(settings: ProfileSettings): String {
    if (!settings.sponsorBlock) return "Off"
    val sponsors = when (settings.segmentActions[SegmentCategory.Sponsor]) {
        SegmentAction.Skip -> "sponsors are skipped"
        SegmentAction.Button -> "sponsors get a skip button"
        SegmentAction.Bar -> "sponsors show on the seek bar"
        SegmentAction.Off, null -> "sponsors aren’t shown"
    }
    return "On · $sponsors"
}

internal fun deArrowSummary(settings: ProfileSettings): String = when {
    settings.dearrowTitles && settings.dearrowThumbnails -> "Titles and thumbnails"
    settings.dearrowTitles -> "Titles"
    settings.dearrowThumbnails -> "Thumbnails"
    else -> "Off"
}

internal fun dislikesSummary(settings: ProfileSettings): String = if (settings.dislikes) "Shown in Details" else "Off"

internal fun interfaceSummary(settings: ProfileSettings, device: DeviceSettings): String = listOfNotNull(
    "Shorts ${if (settings.showShorts) "on" else "off"}",
    "For you ${if (settings.forYou) "on" else "off"}",
    "Hero on Home".takeIf { device.heroOnHome },
).joinToString(" · ")

internal fun backupSummary(backups: Int): String? = backups.takeIf { it > 0 }?.let { "${plural(it, "backup")} on this phone" }

internal fun aboutSummary(version: String?): String = listOfNotNull(version?.let { "Ayn $it" }, "GPL-3.0-or-later").joinToString(" · ")
