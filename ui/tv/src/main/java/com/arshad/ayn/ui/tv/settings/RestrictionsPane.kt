package com.arshad.ayn.ui.tv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arshad.ayn.core.lock.LockKind
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
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.profiles.EntryCopy
import com.arshad.ayn.ui.tv.profiles.EntryKicker
import com.arshad.ayn.ui.tv.profiles.LockEntryScreen
import com.arshad.ayn.ui.tv.profiles.UnlockActions

/** The Restrictions page's first row, where focus lands as it opens. */
internal const val RESTRICTIONS_FIRST_KEY = "restr-rm"

/**
 * Settings › Profiles › a profile › Restrictions (`kp-filters` restrictions, fpk-zainab; KP-D12),
 * in the Profiles pane's place: the notice (the target can't turn these off; they hold only while
 * the other profiles are locked), then the filter rows with Hide Shorts and Patterns, and the
 * filter packs (FPK-D15), each written as the owner's restriction. Waits for the target's rules.
 */
@Composable
internal fun RestrictionsPane(state: RestrictionsUiState, restrictions: RestrictionsViewModel) {
    val target = state.target ?: return
    val rules = state.rules ?: return
    val r = rules.restrictions
    SettingsPane(restrictionsTitle(target.name), sub = restrictionsSub(target.name), who = target) {
        Notice(restrictionsNoticeLead(target.name), restrictionsHole(target.name, state.unlockedOthers))
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
            restrictedModeHint = "YouTube’s own filter for mature videos",
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
            keyPrefix = "restr-",
        )
        PackRows(r.filterPacks, target.isKids, onOpen = restrictions::onOpenPack, keyPrefix = "restr-") { pack ->
            restrictionPackHint(pack, target.isKids)
        }
        PaneNote(PACKS_NOTE)
    }
}

/** The sheet over the Restrictions page (`kp-filters` patterns, patternbad, fpk-z-harmful,
 *  fpk-z-islamic; the Filters sheets as the owner's). */
@Composable
internal fun RestrictionsSheetContent(shown: RestrictionsSheet, state: RestrictionsUiState, restrictions: RestrictionsViewModel) {
    val target = state.target ?: return
    val rules = state.rules ?: return
    val kicker = "${restrictionsTitle(target.name)} · owner only"
    val edits = RuleListEdits(restrictions::onAddRule, restrictions::onRuleChange, restrictions::onRuleDone, restrictions::onRemoveRule)
    when (shown) {
        RestrictionsSheet.RestrictedMode -> RestrictedModeSheet(kicker, rules.restrictions.restrictedMode, restrictions::onRestrictedModePicked)
        RestrictionsSheet.Length -> LengthSheet(kicker, rules.restrictions.lengthLimits, restrictions::onShorterPicked, restrictions::onLongerPicked)
        is RestrictionsSheet.Keywords -> RuleListSheet(
            kicker = kicker,
            copy = RuleListCopy(
                title = "Keywords",
                sub = "A video is hidden when its title uses one of these words. OK on a word removes it.",
                add = "Add a word",
                note = "Whole words only, ignoring case and accents. ${target.name} sees only how many there are.",
            ),
            draft = shown.draft,
            rules = state.keywords.map { it.rule.word },
            edits = edits,
            problemText = { problem, word -> keywordProblemText(problem, word, whose = target.name) },
        )
        is RestrictionsSheet.Patterns -> RuleListSheet(
            kicker = kicker,
            copy = RuleListCopy(
                title = "Patterns",
                sub = "Regular expressions checked against titles, for what a word list can’t catch. ${target.name} sees only how " +
                    "many there are.",
                add = "Add a pattern",
                note = "Up to 200 characters each, and up to 50 patterns. Ayn checks a pattern when you save it; one that isn’t " +
                    "valid isn’t saved. DeArrow titles are checked too.",
                code = true,
            ),
            draft = shown.draft,
            rules = state.patterns.map { it.rule.pattern },
            edits = edits,
            problemText = { problem, _ -> patternProblemText(problem, target.name) },
        )
        RestrictionsSheet.BlockedChannels -> BlockedChannelsSheet(kicker, "${target.name}’s profile", state.channels, restrictions::onUnblockChannel)
        RestrictionsSheet.BlockedVideos -> BlockedVideosSheet(kicker, "${target.name}’s profile", state.videos, restrictions::onUnblockVideo)
        is RestrictionsSheet.Pack -> PackSheet(
            kicker = kicker,
            pack = shown.pack,
            packs = rules.restrictions.filterPacks,
            target = target,
            kids = target.isKids,
            actions = PackActions(restrictions::onUsePack, restrictions::onPackOption, restrictions::onIslamicChannel),
        )
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

/** The owner's lock before a profile's restrictions open (the owner gate's layout). */
@Composable
internal fun RestrictionsGate(gate: RestrictionsScreen.OwnerGate, state: RestrictionsUiState, restrictions: RestrictionsViewModel) {
    val owner = state.owner ?: return
    val target = state.target ?: return
    val actions = remember(restrictions) {
        UnlockActions(
            onPress = restrictions::onPress,
            onSubmitSequence = restrictions::onSubmitSequence,
            onDigit = restrictions::onDigit,
            onErase = restrictions::onErase,
            onBack = restrictions::onBack,
            onRejectionShown = restrictions::onRejectionShown,
        )
    }
    val copy = EntryCopy(
        kicker = EntryKicker(R.drawable.ic_funnel, restrictionsTitle(target.name)),
        line = enterLock(owner.name, gate.lockKind),
        hint = cancelHint(gate.lockKind),
        waitTail = SettingsWaitTail,
    )
    LockEntryScreen(owner, gate.lockKind, gate.entry, copy, actions)
}

internal fun restrictionsTitle(name: String): String = "$name’s restrictions"

internal fun restrictionsSub(name: String): String = "They add to $name’s own filters: a video either one hides stays hidden."

internal fun restrictionsNoticeLead(name: String): String = "Set by you, the owner. $name can’t turn these off."

/** The profile sheet's note under Restrictions: what asks for the owner's lock. */
internal fun restrictionsGateNote(kind: LockKind): String =
    "Restrictions and resets ask for your ${if (kind == LockKind.Pin) "PIN" else "sequence"} first."
