package com.arshad.ayn.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.CaptionSource
import com.arshad.ayn.core.access.CaptionTrack
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DEFAULT_SPEED_PERCENT
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.SubscriptionStore
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.library.orFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where Play all is in its playlist: "Biology · 3 of 22". */
data class PlaylistPosition(
    val title: String,
    /** 0-based position of the playing video. */
    val index: Int,
    /** The playlist's count; -1 when unknown. */
    val videoCount: Long,
    /** Another video follows (known once the playlist's videos have loaded). */
    val hasNext: Boolean,
)

/** Where a Shorts run is in its list (Q36): Up and Down move only where a Short waits. */
data class ShortsPosition(
    /** 0-based position of the playing Short in its list. */
    val index: Int,
    val hasPrevious: Boolean,
    /** Another Short follows, loaded or on a page still to fetch. */
    val hasNext: Boolean,
)

data class PlayerUiState(
    /** The playing video's card: what the screen shows before and around the stream. */
    val video: VideoItem,
    val playlist: PlaylistPosition? = null,
    /** Driven by the store's own flow for the playing video's channel. */
    val isSubscribed: Boolean = false,
    /** How many times the player has moved on (Play all's advance, a related video picked): a new
     *  turn is a new video to play, even the same one again. */
    val turn: Int = 0,
    /** The route asked for the opening video from the start, past its resume point (the card
     *  menu's "Play from the start"); the screen holds the one-shot and drops it once the video
     *  plays. Set again only by a related video picked to play from the start. */
    val startOver: Boolean = false,
    /** This turn's video ended with nothing to follow it ([PlayerViewModel.ended]): Up next's
     *  turn (M5-D12). */
    val atEnd: Boolean = false,
    /** SponsorBlock's skip prompt for this turn's video ([PlayerViewModel.segmentTick]). */
    val skipPrompt: SkipPrompt? = null,
    /** The note after this turn's latest skip, until it goes. */
    val skipNote: SkipNote? = null,
    /** The prompt span this turn's video is in, while the controls offer to skip it (PAF-D12). */
    val skippable: SkipSpan? = null,
    /** Return YouTube Dislike for this turn's video; `null` until Details first opens in the turn. */
    val votes: DetailsVotes? = null,
    /** The Shorts run this turn's Short plays in; `null` for a video (M7-D13). */
    val shorts: ShortsPosition? = null,
    /** This turn's captions; `null` until its resolve lists the tracks, and always on a Short (Q54). */
    val captions: PlayerCaptions? = null,
    /** This turn's comments; `null` until the comments panel first opens in the turn (CMT-D3). */
    val comments: PlayerComments? = null,
    /** This turn's live chat; `null` until the chat first opens in the turn (LIVE-D6). */
    val chat: PlayerChat? = null,
    /** Where this turn's chat shows: its view (the profile's default, Beside until read, until a
     *  pick in the turn), hidden or scrolled for now. */
    val chatPlace: ChatPlace = ChatPlace(),
)

/**
 * The player destination's state beyond the stream (which the screen reads from its
 * `MediaController`, M3-UI-01): the playing video's card, Play all's run through its playlist
 * (ui-rebuild B8p: in order, advancing by itself; a saved playlist also shuffled), what follows a
 * video's end (Play all's next, else Up next's turn), a related video picked in its place
 * (M5-D18), the channel's Subscribe toggle, each video's history card and resume point in the
 * watch store, its SponsorBlock skips by the profile's settings (M7-D10), Details' Return
 * YouTube Dislike votes (M7-D5), its captions (M7-D14), its comments (CMT-D3), a live stream's chat and where it shows (LIVE-D6, D21), and a Shorts run through the list a Short was opened from
 * (M7-D13: Up/Down, paging on; a history card per Short, no resume point). One ViewModel per
 * destination (ARCH-3); seam and stores are constructor-injected (ARCH-4). The resume points'
 * [marks] are read on [compute] and kept apart from [uiState]: every position save re-emits them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val opening: VideoItem,
    playAll: PlayAll?,
    private val extractor: Extractor,
    private val store: SubscriptionStore,
    private val watchStore: WatchStore,
    private val playlistStore: PlaylistStore,
    settingsStore: ProfileSettingsStore,
    private val voteSource: VoteSource,
    private val captionSource: CaptionSource,
    private val liveChat: LiveChat,
    startOver: Boolean = false,
    /** The list the opening Short was opened from, `null` for a video; a list that doesn't hold
     *  the Short (none was handed over, or a stale one) plays it alone. */
    shorts: ShortsList? = null,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val openingIndex = shorts?.shorts?.value?.indexOfFirst { it.id == opening.id } ?: -1

    /** The Shorts run's list; `null` for a video, and once a related pick ends the run. */
    private var shortsList: ShortsList? = shorts?.let { if (openingIndex >= 0) it else ShortsList(listOf(opening)) }

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            video = opening,
            playlist = playAll?.let { PlaylistPosition(it.title, index = 0, videoCount = it.videoCount, hasNext = false) },
            startOver = startOver,
            shorts = shortsList?.let { shortsPosition(it, openingIndex.coerceAtLeast(0)) },
        ),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _marks = MutableStateFlow<Map<VideoId, WatchMark>>(emptyMap())

    /** The profile's resume points, for the bars on the Related row's and Up next's cards. */
    val marks: StateFlow<Map<VideoId, WatchMark>> = _marks.asStateFlow()

    /** Paging goes through this; a saved playlist loads whole. */
    private val playlistUrl = (playAll as? PlayAll.YouTube)?.playlist?.url

    /** The playlist's videos in play order, as far as loaded. */
    private val queue = mutableListOf<VideoItem>()
    private var nextPage: PageToken? = null

    /** The run's page fetch: Play all's queue or the Shorts list (a destination plays one run). */
    private var pageJob: Job? = null

    /** The store's last emission: the revert target when a write fails (as on the channel page). */
    private var storeTruth = false

    /** The session profile's settings; `null` until read (or when the read failed): no skips and
     *  no votes asked then. */
    private var settings: ProfileSettings? = null

    /** Completes at the settings' first answer (read or failed). */
    private val settingsAnswered = CompletableDeferred<Unit>()

    /** This turn's skips, and the lookup they were built for (a new turn drops both). */
    private var skips: SegmentSkips? = null
    private var skipsLookup: SegmentLookup.Found? = null

    /** The last tick's segment lookup was Found; a masked one (not Found) hides the controls' skip with the prompt and note. */
    private var lookupFound = true

    /** The skip prompt's place is in view ([skipPromptInView]); kept across turns. */
    private var promptInView = true

    /** How long a skip prompt lasts, and a skip note is kept past its span ([skipTimes]); kept across turns. */
    private var promptMs = SegmentSkips.PROMPT_MS
    private var noteKeptMs = SegmentSkips.NOTE_KEPT_MS

    private var votesJob: Job? = null

    /** The last turn whose card went into History ([playbackStarted]). */
    private var recordedTurn: Int? = null

    /** The last turn whose caption tracks were reported, and that turn's captions once set up. */
    private var captionsTurn = -1
    private var captions: TurnCaptions? = null

    private var comments: TurnComments? = null

    private var chat: ChatTurn? = null

    /** A view was picked in this turn ([chooseChatPlacement]): the profile's default no longer moves it. */
    private var chatPicked = false

    init {
        viewModelScope.launch {
            _uiState.map { it.video.uploaderUrl }.distinctUntilChanged()
                .flatMapLatest { url -> url?.let(store::subscription) ?: flowOf(null) }
                .collect { subscription ->
                    storeTruth = subscription != null
                    _uiState.update { it.copy(isSubscribed = subscription != null) }
                }
        }
        viewModelScope.launch {
            watchStore.resumePoints()
                .map { it.marks() }
                .flowOn(compute)
                .orFailed("resume points read", emptyMap())
                .collect { _marks.value = it }
        }
        viewModelScope.launch {
            settingsStore.settings()
                .map<ProfileSettingsState, ProfileSettings?> { it.settings }
                .orFailed("profile settings read", null)
                .collect {
                    settings = it
                    settingsAnswered.complete(Unit)
                    if (it != null && !chatPicked) {
                        _uiState.update { state -> state.copy(chatPlace = state.chatPlace.following(it.chatPlacement)) }
                    }
                }
        }
        shortsList?.played(opening.id)
        when (playAll) {
            is PlayAll.YouTube -> loadQueue { loadYouTubeQueue(playAll.playlist.url) }
            is PlayAll.Saved -> loadQueue { loadSavedQueue(playAll) }
            null -> Unit
        }
    }

    /**
     * Where [videoId] resumes, read from the store when the screen is about to ask for it (M5-D8):
     * read then, never cached, so a return to this screen after the video was left elsewhere
     * resumes from where it was left. `null` without a resume point, or when the store fails to
     * answer (the video then plays from the start rather than not at all). A Short never resumes
     * (Q39), nor does any video while the profile's "Resume where you left off" is off (Q57; its
     * settings unreadable = on, the default).
     */
    suspend fun resumePointMs(videoId: VideoId): Long? {
        if (_uiState.value.shorts != null) return null
        settingsAnswered.await()
        if (settings?.resume == false) return null
        return try {
            watchStore.resumePointMs(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlaybackContract.LOG_TAG, "resume point read failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The speed a newly asked-for video starts at, in percent: the profile's (M7-D15); Normal for
     *  a Short (Q58) or with the settings unreadable. */
    suspend fun startSpeedPercent(): Int {
        if (_uiState.value.shorts != null) return DEFAULT_SPEED_PERCENT
        settingsAnswered.await()
        return settings?.speedPercent ?: DEFAULT_SPEED_PERCENT
    }

    /**
     * The video of [turn] ended: Play all moves to the playlist's next video (a later page is
     * fetched first when the loaded videos run out); a single video, or a run with nothing after
     * this one (its last video, or a page that failed to load, which turns
     * [PlaylistPosition.hasNext] false), ends at [PlayerUiState.atEnd] (Q23). An end reported, or
     * still waiting on the playlist, after another turn began is dropped: the new video hasn't
     * ended.
     */
    fun ended(turn: Int) {
        if (_uiState.value.turn != turn) return
        if (_uiState.value.playlist == null) return reachEnd()
        val loading = pageJob?.takeIf { it.isActive }
        if (loading != null) {
            // The playlist (or a page) is still on its way: decide once it lands.
            viewModelScope.launch {
                loading.join()
                ended(turn)
            }
            return
        }
        val next = _uiState.value.playlist!!.index + 1
        if (next < queue.size) {
            moveTo(next)
        } else if (nextPage != null) {
            pageJob = viewModelScope.launch {
                val fetched = fetchNextPage()
                // Extraction can't be interrupted: a fetch cancelled by a pick may still finish.
                if (_uiState.value.turn != turn) return@launch
                if (fetched && next < queue.size) {
                    moveTo(next)
                } else {
                    _uiState.update { state -> state.copy(playlist = state.playlist?.copy(hasNext = false)) }
                    reachEnd()
                }
            }
        } else {
            reachEnd()
        }
    }

    /**
     * The screen's position tick while [turn]'s video plays with [segments] (its dressing's
     * SponsorBlock lookup): where to jump, or `null` to play on. The first tick of a turn (or of a
     * different lookup) builds its skips from the profile's actions as they are then; until the
     * settings are read nothing is skipped. A lookup that isn't Found (a masked or re-resolved
     * item) hides the prompt and note but keeps the turn's skips, so an Undo still holds when the
     * same segments return. A tick for another turn is dropped.
     */
    fun segmentTick(turn: Int, segments: SegmentLookup?, positionMs: Long): Long? {
        if (_uiState.value.turn != turn) return null
        val skips = skipsFor(segments) ?: return null
        return skips.onPosition(positionMs).also { publishSkips() }
    }

    /** OK on the skip prompt: where to jump (its span's end), or `null` when none shows. */
    fun skipPrompted(): Long? = skips?.skipPrompted().also { publishSkips() }

    /** The controls' skip (PAF-D12): where to jump (the span's end), or `null` when playback isn't in one. */
    fun skipFromControls(): Long? = skips?.takeIf { lookupFound }?.skipSpan().also { publishSkips() }

    /**
     * The skip prompt's place came into view or went (the chrome, a panel or another note covers
     * it), with playback at [positionMs]: its time runs only while in view (PAF-D2).
     */
    fun skipPromptInView(inView: Boolean, positionMs: Long) {
        promptInView = inView
        skips?.promptInView(inView, positionMs)
        publishSkips()
    }

    /**
     * A skip prompt lasts [promptMs] of playback and a skip note goes [noteKeptMs] past its span's end
     * (the phone scales both 8 s by the accessibility timeout, as its notes, Q36, Q37), from the next
     * video's skips on, and this one's when none are built yet.
     */
    fun skipTimes(promptMs: Long, noteKeptMs: Long) {
        this.promptMs = promptMs
        this.noteKeptMs = noteKeptMs
    }

    /** BACK on the skip prompt: hidden until playback leaves its span and comes back. */
    fun dismissSkipPrompt() {
        skips?.dismissPrompt()
        publishSkips()
    }

    /** Undo on the skip note: where to jump back to (the skipped span's start), or `null` when none shows. */
    fun undoSkip(): Long? = skips?.undo().also { publishSkips() }

    /** The skip note went: its time ran out, or another key dropped it. */
    fun dismissSkipNote() {
        skips?.dismissNote()
        publishSkips()
    }

    private fun skipsFor(segments: SegmentLookup?): SegmentSkips? {
        if (segments !is SegmentLookup.Found) {
            lookupFound = false
            skips?.let {
                it.dismissPrompt()
                it.dismissNote()
                publishSkips()
            }
            return null
        }
        lookupFound = true
        if (segments != skipsLookup) {
            val settings = settings ?: return null
            skips = SegmentSkips(segments.segments, settings.segmentActions, settings.skipNote, promptInView, promptMs, noteKeptMs)
            skipsLookup = segments
        }
        return skips
    }

    /** Runs on every skip tick ([segmentTick]): the state is copied only when the prompt, note or skippable span changed. */
    private fun publishSkips() {
        val prompt = skips?.prompt
        val note = skips?.note
        val skippable = skips?.skippable?.takeIf { lookupFound }
        _uiState.update {
            if (it.skipPrompt == prompt && it.skipNote == note && it.skippable == skippable) it
            else it.copy(skipPrompt = prompt, skipNote = note, skippable = skippable)
        }
    }

    /**
     * Details opened: Return YouTube Dislike is asked once per turn, and only while the profile has
     * Dislikes on (M7-D5); settings not read (or unreadable) ask nothing.
     */
    fun openDetails() {
        val state = _uiState.value
        if (state.votes != null) return
        if (settings?.dislikes != true) {
            _uiState.update { it.copy(votes = DetailsVotes.Off) }
            return
        }
        _uiState.update { it.copy(votes = DetailsVotes.Loading) }
        votesJob = viewModelScope.launch {
            val votes = DetailsVotes.of(voteSource.votes(state.video.id))
            // The lookup may not have noticed a cancel by a new turn.
            if (_uiState.value.turn == state.turn) _uiState.update { it.copy(votes = votes) }
        }
    }

    /**
     * [turn]'s resolve listed the video's caption [tracks]: captions start on the profile's default
     * track (Q52), its settings read once they first answer (unreadable = Off). A later report in
     * the turn (a re-resolve) only refreshes the tracks' links ([TurnCaptions.refresh]). A Short
     * shows none (Q54); a report for another turn is dropped.
     */
    fun captionsResolved(turn: Int, tracks: List<CaptionTrack>) {
        val state = _uiState.value
        if (state.turn != turn || state.shorts != null) return
        if (captionsTurn == turn) {
            captions?.refresh(tracks)
            return
        }
        captionsTurn = turn
        viewModelScope.launch {
            settingsAnswered.await()
            if (_uiState.value.turn != turn) return@launch
            captions = TurnCaptions(tracks, settings?.captionsLanguage, captionSource, viewModelScope) { shown ->
                _uiState.update { it.copy(captions = shown) }
            }
        }
    }

    /** A track picked in the Captions sheet, or Off (`null`), for this video only. */
    fun chooseCaptions(track: CaptionTrack?) {
        captions?.choose(track)
    }

    /** Try again on the ccfail note. */
    fun retryCaptions() {
        captions?.retry()
    }

    /** The comments panel opened: its first open in the turn asks for page 1; a later one shows
     *  what the turn already has, where it was left. */
    fun openComments() {
        if (comments != null) return
        comments = TurnComments(_uiState.value.video.id, extractor, viewModelScope) { shown ->
            _uiState.update { it.copy(comments = shown) }
        }
    }

    /** The panel's shown list scrolled near its end: its next page, when more follow. */
    fun moreComments() {
        comments?.more()
    }

    /** Try again on the panel's failed page. */
    fun retryComments() {
        comments?.retry()
    }

    /** OK on a comment with replies: they show under its head (Q3). */
    fun openReplies(comment: Comment) {
        comments?.openReplies(comment)
    }

    /** BACK in a comment's replies: the video's list again, on that comment. */
    fun closeReplies() {
        comments?.closeReplies()
    }

    /** The chat opened in [turn] on [seed], that turn's resolved page's ([PlaybackDressing.liveChat]):
     *  the turn's first open starts it, live or a replay (LIVE-D7), `null` showing chat off; a later
     *  open shows what the turn has, except that a seed replaces an off chat (one opened before the
     *  page resolved). An open for another turn is dropped. */
    fun openChat(turn: Int, seed: LiveChatSeed?) {
        if (_uiState.value.turn != turn) return
        val open = chat
        if (open != null && !(open.off && seed != null)) return
        open?.close()
        val publish = { shown: PlayerChat -> _uiState.update { it.copy(chat = shown) } }
        chat = when {
            seed?.replay == true -> TurnReplay(seed.token, liveChat, viewModelScope, publish)
            else -> TurnChat(seed?.token, liveChat, viewModelScope, publish)
        }.also { if (open?.isShown == true) it.shown(true) }
    }

    /** The screen's position tick in [turn] while its chat replay is open ([playing]: the picture
     *  moves); nothing for a live chat or another turn. */
    fun chatTick(turn: Int, positionMs: Long, playing: Boolean) {
        if (_uiState.value.turn != turn) return
        (chat as? TurnReplay)?.tick(positionMs, playing)
    }

    /** The chat is on screen with the app started ([visible]), or not: it polls only then. */
    fun chatShown(visible: Boolean) {
        chat?.shown(visible)
    }

    /** Top chat or Live chat picked in the chat's switch. */
    fun chooseChatView(live: Boolean) {
        chat?.chooseView(live)
    }

    /** Try again on a chat that didn't load. */
    fun retryChat() {
        chat?.retry()
    }

    /** A view picked in the Chat sheet: chat shows there for this stream only (LIVE-D22), no longer
     *  hidden nor in Scroll chat's Beside. */
    fun chooseChatPlacement(placement: ChatPlacement) {
        chatPicked = true
        _uiState.update { it.copy(chatPlace = ChatPlace(placement)) }
    }

    /** Hide chat: off screen for this stream until [showChat] (LIVE-D26); the screen, no longer
     *  showing it, stops its requests ([chatShown]). */
    fun hideChat() {
        _uiState.update { it.copy(chatPlace = it.chatPlace.copy(hidden = true, scrolling = false)) }
    }

    /** Chat in the chrome while chat isn't showing: it shows in the stream's view again (LIVE-D26). */
    fun showChat() {
        _uiState.update { it.copy(chatPlace = it.chatPlace.copy(hidden = false)) }
    }

    /** Scroll chat: chat over the picture moves to the Beside panel for now (LIVE-D22), the
     *  stream's view kept; nothing when chat isn't over the picture. */
    fun scrollChat() {
        _uiState.update { if (it.chatPlace.overlay == null) it else it.copy(chatPlace = it.chatPlace.copy(scrolling = true)) }
    }

    /** BACK in Scroll chat's Beside panel: chat over the picture again. */
    fun endChatScroll() {
        _uiState.update { it.copy(chatPlace = it.chatPlace.copy(scrolling = false)) }
    }

    /** Plays [video] (a related video picked on the screen) as this destination's next turn, from
     *  its resume point unless [startOver] (the card menu's "Play from the start"); a Play all or
     *  Shorts run ends there for good (M5-D18), so a page fetch that still lands only grows a list
     *  this player reads no more. */
    fun play(video: VideoItem, startOver: Boolean = false) {
        pageJob?.cancel()
        nextPage = null
        queue.clear()
        shortsList = null
        beginTurn(video, startOver) { copy(playlist = null, shorts = null) }
    }

    /**
     * [turn]'s video has started playing: its card goes into History, once per turn (PAF-D1; a
     * refused, filtered, live or failed open never gets here). A report for another turn is dropped.
     */
    fun playbackStarted(turn: Int) {
        val state = _uiState.value
        if (state.turn != turn || recordedTurn == turn) return
        recordedTurn = turn
        recordOpened(state.video, isShort = state.shorts != null)
    }

    /**
     * [turn]'s resolve named the video's channel: a card that lacked it (a search Short's) takes
     * it, so the player offers the channel and Subscribe, and a started turn's history card is
     * recorded again with it. A card that has one keeps its own; a report for another turn is
     * dropped.
     */
    fun channelResolved(turn: Int, name: String?, url: String?) {
        if (url == null) return
        val before = _uiState.value
        if (before.turn != turn || before.video.uploaderUrl != null) return
        val named = before.video.copy(uploaderName = name ?: before.video.uploaderName, uploaderUrl = url)
        _uiState.value = before.copy(video = named)
        if (recordedTurn == turn) recordOpened(named, isShort = before.shorts != null)
    }

    /** Up in a Shorts run: the Short before this one; nothing on the first. */
    fun previousShort() {
        val position = _uiState.value.shorts ?: return
        if (position.hasPrevious) moveToShort(position.index - 1)
    }

    /**
     * Down in a Shorts run: the Short after this one, fetching its list's next page first when the
     * loaded Shorts run out (another Down while it loads adds nothing); nothing on the last. A
     * failed page makes this Short the last. A page landing after another turn began moves
     * nothing.
     */
    fun nextShort() {
        val state = _uiState.value
        val position = state.shorts ?: return
        val list = shortsList ?: return
        val next = position.index + 1
        if (next < list.shorts.value.size) return moveToShort(next)
        if (!list.hasMore || pageJob?.isActive == true) return
        pageJob = viewModelScope.launch {
            val grew = list.loadMore()
            if (shortsList !== list) return@launch
            if (grew && _uiState.value.turn == state.turn) return@launch moveToShort(next)
            // Moved elsewhere meanwhile, or nothing came: where the run is now, with what follows it.
            _uiState.update { now -> now.copy(shorts = now.shorts?.let { shortsPosition(list, it.index) }) }
        }
    }

    /** The Short a swipe would move to, as far as the run has loaded: the one after this one
     *  ([next]) or before it; `null` at a loaded end, or outside a run. */
    fun shortBeside(next: Boolean): VideoItem? {
        val position = _uiState.value.shorts ?: return null
        val list = shortsList ?: return null
        return list.shorts.value.getOrNull(if (next) position.index + 1 else position.index - 1)
    }

    private fun moveToShort(index: Int) {
        val list = shortsList ?: return
        val short = list.shorts.value[index]
        beginTurn(short) { copy(shorts = shortsPosition(list, index)) }
        list.played(short.id)
    }

    /** A new turn plays [video]: the last turn's per-video state goes; its history card waits for
     *  [playbackStarted]; [placed] sets where it is in its run. */
    private fun beginTurn(video: VideoItem, startOver: Boolean = false, placed: PlayerUiState.() -> PlayerUiState) {
        leaveTurn()
        _uiState.update { it.copy(video = video, turn = it.turn + 1, startOver = startOver, atEnd = false).placed() }
    }

    private fun shortsPosition(list: ShortsList, index: Int): ShortsPosition =
        ShortsPosition(index, hasPrevious = index > 0, hasNext = index + 1 < list.shorts.value.size || list.hasMore)

    /** Subscribe when clean, unsubscribe when stored; flips at once and reverts if the write
     *  fails. [avatarUrl] comes from the resolved video page (the card has none). */
    fun toggleSubscribe(avatarUrl: String?) {
        val state = _uiState.value
        val url = state.video.uploaderUrl ?: return
        _uiState.update { it.copy(isSubscribed = !state.isSubscribed) }
        viewModelScope.launch {
            try {
                if (state.isSubscribed) {
                    store.unsubscribe(url)
                } else {
                    store.subscribe(
                        ChannelItem(
                            name = state.video.uploaderName.orEmpty(),
                            url = url,
                            avatarUrl = avatarUrl,
                            subscriberCount = -1,
                            description = null,
                            verified = false,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSubscribed = storeTruth) }
            }
        }
    }

    private fun moveTo(index: Int) {
        beginTurn(queue[index]) { copy(playlist = playlist?.copy(index = index, hasNext = hasNextAfter(index))) }
    }

    /** A new turn: the last video's skips go (its prompt and note with them), its votes, its
     *  captions, its comments and its chat; the next stream's chat starts from the profile's view. */
    private fun leaveTurn() {
        chatPicked = false
        skips = null
        skipsLookup = null
        votesJob?.cancel()
        captions?.close()
        captions = null
        comments?.close()
        comments = null
        chat?.close()
        chat = null
        val place = settings?.let { ChatPlace(it.chatPlacement) } ?: ChatPlace()
        _uiState.update { it.copy(skipPrompt = null, skipNote = null, skippable = null, votes = null, captions = null, comments = null, chat = null, chatPlace = place) }
    }

    private fun reachEnd() {
        _uiState.update { it.copy(atEnd = true) }
    }

    /** Records [video]'s card for history, marked a Short when it plays in a Shorts run (PAF-D6); a
     *  failure is logged and playback goes on (the card text is cosmetic, android-arch error note). */
    private fun recordOpened(video: VideoItem, isShort: Boolean) {
        viewModelScope.launch {
            try {
                watchStore.recordOpened(video, isShort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PlaybackContract.LOG_TAG, "history card write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Play all's place is the opening video's in the fresh list (the playlist may have changed
     *  since its page loaded), paging on until it is found (a row from a later page). A failed
     *  load (`null` from [load]), or a video no longer listed, ends Play all: the opening video
     *  plays alone. [load] fills [queue] and returns the playlist's count (-1 when unknown). */
    private fun loadQueue(load: suspend () -> Long?) {
        pageJob = viewModelScope.launch {
            val videoCount = load() ?: return@launch endPlayAll()
            while (queue.none { it.id == opening.id } && fetchNextPage()) Unit
            val index = queue.indexOfFirst { it.id == opening.id }
            if (index < 0) return@launch endPlayAll()
            _uiState.update { state ->
                val position = state.playlist ?: return@update state
                state.copy(
                    playlist = position.copy(
                        index = index,
                        videoCount = videoCount.takeIf { it >= 0 } ?: position.videoCount,
                        hasNext = hasNextAfter(index),
                    ),
                )
            }
        }
    }

    private fun endPlayAll() {
        queue.clear()
        _uiState.update { it.copy(playlist = null) }
    }

    private suspend fun loadYouTubeQueue(url: String): Long? {
        val page = try {
            extractor.playlist(url)
        } catch (e: AccessException) {
            return null
        }
        queue += page.videos.items.filterIsInstance<VideoItem>()
        nextPage = page.videos.next
        return page.details.videoCount
    }

    /** The saved playlist as it is now, dealt in play order; later edits don't move a run. */
    private suspend fun loadSavedQueue(saved: PlayAll.Saved): Long? {
        val videos = try {
            playlistStore.videos(saved.playlistId).first().map { it.video }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlaybackContract.LOG_TAG, "saved playlist read failed: ${e.javaClass.simpleName}")
            return null
        }
        queue += playOrder(videos, saved.shuffleSeed)
        return queue.size.toLong()
    }

    /** True when a page arrived; a failed page ends Play all at the loaded videos. */
    private suspend fun fetchNextPage(): Boolean {
        val token = nextPage ?: return false
        val url = playlistUrl ?: return false
        val page = try {
            extractor.playlistVideos(url, token)
        } catch (e: AccessException) {
            nextPage = null
            return false
        }
        queue += page.items.filterIsInstance<VideoItem>()
        nextPage = page.next
        return true
    }

    private fun hasNextAfter(index: Int): Boolean = index + 1 < queue.size || nextPage != null
}
