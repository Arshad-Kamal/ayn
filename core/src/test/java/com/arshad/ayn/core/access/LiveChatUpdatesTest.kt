package com.arshad.ayn.core.access

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live chat's poll loop (LIVE-D6) on scripted answers, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatUpdatesTest {

    @Test
    fun eachAnswer_isOneBatch_andTheFlowEndsWithTheChat() = runTest {
        val chat = ScriptedChat(
            testScheduler,
            answer(listOf(add("a")), next("t2", 2_000L), views = ChatViews("top", "live")),
            answer(listOf(add("b")), next = null),
        )

        val updates = liveChatUpdates("t1", chat::fetch).toList()

        assertEquals(
            listOf(
                LiveChatUpdate.Batch(listOf(add("a")), ChatViews("top", "live")),
                LiveChatUpdate.Batch(listOf(add("b")), null),
            ),
            updates,
        )
        assertEquals(listOf("t1", "t2"), chat.tokens)
    }

    /** Under 1 s hammers YouTube, over 8 s loses lines; no wait named = the floor. */
    @Test
    fun waitBetweenRequests_isYouTubesTimeoutClampedToOneToEightSeconds() = runTest {
        val chat = ScriptedChat(
            testScheduler,
            answer(next = next("t2", 10_000L)),
            answer(next = next("t3", 200L)),
            answer(next = next("t4", null)),
            answer(next = next("t5", 4_904L)),
            answer(next = null),
        )

        liveChatUpdates("t1", chat::fetch).toList()

        assertEquals(listOf(0L, 8_000L, 9_000L, 10_000L, 14_904L), chat.times)
    }

    @Test
    fun aLineYouTubeSendsAgain_isDropped_whileRemovalsAndReplacementsPass() = runTest {
        val chat = ScriptedChat(
            testScheduler,
            answer(listOf(add("a"), add("b")), next("t2", 1_000L)),
            answer(listOf(add("b"), ChatAction.Replace("b", message("b")), ChatAction.Remove("a"), add("c")), next = null),
        )

        val batches = liveChatUpdates("t1", chat::fetch).toList().filterIsInstance<LiveChatUpdate.Batch>()

        assertEquals(listOf(ChatAction.Replace("b", message("b")), ChatAction.Remove("a"), add("c")), batches[1].actions)
    }

    /** The newest 2000 ids are remembered: the oldest falls out, the newest stays. */
    @Test
    fun deDuplication_remembersTheNewest2000Ids() = runTest {
        val chat = ScriptedChat(
            testScheduler,
            answer((0..2_000).map { add("$it") }, next("t2", 1_000L)),
            answer(listOf(add("0"), add("2000")), next = null),
        )

        val batches = liveChatUpdates("t1", chat::fetch).toList().filterIsInstance<LiveChatUpdate.Batch>()

        assertEquals(listOf(add("0")), batches[1].actions)
    }

    @Test
    fun firstRequestUnreachable_failsTheFlowWithoutRetrying() = runTest {
        val chat = ScriptedChat(testScheduler, offline())

        val failure = runCatching { liveChatUpdates("t1", chat::fetch).toList() }.exceptionOrNull()

        assertEquals(AccessFailure.OFFLINE, (failure as AccessException).failure)
        assertEquals(listOf("t1"), chat.tokens)
    }

    /** 5 s growing to 60 s, the last good token each time; an answer resets the wait. */
    @Test
    fun droppedConnection_reconnectsWithTheLastGoodToken_backingOffToAMinute() = runTest {
        val chat = ScriptedChat(
            testScheduler,
            answer(next = next("t2", 1_000L)),
            offline(), offline(), offline(), offline(), offline(), offline(),
            answer(next = next("t3", 1_000L)),
            offline(),
            answer(next = null),
        )

        val updates = liveChatUpdates("t1", chat::fetch).toList()

        assertEquals(listOf("t1", "t2", "t2", "t2", "t2", "t2", "t2", "t2", "t3", "t3"), chat.tokens)
        assertEquals(listOf(0L, 1_000L, 6_000L, 16_000L, 36_000L, 76_000L, 136_000L, 196_000L, 197_000L, 202_000L), chat.times)
        assertEquals(7, updates.count { it == LiveChatUpdate.Reconnecting })
    }

    @Test
    fun unreadableAnswerAfterTheFirst_failsTheFlow() = runTest {
        val chat = ScriptedChat(testScheduler, answer(next = next("t2", 1_000L)), unreadable())

        val failure = runCatching { liveChatUpdates("t1", chat::fetch).toList() }.exceptionOrNull()

        assertEquals(AccessFailure.UNREADABLE, (failure as AccessException).failure)
    }

    @Test
    fun cancellingTheCollector_stopsPolling() = runTest {
        val chat = ScriptedChat(testScheduler, *Array(10) { answer(next = next("t", 1_000L)) })

        val job = launch { liveChatUpdates("t1", chat::fetch).collect {} }
        advanceTimeBy(2_500L)
        job.cancel()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(3, chat.tokens.size)
    }

    /** Answers in script order, noting each request's token and virtual time. */
    private class ScriptedChat(private val scheduler: TestCoroutineScheduler, vararg answers: () -> LiveChatReply) {
        private val script = ArrayDeque(answers.toList())
        val tokens = mutableListOf<String>()
        val times = mutableListOf<Long>()

        @Suppress("RedundantSuspendModifier")
        suspend fun fetch(token: String): LiveChatReply {
            tokens += token
            times += scheduler.currentTime
            val next = script.removeFirstOrNull() ?: throw AssertionError("unscripted request for $token")
            return next()
        }
    }

    private fun answer(
        actions: List<ChatAction> = emptyList(),
        next: ChatContinuation?,
        views: ChatViews? = null,
    ): () -> LiveChatReply = { LiveChatReply(actions, next, views) }

    private fun offline(): () -> LiveChatReply =
        { throw AccessException("unreachable", IOException("reset"), AccessFailure.OFFLINE) }

    private fun unreadable(): () -> LiveChatReply =
        { throw AccessException("unreadable", IllegalStateException("shape"), AccessFailure.UNREADABLE) }

    private fun next(token: String, timeoutMs: Long?): ChatContinuation = ChatContinuation(token, timeoutMs)

    private fun add(id: String): ChatAction = ChatAction.Add(message(id))

    private fun message(id: String): ChatMessage =
        ChatMessage(id, ChatKind.TEXT, "author", null, null, emptySet(), listOf(ChatRun.Text(id)))
}
