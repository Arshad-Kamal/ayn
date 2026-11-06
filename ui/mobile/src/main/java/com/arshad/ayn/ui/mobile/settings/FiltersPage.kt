package com.arshad.ayn.ui.mobile.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.settings.FilterSwitch
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
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.ShellTime

internal const val RESTRICTED_MODE_HINT = "YouTube’s own filter for mature videos"

/**
 * Settings › Filters (`m6-settings` 17–22, P1, P9, P10): [me]'s own filters and filter packs (a
 * pack the owner keeps on for them says so), Show Shorts leading to Interface's switch
 * ([onShowShorts]), and for anyone but the owner what the owner set on them, dimmed under a lock
 * line (counts only); the sheets over them. It waits for the profile's settings and rules and for
 * who the owner is. [time] dates the listed rules.
 */
@Composable
internal fun FiltersPage(me: Profile, filters: FiltersViewModel, time: ShellTime, onShowShorts: () -> Unit, onBack: () -> Unit) {
    val state by filters.uiState.collectAsStateWithLifecycle()
    SettingsFrame(SettingsPage.Filters.title(me.isOwner), onBack) {
        val settings = state.profile?.settings ?: return@SettingsFrame
        if (state.rules == null) return@SettingsFrame
        val owner = state.owner ?: return@SettingsFrame
        item(key = "scope") { ProfileScope(me, eachOwn = true) }
        item(key = "filters") {
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
        }
        item(key = "packs") {
            PackRows(settings.filterPacks, state.kids, onOpen = filters::onOpenPack) { pack ->
                ownPackHint(pack, owner.name.takeIf { state.ownerKeeps(pack) })
            }
        }
        item(key = "shorts") {
            SubHeading("Shorts")
            SettingRow(
                "Show Shorts",
                onShowShorts,
                hint = shortsLinkHint(owner.name.takeIf { state.ownerHidesShorts }),
                value = if (state.showsShorts) "On" else "Off",
            )
        }
        state.ownerSet?.let { set -> item(key = "owner") { OwnerSetGroup(owner, set, state.kids) } }
        item(key = "foot") { PageFoot("$PACKS_NOTE ${filtersReach(state.isOwner)}") }
    }
    FiltersSheets(state, me, filters, time)
}

/** What the owner set on this profile (state 22, P10): the owner's scope and lock line, then the
 *  same rows with the restriction-only ones (Hide Shorts, Patterns) and the owner's packs ([kids]
 *  picks the channel defaults counted), dimmed, as text. */
@Composable
private fun OwnerSetGroup(owner: Profile, set: OwnerFilters, kids: Boolean) {
    val restrictions = set.restrictions
    Column {
        GroupLine()
        ScopeLine(owner, setByOwner(owner.name))
        OwnerOnlyLine(onlyOwnerChanges(owner.name))
        Dimmed(lit = false) {
            FilterRows(
                FilterValues(
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
                RESTRICTED_MODE_HINT,
                actions = null,
            )
            PackRows(restrictions.filterPacks, kids, onOpen = null)
        }
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

/** What the filter rows open or change; the owner's restrictions also [onHideShorts] and [onPatterns]. */
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
 * The filter rows (`m6-settings` 17, 23): Restricted Mode, Hide Shorts, the live and members-only
 * switches, Video length, then under Blocked the channels, videos, keywords and patterns. Without
 * [actions] they are text; a blocked list's row opens only while it holds something.
 */
@Composable
internal fun FilterRows(values: FilterValues, restrictedModeHint: String, actions: FilterActions?) {
    val live = actions != null
    Column {
        SettingRow("Restricted Mode", actions?.onRestrictedMode, hint = restrictedModeHint, value = values.restrictedMode.label, chevron = live)
        values.hideShorts?.let { on ->
            SwitchRow("Hide Shorts", on, { actions?.onHideShorts?.invoke(it) }, hint = HIDE_SHORTS_HINT, enabled = actions?.onHideShorts != null)
        }
        SwitchRow("Hide live streams", values.hideLive, { actions?.onSwitch(FilterSwitch.HideLive, it) }, hint = HIDE_LIVE_HINT, enabled = live)
        SwitchRow(
            "Hide members-only videos",
            values.hideMembersOnly,
            { actions?.onSwitch(FilterSwitch.HideMembersOnly, it) },
            hint = "Videos only a channel’s paying members can watch",
            enabled = live,
        )
        SettingRow("Video length", actions?.onLength, hint = "Hide videos shorter or longer than you choose", value = lengthLabel(values.length), chevron = live)
        SubHeading("Blocked")
        SettingRow(
            "Blocked channels",
            actions?.onChannels?.takeIf { values.channels > 0 },
            hint = "Block one from a video’s menu: long-press its card",
            value = countLabel(values.channels),
            chevron = live && values.channels > 0,
        )
        SettingRow("Blocked videos", actions?.onVideos?.takeIf { values.videos > 0 }, value = countLabel(values.videos), chevron = live && values.videos > 0)
        SettingRow("Keywords", actions?.onKeywords, hint = "Hide videos whose titles use a word", value = wordsLabel(values.keywords), chevron = live)
        values.patterns?.let { patterns ->
            SettingRow(
                "Patterns",
                actions?.onPatterns,
                hint = "Regular expressions, for what a word list can’t catch",
                value = countLabel(patterns),
                chevron = actions?.onPatterns != null,
                labelTrail = { SettingsTag("Owner only") },
            )
        }
    }
}
