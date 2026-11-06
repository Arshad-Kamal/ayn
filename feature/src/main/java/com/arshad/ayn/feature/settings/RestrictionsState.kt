package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.feature.profiles.UnlockEntry

/**
 * Settings › Profiles › a profile › Restrictions (`kp-filters` psheet, restrictions, patterns,
 * patternbad; KP-D12): the owner's restrictions on another profile. [profiles] are everyone on the
 * device; [rules] are the shown profile's, `null` until they load for it.
 */
data class RestrictionsUiState(
    val profiles: List<Profile> = emptyList(),
    val screen: RestrictionsScreen = RestrictionsScreen.Closed,
    val rules: ProfileRules? = null,
) {
    /** The profile whose restrictions show (or whose gate asks), `null` while closed. */
    val target: Profile? get() = screen.profileId?.let { id -> profiles.firstOrNull { it.id == id } }

    val owner: Profile? get() = profiles.firstOrNull { it.isOwner }

    /** Profiles besides [target] that open without a lock, so the target could switch to them
     *  (KP-D12: a restriction holds only as far as the others are locked). A kids profile is no
     *  way out: it is pinned and filtered more. */
    val unlockedOthers: List<Profile> get() = profiles.filter { it.id != target?.id && it.lock == null && !it.isKids }

    /** The owner's filter pack choices on [target] (FPK-D15), `null` until its rules load. */
    val packs: FilterPacks? get() = rules?.restrictions?.filterPacks

    val channels: List<Listed<BlockedChannel>> get() = rules.listed(byOwner = true)
    val videos: List<Listed<BlockedVideo>> get() = rules.listed(byOwner = true)
    val keywords: List<Listed<Keyword>> get() = rules.listed(byOwner = true)
    val patterns: List<Listed<Pattern>> get() = rules.listed(byOwner = true)
}

sealed interface RestrictionsScreen {
    val profileId: Long?

    data object Closed : RestrictionsScreen {
        override val profileId: Long? get() = null
    }

    /** The owner's [lockKind] lock before [profileId]'s restrictions open. */
    data class OwnerGate(override val profileId: Long, val lockKind: LockKind, val entry: UnlockEntry = UnlockEntry()) : RestrictionsScreen

    /** [profileId]'s restrictions, [sheet] over them. [arriving] until focus lands on the first row. */
    data class Page(override val profileId: Long, val sheet: RestrictionsSheet? = null, val arriving: Boolean = true) : RestrictionsScreen
}

/** What shows over the Restrictions page. */
sealed interface RestrictionsSheet {
    data object RestrictedMode : RestrictionsSheet

    data object Length : RestrictionsSheet

    data class Keywords(val draft: RuleDraft? = null) : RestrictionsSheet

    /** Owner-only regular expressions (KP-D7), checked when saved. */
    data class Patterns(val draft: RuleDraft? = null) : RestrictionsSheet

    data object BlockedChannels : RestrictionsSheet

    data object BlockedVideos : RestrictionsSheet

    /** A filter pack, as [FiltersSheet.Pack], written as the owner's restriction. */
    data class Pack(val pack: FilterPack) : RestrictionsSheet

    /** Also turn these on?, as [FiltersSheet.Suggestions]; it returns to Harmful for kids' sheet. */
    data class Suggestions(val offer: SuggestionOffer) : RestrictionsSheet
}
