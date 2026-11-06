package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
import com.arshad.ayn.feature.settings.addedText
import com.arshad.ayn.feature.settings.keywordProblemText
import com.arshad.ayn.feature.settings.minutesLabel
import com.arshad.ayn.feature.settings.monthDayText
import com.arshad.ayn.feature.settings.profileScope
import com.arshad.ayn.feature.settings.suggestionsNote
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.aynFieldColors
import com.arshad.ayn.ui.mobile.menu.SheetRow
import com.arshad.ayn.ui.mobile.menu.StageSheet
import com.arshad.ayn.ui.mobile.menu.tap
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.theme.AynColors
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.time.toJavaInstant

/** The sheets over Filters (`m6-settings` 18–21, P2–P5), on [me]'s own filters. */
@Composable
internal fun FiltersSheets(state: FiltersUiState, me: Profile, filters: FiltersViewModel, time: ShellTime) {
    StageSheet(state.sheet, onDismiss = filters::onDismiss, onBack = filters::onBack, stageKey = { it::class }) { shown ->
        val settings = state.profile?.settings ?: return@StageSheet
        val kicker = "Filters · ${profileScope(me.name)}"
        when (shown) {
            FiltersSheet.RestrictedMode -> RestrictedModeChoices(kicker, settings.restrictedMode, filters::onRestrictedModePicked)
            FiltersSheet.Length -> LengthChoices(kicker, settings.lengthLimits, filters::onShorterPicked, filters::onLongerPicked)
            is FiltersSheet.Keywords -> RuleList(
                kicker = kicker,
                copy = keywordsCopy("Whole words only, ignoring case and accents: “prank” hides “PRANK WAR” but not “pranksters”. DeArrow titles are checked too."),
                draft = shown.draft,
                rules = state.ownKeywords.map { Listed(it.rule.word, it.addedAtMillis) },
                edits = RuleListEdits(filters::onAddKeyword, filters::onKeywordChange, filters::onKeywordDone, filters::onRemoveKeyword),
                problemText = { problem, word -> keywordProblemText(problem, word) },
                time = time,
            )
            FiltersSheet.BlockedChannels -> BlockedChannels(kicker, YOUR_PROFILE, state.ownChannels, filters::onUnblockChannel, time, state::alsoIn)
            FiltersSheet.BlockedVideos -> BlockedVideos(kicker, YOUR_PROFILE, state.ownVideos, filters::onUnblockVideo, time, state::alsoIn)
            is FiltersSheet.UnblockWhere -> UnblockWhere(shown.rule, me, state.alsoIn(shown.rule), filters)
            is FiltersSheet.Pack -> PackSheet(
                kicker = kicker,
                pack = shown.pack,
                packs = settings.filterPacks,
                target = null,
                kids = state.kids,
                actions = PackActions(filters::onUsePack, filters::onPackOption, filters::onIslamicChannel),
            )
            // Its stage key is its class, so a tick keeps the sheet.
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
}

/** Unblock where? (`m6-settings` 21b, KP.15): the owner's block of [rule] is also on [others];
 *  a tap on either row unblocks and returns to the list. */
@Composable
private fun UnblockWhere(rule: SelfRule, me: Profile, others: List<Profile>, filters: FiltersViewModel) {
    val copy = UnblockWhereCopy(rule, me, others)
    SheetColumn(copy.kicker, copy.title, copy.sub, choices = false) {
        SheetRow(copy.everywhere, tap(filters::onUnblockEverywhere), hint = copy.everywhereHint)
        SheetRow(copy.hereOnly, tap(filters::onUnblockHereOnly), hint = copy.hereOnlyHint)
    }
}

/** Restricted Mode (state 18): Off or Strict, with YouTube's own caveats; a pick saves and closes. */
@Composable
internal fun RestrictedModeChoices(kicker: String, chosen: RestrictedMode, onPick: (RestrictedMode) -> Unit) {
    SheetColumn(
        kicker,
        "Restricted Mode",
        "YouTube’s own automated filter for mature videos. Ayn asks YouTube to apply it to search, related videos and playback.",
        note = "It hides some videos that are fine and misses some that aren’t. Your subscription feeds and saved videos aren’t checked " +
            "until you play one: a video it blocks then won’t play.",
    ) {
        ChoiceRow("Off", chosen == RestrictedMode.Off, { onPick(RestrictedMode.Off) }, hint = "YouTube’s results as they come")
        ChoiceRow("Strict", chosen == RestrictedMode.Strict, { onPick(RestrictedMode.Strict) }, hint = "Hides what YouTube’s filter flags, and turns off search suggestions")
    }
}

/** Video length (state 19): two groups of chips, one choice each; a pick saves and the sheet stays. */
@Composable
internal fun LengthChoices(kicker: String, limits: LengthLimits, onShorter: (Int?) -> Unit, onLonger: (Int?) -> Unit) {
    SheetColumn(kicker, "Video length", "One choice in each group. Live streams have no length, so these never hide them.", choices = false) {
        ChoiceHeading("Hide videos shorter than")
        PickChips(listOf(null) + SHORTER_THAN_MINUTES, limits.minMinutes, ::lengthLabel, onShorter)
        ChoiceHeading("Hide videos longer than")
        PickChips(listOf(null) + LONGER_THAN_MINUTES, limits.maxMinutes, ::lengthLabel, onLonger)
    }
}

private fun lengthLabel(minutes: Int?): String = minutes?.let(::minutesLabel) ?: "Off"

/** A rule list sheet's words; [code] lists the rules in a monospace face (patterns). */
internal class RuleListCopy(val title: String, val sub: String, val add: String, val note: String, val code: Boolean = false)

/** What a rule list's rows do: Add starts a [draft][RuleDraft], the field changes and saves it,
 *  Remove takes a rule off. */
internal class RuleListEdits(val onAdd: () -> Unit, val onChange: (String) -> Unit, val onDone: () -> Unit, val onRemove: (String) -> Unit)

/**
 * A list of typed rules (states 20, 24): Add, then the [rules] newest first, each with when it was
 * added and Remove. Add becomes the field with the keyboard up; its ✓ key saves to the top of the
 * list and Add comes back, or the field keeps the text and says why ([problemText]).
 */
@Composable
internal fun RuleList(
    kicker: String,
    copy: RuleListCopy,
    draft: RuleDraft?,
    rules: List<Listed<String>>,
    edits: RuleListEdits,
    problemText: (RuleProblem, String) -> String,
    time: ShellTime,
) {
    val font = if (copy.code) FontFamily.Monospace else null
    val dates = rememberRuleDates(time)
    SheetColumn(kicker, copy.title, copy.sub, Modifier.imePadding(), choices = false, note = copy.note) {
        if (draft == null) {
            SheetRow(copy.add, tap(edits.onAdd), icon = R.drawable.ic_plus)
        } else {
            RuleField(copy.add, draft, font, edits, problemText)
        }
        rules.forEach { rule ->
            RemoveRow(
                label = rule.rule,
                hint = dates.added(rule.addedAtMillis),
                action = "Remove",
                onClick = { edits.onRemove(rule.rule) },
                icon = R.drawable.ic_trash,
                font = font,
            )
        }
    }
}

/** The rule being typed (`.addf .field`), focused with the keyboard up; why it wasn't saved under it. */
@Composable
private fun RuleField(label: String, draft: RuleDraft, font: FontFamily?, edits: RuleListEdits, problemText: (RuleProblem, String) -> String) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.requestFocus() }
    val reason = draft.problem?.let { problemText(it, draft.text) }
    TextField(
        value = draft.text,
        onValueChange = edits.onChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = draft.saving,
        isError = reason != null,
        // The reason is the field's own supporting text, so TalkBack reads it as the field's error.
        supportingText = reason?.let { { FieldProblem(it) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = font ?: MaterialTheme.typography.bodyLarge.fontFamily),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { edits.onDone() }),
        colors = aynFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).focusRequester(field),
    )
}

/** Why the rule wasn't saved (`.ferr`): the alert, then the reason. */
@Composable
private fun FieldProblem(reason: String) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AynColors.ErrorInk, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(reason, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = AynColors.ErrorInk)
    }
}

/** Blocked channels (state 21, 21a): each with when it was blocked (and where else the owner
 *  blocked it, [alsoIn]), newest first; Unblock takes it off at once, or for one also blocked
 *  elsewhere asks where first (21b; [onUnblock] decides). The sheet closes with the last one.
 *  [whose] is the profile they're hidden from. */
@Composable
internal fun BlockedChannels(
    kicker: String,
    whose: String,
    channels: List<Listed<BlockedChannel>>,
    onUnblock: (BlockedChannel) -> Unit,
    time: ShellTime,
    alsoIn: (SelfRule) -> List<Profile> = { emptyList() },
) {
    val dates = rememberRuleDates(time)
    SheetColumn(
        kicker,
        "Blocked channels",
        "Nothing from these channels shows anywhere in $whose, and their videos won’t play.",
        choices = false,
        note = "To block a channel, long-press any of its videos and choose Block.",
    ) {
        channels.forEach { listed ->
            val name = listed.rule.name ?: listed.rule.channelUrl
            RemoveRow(
                label = name,
                hint = dates.blocked(listed.addedAtMillis) + alsoInTail(alsoIn(listed.rule)),
                action = "Unblock",
                onClick = { onUnblock(listed.rule) },
                lead = { Box(Modifier.size(40.dp)) { ChannelAvatar(name, url = null, monogramSize = 14f) } },
            )
        }
    }
}

/** Blocked videos (unmocked; Blocked channels' layout and Unblock): each video's title and when it was blocked. */
@Composable
internal fun BlockedVideos(
    kicker: String,
    whose: String,
    videos: List<Listed<BlockedVideo>>,
    onUnblock: (BlockedVideo) -> Unit,
    time: ShellTime,
    alsoIn: (SelfRule) -> List<Profile> = { emptyList() },
) {
    val dates = rememberRuleDates(time)
    SheetColumn(
        kicker,
        "Blocked videos",
        "These videos don’t show anywhere in $whose, and won’t play.",
        choices = false,
        note = "To block a video, long-press it and choose Block this video.",
    ) {
        videos.forEach { listed ->
            RemoveRow(
                label = listed.rule.title ?: listed.rule.videoId,
                hint = dates.blocked(listed.addedAtMillis) + alsoInTail(alsoIn(listed.rule)),
                action = "Unblock",
                onClick = { onUnblock(listed.rule) },
            )
        }
    }
}

/** A listed rule (`.mrow.kw`): [lead], its [label] (in [font] when it is code) over its [hint], then
 *  the small tonal [action] button (after [icon]). */
@Composable
internal fun RemoveRow(
    label: String,
    hint: String,
    action: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    font: FontFamily? = null,
    lead: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = if (font != null) 14.sp else 15.5.sp, fontFamily = font ?: MaterialTheme.typography.titleMedium.fontFamily),
            )
            Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 2.dp))
        }
        PillButton(action, onClick, Modifier.height(36.dp).semantics { contentDescription = "$action $label" }, kind = ButtonKind.Tonal, icon = icon)
    }
}

/** How the listed rules date themselves, from today in [time]'s zone and this phone's locale. */
internal class RuleDates(private val today: LocalDate, private val zone: ZoneId, private val locale: Locale) {
    fun added(epochMillis: Long): String = addedText(epochMillis, today, zone, locale)

    fun blocked(epochMillis: Long): String = "Blocked ${monthDayText(epochMillis, today, zone, locale)}"

    fun approved(epochMillis: Long): String = "Approved ${monthDayText(epochMillis, today, zone, locale)}"
}

@Composable
internal fun rememberRuleDates(time: ShellTime): RuleDates {
    val locale = LocalConfiguration.current.locales[0]
    return remember(time, locale) { RuleDates(time.clock.now().toJavaInstant().atZone(time.zone).toLocalDate(), time.zone, locale) }
}
