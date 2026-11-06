package com.arshad.ayn.ui.mobile.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.search.SearchState
import com.arshad.ayn.feature.search.SearchUiState
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.menu.openShort
import com.arshad.ayn.ui.mobile.shell.PillHead
import com.arshad.ayn.ui.mobile.shell.PillHeight
import com.arshad.ayn.ui.mobile.shell.PillTextStyle
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.pillGround
import com.arshad.ayn.ui.mobile.shell.rememberShortsReturn
import com.arshad.ayn.ui.mobile.shell.searchIcon
import com.arshad.ayn.ui.mobile.shell.searchPlaceholder
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlinx.coroutines.flow.Flow

/** What Search shows (`m6-search` states), derived from [SearchUiState]. */
internal sealed interface SearchShown {
    /** States 3–5, 14: the query being typed, with what helps under it. */
    data object Typing : SearchShown

    /** State 6: skeleton rows under the query. */
    data object Searching : SearchShown

    /** States 7–9, 15: the answer, a channel first when YouTube puts one there, the Shorts shelf
     *  while Show Shorts is on (one of the two is never empty). */
    data class Results(val items: List<ListingItem>, val shorts: List<VideoItem>) : SearchShown

    /** States 11, 16. */
    data class NoResults(val query: String) : SearchShown

    /** States 12–13: the query is kept for another try. */
    data class Failed(val failure: AccessFailure) : SearchShown
}

/** Without what [filter] now hides: a block from a row's menu takes it out at once (KP-D19), and
 *  a block that leaves nothing shows No results. Playlists aren't listed: nothing here opens one. */
internal fun searchShown(state: SearchUiState, filter: ContentFilter?): SearchShown {
    val query = state.submittedQuery
    if (state.editing || query == null) return SearchShown.Typing
    return when (val answer = state.state) {
        SearchState.Idle -> SearchShown.Typing
        SearchState.Loading -> SearchShown.Searching
        is SearchState.Ready -> {
            val items = answer.items.filter { it !is PlaylistItem && filter.shows(it) }
            val shorts = answer.shorts.filter { filter.shows(it) }
            if (items.isEmpty() && shorts.isEmpty()) SearchShown.NoResults(query) else SearchShown.Results(items, shorts)
        }
        SearchState.Empty -> SearchShown.NoResults(query)
        is SearchState.Failed -> SearchShown.Failed(answer.failure)
    }
}

/**
 * Search on the phone (`m6-search`, M6-D13) on [SearchViewModel], opened from the pill over the
 * shown tab: the pill becomes the field and the keyboard rises; YouTube's suggestions follow the
 * typing (a tap searches one); the answer is compact rows ([ResultRow]) with their watch bars from
 * [marks], a leading channel and the Shorts shelf. A tap on the pill over an answer returns to
 * typing with the query selected. ← and Back close Search to the tab (Q18); the next opening
 * starts empty (Q19). A Short plays in the Shorts player with the shelf as its run, and coming
 * back lights the one last played. A kids profile's Search looks only through what is approved for it (KP-D10):
 * no suggestions, and its rows have no menu.
 */
@Composable
internal fun SearchPage(
    viewModel: SearchViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    marks: Flow<Map<VideoId, WatchMark>>,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shown = searchShown(state, LocalContentFilter.current)
    val kid = chrome.profile.name.takeIf { chrome.profile.isKids }
    val watched by marks.collectAsStateWithLifecycle(emptyMap())
    // The field owns its text and selection; the ViewModel hears every change and seeds it when recreated.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    // A field restored after process death holds text the new ViewModel hasn't heard.
    LaunchedEffect(viewModel) { if (field.text != viewModel.uiState.value.query) viewModel.onQueryChange(field.text) }
    val keyboard = LocalSoftwareKeyboardController.current
    val onFieldChange: (TextFieldValue) -> Unit = {
        field = it
        viewModel.onQueryChange(it.text)
    }
    val submit: () -> Unit = {
        viewModel.submit()
        // A blank query isn't searched: the keyboard stays up for it.
        if (!viewModel.uiState.value.editing) keyboard?.hide()
    }
    val searchFor: (String) -> Unit = { text ->
        onFieldChange(TextFieldValue(text, TextRange(text.length)))
        submit()
    }
    val editSearch: () -> Unit = {
        field = TextFieldValue(state.query, TextRange(0, state.query.length))
        viewModel.editSearch()
    }
    // The row whose menu is open stays lifted behind the dim.
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<String?>(null) }
    val shortsBack = rememberShortsReturn(chrome, viewModel::openShorts, onPlayShort, viewModel::shortsReturn, viewModel::shortsReturned)
    val playShort: (VideoItem) -> Unit = shortsBack::play
    VideoMenuHost(menu, onPlay, onOpenChannel, modifier, onPlayShort = playShort) { openMenu ->
        // A row stays held behind the menu; a Short has no held look (`m6-search` 10).
        val openRowMenu: ((VideoItem, String?) -> Unit)? = if (kid != null) {
            null
        } else {
            { video, row ->
                heldRow = row
                openMenu(video, VideoSource.Elsewhere)
            }
        }
        Column(Modifier.fillMaxSize().background(AynColors.Ground)) {
            PillHead(chrome) {
                SearchField(
                    typing = shown == SearchShown.Typing,
                    field = field,
                    onFieldChange = onFieldChange,
                    onSubmit = submit,
                    onClose = chrome.nav::back,
                    onEdit = editSearch,
                    query = state.submittedQuery.orEmpty(),
                    pencil = shown is SearchShown.Results,
                    kids = kid != null,
                )
            }
            // Typing, searching, an answer and a statement cross-fade (alpha only, D8).
            AnimatedContent(
                shown,
                transitionSpec = { fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base()) },
                contentKey = { it::class },
                label = "search",
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    SearchShown.Typing -> TypingAids(state, kid, searchFor)
                    SearchShown.Searching -> Searching()
                    is SearchShown.Results -> Results(
                        results = page,
                        marks = watched,
                        heldRow = heldRow.takeIf { menuOpen },
                        onPlay = { onPlay(it, false) },
                        onPlayShort = playShort,
                        onOpenChannel = onOpenChannel,
                        onOpenMenu = openRowMenu,
                        onOpenShortMenu = menu::openShort.takeIf { kid == null },
                        litShort = shortsBack.lit,
                    )
                    is SearchShown.NoResults, is SearchShown.Failed -> SearchStatement(page, kid, onRetry = submit, onEdit = editSearch)
                }
            }
        }
    }
}

/** No results (states 11, 16) or a search that didn't go through (12–13), announced as it shows. */
@Composable
private fun SearchStatement(page: SearchShown, kid: String?, onRetry: () -> Unit, onEdit: () -> Unit) {
    val announced = StatementPadding.semantics { liveRegion = LiveRegionMode.Polite }
    when (page) {
        is SearchShown.NoResults -> Statement(
            icon = R.drawable.ic_nav_search,
            kicker = if (kid != null) "No match" else "No results",
            title = if (kid != null) "Nothing approved matches “${page.query}”" else "Nothing matched that search",
            body = if (kid != null) "Ask a grown-up to approve more." else "Check the spelling, or try fewer words.",
            modifier = announced,
        ) { PillButton("Edit search", onEdit, Modifier.height(StatementButton), icon = R.drawable.ic_edit) }
        is SearchShown.Failed -> {
            val offline = page.failure == AccessFailure.OFFLINE
            Statement(
                icon = if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
                kicker = if (offline) "No connection to YouTube" else "YouTube changed something",
                title = "Search didn’t go through",
                body = if (offline) "Your search is kept. Check that the phone is online and try again." else "Your search is kept. $UnreadableReply",
                modifier = announced,
            ) {
                PillButton("Try again", onRetry, Modifier.height(StatementButton), icon = R.drawable.ic_retry)
                PillButton("Edit search", onEdit, Modifier.height(StatementButton), kind = ButtonKind.Tonal, icon = R.drawable.ic_edit)
            }
        }
        SearchShown.Typing, SearchShown.Searching, is SearchShown.Results -> Unit
    }
}

private val StatementPadding = Modifier.padding(start = 24.dp, end = 24.dp, top = 30.dp)
private val StatementButton = 48.dp
private val HintStyleSize = 14.5.sp

/**
 * The pill at the head of Search: the field while [typing] (← closes Search, ✕ clears, the
 * keyboard's search key submits; Surface 3 in an ink ring, 150 ms), else the submitted [query],
 * which a tap returns to typing with the query selected ([onEdit]); the pencil shows over an answer.
 */
@Composable
private fun RowScope.SearchField(
    typing: Boolean,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    query: String,
    pencil: Boolean,
    kids: Boolean,
) {
    val active by animateFloatAsState(if (typing) 1f else 0f, AynMotion.press(), label = "field")
    if (typing) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
        Row(
            Modifier.weight(1f).height(PillHeight).pillGround(active = { active }).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Close Search", tint = AynColors.Ink2)
            }
            BasicTextField(
                value = field,
                onValueChange = onFieldChange,
                singleLine = true,
                textStyle = PillTextStyle.copy(color = AynColors.Ink),
                cursorBrush = SolidColor(AynColors.Ink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) {
                            Text(searchPlaceholder(kids), style = PillTextStyle.atWeight(500), color = AynColors.Ink3, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (field.text.isNotEmpty()) {
                IconButton(onClick = { onFieldChange(TextFieldValue()) }) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = "Clear search", tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
                }
            }
        }
    } else {
        Row(
            Modifier
                .weight(1f)
                .height(PillHeight)
                .pillGround(active = { active })
                .clickable(role = Role.Button, onClickLabel = "Edit search", onClick = onEdit)
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(searchIcon(kids)), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
            Text(query, style = PillTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (pencil) Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Under the field while typing: a kids profile's note (nothing is asked of YouTube), YouTube's
 * suggestions, the empty field's hint, or, back on a kept answer's query, what typing does.
 */
@Composable
private fun TypingAids(state: SearchUiState, kid: String?, onPick: (String) -> Unit) {
    when {
        kid != null -> Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(14.dp))
                Text("Only what’s approved for $kid", style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = AynColors.Ink2)
            }
            Hint("Nothing is asked of YouTube, so there are no suggestions.", Modifier.padding(top = 10.dp))
        }
        state.suggestions.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(top = 4.dp)) {
            items(state.suggestions, key = { it }) { suggestion -> SuggestionRow(suggestion, state.query, onClick = { onPick(suggestion) }) }
        }
        state.query.isBlank() -> Hint("Type, or tap the keyboard’s mic and say it.", HintPadding)
        state.query.trim() == state.submittedQuery && (state.state is SearchState.Ready || state.state == SearchState.Empty) ->
            Hint("Typing replaces it. Searching the same words again brings back the results you had, without a new search.", HintPadding)
        else -> Unit
    }
}

private val HintPadding = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = HintStyleSize), color = AynColors.Ink3, modifier = modifier)
}

/** State 6: three rows' shapes while the answer comes. */
@Composable
private fun Searching() {
    val pulse = rememberPulse()
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(3) { ResultSkeleton(pulse) }
    }
}

@Composable
private fun Results(
    results: SearchShown.Results,
    marks: Map<VideoId, WatchMark>,
    heldRow: String?,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMenu: ((VideoItem, String?) -> Unit)?,
    onOpenShortMenu: ((VideoItem) -> Unit)?,
    litShort: VideoId?,
) {
    val first = results.items.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The shelf sits after the first result.
        if (first != null) item(key = first.listingKey, contentType = first::class.simpleName) { Result(first, marks, heldRow, onPlay, onOpenChannel, onOpenMenu) }
        if (results.shorts.isNotEmpty()) {
            item(key = ShelfKey, contentType = ShelfKey) {
                ShortsShelf(results.shorts, onPlayShort, onOpenShortMenu, lit = litShort)
            }
        }
        items(results.items.drop(1), key = { it.listingKey }, contentType = { it::class.simpleName }) {
            Result(it, marks, heldRow, onPlay, onOpenChannel, onOpenMenu)
        }
    }
}

/** One result: a video's row or a channel's lead; one leaving the list (a block) fades out. */
@Composable
private fun LazyItemScope.Result(
    item: ListingItem,
    marks: Map<VideoId, WatchMark>,
    heldRow: String?,
    onPlay: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMenu: ((VideoItem, String?) -> Unit)?,
) {
    val motion = Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = AynMotion.base(), fadeOutSpec = AynMotion.base())
    when (item) {
        is VideoItem -> ResultRow(
            video = item,
            progress = marks[item.id].barFraction(),
            onClick = { onPlay(item) },
            onOpenMenu = onOpenMenu?.let { open -> { open(item, item.listingKey) } },
            held = heldRow == item.listingKey,
            modifier = motion,
        )
        is ChannelItem -> ChannelLead(item, onClick = { onOpenChannel(item.url) }, modifier = motion)
        // searchShown leaves playlists out.
        is PlaylistItem -> Unit
    }
}

private const val ShelfKey = "shorts"
