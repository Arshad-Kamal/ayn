package com.arshad.ayn.ui.mobile.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.feature.settings.Listed
import com.arshad.ayn.feature.settings.PACKS_NOTE
import com.arshad.ayn.feature.settings.RestrictionsScreen
import com.arshad.ayn.feature.settings.RestrictionsSheet
import com.arshad.ayn.feature.settings.RestrictionsUiState
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.keywordProblemText
import com.arshad.ayn.feature.settings.patternProblemText
import com.arshad.ayn.feature.settings.restrictionPackHint
import com.arshad.ayn.feature.settings.restrictionsHole
import com.arshad.ayn.feature.settings.suggestionsNote
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.profiles.EntryActions
import com.arshad.ayn.ui.mobile.profiles.EntryCopy
import com.arshad.ayn.ui.mobile.profiles.LockEntryPage
import com.arshad.ayn.ui.mobile.shell.ShellTime
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first

/**
 * The owner's restrictions on [profileId] (`m6-settings` 23–24, 32, P6–P8), opened from its
 * sheet in Profiles: the owner's lock while the owner has one, then the page: Filters' rows plus
 * Hide Shorts and Patterns, then the filter packs; their sheets over it. Closing it (Back from the page or the lock, the profile
 * gone) leaves the page ([onGone]). [time] dates the listed rules.
 */
@Composable
internal fun RestrictionsPage(profileId: Long, restrictions: RestrictionsViewModel, time: ShellTime, onGone: () -> Unit) {
    val state by restrictions.uiState.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(restrictions, profileId) {
        // A ViewModel kept across recreation is already open; a new one opens once who the owner is
        // has loaded (onOpen ignores the call before that).
        if (restrictions.uiState.value.screen == RestrictionsScreen.Closed) {
            restrictions.uiState.first { it.owner != null }
            restrictions.onOpen(profileId)
        }
        // onOpen shows the gate or the page only after reading the owner's lock, so the screen is
        // still Closed here: wait for it to open before waiting for it to close.
        restrictions.uiState.dropWhile { it.screen == RestrictionsScreen.Closed }.first { it.screen == RestrictionsScreen.Closed }
        gone()
    }
    when (val screen = state.screen) {
        is RestrictionsScreen.OwnerGate -> RestrictionsGate(screen, state, restrictions)
        is RestrictionsScreen.Page -> RestrictionsRows(screen, state, restrictions, time)
        // Before it opens, as it leaves, or when it won't open (the owner's own profile, or one
        // that's gone), the page is empty and Back leaves.
        RestrictionsScreen.Closed -> SettingsFrame(
            state.profiles.firstOrNull { it.id == profileId }?.name?.let(::restrictionsTitle).orEmpty(),
            onBack = onGone,
        ) {}
    }
}

@Composable
private fun RestrictionsRows(page: RestrictionsScreen.Page, state: RestrictionsUiState, restrictions: RestrictionsViewModel, time: ShellTime) {
    val target = state.target ?: return
    SettingsFrame(restrictionsTitle(target.name), onBack = restrictions::onBack) {
        val rules = state.rules ?: return@SettingsFrame
        val r = rules.restrictions
        item(key = "lede") { PageLede(restrictionsLede(target.name), target) }
        item(key = "notice") { SettingsNotice(restrictionsNoticeLead(target.name), restrictionsHole(target.name, state.unlockedOthers)) }
        item(key = "filters") {
            FilterRows(
                values = FilterValues(
                    restrictedMode = r.restrictedMode,
                    hideShorts = r.hideShorts,
                    hideLive = r.hideLive,
                    hideMembersOnly = r.hideMembersOnly,
                    length = r.lengthLimits,
                    channels = state.channels.size,
                    videos = state.videos.size,
                    keywords = state.keywords.size,
                    patterns = state.patterns.size,
                ),
                restrictedModeHint = RESTRICTED_MODE_HINT,
                actions = FilterActions(
                    onRestrictedMode = restrictions::onOpenRestrictedMode,
                    onSwitch = restrictions::onSwitch,
                    onLength = restrictions::onOpenLength,
                    onChannels = restrictions::onOpenBlockedChannels,
                    onVideos = restrictions::onOpenBlockedVideos,
                    onKeywords = restrictions::onOpenKeywords,
                    onHideShorts = restrictions::onHideShorts,
                    onPatterns = restrictions::onOpenPatterns,
                ),
            )
        }
        item(key = "packs") {
            PackRows(r.filterPacks, target.isKids, onOpen = restrictions::onOpenPack) { pack -> restrictionPackHint(pack, target.isKids) }
        }
        item(key = "foot") { PageFoot(PACKS_NOTE) }
    }
    RestrictionsSheets(page.sheet, state, target, restrictions, time)
}

/** The sheets over the restrictions (state 24, P7, P8: Filters' sheets as the owner's, plus Patterns). */
@Composable
private fun RestrictionsSheets(sheet: RestrictionsSheet?, state: RestrictionsUiState, target: Profile, restrictions: RestrictionsViewModel, time: ShellTime) {
    StageSheet(sheet, onDismiss = restrictions::onDismiss, onBack = restrictions::onBack, stageKey = { it::class }) { shown ->
        val rules = state.rules ?: return@StageSheet
        val kicker = "${restrictionsTitle(target.name)} · owner only"
        val edits = RuleListEdits(restrictions::onAddRule, restrictions::onRuleChange, restrictions::onRuleDone, restrictions::onRemoveRule)
        val whose = "${target.name}’s profile"
        when (shown) {
            RestrictionsSheet.RestrictedMode -> RestrictedModeChoices(kicker, rules.restrictions.restrictedMode, restrictions::onRestrictedModePicked)
            RestrictionsSheet.Length -> LengthChoices(kicker, rules.restrictions.lengthLimits, restrictions::onShorterPicked, restrictions::onLongerPicked)
            is RestrictionsSheet.Keywords -> RuleList(
                kicker = kicker,
                copy = keywordsCopy("Whole words only, ignoring case and accents. ${target.name} sees only how many there are."),
                draft = shown.draft,
                rules = state.keywords.map { Listed(it.rule.word, it.addedAtMillis) },
                edits = edits,
                problemText = { problem, word -> keywordProblemText(problem, word, whose = target.name) },
                time = time,
            )
            is RestrictionsSheet.Patterns -> RuleList(
                kicker = kicker,
                copy = RuleListCopy(
                    title = "Patterns",
                    sub = "Regular expressions checked against titles, for what a word list can’t catch. ${target.name} sees only how many there are.",
                    add = "Add a pattern",
                    note = "Up to 200 characters each, and up to 50 patterns. Ayn checks a pattern when you save it; one that isn’t valid " +
                        "isn’t saved. DeArrow titles are checked too.",
                    code = true,
                ),
                draft = shown.draft,
                rules = state.patterns.map { Listed(it.rule.pattern, it.addedAtMillis) },
                edits = edits,
                problemText = { problem, _ -> patternProblemText(problem, target.name) },
                time = time,
            )
            RestrictionsSheet.BlockedChannels -> BlockedChannels(kicker, whose, state.channels, restrictions::onUnblockChannel, time)
            RestrictionsSheet.BlockedVideos -> BlockedVideos(kicker, whose, state.videos, restrictions::onUnblockVideo, time)
            is RestrictionsSheet.Pack -> PackSheet(
                kicker = kicker,
                pack = shown.pack,
                packs = rules.restrictions.filterPacks,
                target = target,
                kids = target.isKids,
                actions = PackActions(restrictions::onUsePack, restrictions::onPackOption, restrictions::onIslamicChannel),
            )
            // Its stage key is its class, so a tick keeps the sheet.
            is RestrictionsSheet.Suggestions -> SuggestionsSheet(
                offer = shown.offer,
                restriction = true,
                note = suggestionsNote(target),
                onTick = restrictions::onSuggestionTicked,
                onTurnOn = restrictions::onTurnOnSuggestions,
                onNotNow = restrictions::onNotNow,
            )
        }
    }
}

/** The owner's lock before the restrictions open (state 32): touch Back leaves even with taps entered. */
@Composable
private fun RestrictionsGate(gate: RestrictionsScreen.OwnerGate, state: RestrictionsUiState, restrictions: RestrictionsViewModel) {
    val owner = state.owner ?: return
    val target = state.target ?: return
    val actions = remember(restrictions) {
        EntryActions(
            onPress = restrictions::onPress,
            onSubmitSequence = restrictions::onSubmitSequence,
            onDigit = restrictions::onDigit,
            onErase = restrictions::onErase,
            onBack = restrictions::onGateLeave,
            onRejectionShown = restrictions::onRejectionShown,
        )
    }
    val copy = EntryCopy(
        label = restrictionsTitle(target.name),
        line = enterLockLine(owner.name, gate.lockKind),
        hint = restrictionsGateHint(gate.lockKind),
        waitTail = GO_BACK_WAIT_TAIL,
        labelIcon = R.drawable.ic_funnel,
    )
    LockEntryPage(owner, gate.lockKind, gate.entry, copy, actions)
}
