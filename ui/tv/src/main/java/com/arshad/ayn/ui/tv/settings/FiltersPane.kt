package com.arshad.ayn.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.settings.FilterSwitch
import com.arshad.ayn.feature.settings.FiltersUiState
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.HIDE_LIVE_HINT
import com.arshad.ayn.feature.settings.HIDE_SHORTS_HINT
import com.arshad.ayn.feature.settings.OwnerFilters
import com.arshad.ayn.feature.settings.PACKS_NOTE
import com.arshad.ayn.feature.settings.countLabel
import com.arshad.ayn.feature.settings.label
import com.arshad.ayn.feature.settings.lengthLabel
import com.arshad.ayn.feature.settings.ownPackHint
import com.arshad.ayn.feature.settings.wordsLabel
import com.arshad.ayn.ui.tv.focus.focusMemory

// The Show Shorts row's focus key on Filters, and Interface's Show Shorts row, where it leads.
internal const val SHORTS_LINK_KEY = "shorts-link"
internal const val INTERFACE_SHORTS_KEY = "shorts"

private const val RESTRICTED_MODE_HINT = "YouTube’s own filter for mature videos"

/**
 * Settings › Filters (`kp-filters` filters, mirafilters, fpk-filters, fpk-mira; KP-D12): [me]'s
 * own filters and filter packs (a pack the owner keeps on for them says so), Show Shorts leading
 * to Interface's switch ([onShowShorts]), where filters apply; for anyone but the owner, what the
 * owner set on them, dimmed, focus passing over it (counts only). It waits for the profile's
 * settings and rules and for who the owner is.
 */
@Composable
internal fun FiltersPane(me: Profile?, state: FiltersUiState, filters: FiltersViewModel, onShowShorts: () -> Unit) {
    me ?: return
    val settings = state.profile?.settings ?: return
    if (state.rules == null) return
    val owner = state.owner ?: return
    val ownerSet = state.ownerSet
    SettingsPane("Filters", sub = null) {
        ScopeLine(me)
        FilterRows(
            values = FilterValues(
                restrictedMode = settings.restrictedMode,
                hideShorts = null,
                hideLive = settings.hideLive,
                hideMembersOnly = settings.hideMembersOnly,
                length = settings.lengthLimits,
                channels = state.ownChannels.size,
                videos = state.ownVideos.size,
                keywords = state.ownKeywords.size,
                patterns = null,
            ),
            restrictedModeHint = if (state.ownerStrict) ownerStrictHint(owner.name) else RESTRICTED_MODE_HINT,
            actions = FilterActions(
                onRestrictedMode = filters::onOpenRestrictedMode,
                onSwitch = filters::onSwitch,
                onLength = filters::onOpenLength,
                onChannels = filters::onOpenBlockedChannels,
                onVideos = filters::onOpenBlockedVideos,
                onKeywords = filters::onOpenKeywords,
            ),
        )
        PackRows(settings.filterPacks, state.kids, onOpen = filters::onOpenPack) { pack ->
            ownPackHint(pack, owner.name.takeIf { state.ownerKeeps(pack) })
        }
        PaneSubHeading("Shorts")
        SettingRow(
            label = "Show Shorts",
            hint = shortsLinkHint(owner.name.takeIf { state.ownerHidesShorts }),
            value = if (state.showsShorts) "On" else "Off",
            onClick = onShowShorts,
            modifier = Modifier.focusMemory(SHORTS_LINK_KEY),
        )
        PaneNote("$PACKS_NOTE $FILTERS_REACH" + if (state.isOwner) FILTERS_OWNER_TAIL else "")
        ownerSet?.let { OwnerSetGroup(owner, it, state.kids) }
    }
}

/**
 * What the owner set on this profile (`mirafilters`, fpk-mira): the owner's heading and lock
 * line, then the same rows with the restriction-only ones (Hide Shorts, Patterns) and the
 * owner's packs ([kids] picks the channel defaults counted), dimmed, as text.
 */
@Composable
private fun OwnerSetGroup(owner: Profile, set: OwnerFilters, kids: Boolean) {
    val restrictions = set.restrictions
    ScopeHeading(owner, first = false, text = setByOwner(owner.name))
    OwnerOnlyLine(onlyOwnerChanges(owner.name))
    Column(Modifier.graphicsLayer { alpha = OFF_ALPHA }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FilterRows(
            values = FilterValues(
                restrictedMode = restrictions.restrictedMode,
                hideShorts = restrictions.hideShorts,
                hideLive = restrictions.hideLive,
                hideMembersOnly = restrictions.hideMembersOnly,
                length = restrictions.lengthLimits,
                channels = set.channels,
                videos = set.videos,
                keywords = set.keywords,
                patterns = set.patterns,
            ),
            restrictedModeHint = RESTRICTED_MODE_HINT,
            actions = null,
            keyPrefix = "owner-",
        )
        PackRows(restrictions.filterPacks, kids, onOpen = null)
    }
}

/** One set of filter values, a profile's own or the owner's on it (KP-D12: same fields, two
 *  setters); [hideShorts] and [patterns] are the owner's only, `null` for no row. */
internal class FilterValues(
    val restrictedMode: RestrictedMode,
    val hideShorts: Boolean?,
    val hideLive: Boolean,
    val hideMembersOnly: Boolean,
    val length: LengthLimits,
    val channels: Int,
    val videos: Int,
    val keywords: Int,
    val patterns: Int?,
)

/** What the filter rows open or change; the owner's restrictions also [onHideShorts] and
 *  [onPatterns]. */
internal class FilterActions(
    val onRestrictedMode: () -> Unit,
    val onSwitch: (FilterSwitch, Boolean) -> Unit,
    val onLength: () -> Unit,
    val onChannels: () -> Unit,
    val onVideos: () -> Unit,
    val onKeywords: () -> Unit,
    val onHideShorts: ((Boolean) -> Unit)? = null,
    val onPatterns: (() -> Unit)? = null,
)

/**
 * The filter rows (`kp-filters` filterRows): Restricted Mode, Hide Shorts, the live and
 * members-only switches, Video length, then under Blocked the channels, videos, keywords and
 * patterns. Without [actions] they are text focus passes over; a blocked list's row opens only
 * while it holds something. [keyPrefix] keeps their focus keys apart from another set's.
 */
@Composable
internal fun FilterRows(values: FilterValues, restrictedModeHint: String, actions: FilterActions?, keyPrefix: String = "") {
    val live = actions != null
    fun keyed(key: String): Modifier = if (live) Modifier.focusMemory(keyPrefix + key) else Modifier
    SettingRow(
        label = "Restricted Mode",
        hint = restrictedModeHint,
        value = values.restrictedMode.label,
        chevron = live,
        focusable = live,
        onClick = { actions?.onRestrictedMode() },
        modifier = keyed("rm"),
    )
    values.hideShorts?.let { hideShorts ->
        SwitchRow(
            label = "Hide Shorts",
            on = hideShorts,
            onSwitch = { actions?.onHideShorts?.invoke(it) },
            focusKey = keyPrefix + "hide-shorts",
            hint = HIDE_SHORTS_HINT,
            focusable = actions?.onHideShorts != null,
        )
    }
    SwitchRow(
        label = "Hide live streams",
        on = values.hideLive,
        onSwitch = { actions?.onSwitch(FilterSwitch.HideLive, it) },
        focusKey = keyPrefix + "live",
        hint = HIDE_LIVE_HINT,
        focusable = live,
    )
    SwitchRow(
        label = "Hide members-only videos",
        on = values.hideMembersOnly,
        onSwitch = { actions?.onSwitch(FilterSwitch.HideMembersOnly, it) },
        focusKey = keyPrefix + "members",
        hint = "Videos only a channel’s paying members can watch",
        focusable = live,
    )
    SettingRow(
        label = "Video length",
        hint = "Hide videos shorter or longer than you choose",
        value = lengthLabel(values.length),
        valueMaxWidth = 220.dp,
        chevron = live,
        focusable = live,
        onClick = { actions?.onLength() },
        modifier = keyed("length"),
    )
    PaneSubHeading("Blocked")
    SettingRow(
        label = "Blocked channels",
        hint = "Block one from a video’s menu: hold OK on its card",
        value = countLabel(values.channels),
        chevron = live && values.channels > 0,
        focusable = live,
        onClick = { actions?.onChannels() },
        modifier = keyed("channels"),
    )
    SettingRow(
        label = "Blocked videos",
        value = countLabel(values.videos),
        chevron = live && values.videos > 0,
        focusable = live,
        onClick = { actions?.onVideos() },
        modifier = keyed("videos"),
    )
    SettingRow(
        label = "Keywords",
        hint = "Hide videos whose titles use a word",
        value = wordsLabel(values.keywords),
        chevron = live,
        focusable = live,
        onClick = { actions?.onKeywords() },
        modifier = keyed("keywords"),
    )
    values.patterns?.let { patterns ->
        val onPatterns = actions?.onPatterns
        SettingRow(
            label = "Patterns",
            hint = "Regular expressions, for what a word list can’t catch",
            value = countLabel(patterns),
            chevron = onPatterns != null,
            focusable = onPatterns != null,
            onClick = { onPatterns?.invoke() },
            modifier = keyed("patterns"),
            labelTrail = { RowTag("Owner only", it) },
        )
    }
}
