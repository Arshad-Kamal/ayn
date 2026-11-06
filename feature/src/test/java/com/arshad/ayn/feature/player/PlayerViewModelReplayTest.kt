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
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.newpipe.ChatServer
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.NewPipeLiveChat
import com.arshad.ayn.core.access.newpipe.RecordedLiveChat
import com.arshad.ayn.core.access.ryd.RecordedVotes
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The player's chat replay (LIVE-D7): the recorded first page through the real seam, then the
 * rules on scripted pages (each request waits until the test answers it): lines by position,
 * paging ahead only while playing, a jump back or past the pages held re-seeding with the seek
 * token, the end, a show starting again, failure and reconnecting, the Top/Live switch; and when
 * the player offers Chat.
 */
class PlayerViewModelReplayTest {

    private val dispatcher = StandardTestDispatcher()
    private val requests = mutableListOf<Pair<Pair<String, Long>, CompletableDeferred<ReplayPage>>>()
    private val scripted = FakeLiveChat(page = { token, offsetMs ->
        CompletableDeferred<ReplayPage>().also { requests += (token to offsetMs) to it }.await()
    })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun player(chat: LiveChat = scripted): PlayerViewModel = PlayerViewModel(
        opening = testVideo(FIXED_VIDEO_ID),
        playAll = null,
        extractor = UnsupportedExtractor("replay"),
        store = FakeSubscriptionStore(),
        watchStore = FakeWatchStore(),
        playlistStore = FakePlaylistStore(),
        settingsStore = FakeProfileSettingsStore(selectedId = 1),
        voteSource = RecordedVotes.source {},
        captionSource = RecordedCaptions.source(),
        liveChat = chat,
        compute = Dispatchers.Main,
    )

    private fun PlayerViewModel.chat(): PlayerChat = uiState.value.chat!!

    private fun PlayerViewModel.ids(): List<String> = chat().lines.map { it.message.id }

    /** A replay opened and shown, its first page asked at [atMs] and answered with [first]. */
    private fun TestScope.opened(first: ReplayPage, atMs: Long = 0, playing: Boolean = true): PlayerViewModel {
        val viewModel = player()
        viewModel.openChat(0, REPLAY)
        viewModel.chatShown(true)
        viewModel.chatTick(0, atMs, playing)
        runCurrent()
        answer(first)
        return viewModel
    }

    private fun TestScope.answer(page: ReplayPage) {
        requests.last().second.complete(page)
        runCurrent()
    }

    private fun asked(): List<Pair<String, Long>> = requests.map { it.first }

    /** 101 items to 65 577 ms; at 30 s the 53 sent by 13 013 ms show (a quiet stretch follows). */
    @Test
    fun recordedFirstPage_showsTheLinesTheMomentReached_withTheirTimesAndViews() = runTest(dispatcher) {
        val viewModel = recordedReplay(ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST)))

        viewModel.chatTick(0, 30_000, playing = false)
        runCurrent()
        val chat = viewModel.chat()

        assertEquals(listOf(53, 0, 13_013), listOf(chat.lines.size, chat.lines.first().atMs!!.toInt(), chat.lines.last().atMs!!.toInt()))
        assertEquals(ChatKind.SYSTEM, chat.lines.first().message.kind)
        assertEquals(ChatViews(RecordedLiveChat.REPLAY_TOP_TOKEN, RecordedLiveChat.REPLAY_LIVE_TOKEN), chat.views)
        assertEquals(ChatStatus.Flowing, chat.status)
    }

    /** The recorded first page, then its next token asked once the position nears its end. */
    @Test
    fun recordedWalk_asksTheFirstPagesNextToken_fromItsReach() = runTest(dispatcher) {
        val server = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST), 200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_MIDDLE))
        val viewModel = recordedReplay(server)

        viewModel.chatTick(0, 0, playing = true)
        runCurrent()
        viewModel.chatTick(0, 6_000, playing = true)
        runCurrent()

        val body = server.requests[1].dataToSend()!!.decodeToString()
        assertEquals(true to true, ("\"continuation\":\"${RecordedLiveChat.REPLAY_FIRST_NEXT}\"" in body) to ("\"playerOffsetMs\":\"65577\"" in body))
    }

    /** A jump past the recorded first page asks its seek token there; the seek page's 45 lines sent
     *  by then show at once, the last at 6 529 329 ms. */
    @Test
    fun recordedWalk_jumpAsksTheFirstPagesSeekToken() = runTest(dispatcher) {
        val server = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST), 200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_SEEK))
        val viewModel = recordedReplay(server)

        viewModel.chatTick(0, 0, playing = false)
        runCurrent()
        viewModel.chatTick(0, 6_545_000, playing = false)
        runCurrent()

        val body = server.requests[1].dataToSend()!!.decodeToString()
        assertEquals(true to true, ("\"continuation\":\"${RecordedLiveChat.REPLAY_FIRST_SEEK}\"" in body) to ("\"playerOffsetMs\":\"6545000\"" in body))
        assertEquals(45 to 6_529_329L, viewModel.chat().lines.size to viewModel.chat().lines.last().atMs)
    }

    private fun recordedReplay(server: ChatServer): PlayerViewModel = player(NewPipeLiveChat(server, io = dispatcher)).apply {
        openChat(0, LiveChatSeed(RecordedLiveChat.NEXT_REPLAY_SEED, replay = true))
        chatShown(true)
    }

    @Test
    fun opened_asksTheSeedAtThePosition_linesShowAsThePositionReachesThem() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 2_000 to "b", 5_000 to "c", next = "n"), atMs = 1_500)

        val atOpen = viewModel.ids()
        viewModel.chatTick(0, 4_000, playing = true)
        val later = viewModel.ids()

        assertEquals(listOf("seed" to 1_500L), asked().take(1))
        assertEquals(listOf("a") to listOf("a", "b"), atOpen to later)
        assertEquals(listOf(1_000L, 2_000L), viewModel.chat().lines.map { it.atMs })
    }

    @Test
    fun aTickBeforeTheShow_asksNothing() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openChat(0, REPLAY)

        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()

        assertEquals(emptyList<Pair<String, Long>>(), asked())
    }

    /** The pages held end within a minute of the position: the next is asked from where they end. */
    @Test
    fun playing_asksTheNextPageAMinuteAhead_once() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 10_000 to "b", next = "n1"))

        viewModel.chatTick(0, 500, playing = true)
        viewModel.chatTick(0, 700, playing = true)
        runCurrent()

        assertEquals(listOf("seed" to 0L, "n1" to 10_000L), asked())
    }

    @Test
    fun paused_asksNoPage() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1"), playing = false)

        viewModel.chatTick(0, 1_000, playing = false)
        runCurrent()

        assertEquals(listOf("seed" to 0L) to listOf("a"), asked() to viewModel.ids())
    }

    @Test
    fun pageFarAhead_isntAskedYet() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 90_000 to "b", next = "n1"))

        viewModel.chatTick(0, 20_000, playing = true)
        runCurrent()

        assertEquals(listOf("seed" to 0L), asked())
    }

    /** A tick a little behind the last (under a second) is jitter, not a jump. */
    @Test
    fun aTickSlightlyBack_keepsTheList() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 10_000 to "b", next = "n1"), atMs = 5_000, playing = false)

        viewModel.chatTick(0, 4_001, playing = false)

        assertEquals(1 to listOf("a"), asked().size to viewModel.ids())
    }

    @Test
    fun aTickJustOverASecondBack_isAJump() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 10_000 to "b", next = "n1"), atMs = 5_000, playing = false)

        viewModel.chatTick(0, 3_999, playing = false)

        assertEquals(ChatStatus.Finding(3_999), viewModel.chat().status)
    }

    /** The transport blocks: a request cancelled by a jump still ends, here failing. That stale
     *  failure doesn't overwrite the jump's state. */
    @Test
    fun cancelledAskFailingLate_publishesNothing() = runTest(dispatcher) {
        val late = CompletableDeferred<ReplayPage>()
        val asks = ArrayDeque(listOf(late, CompletableDeferred()))
        val viewModel = player(FakeLiveChat(page = { _, _ -> asks.removeFirst().let { withContext(NonCancellable) { it.await() } } }))
        viewModel.openChat(0, REPLAY)
        viewModel.chatShown(true)
        viewModel.chatTick(0, 0, playing = false)
        runCurrent()
        viewModel.chatTick(0, 500_000, playing = false)
        runCurrent()

        late.completeExceptionally(UNREADABLE)
        runCurrent()

        assertEquals(ChatStatus.Finding(500_000), viewModel.chat().status)
    }

    /** A seek's page starts before the moment asked: the lines up to it show at once. */
    @Test
    fun jumpBack_emptiesTheList_andAsksTheSeekTokenThere() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 30_000 to "b", next = "n1", seek = "s1"), atMs = 40_000)

        viewModel.chatTick(0, 10_000, playing = true)
        runCurrent()
        val finding = viewModel.chat()
        answer(page(8_000 to "x", 12_000 to "y", next = "n2", seek = "s2"))

        assertEquals(ChatStatus.Finding(10_000) to emptyList<ChatLine>(), finding.status to finding.lines)
        assertEquals("s1" to 10_000L, asked().last())
        assertEquals(listOf("x"), viewModel.ids())
    }

    @Test
    fun jumpForwardPastThePagesHeld_reseeds_oneWithinThemDoesnt() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 30_000 to "b", next = "n1", seek = "s1"))

        viewModel.chatTick(0, 85_000, playing = false)
        val within = asked().size to viewModel.ids()
        viewModel.chatTick(0, 95_000, playing = false)
        runCurrent()

        assertEquals(1 to listOf("a", "b"), within)
        assertEquals(("s1" to 95_000L) to ChatStatus.Finding(95_000), asked().last() to viewModel.chat().status)
    }

    /** No next token: nothing more is asked, however far the position runs, until a jump back. */
    @Test
    fun lastPage_endsTheAsking_untilAJumpBack() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", seek = "s1"))

        viewModel.chatTick(0, 500_000, playing = true)
        viewModel.chatTick(0, 600_000, playing = true)
        runCurrent()
        val atEnd = asked().size
        viewModel.chatTick(0, 2_000, playing = true)
        runCurrent()

        assertEquals(1 to ("s1" to 2_000L), atEnd to asked().last())
    }

    /** Every line not drawn or filtered out: the replay goes on from as far as the page reached,
     *  and playing past it within a minute of that reach isn't a jump. */
    @Test
    fun pageWithoutItemsButANext_goesOnFromItsReach() = runTest(dispatcher) {
        val viewModel = opened(page(next = "n1").copy(untilMs = 200_000))

        viewModel.chatTick(0, 150_000, playing = true)
        runCurrent()

        assertEquals(listOf("seed" to 0L, "n1" to 200_000L), asked())
    }

    /** A page answered after the chat left the screen shows nothing. */
    @Test
    fun hidden_cancelsTheAskInFlight() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 10_000 to "b", next = "n1"), atMs = 2_000)
        viewModel.chatTick(0, 2_200, playing = true)
        runCurrent()
        val before = viewModel.chat()

        viewModel.chatShown(false)
        requests.last().second.complete(page(11_000 to "c", next = "n2"))
        runCurrent()

        assertEquals(before, viewModel.chat())
        assertEquals("n1" to 10_000L, asked().last())
    }

    @Test
    fun hidden_thenShown_startsAgainAtThePosition() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1", seek = "s1"), atMs = 2_000)

        viewModel.chatShown(false)
        viewModel.chatShown(true)
        viewModel.chatTick(0, 3_000, playing = true)
        runCurrent()

        assertEquals(("s1" to 3_000L) to ChatStatus.Loading, asked().last() to viewModel.chat().status)
        assertEquals(emptyList<String>(), viewModel.ids())
    }

    /** A replay whose first page failed. */
    private fun TestScope.failedAtFirst(): PlayerViewModel {
        val viewModel = player()
        viewModel.openChat(0, REPLAY)
        viewModel.chatShown(true)
        viewModel.chatTick(0, 0, playing = true)
        runCurrent()
        requests.last().second.completeExceptionally(OFFLINE)
        runCurrent()
        return viewModel
    }

    @Test
    fun firstPageFailing_fails_ticksAskNothing() = runTest(dispatcher) {
        val viewModel = failedAtFirst()

        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()

        assertEquals(ChatStatus.Failed(AccessFailure.OFFLINE) to 1, viewModel.chat().status to asked().size)
    }

    @Test
    fun tryAgain_asksAtTheNextTicksPosition() = runTest(dispatcher) {
        val viewModel = failedAtFirst()

        viewModel.retryChat()
        viewModel.chatTick(0, 2_000, playing = true)
        runCurrent()

        assertEquals(("seed" to 2_000L) to ChatStatus.Loading, asked().last() to viewModel.chat().status)
    }

    /** A seek token that failed isn't asked again: Try again asks the view's own. */
    @Test
    fun tryAgainAfterAFailedSeek_asksTheViewsToken() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1", seek = "s1"), atMs = 2_000)
        viewModel.chatTick(0, 500_000, playing = false)
        runCurrent()
        requests.last().second.completeExceptionally(UNREADABLE)
        runCurrent()

        viewModel.retryChat()
        viewModel.chatTick(0, 500_000, playing = false)
        runCurrent()

        assertEquals(listOf("seed" to 2_000L, "s1" to 500_000L, "seed" to 500_000L), asked())
    }

    /** Anything but a dropped connection fails, even after a page: no retry. */
    @Test
    fun unreadablePageAfterAPage_fails_withoutRetrying() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1"))
        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()

        requests.last().second.completeExceptionally(UNREADABLE)
        runCurrent()
        advanceTimeBy(120_000)

        assertEquals(ChatStatus.Failed(AccessFailure.UNREADABLE) to 2, viewModel.chat().status to asked().size)
    }

    @Test
    fun droppedConnectionAfterAPage_reconnects_keepingTheLines() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1"))
        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()

        requests.last().second.completeExceptionally(OFFLINE)
        runCurrent()

        assertEquals(ChatStatus.Reconnecting to listOf("a"), viewModel.chat().status to viewModel.ids())
    }

    /** The same request again after 5 s, doubling to a minute and holding there. */
    @Test
    fun reconnecting_asksTheSameAgain_5sDoublingTo60s() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1"))
        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()
        val waits = mutableListOf<Long>()

        repeat(6) {
            val before = requests.size
            requests.last().second.completeExceptionally(OFFLINE)
            runCurrent()
            val start = testScheduler.currentTime
            while (requests.size == before) {
                advanceTimeBy(1_000)
                runCurrent()
            }
            waits += testScheduler.currentTime - start
        }

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), waits)
        assertEquals(setOf("n1" to 1_000L), asked().drop(1).toSet())
    }

    /** The wait grows within one ask only: a later ask that drops waits 5 s again. */
    @Test
    fun aLaterAsksDroppedConnection_waits5sAgain() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1"))
        viewModel.chatTick(0, 1_000, playing = true)
        runCurrent()
        repeat(2) { dropAndWaitForTheRetry() }
        requests.last().second.complete(page(2_000 to "b", next = "n2"))
        runCurrent()
        viewModel.chatTick(0, 2_000, playing = true)
        runCurrent()
        assertEquals("n2" to 2_000L, asked().last())

        assertEquals(5_000L, dropAndWaitForTheRetry())
    }

    /** Fails the newest request as a dropped connection; how long until it is asked again. */
    private fun TestScope.dropAndWaitForTheRetry(): Long {
        val before = requests.size
        requests.last().second.completeExceptionally(OFFLINE)
        runCurrent()
        val start = testScheduler.currentTime
        while (requests.size == before) {
            advanceTimeBy(1_000)
            runCurrent()
        }
        return testScheduler.currentTime - start
    }

    /** The old view's page still on its way when Live is picked: it is dropped, its seek token too. */
    @Test
    fun liveViewPickedWhileAPageIsOnItsWay_asksTheLiveToken() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", 10_000 to "b", next = "n1", seek = "s1").copy(views = ChatViews("top", "live")), atMs = 2_000)
        viewModel.chatTick(0, 2_200, playing = true)
        runCurrent()

        viewModel.chooseChatView(live = true)
        requests.last().second.complete(page(11_000 to "c", next = "n2", seek = "s-top"))
        runCurrent()
        viewModel.chatTick(0, 2_400, playing = true)
        runCurrent()

        assertEquals(("live" to 2_400L) to listOf("n1" to 10_000L), asked().last() to asked().filter { it.first.startsWith("n") })
    }

    @Test
    fun liveViewPicked_asksItsTokenAtThePosition() = runTest(dispatcher) {
        val viewModel = opened(page(1_000 to "a", next = "n1", seek = "s1").copy(views = ChatViews("top", "live")), atMs = 2_000)

        viewModel.chooseChatView(live = true)
        viewModel.chatTick(0, 2_500, playing = true)
        runCurrent()

        assertEquals(("live" to 2_500L) to true, asked().last() to viewModel.chat().liveView)
    }

    /** Opened on screen before the page resolved (no seed yet): the replay starts at the next tick. */
    @Test
    fun offChat_isReplacedByAReplay_stillShown() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openChat(0, null)
        viewModel.chatShown(true)

        viewModel.openChat(0, REPLAY)
        viewModel.chatTick(0, 3_000, playing = true)
        runCurrent()

        assertEquals(listOf("seed" to 3_000L), asked())
    }

    @Test
    fun chatIsOffered_forALiveOrAKeptReplay_orBeforeThePageResolves() {
        val video = PlaybackDressing(null, emptyList(), null)

        assertEquals(
            listOf(true, true, true, false),
            listOf(null, video.copy(liveChat = REPLAY), video.copy(liveChat = LiveChatSeed("s", replay = false)), video).map(::liveChatOffered),
        )
    }

    @Test
    fun aTickForAnotherTurn_isDropped() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openChat(0, REPLAY)
        viewModel.chatShown(true)

        viewModel.chatTick(1, 1_000, playing = true)
        runCurrent()

        assertEquals(emptyList<Pair<String, Long>>(), asked())
    }

    /** A page as the seam reads one: it reaches its last line. */
    private fun page(vararg lines: Pair<Int, String>, next: String? = null, seek: String? = null): ReplayPage = ReplayPage(
        lines.map { (at, id) -> ReplayItem(at.toLong(), ChatAction.Add(line(id))) },
        next,
        seek,
        views = null,
        untilMs = lines.maxOfOrNull { it.first.toLong() },
    )

    private fun line(id: String): ChatMessage = ChatMessage(id, ChatKind.TEXT, "author", null, null, emptySet(), listOf(ChatRun.Text(id)))

    private companion object {
        val REPLAY = LiveChatSeed("seed", replay = true)
        val OFFLINE = AccessException("unreachable", IOException("reset"), AccessFailure.OFFLINE)
        val UNREADABLE = AccessException("not chat", IllegalStateException("no chat contents"), AccessFailure.UNREADABLE)
    }
}
