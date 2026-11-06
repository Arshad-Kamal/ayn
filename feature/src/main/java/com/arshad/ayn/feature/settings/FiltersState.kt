package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.RuleEdit
import com.arshad.ayn.core.store.SelfRule
import com.arshad.ayn.core.store.patternProblem

/**
 * Settings › Filters (`kp-filters` filters, mirafilters; KP-D12): the session profile's own
 * filters ([profile]'s settings and its own rules in [rules]) and, for anyone but the owner, what
 * the owner set on it ([ownerSet]). [rules] is [profile]'s, `null` until they load; [owner] is
 * `null` until the profiles load. On the owner's page [elsewhere] holds every other profile's
 * rules (empty for anyone else), so a block the owner also set there can be lifted there too
 * (KP.15). [sheet] is what shows over the page; [listFocus] is the blocked list's row focus lands
 * on as the list comes back from [FiltersSheet.UnblockWhere].
 */
data class FiltersUiState(
    val profile: ProfileSettingsState? = null,
    val rules: ProfileRules? = null,
    val owner: Profile? = null,
    val sheet: FiltersSheet? = null,
    val profiles: List<Profile> = emptyList(),
    val elsewhere: Map<Long, ProfileRules> = emptyMap(),
    val listFocus: String? = null,
) {
    val isOwner: Boolean get() = profile != null && profile.profileId == owner?.id

    /** The other profiles carrying [rule] (same kind and key) as the owner's restriction, in
     *  profile order; none on anyone but the owner's page. */
    fun alsoIn(rule: SelfRule): List<Profile> = if (!isOwner) emptyList() else profiles.filter { other ->
        elsewhere[other.id]?.rules.orEmpty().any { it.byOwner && it.rule::class == rule::class && it.rule.key == rule.key }
    }

    /** The profile's own blocked channels, newest first. */
    val ownChannels: List<Listed<BlockedChannel>> get() = rules.listed(byOwner = false)

    /** The profile's own blocked videos, newest first. */
    val ownVideos: List<Listed<BlockedVideo>> get() = rules.listed(byOwner = false)

    /** The profile's own keywords, newest first (a word just added leads), each with when it was added (Q52). */
    val ownKeywords: List<Listed<Keyword>> get() = rules.listed(byOwner = false)

    /** The owner's Strict holds over this profile's own Off. */
    val ownerStrict: Boolean
        get() = ownerSet?.restrictions?.restrictedMode == RestrictedMode.Strict && profile?.settings?.restrictedMode == RestrictedMode.Off

    /** The owner keeps Shorts off for this profile, whatever Interface says. */
    val ownerHidesShorts: Boolean get() = ownerSet?.restrictions?.hideShorts == true

    /** Whether Shorts show for this profile: its own switch, unless the owner hides them. */
    val showsShorts: Boolean get() = profile?.settings?.showShorts == true && !ownerHidesShorts

    /** A kids profile's Filters (its Islamic channel defaults are the kids ones, FPK-D12). */
    val kids: Boolean get() = profile?.kids == true

    /** The profile's own filter pack choices, `null` until its settings load. */
    val packs: FilterPacks? get() = profile?.settings?.filterPacks

    /** The owner keeps [pack] on for this profile ("Arshad keeps it on for you", FPK-D15). */
    fun ownerKeeps(pack: FilterPack): Boolean = ownerSet?.restrictions?.filterPacks?.isOn(pack) == true

    /** What the owner set on this profile (counts only, KP-D12; its packs with them, FPK-D15),
     *  `null` for the owner or when the owner set nothing. A kids profile's time rules aren't
     *  filters, so they don't count; Harmful for kids, on for every new kids profile, does. */
    val ownerSet: OwnerFilters?
        get() {
            val rules = rules ?: return null
            if (isOwner) return null
            val byOwner = rules.rules.filter { it.byOwner }.map { it.rule }
            val set = OwnerFilters(
                restrictions = rules.restrictions,
                channels = byOwner.count { it is BlockedChannel },
                videos = byOwner.count { it is BlockedVideo },
                keywords = byOwner.count { it is Keyword },
                patterns = byOwner.count { it is Pattern },
            )
            return set.takeIf { it.setsAnything }
        }
}

/** A rule as a sheet lists it, with when it was added. */
data class Listed<T>(val rule: T, val addedAtMillis: Long)

/** These rules of type [T] set by the profile itself, or by the owner when [byOwner], newest first
 *  (the store lists them oldest first); none before they load. */
inline fun <reified T : ContentRule> ProfileRules?.listed(byOwner: Boolean): List<Listed<T>> =
    this?.rules.orEmpty().reversed().mapNotNull { stored ->
        (stored.rule as? T)?.takeIf { stored.byOwner == byOwner }?.let { Listed(it, stored.addedAtMillis) }
    }

/** The owner's restrictions on a profile and how many list rules the owner set of each kind. */
data class OwnerFilters(
    val restrictions: ProfileRestrictions,
    val channels: Int,
    val videos: Int,
    val keywords: Int,
    val patterns: Int,
) {
    /** Time rules aren't filters, and pack options or channel switches count only while a pack is on. */
    val setsAnything: Boolean
        get() {
            val packs = restrictions.filterPacks.takeIf { it.anyOn } ?: FilterPacks()
            return restrictions.copy(dailyLimitMinutes = emptyMap(), bedtime = null, filterPacks = packs) != ProfileRestrictions() ||
                channels + videos + keywords + patterns > 0
        }
}

/** What shows over the Filters page. */
sealed interface FiltersSheet {
    /** Restricted Mode: Off or Strict (KP-D6). */
    data object RestrictedMode : FiltersSheet

    /** Video length: one choice in each of two groups; a pick keeps the sheet open. */
    data object Length : FiltersSheet

    /** Keywords: add a word ([draft] while one is typed) or remove one. */
    data class Keywords(val draft: RuleDraft? = null) : FiltersSheet

    /** Blocked channels: OK unblocks one. Opens only while there is one. */
    data object BlockedChannels : FiltersSheet

    /** Blocked videos: OK unblocks one. Opens only while there is one. */
    data object BlockedVideos : FiltersSheet

    /** Unblock [rule] (a [BlockedChannel] or [BlockedVideo]) the owner also set on other profiles:
     *  in all of them, or only in the owner's own (`kp-filters` unblockall, KP.15). */
    data class UnblockWhere(val rule: SelfRule) : FiltersSheet {
        /** The list it came from, which it returns to. */
        val list: FiltersSheet get() = if (rule is BlockedVideo) BlockedVideos else BlockedChannels
    }

    /** A filter pack: Use this pack, its options, Islamic content only's channel switches. */
    data class Pack(val pack: FilterPack) : FiltersSheet

    /** Also turn these on?, right after Harmful for kids goes on (FPK-D7); it returns to that
     *  pack's sheet. */
    data class Suggestions(val offer: SuggestionOffer) : FiltersSheet
}

/** A list rule being typed: [problem] says why the last save didn't add it. */
data class RuleDraft(val text: String = "", val problem: RuleProblem? = null, val saving: Boolean = false)

sealed interface RuleProblem {
    /** The setter already has it on this profile. */
    data object AlreadyThere : RuleProblem

    /** The setter has as many of its kind on this profile as it may keep. */
    data object Full : RuleProblem

    /** A pattern that doesn't compile: RE2/J's [description] and the part it stopped [near]. */
    data class Invalid(val description: String, val near: String?) : RuleProblem

    /** The write failed. */
    data object Failed : RuleProblem
}

/** A profile's own on/off filters. */
enum class FilterSwitch { HideLive, HideMembersOnly }

/** Video length's choices, in minutes (`kp-filters` length); `null`, Off, leads each group. */
val SHORTER_THAN_MINUTES: List<Int> = listOf(1, 3, 5, 10)
val LONGER_THAN_MINUTES: List<Int> = listOf(20, 30, 60, 120)

/** These limits with [minutes] (one of [SHORTER_THAN_MINUTES], or `null` for Off) as the
 *  shorter-than bound; a longer-than bound not above it goes (only a restored value can be).
 *  `null` for a choice not offered. */
internal fun LengthLimits.withShorterThan(minutes: Int?): LengthLimits? =
    if (minutes != null && minutes !in SHORTER_THAN_MINUTES) null else LengthLimits(minutes, maxLengthAbove(minutes))

/** These limits with [minutes] (one of [LONGER_THAN_MINUTES], or `null` for Off) as the
 *  longer-than bound; a shorter-than bound not below it goes. `null` for a choice not offered. */
internal fun LengthLimits.withLongerThan(minutes: Int?): LengthLimits? =
    if (minutes != null && minutes !in LONGER_THAN_MINUTES) null else LengthLimits(minLengthBelow(minutes), minutes)

private fun LengthLimits.minLengthBelow(max: Int?): Int? = minMinutes?.takeIf { max == null || it < max }

private fun LengthLimits.maxLengthAbove(min: Int?): Int? = maxMinutes?.takeIf { min == null || it > min }

/** What adding a rule answered, as the field shows it; `null` when it was added. A refused
 *  pattern says why ([patternProblem]). */
internal fun RuleEdit.problem(rule: ContentRule): RuleProblem? = when (this) {
    RuleEdit.Added -> null
    RuleEdit.AlreadyThere -> RuleProblem.AlreadyThere
    RuleEdit.Full -> RuleProblem.Full
    RuleEdit.Invalid -> (rule as? Pattern)?.let { patternProblem(it.pattern) }
        ?.let { RuleProblem.Invalid(it.description, it.near) } ?: RuleProblem.Failed
    RuleEdit.Failed -> RuleProblem.Failed
}
