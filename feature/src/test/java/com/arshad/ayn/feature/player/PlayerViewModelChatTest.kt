package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.newpipe.ChatServer
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveChat
import com.arshad.ayn.core.access.ryd.RecordedVotes
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.FilteringLiveChat
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The player's live chat (LIVE-D6, D14): the recorded first answer through the real seam, then the
 * turn's rules on scripted answers (each `live` call reads its own channel): off without a seed,
 * polling only while shown, a fresh list on each show, the Top/Live switch, failure and Try again,
 * the end, a new turn, and the list's own rules (moderation, pin, the 300 cap); and where the stream's
 * chat shows (LIVE-D21, D22, D26): the profile's view, a pick for this stream, Hide chat, Scroll chat.
 */
class PlayerViewModelChatTest {

    private val dispatcher = StandardTestDispatcher()
    private val answers = mutableListOf<Channel<LiveChatUpdate>>()
    private var collecting = 0
    private val scripted = FakeLiveChat {
        val answer = Channel<LiveChatUpdate>(Channel.UNLIMITED).also(answers::add)
        collecting++
        answer.consumeAsFlow().onCompletion { collecting-- }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun player(chat: LiveChat = scripted, settings: FakeProfileSettingsStore = FakeProfileSettingsStore(selectedId = 1)): PlayerViewModel = PlayerViewModel(
        opening = testVideo(FIXED_VIDEO_ID),
        playAll = null,
        extractor = UnsupportedExtractor("chat"),
        store = FakeSubscriptionStore(),
        watchStore = FakeWatchStore(),
        playlistStore = FakePlaylistStore(),
        settingsStore = settings,
        voteSource = RecordedVotes.source {},
        captionSource = RecordedCaptions.source(),
        liveChat = chat,
        compute = Dispatchers.Main,
    )

    private fun PlayerViewModel.chat(): PlayerChat = uiState.value.chat!!

    /** The first recorded answer: 67 lines (its 6 removals name earlier lines), the channel's pin,
     *  and the two views its header names. */
    @Test
    fun recordedFirstAnswer_listsItsLinesPinAndViews() = runTest(dispatcher) {
        val viewModel = player(NewPipeLiveChat(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.FIRST)), io = dispatcher))

        viewModel.openChat(0, LiveChatSeed(RecordedLiveChat.NEXT_LIVE_SEED, replay = false))
        viewModel.chatShown(true)
        runCurrent()
        val chat = viewModel.chat()
        viewModel.chatShown(false)

        assertEquals(listOf(67, 0), listOf(chat.lines.size, chat.lines.count { it.removed }))
        assertEquals("Pinned by Lofi Girl", chat.pinned?.title)
        assertEquals(ChatViews(RecordedLiveChat.FIRST_TOP_TOKEN, RecordedLiveChat.FIRST_LIVE_TOKEN), chat.views)
    }

    /** LIVE-D17: a keyword from one recorded line hides that line alone. */
    @Test
    fun recordedFirstAnswer_underABlockedKeyword_losesThatLine() = runTest(dispatcher) {
        val filter = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("dimag"), byOwner = false, addedAtMillis = 0))))
        val seam = NewPipeLiveChat(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.FIRST)), io = dispatcher)
        val viewModel = player(FilteringLiveChat(seam) { filter })

        viewModel.openChat(0, LiveChatSeed(RecordedLiveChat.NEXT_LIVE_SEED, replay = false))
        viewModel.chatShown(true)
        runCurrent()
        val lines = viewModel.chat().lines
        viewModel.chatShown(false)

        assertEquals(66, lines.size)
    }

    @Test
    fun noSeed_isChatOff_andAsksNothing() = runTest(dispatcher) {
        val viewModel = player()

        viewModel.openChat(0, null)
        viewModel.chatShown(true)
        advanceUntilIdle()

        assertEquals(ChatStatus.Off to emptyList<String>(), viewModel.chat().status to scripted.tokens)
    }

    /** Opened on screen before the page resolved (no seed yet), the chat starts once the seed
     *  comes, still on screen. */
    @Test
    fun offChat_isReplacedWhenTheSeedComes() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openChat(0, null)
        viewModel.chatShown(true)

        viewModel.openChat(0, SEED)
        advanceUntilIdle()

        assertEquals(ChatStatus.Loading to listOf(SEED.token), viewModel.chat().status to scripted.tokens)
    }

    /** The transport blocks: a poll stopped by the chat leaving the screen still ends, here failing.
     *  That stale failure doesn't show as "didn't load". */
    @Test
    fun stoppedPollFailingLate_publishesNothing() = runTest(dispatcher) {
        val late = CompletableDeferred<LiveChatUpdate>()
        val viewModel = player(FakeLiveChat { flow { emit(withContext(NonCancellable) { late.await() }) } })
        viewModel.openChat(0, SEED)
        viewModel.chatShown(true)
        runCurrent()
        viewModel.chatShown(false)

        late.completeExceptionally(OFFLINE)
        runCurrent()

        assertEquals(ChatStatus.Loading, viewModel.chat().status)
    }

    @Test
    fun openForAnotherTurn_isDropped() = runTest(dispatcher) {
        val viewModel = player()

        viewModel.openChat(1, SEED)

        assertNull(viewModel.uiState.value.chat)
    }

    @Test
    fun secondOpen_keepsTheChatAndItsOneCollection() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        viewModel.openChat(0, SEED)
        viewModel.chatShown(true)
        advanceUntilIdle()

        assertEquals(listOf("a") to 1, viewModel.chat().lines.map { it.message.id } to collecting)
    }

    @Test
    fun notShown_asksNothing() = runTest(dispatcher) {
        val viewModel = player()

        viewModel.openChat(0, SEED)
        advanceUntilIdle()

        assertEquals(ChatStatus.Loading to emptyList<String>(), viewModel.chat().status to scripted.tokens)
    }

    @Test
    fun hidden_stopsPolling() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        viewModel.chatShown(false)
        advanceUntilIdle()

        assertEquals(0, collecting)
    }

    /** Removals and edits made while hidden never reach a kept list: a show starts it again from
     *  the view's token. */
    @Test
    fun shownAgain_startsAFreshListFromTheViewsToken() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"), add("b"))
        viewModel.chatShown(false)

        viewModel.chatShown(true)
        advanceUntilIdle()
        answers.last().send(LiveChatUpdate.Batch(listOf(add("c")), null))
        advanceUntilIdle()

        assertEquals(listOf(SEED.token, VIEWS.top), scripted.tokens)
        assertEquals(listOf("c"), viewModel.chat().lines.map { it.message.id })
    }

    @Test
    fun shownTwice_pollsOnce() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        viewModel.chatShown(true)
        advanceUntilIdle()

        assertEquals(1, collecting)
    }

    @Test
    fun liveView_startsAgainFromTheNewestOnItsToken() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        viewModel.chooseChatView(live = true)
        advanceUntilIdle()

        assertEquals(PlayerChat(views = VIEWS, liveView = true, status = ChatStatus.Loading), viewModel.chat())
        assertEquals(VIEWS.live to 1, scripted.tokens.last() to collecting)
    }

    @Test
    fun endedChat_ignoresTheSwitchAndAShow() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))
        answers.last().close()
        advanceUntilIdle()

        viewModel.chooseChatView(live = true)
        viewModel.chatShown(false)
        viewModel.chatShown(true)
        advanceUntilIdle()

        assertEquals(ChatStatus.Ended to 1, viewModel.chat().status to scripted.tokens.size)
    }

    @Test
    fun reconnecting_keepsTheLines() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        answers.last().send(LiveChatUpdate.Reconnecting)
        advanceUntilIdle()

        assertEquals(ChatStatus.Reconnecting to 1, viewModel.chat().status to viewModel.chat().lines.size)
    }

    @Test
    fun failedChat_saysWhy() = runTest(dispatcher) {
        val viewModel = shownWith()

        answers.last().close(OFFLINE)
        advanceUntilIdle()

        assertEquals(ChatStatus.Failed(AccessFailure.OFFLINE), viewModel.chat().status)
    }

    @Test
    fun failedChat_waitsForTryAgain() = runTest(dispatcher) {
        val viewModel = shownWith()
        answers.last().close(OFFLINE)
        advanceUntilIdle()

        viewModel.chatShown(false)
        viewModel.chatShown(true)
        advanceUntilIdle()

        assertEquals(1, scripted.tokens.size)
    }

    @Test
    fun tryAgain_asksAgain() = runTest(dispatcher) {
        val viewModel = shownWith()
        answers.last().close(OFFLINE)
        advanceUntilIdle()

        viewModel.retryChat()
        advanceUntilIdle()

        assertEquals(ChatStatus.Loading to 2, viewModel.chat().status to scripted.tokens.size)
    }

    @Test
    fun chatThatEnds_saysSo() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        answers.last().close()
        advanceUntilIdle()

        assertEquals(ChatStatus.Ended, viewModel.chat().status)
    }

    @Test
    fun aNewTurn_startsWithoutTheChat() = runTest(dispatcher) {
        val viewModel = shownWith(add("a"))

        viewModel.play(testVideo("bbbbbbbbbbb"))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.chat)
        assertEquals(0, collecting)
    }

    /** `chat-removed`: a removed line keeps its place, marked; a banned author's lines go so too. */
    @Test
    fun moderation_marksRemovedLinesInPlace_andReplacesAnEditedOne() {
        val before = PlayerChat(lines = listOf(ChatLine(line("a", "UCa")), ChatLine(line("b", "UCb")), ChatLine(line("c", "UCa"))))

        val after = applied(before, listOf(ChatAction.Remove("b"), ChatAction.RemoveAuthor("UCa"), ChatAction.Replace("b", line("b", "UCb", "fixed"))))

        assertEquals(
            listOf(ChatLine(line("a", "UCa"), removed = true), ChatLine(line("b", "UCb", "fixed")), ChatLine(line("c", "UCa"), removed = true)),
            after.lines,
        )
    }

    @Test
    fun pin_showsAboveTheList() {
        assertEquals(ChatPinned("p1", "Pinned by NASA", line("x")), applied(PlayerChat(), listOf(PIN)).pinned)
    }

    @Test
    fun anotherBannersUnpin_keepsThePin() {
        val pinned = applied(PlayerChat(), listOf(PIN))

        assertEquals(pinned.pinned, applied(pinned, listOf(ChatAction.Unpin("other"))).pinned)
    }

    @Test
    fun itsOwnUnpin_clearsThePin() {
        val pinned = applied(PlayerChat(), listOf(PIN))

        assertNull(applied(pinned, listOf(ChatAction.Unpin("p1"))).pinned)
    }

    @Test
    fun theListKeepsTheNewest300() {
        val capped = applied(PlayerChat(), (1..305).map { add("$it") })

        assertEquals((6..305).map { "$it" }, capped.lines.map { it.message.id })
    }

    /** A profile whose live chat shows along the right side. */
    private fun sideProfile(): FakeProfileSettingsStore = FakeProfileSettingsStore(selectedId = 1).apply {
        byProfile.value = mapOf(1L to ProfileSettings(chatPlacement = ChatPlacement.Side))
    }

    @Test
    fun stream_startsInTheProfilesView() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.Side), viewModel.uiState.value.chatPlace)
        assertEquals(ChatPlacement.Side, viewModel.uiState.value.chatPlace.overlay)
    }

    @Test
    fun unreadableSettings_keepBeside() = runTest(dispatcher) {
        val viewModel = player(settings = FakeProfileSettingsStore(selectedId = 1).apply { failReads = true })
        advanceUntilIdle()

        assertEquals(ChatPlace(), viewModel.uiState.value.chatPlace)
        assertNull(viewModel.uiState.value.chatPlace.overlay)
    }

    /** LIVE-D22: a pick holds for this stream, whatever the profile's default does meanwhile. */
    @Test
    fun pick_holdsAgainstTheProfilesDefault() = runTest(dispatcher) {
        val settings = sideProfile()
        val viewModel = player(settings = settings)
        advanceUntilIdle()

        viewModel.chooseChatPlacement(ChatPlacement.TopLeft)
        settings.update(1) { it.copy(chatPlacement = ChatPlacement.BottomRight) }
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.TopLeft), viewModel.uiState.value.chatPlace)
    }

    /** Unpicked, the stream follows the default as it changes, and stays hidden. */
    @Test
    fun unpickedStream_followsTheDefault_keepingHide() = runTest(dispatcher) {
        val settings = sideProfile()
        val viewModel = player(settings = settings)
        advanceUntilIdle()

        viewModel.hideChat()
        settings.update(1) { it.copy(chatPlacement = ChatPlacement.BottomRight) }
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.BottomRight, hidden = true), viewModel.uiState.value.chatPlace)
    }

    /** A pick made before the profile's settings are read isn't undone when they arrive. */
    @Test
    fun pickBeforeTheSettingsAreRead_holds() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())

        viewModel.chooseChatPlacement(ChatPlacement.TopLeft)
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.TopLeft), viewModel.uiState.value.chatPlace)
    }

    /** LIVE-D26: the next stream starts from the profile's default, not hidden. */
    @Test
    fun nextStream_startsFromTheDefault() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()
        viewModel.chooseChatPlacement(ChatPlacement.TopLeft)
        viewModel.hideChat()

        viewModel.play(testVideo("bbbbbbbbbbb"))
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.Side), viewModel.uiState.value.chatPlace)
    }

    /** The last stream's pick doesn't hold the next one: it follows the default as it changes. */
    @Test
    fun nextStream_followsTheDefaultAgain() = runTest(dispatcher) {
        val settings = sideProfile()
        val viewModel = player(settings = settings)
        advanceUntilIdle()
        viewModel.chooseChatPlacement(ChatPlacement.TopLeft)
        viewModel.play(testVideo("bbbbbbbbbbb"))

        settings.update(1) { it.copy(chatPlacement = ChatPlacement.BottomRight) }
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.BottomRight), viewModel.uiState.value.chatPlace)
    }

    /** LIVE-D26: Hide chat takes chat off the picture; Chat shows it in the stream's view again. */
    @Test
    fun hideThenShow_returnsToTheStreamsView() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()
        viewModel.chooseChatPlacement(ChatPlacement.BottomLeft)

        viewModel.hideChat()
        val hidden = viewModel.uiState.value.chatPlace.overlay
        viewModel.showChat()

        assertEquals(null to ChatPlacement.BottomLeft, hidden to viewModel.uiState.value.chatPlace.overlay)
    }

    /** LIVE-D22: Scroll chat moves chat to Beside for now; BACK puts it back over the picture. */
    @Test
    fun scrollThenBack_returnsOverThePicture() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()

        viewModel.scrollChat()
        val scrolling = viewModel.uiState.value.chatPlace
        viewModel.endChatScroll()

        assertEquals(ChatPlace(ChatPlacement.Side, scrolling = true), scrolling)
        assertNull(scrolling.overlay)
        assertEquals(ChatPlacement.Side, viewModel.uiState.value.chatPlace.overlay)
    }

    @Test
    fun scroll_isNothingInBesideOrHidden() = runTest(dispatcher) {
        val beside = player()
        val hidden = player(settings = sideProfile())
        advanceUntilIdle()
        hidden.hideChat()

        beside.scrollChat()
        hidden.scrollChat()

        assertEquals(ChatPlace(), beside.uiState.value.chatPlace)
        assertEquals(ChatPlace(ChatPlacement.Side, hidden = true), hidden.uiState.value.chatPlace)
    }

    @Test
    fun hide_endsTheScroll() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()
        viewModel.scrollChat()

        viewModel.hideChat()

        assertEquals(ChatPlace(ChatPlacement.Side, hidden = true), viewModel.uiState.value.chatPlace)
    }

    @Test
    fun pick_endsTheScroll() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()
        viewModel.scrollChat()

        viewModel.chooseChatPlacement(ChatPlacement.TopRight)

        assertEquals(ChatPlace(ChatPlacement.TopRight), viewModel.uiState.value.chatPlace)
    }

    @Test
    fun pick_showsAHiddenChat() = runTest(dispatcher) {
        val viewModel = player(settings = sideProfile())
        advanceUntilIdle()
        viewModel.hideChat()

        viewModel.chooseChatPlacement(ChatPlacement.TopRight)

        assertEquals(ChatPlace(ChatPlacement.TopRight), viewModel.uiState.value.chatPlace)
    }

    /** An unpicked stream in Scroll chat stays there while the default moves between views over the picture. */
    @Test
    fun scroll_holdsWhileTheDefaultMovesOverThePicture() = runTest(dispatcher) {
        val settings = sideProfile()
        val viewModel = player(settings = settings)
        advanceUntilIdle()
        viewModel.scrollChat()

        settings.update(1) { it.copy(chatPlacement = ChatPlacement.BottomRight) }
        advanceUntilIdle()

        assertEquals(ChatPlace(ChatPlacement.BottomRight, scrolling = true), viewModel.uiState.value.chatPlace)
    }

    /** A default moved to Beside ends Scroll chat: there's no picture to return to. */
    @Test
    fun scroll_endsWhenTheDefaultTurnsBeside() = runTest(dispatcher) {
        val settings = sideProfile()
        val viewModel = player(settings = settings)
        advanceUntilIdle()
        viewModel.scrollChat()

        settings.update(1) { it.copy(chatPlacement = ChatPlacement.Beside) }
        advanceUntilIdle()

        assertEquals(ChatPlace(), viewModel.uiState.value.chatPlace)
    }

    private suspend fun TestScope.shownWith(vararg actions: ChatAction): PlayerViewModel {
        val viewModel = player()
        viewModel.openChat(0, SEED)
        viewModel.chatShown(true)
        advanceUntilIdle()
        if (actions.isNotEmpty()) answers.last().send(LiveChatUpdate.Batch(actions.toList(), VIEWS))
        advanceUntilIdle()
        return viewModel
    }

    /** A chat picture loads over https alone: the image loader also opens files and content URIs. */
    @Test
    fun chatPicture_keepsHttpsOnly() {
        val pictures = listOf("https://yt3.ggpht.com/a=s64", "HTTPS://yt4.ggpht.com/b", "http://yt3.ggpht.com/c", "file:///data/data/x", "content://x/y", null)

        assertEquals(listOf("https://yt3.ggpht.com/a=s64", "HTTPS://yt4.ggpht.com/b", null, null, null, null), pictures.map(::chatPicture))
    }

    @Test
    fun newChatLinesText_singularAndPlural() {
        assertEquals(listOf("1 new message", "14 new messages"), listOf(newChatLinesText(1), newChatLinesText(14)))
    }

    /** A replacement naming a line already listed never lists it twice: the replaced line goes. */
    @Test
    fun replaceNamingAListedId_dropsTheReplacedLine() {
        val chat = applied(
            PlayerChat(),
            listOf(add("a"), add("b"), ChatAction.Replace("b", line("a")), ChatAction.Replace("a", line("c")), add("c"), add("b")),
        )

        assertEquals(listOf("c", "b"), chat.lines.map { it.message.id })
    }

    private fun add(id: String): ChatAction = ChatAction.Add(line(id))

    private fun line(id: String, channel: String? = null, text: String = id): ChatMessage =
        ChatMessage(id, ChatKind.TEXT, "author", channel, null, emptySet(), listOf(ChatRun.Text(text)))

    private companion object {
        val SEED = LiveChatSeed("seed", replay = false)
        val VIEWS = ChatViews("top", "live")
        val PIN = ChatAction.Pin("p1", "Pinned by NASA", pinnedLine("x"))
        val OFFLINE = AccessException("unreachable", IOException("reset"), AccessFailure.OFFLINE)

        private fun pinnedLine(id: String): ChatMessage = ChatMessage(id, ChatKind.TEXT, "author", null, null, emptySet(), listOf(ChatRun.Text(id)))
    }
}
