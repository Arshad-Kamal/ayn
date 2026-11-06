package com.arshad.ayn.ui.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.PagePill
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PrivateTextInput
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.weightSpan

// b3-search geometry (px ÷ 2): the page head is 75 dp tall (64 compact) from the safe line; the
// answer starts 75 dp down, a statement 150.
private val HeadHeight = 75.dp
private val CompactHeadHeight = 64.dp
private val QueryTop = 5.dp
private val CompactQueryTop = 4.dp
private val HintTop = 15.dp
private val SuggestionsTop = 20.dp
private val SuggestionGap = 8.dp
private val SuggestionsWidth = 750.dp
private val AnswerTop = 75.dp
private val StatementTop = 150.dp
private val LabelSize = 13.sp
private val PillTextSize = 12.5.sp
private val SuggestionTextSize = 14.5.sp
private val PlaceholderInk = KeColors.Ink.copy(alpha = 0.3f)

/**
 * Search (`b3-search` states 1–9) on [SearchViewModel]. The query is the page heading and is
 * always visible (B-D6): typed with the system keyboard, YouTube's suggestions below it, then the
 * answer under the query with Edit search, which (like BACK) reopens the keyboard with the query
 * selected so typing replaces it. A Short on the shelf plays through [onPlayShort] with the shelf
 * as its run (M7-D13). Holding OK on a video or Short opens its card menu on [menu] (BACK then
 * steps the menu back; [onRailShade] while it shows). A kids profile's Search ([approvedFor]: the
 * kid's name, `kp-kids` kidsearch) says it looks only through what is approved for them.
 */
@Composable
internal fun SearchScreen(
    viewModel: SearchViewModel,
    menu: VideoMenuViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    approvedFor: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = searchLayout(state, LocalContentFilter.current)
    val copy = remember(approvedFor) { SearchCopy(approvedFor) }
    val playShort: (VideoItem) -> Unit = { short -> onPlayShort(short, viewModel.openShorts()) }
    // Read as the page comes back from the Shorts player (a covered destination leaves composition).
    var shortsReturn by remember { mutableStateOf(viewModel.shortsReturn()) }
    // The ViewModel hears every change and seeds the field when it is recreated.
    val field = remember { QueryFieldState(state.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val onQueryChange: (TextFieldValue) -> Unit = {
        field.onValueChange(it)
        viewModel.onQueryChange(field.value.text)
    }
    val submit: () -> Unit = {
        viewModel.submit()
        // A blank query isn't searched: the keyboard stays up for it.
        if (!viewModel.uiState.value.editing) keyboard?.hide()
    }
    val editSearch: () -> Unit = {
        field.selectAll(state.query)
        viewModel.editSearch()
    }
    // Registered before VideoMenuHost's, so the menu's BACK wins while it shows.
    BackHandler(enabled = layout !is SearchLayout.Typing, onBack = editSearch)

    VideoMenuHost(menu, onPlay, onOpenChannel, onRailShade, modifier, onPlayShort = playShort) { pageModifier, openMenu ->
        ScreenFocus(
            onNothingToFocus = onNothingToFocus,
            modifier = pageModifier,
            initialFocusKey = layout.initialFocusKey,
            entryKey = layout.entryKey,
            focusHeldElsewhere = focusHeldElsewhere,
            returnKey = shortsReturn?.let(::shortKey),
            onReturned = {
                shortsReturn = null
                viewModel.shortsReturned()
            },
        ) {
            when (layout) {
                SearchLayout.Typing -> Typing(
                    field = field,
                    copy = copy,
                    suggestions = state.suggestions,
                    onQueryChange = onQueryChange,
                    onSubmit = submit,
                    onPick = { suggestion ->
                        onQueryChange(TextFieldValue(suggestion, TextRange(suggestion.length)))
                        submit()
                    },
                )
                is SearchLayout.Searching -> Answer(layout.query, copy) { SkeletonGrid() }
                is SearchLayout.Results -> Results(
                    layout = layout,
                    copy = copy,
                    onEditSearch = editSearch,
                    onPlay = { video -> onPlay(video, false) },
                    onPlayShort = playShort,
                    onOpenMenu = openMenu,
                    onOpenShortMenu = { short -> menu.open(short, VideoSource.Elsewhere, short = true) },
                    onOpenChannel = onOpenChannel,
                )
                is SearchLayout.NoResults -> Answer(layout.query, copy) {
                    SearchStatement(
                        kicker = Kicker(R.drawable.ic_nav_search, copy.noResultsKicker),
                        title = copy.noResultsTitle(layout.query),
                        body = copy.noResultsBody,
                    ) {
                        PillButton(R.drawable.ic_edit, "Edit search", EditKey, editSearch)
                    }
                }
                is SearchLayout.Failed -> Answer(layout.query, copy) { FailedStatement(layout.failure, onRetry = viewModel::submit, editSearch) }
            }
        }
    }
}

/** States 1–3: the field under the Search label, a hint while it's empty, suggestions once typed. */
@Composable
private fun Typing(
    field: QueryFieldState,
    copy: SearchCopy,
    suggestions: List<String>,
    onQueryChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().restingRoomLight().pagePadding()) {
        Column(Modifier.height(HeadHeight)) {
            SearchLabel(copy)
            QueryField(field.value, copy.placeholder, onQueryChange, field::onCursorKey, onSubmit, Modifier.padding(top = QueryTop))
        }
        when {
            field.value.text.isEmpty() -> Text(
                text = "Type or press the mic to speak.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = KeColors.Ink3,
                modifier = Modifier.padding(top = HintTop),
            )
            suggestions.isNotEmpty() -> FlowRow(
                modifier = Modifier.padding(top = SuggestionsTop).widthIn(max = SuggestionsWidth),
                horizontalArrangement = Arrangement.spacedBy(SuggestionGap),
                verticalArrangement = Arrangement.spacedBy(SuggestionGap),
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionPill(field.value.text, suggestion, onClick = { onPick(suggestion) })
                }
            }
        }
    }
}

/**
 * The query as the page heading, typed with the system keyboard: focus brings the keyboard up,
 * its Search key submits. DOWN leaves the field for the suggestions (a field holding text keeps
 * DOWN for its cursor otherwise). An empty field has no cursor to move, so
 * LEFT leaves it for the rail from any key source (the field's own D-pad exit takes only some
 * devices' keys). Otherwise LEFT and RIGHT stay with the field's cursor; [onCursorKey] first tells
 * the state the user is moving it (ending Edit search's held selection).
 * The keyboard neither corrects nor learns from what is typed, so it can't offer one profile's
 * searches to another (§ B3p).
 */
@Composable
private fun QueryField(
    value: TextFieldValue,
    placeholder: String,
    onValueChange: (TextFieldValue) -> Unit,
    onCursorKey: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val style = MaterialTheme.typography.displayMedium.copy(color = KeColors.Ink)
    PrivateTextInput {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .fillMaxWidth()
                .focusMemory(QueryKey)
                .onFocusChanged { if (it.isFocused) keyboard?.show() }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionLeft if value.text.isEmpty() -> focusManager.moveFocus(FocusDirection.Left)
                        Key.DirectionLeft, Key.DirectionRight -> {
                            onCursorKey()
                            false
                        }
                        else -> false
                    }
                },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(KeColors.Ink),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Text(placeholder, style = style, color = PlaceholderInk, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** A suggestion: what was typed in regular weight, YouTube's completion in bold. */
@Composable
private fun SuggestionPill(typed: String, suggestion: String, onClick: () -> Unit) {
    val typography = MaterialTheme.typography
    val typedLength = if (suggestion.startsWith(typed, ignoreCase = true)) typed.length else 0
    val text = buildAnnotatedString {
        append(suggestion.substring(0, typedLength))
        withStyle(weightSpan(640)) {
            append(suggestion.substring(typedLength))
        }
    }
    PagePill(onClick) {
        TintedText(text = text, style = typography.bodySmall.copy(fontSize = SuggestionTextSize), maxLines = 1)
    }
}

/** States 4–5 and M7.m shorts: the answer under the compact query, a focused card lighting the room. */
@Composable
private fun Results(
    layout: SearchLayout.Results,
    copy: SearchCopy,
    onEditSearch: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val cardLight = remember { FocusedCardLight() }
    var gridFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        CardRoomLight(cardLight, lit = gridFocused)
        CompactHead(layout.query, copy) {
            PagePill(onEditSearch, Modifier.focusMemory(EditKey)) {
                TintedIcon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(14.dp))
                TintedText("Edit search", style = MaterialTheme.typography.labelLarge.copy(fontSize = PillTextSize))
            }
        }
        ResultGrid(
            items = layout.items,
            shorts = layout.shorts,
            onPlay = onPlay,
            onPlayShort = onPlayShort,
            onOpenMenu = onOpenMenu,
            onOpenShortMenu = onOpenShortMenu,
            onOpenChannel = onOpenChannel,
            onCardFocused = cardLight::onCardFocused,
            modifier = Modifier
                .padding(top = AnswerTop)
                .onFocusChanged { gridFocused = it.hasFocus },
        )
    }
}

/** States 6–9: the submitted query in the compact head, [content] below it. */
@Composable
private fun Answer(query: String, copy: SearchCopy, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        CompactHead(query, copy)
        Box(Modifier.padding(top = AnswerTop)) { content() }
    }
}

@Composable
private fun CompactHead(query: String, copy: SearchCopy, action: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.pagePadding().height(CompactHeadHeight)) {
        SearchLabel(copy)
        Row(
            modifier = Modifier.padding(top = CompactQueryTop),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = query,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            action()
        }
    }
}

@Composable
private fun SearchLabel(copy: SearchCopy) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(14.dp))
        Text(copy.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = LabelSize), color = KeColors.Ink3)
    }
}

/** Search's words: YouTube's search, or a kids profile's local one over what is approved for
 *  [approvedFor] (`kp-kids` kidsearch, kidsearchnone). */
internal class SearchCopy(private val approvedFor: String?) {
    val label: String = approvedFor?.let { "Search · only what’s approved for $it" } ?: "Search"
    val placeholder: String = if (approvedFor != null) "Search what’s approved" else "Search YouTube"
    val noResultsKicker: String = if (approvedFor != null) "No match" else "No results"
    val noResultsBody: String = if (approvedFor != null) "Ask a grown-up to approve more." else "Check the spelling, or try fewer words."

    fun noResultsTitle(query: String): String =
        if (approvedFor != null) "Nothing approved matches “$query”" else "Nothing matched that search"
}

@Composable
private fun FailedStatement(failure: AccessFailure, onRetry: () -> Unit, onEditSearch: () -> Unit) {
    val offline = failure == AccessFailure.OFFLINE
    SearchStatement(
        kicker = failureKicker(failure),
        title = "Search didn’t go through",
        body = if (offline) {
            "Your search is kept. Check that the Shield is online and try again."
        } else {
            "Your search is kept. $UnreadableReply"
        },
    ) {
        PillButton(R.drawable.ic_retry, "Try again", RetryKey, onRetry)
        PillButton(R.drawable.ic_edit, "Edit search", EditKey, onEditSearch, style = ControlStyle.Secondary)
    }
}

@Composable
private fun SearchStatement(
    kicker: Kicker,
    title: String,
    body: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Statement(
        kicker = kicker,
        title = title,
        body = body,
        modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop - AnswerTop),
        actions = actions,
    )
}

private fun Modifier.pagePadding(): Modifier =
    padding(start = KeLayout.ContentStart, top = KeLayout.SafeVertical, end = KeLayout.SafeHorizontal)
