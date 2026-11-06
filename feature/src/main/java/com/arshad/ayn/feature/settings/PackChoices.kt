package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileSettings

/** A filter pack's row and sheet in Filters and Restrictions (`kp-filters` fpk-*; FPK-D1). */
enum class FilterPack { HarmfulForKids, HalalOnly, IslamicOnly }

/** A switch under its [pack] in the pack's sheet (FPK-D6, D8); off by default, counted only while
 *  the pack is on. */
enum class PackOption(val pack: FilterPack) {
    Overstimulating(FilterPack.HarmfulForKids),
    ChallengesAndVlogs(FilterPack.HarmfulForKids),
    StageMagic(FilterPack.HalalOnly),
    Festivals(FilterPack.HalalOnly),
    LootBoxes(FilterPack.HalalOnly),
}

fun FilterPacks.isOn(pack: FilterPack): Boolean = when (pack) {
    FilterPack.HarmfulForKids -> harmfulForKids
    FilterPack.HalalOnly -> halalOnly
    FilterPack.IslamicOnly -> islamicOnly
}

/** Any pack on; with none, these choices hide nothing. */
val FilterPacks.anyOn: Boolean get() = FilterPack.entries.any(::isOn)

/** These choices with [pack] [on]; Halal only and Islamic content only are one at a time (FPK-D2),
 *  so turning one on turns the other off. */
fun FilterPacks.withPack(pack: FilterPack, on: Boolean): FilterPacks = when (pack) {
    FilterPack.HarmfulForKids -> copy(harmfulForKids = on)
    FilterPack.HalalOnly -> copy(halalOnly = on, islamicOnly = islamicOnly && !on)
    FilterPack.IslamicOnly -> copy(islamicOnly = on, halalOnly = halalOnly && !on)
}

fun FilterPacks.isOn(option: PackOption): Boolean = when (option) {
    PackOption.Overstimulating -> overstimulating
    PackOption.ChallengesAndVlogs -> challengesAndVlogs
    PackOption.StageMagic -> stageMagic
    PackOption.Festivals -> festivals
    PackOption.LootBoxes -> lootBoxes
}

fun FilterPacks.withOption(option: PackOption, on: Boolean): FilterPacks = when (option) {
    PackOption.Overstimulating -> copy(overstimulating = on)
    PackOption.ChallengesAndVlogs -> copy(challengesAndVlogs = on)
    PackOption.StageMagic -> copy(stageMagic = on)
    PackOption.Festivals -> copy(festivals = on)
    PackOption.LootBoxes -> copy(lootBoxes = on)
}

/** The Islamic content only channels these switches turn on, for a kids profile or another
 *  (FPK-D12): what each switch shows and the row's channel count. */
fun FilterPacks.enabledIslamicChannels(kids: Boolean): Set<String> = IslamicOnly.enabledChannels(islamicChannels, kids)

/** These choices with listed channel [channelId]'s switch [on]. Only a switch moved off its
 *  default is kept, so a later release's new default still reaches the others; an id the list
 *  doesn't carry changes nothing. */
fun FilterPacks.withIslamicChannel(channelId: String, on: Boolean, kids: Boolean): FilterPacks {
    val channel = IslamicOnly.channel(channelId) ?: return this
    val default = if (kids) channel.onForKids else channel.onForAdults
    return copy(islamicChannels = if (on == default) islamicChannels - channelId else islamicChannels + (channelId to on))
}

/** A setting offered right after Harmful for kids goes on (FPK-D7). */
enum class Suggestion { HideShorts, HideLive, HideLongerThan30 }

/** Hide videos longer than this, the length [Suggestion.HideLongerThan30] sets (one of [LONGER_THAN_MINUTES]). */
const val SUGGESTED_LONGER_THAN_MINUTES: Int = 30

/** Also turn these on? (`kp-filters` fpk-suggest): the [offered] settings in [Suggestion] order,
 *  each ticked to start; nothing is set until Turn on sets the [ticked] ones. */
data class SuggestionOffer(val offered: List<Suggestion>, val ticked: Set<Suggestion> = offered.toSet())

/** The suggestions a setter doesn't already have, as it would hide Shorts ([hidesShorts]), live
 *  streams ([hidesLive]) and long videos ([limits]); `null` when it has them all, so nothing is
 *  offered. */
internal fun suggestionOffer(hidesShorts: Boolean, hidesLive: Boolean, limits: LengthLimits): SuggestionOffer? {
    val offered = Suggestion.entries.filter { suggestion ->
        when (suggestion) {
            Suggestion.HideShorts -> !hidesShorts
            Suggestion.HideLive -> !hidesLive
            Suggestion.HideLongerThan30 -> !limits.hidesSuggestedLength
        }
    }
    return offered.takeIf { it.isNotEmpty() }?.let(::SuggestionOffer)
}

/** The profile's own settings with the [ticked] suggestions set: Show Shorts off, live hidden,
 *  longer than 30 min hidden. Nothing set already loosens. */
internal fun ProfileSettings.withSuggestions(ticked: Set<Suggestion>): ProfileSettings = copy(
    showShorts = showShorts && Suggestion.HideShorts !in ticked,
    hideLive = hideLive || Suggestion.HideLive in ticked,
    lengthLimits = if (Suggestion.HideLongerThan30 in ticked) lengthLimits.withSuggestedLength() else lengthLimits,
)

/** The owner's restrictions with the [ticked] suggestions set. Nothing set already loosens. */
internal fun ProfileRestrictions.withSuggestions(ticked: Set<Suggestion>): ProfileRestrictions = copy(
    hideShorts = hideShorts || Suggestion.HideShorts in ticked,
    hideLive = hideLive || Suggestion.HideLive in ticked,
    lengthLimits = if (Suggestion.HideLongerThan30 in ticked) lengthLimits.withSuggestedLength() else lengthLimits,
)

private val LengthLimits.hidesSuggestedLength: Boolean
    get() = maxMinutes.let { it != null && it <= SUGGESTED_LONGER_THAN_MINUTES }

/** Longer than 30 min hidden, unless a shorter bound already hides more. */
private fun LengthLimits.withSuggestedLength(): LengthLimits =
    if (hidesSuggestedLength) this else checkNotNull(withLongerThan(SUGGESTED_LONGER_THAN_MINUTES)) { "the suggested length is not offered" }

/** This offer with [suggestion] ticked or not; one not offered changes nothing. */
internal fun SuggestionOffer.withTick(suggestion: Suggestion, on: Boolean): SuggestionOffer =
    if (suggestion !in offered) this else copy(ticked = if (on) ticked + suggestion else ticked - suggestion)
