package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.feature.sameLanguage

/**
 * Settings' preference pages (`b3-settings`): [profile] is the session profile's settings (each
 * profile its own), [device] the TV's (everyone's); each is `null` until it loads. [ownerId] and
 * [ownerName] are the device owner's, `null` until the profiles load. [sheet] is what shows over
 * the page.
 */
data class PreferencesUiState(
    val profile: ProfileSettingsState? = null,
    val device: DeviceSettings? = null,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val sheet: PreferencesSheet? = null,
) {
    /** Only the owner changes the TV's settings (Q17); anyone else sees them dimmed. */
    val changesTheTv: Boolean get() = profile != null && profile.profileId == ownerId
}

/** What shows over a preference page. */
sealed interface PreferencesSheet {
    /** Playback › Speed: the speed every video starts at. */
    data object Speed : PreferencesSheet

    /** Playback › Captions: Off or a language, for every video. */
    data object Captions : PreferencesSheet

    /** Playback › Captions › All languages: every language the platform names. */
    data object AllLanguages : PreferencesSheet

    /** Playback › Live chat: where a live stream's chat shows when opened. */
    data object LiveChat : PreferencesSheet

    /** Playback › Quality: the highest the TV plays (the owner's). */
    data object Quality : PreferencesSheet

    /** Interface › Interface size: how large everything shows on the TV (the owner's). */
    data object InterfaceSize : PreferencesSheet

    /** SponsorBlock › a category: what the player does with its segments. */
    data class Category(val category: SegmentCategory) : PreferencesSheet

    /** About › the licences Ayn and its parts are under. */
    data object Licences : PreferencesSheet

    /** About › Licences › [part]'s licence, in full ([part] is the part's name in the list). */
    data class LicenceText(val part: String) : PreferencesSheet

    /** About › Source code: the public repository, as a code to scan (a TV has no browser). */
    data object SourceCode : PreferencesSheet
}

/** A profile's on/off settings. */
enum class ProfileSwitch { Resume, SponsorBlock, SkipNote, DeArrowTitles, DeArrowThumbnails, Dislikes, ShowShorts, ForYou }

/** The device's on/off settings (Background audio: the phone's, M6-D8). */
enum class DeviceSwitch { FrameRateMatching, ClockInPlayer, HeroOnHome, BackgroundAudio }

/** A heading over some of the captions sheet's languages (Q16). */
enum class CaptionsGroup { Gaelic, Indigenous }

/** Some of the captions sheet's languages, by language tag, under [group] (`null`: the ones leading it). */
data class CaptionsSection(val group: CaptionsGroup?, val tags: List<String>)

// The captions languages offered (Q16): English, Bengali and Arabic lead, then
// Irish and Scottish Gaelic, then Indigenous languages of Canada (Plains Cree, Blackfoot, Dene,
// Stoney Nakoda, Ojibwe, Inuktitut). Any other language is a pick from All languages.
private val LEADING_LANGUAGES: List<String> = listOf("en", "bn", "ar")
private val GROUPED_LANGUAGES: List<Pair<CaptionsGroup, List<String>>> = listOf(
    CaptionsGroup.Gaelic to listOf("ga", "gd"),
    CaptionsGroup.Indigenous to listOf("crk", "bla", "chp", "sto", "oj", "iu"),
)
private val OFFERED_LANGUAGES: List<String> = LEADING_LANGUAGES + GROUPED_LANGUAGES.flatMap { it.second }

/**
 * The captions sheet's languages: the TV's language ([deviceLanguage], an ISO 639 code; blank
 * when the TV has none, and anything else is left out, as a stored choice must be a language
 * tag) first, taken from wherever it is offered; a [chosen] language that isn't offered (a pick
 * from All languages) follows it, so the sheet always shows what the row says; then the leading
 * languages and the groups. A group left empty isn't shown.
 */
fun captionsChoices(deviceLanguage: String, chosen: String?): List<CaptionsSection> {
    val device = OFFERED_LANGUAGES.firstOrNull { sameLanguage(it, deviceLanguage) }
        ?: deviceLanguage.takeIf { ISO_639.matches(it) }
    val taken = setOfNotNull(device)
    val extra = chosen?.takeIf { it != device && it !in OFFERED_LANGUAGES }
    val lead = CaptionsSection(group = null, tags = listOfNotNull(device, extra) + (LEADING_LANGUAGES - taken))
    val groups = GROUPED_LANGUAGES.map { (group, tags) -> CaptionsSection(group, tags - taken) }
    return (listOf(lead) + groups).filter { it.tags.isNotEmpty() }
}

private val ISO_639 = Regex("[A-Za-z]{2,3}")
