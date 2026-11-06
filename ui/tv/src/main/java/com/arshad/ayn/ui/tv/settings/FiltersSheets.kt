package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SelfRule
import com.arshad.ayn.feature.settings.FiltersSheet
import com.arshad.ayn.feature.settings.FiltersUiState
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.LONGER_THAN_MINUTES
import com.arshad.ayn.feature.settings.Listed
import com.arshad.ayn.feature.settings.RuleDraft
import com.arshad.ayn.feature.settings.RuleProblem
import com.arshad.ayn.feature.settings.SHORTER_THAN_MINUTES
import com.arshad.ayn.feature.settings.UnblockWhereCopy
import com.arshad.ayn.feature.settings.alsoInTail
import com.arshad.ayn.feature.settings.keywordProblemText
import com.arshad.ayn.feature.settings.minutesLabel
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.feature.settings.suggestionsNote
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.TintedIcon

private const val ADD_RULE_KEY = "add"
private const val OFF_KEY = "off"

/** The sheet over the Filters page, [me]'s (`kp-filters` rm, length, keywords, keywordadd, channels,
 *  fpk-harmful, fpk-suggest, fpk-halal, fpk-islamic). */
@Composable
internal fun FiltersSheetContent(shown: FiltersSheet, state: FiltersUiState, me: Profile?, filters: FiltersViewModel) {
    me ?: return
    val settings = state.profile?.settings ?: return
    val kicker = "Filters · ${profileScope(me.name)}"
    when (shown) {
        FiltersSheet.RestrictedMode -> RestrictedModeSheet(kicker, settings.restrictedMode, filters::onRestrictedModePicked)
        FiltersSheet.Length -> LengthSheet(kicker, settings.lengthLimits, filters::onShorterPicked, filters::onLongerPicked)
        is FiltersSheet.Keywords -> RuleListSheet(
            kicker = kicker,
            copy = KeywordsCopy,
            draft = shown.draft,
            rules = state.ownKeywords.map { it.rule.word },
            edits = RuleListEdits(filters::onAddKeyword, filters::onKeywordChange, filters::onKeywordDone, filters::onRemoveKeyword),
            problemText = { problem, word -> keywordProblemText(problem, word) },
        )
        FiltersSheet.BlockedChannels -> BlockedChannelsSheet(kicker, "your profile", state.ownChannels, filters::onUnblockChannel, state.listFocus, state::alsoIn)
        FiltersSheet.BlockedVideos -> BlockedVideosSheet(kicker, "your profile", state.ownVideos, filters::onUnblockVideo, state.listFocus, state::alsoIn)
        is FiltersSheet.UnblockWhere -> UnblockWhereSheet(shown.rule, me, state.alsoIn(shown.rule), filters)
        is FiltersSheet.Pack -> PackSheet(
            kicker = kicker,
            pack = shown.pack,
            packs = settings.filterPacks,
            target = null,
            kids = state.kids,
            actions = PackActions(filters::onUsePack, filters::onPackOption, filters::onIslamicChannel),
        )
        is FiltersSheet.Suggestions -> SuggestionsSheet(
            offer = shown.offer,
            restriction = false,
            note = suggestionsNote(target = null),
            onTick = filters::onSuggestionTicked,
            onTurnOn = filters::onTurnOnSuggestions,
            onNotNow = filters::onNotNow,
        )
    }
}

private const val EVERYWHERE_KEY = "everywhere"
private const val HERE_ONLY_KEY = "here-only"

/** Unblock where? (`kp-filters` unblockall): the owner's block of [rule] is also on
 *  [others]; Unblock in all profiles (focused) or Only in your profile, either back to the list. */
@Composable
private fun UnblockWhereSheet(rule: SelfRule, me: Profile, others: List<Profile>, filters: FiltersViewModel) {
    val copy = UnblockWhereCopy(rule, me, others)
    val focus = rememberSheetFocus(EVERYWHERE_KEY)
    SettingsSheet(kicker = copy.kicker, title = { SheetTitle(copy.title) }, sub = copy.sub) {
        SettingRow(
            label = copy.everywhere,
            hint = copy.everywhereHint,
            minHeight = SheetRowMinHeight,
            onClick = filters::onUnblockEverywhere,
            modifier = focus.on(EVERYWHERE_KEY),
        )
        SettingRow(
            label = copy.hereOnly,
            hint = copy.hereOnlyHint,
            minHeight = SheetRowMinHeight,
            onClick = filters::onUnblockHereOnly,
            modifier = focus.on(HERE_ONLY_KEY),
        )
    }
}

/** Restricted Mode (`kp-filters` rm; KP-D2, D6, D15): Off or Strict; OK picks ([onPick]) and closes. */
@Composable
internal fun RestrictedModeSheet(kicker: String, chosen: RestrictedMode, onPick: (RestrictedMode) -> Unit) {
    val focus = rememberSheetFocus(chosen.key)
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Restricted Mode") },
        sub = "YouTube’s own automated filter for mature videos. Ayn asks YouTube to apply it to search, related videos and playback.",
        note = "It hides some videos that are fine and misses some that aren’t. Your subscription feeds and saved videos aren’t " +
            "checked until you play one: a video it blocks then won’t play.",
    ) {
        Choice(
            label = "Off",
            hint = "YouTube’s results as they come",
            on = chosen == RestrictedMode.Off,
            onClick = { onPick(RestrictedMode.Off) },
            modifier = focus.on(RestrictedMode.Off.key),
        )
        Choice(
            label = "Strict",
            hint = "Hides what YouTube’s filter flags, and turns off search suggestions",
            on = chosen == RestrictedMode.Strict,
            onClick = { onPick(RestrictedMode.Strict) },
            modifier = focus.on(RestrictedMode.Strict.key),
        )
    }
}

/**
 * Video length (`kp-filters` length; F3): Off and each bound in two groups, one choice each; a
 * pick keeps the sheet open, so both groups can change. It opens on the shorter-than choice (Off
 * when the stored bound isn't offered).
 */
@Composable
internal fun LengthSheet(kicker: String, limits: LengthLimits, onShorter: (Int?) -> Unit, onLonger: (Int?) -> Unit) {
    val focus = rememberSheetFocus(lengthKey("min", limits.minMinutes?.takeIf { it in SHORTER_THAN_MINUTES }))
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Video length") },
        sub = "One choice in each group. Live streams have no length, so these never hide them.",
        scroll = true,
    ) {
        SheetGroupHeading("Hide videos shorter than")
        (listOf(null) + SHORTER_THAN_MINUTES).forEach { minutes ->
            Choice(
                label = minutes?.let(::minutesLabel) ?: "Off",
                on = limits.minMinutes == minutes,
                onClick = { onShorter(minutes) },
                modifier = focus.on(lengthKey("min", minutes)),
            )
        }
        SheetGroupHeading("Hide videos longer than")
        (listOf(null) + LONGER_THAN_MINUTES).forEach { minutes ->
            Choice(
                label = minutes?.let(::minutesLabel) ?: "Off",
                on = limits.maxMinutes == minutes,
                onClick = { onLonger(minutes) },
                modifier = focus.on(lengthKey("max", minutes)),
            )
        }
    }
}

private fun lengthKey(group: String, minutes: Int?): String = "$group-${minutes ?: OFF_KEY}"

/** A rule list sheet's words: [title], [sub], the [add] row's label and the [note] at its foot;
 *  [code] lists the rules in a monospace face (patterns). */
internal class RuleListCopy(val title: String, val sub: String, val add: String, val note: String, val code: Boolean = false)

private val KeywordsCopy = RuleListCopy(
    title = "Keywords",
    sub = "A video is hidden when its title uses one of these words. OK on a word removes it.",
    add = "Add a word",
    note = "Whole words only, ignoring case and accents: “prank” hides “PRANK WAR” but not “pranksters”. DeArrow titles are checked too.",
)

/** What a rule list sheet's rows do: the Add row starts a [draft][RuleDraft], the field changes
 *  and saves it, OK on a rule removes it. */
internal class RuleListEdits(
    val onAdd: () -> Unit,
    val onChange: (String) -> Unit,
    val onDone: () -> Unit,
    val onRemove: (String) -> Unit,
)

/**
 * A list of typed rules (`kp-filters` keywords, keywordadd, patterns, patternbad): the Add row,
 * then the [rules], newest first; OK on one removes it. The Add row turns into the field in place
 * (the system keyboard comes up); Done saves to the top of the list and the Add row comes back
 * focused, or the field keeps the text and focus with why it wasn't saved ([problemText]). A
 * removed rule hands focus to the next row.
 */
@Composable
internal fun RuleListSheet(
    kicker: String,
    copy: RuleListCopy,
    draft: RuleDraft?,
    rules: List<String>,
    edits: RuleListEdits,
    problemText: (RuleProblem, String) -> String,
) {
    val focus = rememberSheetFocus(ADD_RULE_KEY)
    FocusOnClose(open = draft != null, target = focus.requester(ADD_RULE_KEY))
    val keys = rules.map { "rule-$it" }
    val font = if (copy.code) FontFamily.Monospace else null
    SettingsSheet(kicker = kicker, title = { SheetTitle(copy.title) }, sub = copy.sub, note = copy.note, scroll = true) {
        if (draft == null) {
            SettingRow(
                label = copy.add,
                minHeight = SheetRowMinHeight,
                onClick = edits.onAdd,
                modifier = focus.on(ADD_RULE_KEY),
                lead = { RowIcon(R.drawable.ic_plus, it) },
            )
        } else {
            SettingEditRow(
                label = null,
                value = draft.text,
                onValueChange = edits.onChange,
                onDone = edits.onDone,
                readOnly = draft.saving,
                minHeight = SheetRowMinHeight,
                capitalization = KeyboardCapitalization.None,
                fieldFont = font,
                leadIcon = R.drawable.ic_plus,
            )
            draft.problem?.let { FieldProblem(problemText(it, draft.text), Modifier.padding(start = RowInset, top = 6.dp, bottom = 4.dp)) }
        }
        rules.forEachIndexed { index, rule ->
            key(keys[index]) {
                RemoveRow(
                    label = rule,
                    action = "Remove",
                    onClick = {
                        focus.focus(keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1) ?: ADD_RULE_KEY)
                        edits.onRemove(rule)
                    },
                    modifier = focus.on(keys[index]),
                    actionIcon = R.drawable.ic_trash,
                    labelFont = font,
                )
            }
        }
    }
}

/**
 * Blocked channels (`kp-filters` channels; F2, by channel id): each channel's monogram, name and
 * when it was blocked (and where else the owner blocked it, [alsoIn]), newest first; OK unblocks
 * it and focus moves to the next, or, for one the owner also blocked elsewhere, asks where first
 * ([onUnblock] decides). It opens on [focusKey]'s row when that is listed, else the first. The
 * sheet closes with the last one. [whose] is the profile they're hidden from.
 */
@Composable
internal fun BlockedChannelsSheet(
    kicker: String,
    whose: String,
    channels: List<Listed<BlockedChannel>>,
    onUnblock: (BlockedChannel) -> Unit,
    focusKey: String? = null,
    alsoIn: (SelfRule) -> List<Profile> = { emptyList() },
) {
    val keys = channels.map { it.rule.channelUrl }
    val focus = rememberSheetFocus(focusKey?.takeIf { it in keys } ?: keys.firstOrNull().orEmpty())
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Blocked channels") },
        sub = "Nothing from these channels shows anywhere in $whose, and their videos won’t play. OK unblocks one.",
        note = "To block a channel, hold OK on any of its videos and choose Block.",
        scroll = true,
    ) {
        channels.forEachIndexed { index, listed ->
            val name = listed.rule.name ?: listed.rule.channelUrl
            key(keys[index]) {
                RemoveRow(
                    label = name,
                    hint = "Blocked ${monthDay(listed.addedAtMillis)}${alsoInTail(alsoIn(listed.rule))}",
                    action = "Unblock",
                    onClick = {
                        (keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1))?.let(focus::focus)
                        onUnblock(listed.rule)
                    },
                    modifier = focus.on(keys[index]),
                    lead = { ChannelMonogramLead(name) },
                )
            }
        }
    }
}

/**
 * Blocked videos (unmocked; Blocked channels' layout): each video's title and when it was
 * blocked (and where else, [alsoIn]), newest first; OK unblocks it (or asks where, as Blocked
 * channels does) and focus moves to the next. It opens on [focusKey]'s row when listed. The sheet
 * closes with the last one.
 */
@Composable
internal fun BlockedVideosSheet(
    kicker: String,
    whose: String,
    videos: List<Listed<BlockedVideo>>,
    onUnblock: (BlockedVideo) -> Unit,
    focusKey: String? = null,
    alsoIn: (SelfRule) -> List<Profile> = { emptyList() },
) {
    val keys = videos.map { it.rule.videoId }
    val focus = rememberSheetFocus(focusKey?.takeIf { it in keys } ?: keys.firstOrNull().orEmpty())
    SettingsSheet(
        kicker = kicker,
        title = { SheetTitle("Blocked videos") },
        sub = "These videos don’t show anywhere in $whose, and won’t play. OK unblocks one.",
        note = "To block a video, hold OK on it and choose Block this video.",
        scroll = true,
    ) {
        videos.forEachIndexed { index, listed ->
            key(keys[index]) {
                RemoveRow(
                    label = listed.rule.title ?: listed.rule.videoId,
                    hint = "Blocked ${monthDay(listed.addedAtMillis)}${alsoInTail(alsoIn(listed.rule))}",
                    action = "Unblock",
                    onClick = {
                        (keys.getOrNull(index + 1) ?: keys.getOrNull(index - 1))?.let(focus::focus)
                        onUnblock(listed.rule)
                    },
                    modifier = focus.on(keys[index]),
                )
            }
        }
    }
}

// `.srow .cav` (px ÷ 2): 26 dp round.
private val ChannelMonogram = 26.dp

/** A channel's avatar at a row's start (`.srow .cav`): its picture, else its initials. */
@Composable
internal fun ChannelMonogramLead(name: String, url: String? = null) {
    Box(Modifier.size(ChannelMonogram)) { AvatarPicture(name, url, monogramSize = 11.sp) }
}

/** A listed rule OK takes off: its [label] (in [labelFont] when it is code) and [hint], then
 *  [action] ("Remove", "Unblock"), after [actionIcon] when it has one. */
@Composable
internal fun RemoveRow(
    label: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier,
    hint: String? = null,
    @DrawableRes actionIcon: Int? = null,
    labelFont: FontFamily? = null,
    lead: (@Composable (RowTones) -> Unit)? = null,
) {
    SettingRow(
        label = label,
        hint = hint,
        labelMaxLines = if (labelFont != null) 3 else 1,
        labelFont = labelFont,
        value = action,
        chevron = false,
        minHeight = SheetRowMinHeight,
        onClick = onClick,
        modifier = modifier,
        lead = lead,
        valueLead = if (actionIcon == null) {
            null
        } else {
            { tones -> TintedIcon(painterResource(actionIcon), contentDescription = null, tint = tones.soft, modifier = Modifier.size(14.dp)) }
        },
    )
}
