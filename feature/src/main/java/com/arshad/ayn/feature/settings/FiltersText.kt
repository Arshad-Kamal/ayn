package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.filter.packs.HarmfulForKids
import com.arshad.ayn.core.filter.packs.IslamicGroup
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.MAX_PATTERNS
import com.arshad.ayn.core.store.MAX_RULES_PER_KIND
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SelfRule

// Filters and restrictions text both UIs show (`kp-filters` v2, `m6-settings` v1).

val RestrictedMode.label: String
    get() = when (this) {
        RestrictedMode.Off -> "Off"
        RestrictedMode.Strict -> "Strict"
    }

/** Hide Shorts' hint, the owner's restriction (and the suggestion that sets it). */
const val HIDE_SHORTS_HINT = "Shorts stay off whatever Interface says"

/** Hide live streams' hint. */
const val HIDE_LIVE_HINT = "Live and upcoming streams"

/** A length choice: "1 min", "60 min", "2 hours". */
fun minutesLabel(minutes: Int): String =
    if (minutes >= 120 && minutes % 60 == 0) "${minutes / 60} hours" else "$minutes min"

/** Video length's value: "Any length", "Under 3 min hidden", "Over 30 min hidden", or both. */
fun lengthLabel(limits: LengthLimits): String {
    val min = limits.minMinutes
    val max = limits.maxMinutes
    return when {
        min != null && max != null -> "Under ${minutesLabel(min)}, over ${minutesLabel(max)} hidden"
        min != null -> "Under ${minutesLabel(min)} hidden"
        max != null -> "Over ${minutesLabel(max)} hidden"
        else -> "Any length"
    }
}

/** A count of blocked items: "None", "3". */
fun countLabel(count: Int): String = if (count == 0) "None" else "$count"

/** A count of keywords: "None", "1 word", "4 words". */
fun wordsLabel(count: Int): String = if (count == 0) "None" else plural(count, "word")

/** Why a word wasn't added, under its field; [whose] names the profile when the owner sets it
 *  for someone else. */
fun keywordProblemText(problem: RuleProblem, word: String, whose: String? = null): String = when (problem) {
    RuleProblem.AlreadyThere -> "“${word.trim()}” is already on the list."
    RuleProblem.Full -> "${whose?.let { "$it has" } ?: "You have"} $MAX_RULES_PER_KIND words, the most a profile keeps. Remove one to add another."
    is RuleProblem.Invalid, RuleProblem.Failed -> "Ayn couldn’t save the word. Try again."
}

/** Why a pattern wasn't added (`patternbad`), under its field; [whose] is the profile's name. */
fun patternProblemText(problem: RuleProblem, whose: String): String = when (problem) {
    RuleProblem.AlreadyThere -> "That pattern is already on the list."
    RuleProblem.Full -> "$whose has $MAX_PATTERNS patterns, the most a profile keeps. Remove one to add another."
    is RuleProblem.Invalid -> "This pattern isn’t valid: ${problem.description}${problem.near?.let { " near “$it”" }.orEmpty()}. Nothing was saved."
    RuleProblem.Failed -> "Ayn couldn’t save the pattern. Try again."
}

/** The restrictions notice's second part: which profiles open without a lock (the owner's own is
 *  "your profile"); empty when every other profile is locked. */
fun restrictionsHole(name: String, unlocked: List<Profile>): String {
    if (unlocked.isEmpty()) return ""
    val names = unlocked.map { if (it.isOwner) "your profile" else it.name }
    val verb = if (names.size == 1) "has" else "have"
    return "They hold only while every other profile is locked: ${spokenList(names)} $verb no lock, so $name could switch to " +
        (if (names.size == 1) "it." else "them.")
}

/** A blocked row's hint tail when the owner also blocked it in [others] (`kp-filters` channels,
 *  unblockall): " · also in Mira and Yuri"; empty for none. */
fun alsoInTail(others: List<Profile>): String = if (others.isEmpty()) "" else " · also in ${spokenList(others.map { it.name })}"

/** Unblock where? (`kp-filters` unblockall, `m6-settings` 21b): the words for unblocking [rule]
 *  (a blocked channel or video), which the owner also blocked in [others]. */
class UnblockWhereCopy(rule: SelfRule, owner: Profile, others: List<Profile>) {
    private val names = spokenList(others.map { it.name })
    val kicker: String = if (rule is BlockedVideo) "Blocked videos" else "Blocked channels"
    val title: String = "Unblock ${blockedName(rule)}?"
    val sub: String = "You blocked it in $names too."
    val everywhere: String = "Unblock in all profiles"
    val everywhereHint: String = spokenList(listOf(owner.name) + others.map { it.name })
    val hereOnly: String = "Only in your profile"
    val hereOnlyHint: String = "$names keep${if (others.size == 1) "s" else ""} it blocked"
}

/** A blocked channel's or video's name as its list row shows it (the id when the name is unknown). */
fun blockedName(rule: SelfRule): String = when (rule) {
    is BlockedChannel -> rule.name ?: rule.channelUrl
    is BlockedVideo -> rule.title ?: rule.videoId
    is Keyword -> rule.word
}

// Filter packs (`kp-filters` fpk-*, `m6-settings` packs; FPK-D1–D15).

/** The heading over the pack rows. */
const val FILTER_PACKS = "Filter packs"

val FilterPack.label: String
    get() = when (this) {
        FilterPack.HarmfulForKids -> "Harmful for kids"
        FilterPack.HalalOnly -> "Halal only"
        FilterPack.IslamicOnly -> "Islamic content only"
    }

/** A pack row's hint: what the pack hides or keeps. */
val FilterPack.hint: String
    get() = when (this) {
        FilterPack.HarmfulForKids -> "Pranks, unboxing, AI cartoons, kidfluencer channels, horror for kids"
        FilterPack.HalalOnly -> "Hides music and haram topics; everything else stays"
        FilterPack.IslamicOnly -> "Only Qur’an, nasheed, lecture and children’s channels from a list"
    }

/** A pack row's hint on a profile's own Filters: the owner's name while the owner keeps [pack] on
 *  for it (FPK-D15, [ownerKeeping]), else what the pack does. */
fun ownPackHint(pack: FilterPack, ownerKeeping: String?): String = ownerKeeping?.let { "$it keeps it on for you" } ?: pack.hint

/** A pack row's hint on the owner's restrictions for a profile: Harmful for kids on a [kids]
 *  profile says it starts on (FPK-D1), else what the pack does. */
fun restrictionPackHint(pack: FilterPack, kids: Boolean): String =
    if (kids && pack == FilterPack.HarmfulForKids) "On for every new kids profile" else pack.hint

/** A pack row's value: "Off", "On", or for Islamic content only "On · 11 channels", the channels
 *  its switches turn on for a kids profile or another. */
fun packValue(packs: FilterPacks, pack: FilterPack, kids: Boolean): String = when {
    !packs.isOn(pack) -> "Off"
    pack == FilterPack.IslamicOnly -> "On · ${plural(packs.enabledIslamicChannels(kids).size, "channel")}"
    else -> "On"
}

/** Under the Filter packs rows (FPK-D2, D4). */
const val PACKS_NOTE = "Packs come with Ayn and are updated with each release. Halal only and Islamic content only can’t both be on."

const val USE_PACK = "Use this pack"

/** Use this pack's hint while [pack] is off and the other of Halal only and Islamic content only
 *  is on, which turning it on turns off (FPK-D2); `null` otherwise. */
fun usePackHint(pack: FilterPack, packs: FilterPacks): String? {
    val other = when (pack) {
        FilterPack.HalalOnly -> FilterPack.IslamicOnly
        FilterPack.IslamicOnly -> FilterPack.HalalOnly
        FilterPack.HarmfulForKids -> return null
    }
    return if (!packs.isOn(pack) && packs.isOn(other)) "Turns ${other.label} off: one of the two at a time" else null
}

val PackOption.label: String
    get() = when (this) {
        PackOption.Overstimulating -> "Overstimulating"
        PackOption.ChallengesAndVlogs -> "Challenges and vlogs"
        PackOption.StageMagic -> "Stage magic"
        PackOption.Festivals -> "Other religions’ festivals"
        PackOption.LootBoxes -> "Loot boxes and gacha"
    }

val PackOption.hint: String
    get() = when (this) {
        PackOption.Overstimulating -> "Cocomelon-style: Cocomelon, ChuChu TV, Pinkfong, BabyBus. Pace isn’t shown to harm; a family choice"
        PackOption.ChallengesAndVlogs -> "Money-prize stunts, family vlogs, hours-long sleep videos. Can hide Umrah vlogs too"
        PackOption.StageMagic -> "Magic tricks, illusionists, Harry Potter"
        PackOption.Festivals -> "Christmas, Easter, Diwali, Holi, Valentine’s Day"
        PackOption.LootBoxes -> "Paid random rewards in games"
    }

/** A heading in a pack's sheet, [sub] after it in a lighter weight. */
data class PackHeading(val text: String, val sub: String? = null)

/** A line in a pack's sheet: [lead] in bold with its own punctuation, then a space and [text]. */
data class PackLine(val text: String, val lead: String? = null)

/** What a pack always hides or keeps, under its [heading]. */
class PackList(val heading: PackHeading, val lines: List<PackLine>)

val IslamicGroup.heading: PackHeading
    get() = when (this) {
        IslamicGroup.Quran -> PackHeading("Qur’an")
        IslamicGroup.Nasheeds -> PackHeading("Nasheeds", "voice only")
        IslamicGroup.Children -> PackHeading("For children")
        IslamicGroup.Lectures -> PackHeading("Lectures")
    }

/**
 * A pack's sheet (`kp-filters` fpk-harmful, fpk-halal, fpk-islamic, fpk-z-*): [sub] under its
 * title, the [lists] of what it always hides or keeps, [optionsHeading] over its options, the
 * [approvals] line on a kids profile's restrictions (FPK-D13, D14), and the [note] at its foot.
 * [target] is the profile the owner restricts, `null` on the session profile's own Filters.
 */
class PackSheetCopy(pack: FilterPack, target: Profile?) {
    val title: String = pack.label

    val sub: String = when (pack) {
        FilterPack.HarmfulForKids -> "Channels and title rules for videos made to hook children or sell to them."
        FilterPack.HalalOnly -> "Hides music and what’s haram to watch. Videos about anything else stay."
        FilterPack.IslamicOnly -> if (target?.isKids == true) {
            "Only videos from these channels and the ones you approve show in ${profileScope(target.name)}. Set for children: lectures start off."
        } else {
            "Only videos from these channels show in ${target?.let { profileScope(it.name) } ?: "your profile"}. The list comes with Ayn; turn off any you don’t want."
        }
    }

    val lists: List<PackList> = when (pack) {
        FilterPack.HarmfulForKids -> listOf(
            PackList(
                PackHeading("Always hidden", "${plural(HarmfulForKids.core.channelIds.size, "channel")}, ${plural(HarmfulForKids.core.titleRules.size, "title rule")}"),
                listOf(
                    PackLine("Pranks and “gone wrong” videos"),
                    PackLine("Toy unboxing and surprise eggs"),
                    PackLine("AI-made and Elsagate-style cartoons"),
                    PackLine("Kidfluencer channels: Vlad and Niki, Kids Diana Show, Like Nastya"),
                    PackLine("Horror for kids: Huggy Wuggy, Poppy Playtime"),
                    PackLine("Skibidi and “Italian brainrot”"),
                ),
            ),
        )
        FilterPack.HalalOnly -> listOf(
            PackList(
                PackHeading("Always hidden"),
                listOf(
                    PackLine("record labels, “ - Topic” and VEVO channels, music-video titles", lead = "Music:"),
                    PackLine("Explicit content, dating and LGBTQ themes"),
                    PackLine("Gambling, alcohol and drugs"),
                    PackLine("Occult, astrology and horror"),
                    PackLine("Strong swearing and Halloween"),
                ),
            ),
            PackList(
                PackHeading("Always kept"),
                listOf(PackLine("Nasheeds and vocal-only versions"), PackLine("Lectures on these topics: ruqyah, the rulings on music")),
            ),
        )
        FilterPack.IslamicOnly -> emptyList()
    }

    val optionsHeading: PackHeading? = when (pack) {
        FilterPack.HarmfulForKids -> PackHeading("Also hide")
        FilterPack.HalalOnly -> PackHeading("Scholars differ", "off unless you turn them on")
        FilterPack.IslamicOnly -> null
    }

    val approvals: PackLine? = target?.takeIf { it.isKids }?.let { kid ->
        when (pack) {
            FilterPack.HarmfulForKids -> PackLine(
                "Videos from channels you approved still go through this pack: a prank video from an approved channel stays hidden.",
                lead = "Videos you approved one by one always play.",
            )
            FilterPack.IslamicOnly -> PackLine(
                "their new videos show on ${kid.name}’s Home and in Search, beside the channels you approved.",
                lead = "These count as approved for ${kid.name}:",
            )
            FilterPack.HalalOnly -> null
        }
    }

    val note: String = when (pack) {
        FilterPack.HarmfulForKids -> "Ayn reads titles and channel names, so it misses some videos and hides a few that are fine."
        FilterPack.HalalOnly -> "Ayn reads titles and channel names, so some music gets through and a few videos that are fine are hidden. " +
            "Cartoons and pictures aren’t filtered: scholars differ on them."
        FilterPack.IslamicOnly -> "${IslamicOnly.channels.size} channels in all, by channel ID: a lookalike or copy channel never counts. " +
            "A channel is shown only by its own ID, so new uploads come in as they’re posted."
    }
}

// Also turn these on? (`kp-filters` fpk-suggest; FPK-D7).

const val SUGGESTIONS_KICKER = "Harmful for kids is on"
const val SUGGESTIONS_TITLE = "Also turn these on?"
const val SUGGESTIONS_SUB =
    "Shorts and live streams have no natural end, and long videos run on. Limits like these help children more than any list."
const val NOT_NOW = "Not now"

val Suggestion.label: String
    get() = when (this) {
        Suggestion.HideShorts -> "Hide Shorts"
        Suggestion.HideLive -> "Hide live streams"
        Suggestion.HideLongerThan30 -> "Hide videos longer than ${minutesLabel(SUGGESTED_LONGER_THAN_MINUTES)}"
    }

/** A suggestion's hint; [restriction] when the owner sets it on another profile. */
fun suggestionHint(suggestion: Suggestion, restriction: Boolean): String = when (suggestion) {
    Suggestion.HideShorts -> if (restriction) HIDE_SHORTS_HINT else "Turns off Show Shorts in Interface"
    Suggestion.HideLive -> HIDE_LIVE_HINT
    Suggestion.HideLongerThan30 -> "Video length; live streams aren’t affected"
}

/** The row that sets the [ticked] suggestions: "Turn on 3". */
fun turnOnLabel(ticked: Int): String = if (ticked == 0) "Turn on" else "Turn on $ticked"

/** The suggestions' foot note; [target] is the profile the owner restricts, `null` on one's own Filters. */
fun suggestionsNote(target: Profile?): String =
    "Nothing changes until you choose Turn on. You can change each one any time in ${target?.let { "${it.name}’s restrictions" } ?: "Filters"}."
