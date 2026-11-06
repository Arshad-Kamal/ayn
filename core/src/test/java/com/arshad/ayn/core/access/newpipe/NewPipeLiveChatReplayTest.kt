package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.ReplayPage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat replay pages (LIVE-D7) as requested and read, on the LIVE.S recordings; goldens read from
 *  the raw answers. */
class NewPipeLiveChatReplayTest {

    @Test
    fun firstPage_postsTheSeedAtThePosition_toTheReplayEndpoint() = runTest {
        val chat = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_FIRST))

        replayOver(chat).replay(RecordedLiveChat.NEXT_REPLAY_SEED, offsetMs = 0)

        val request = chat.requests.single()
        assertEquals("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat_replay?prettyPrint=false", request.url())
        val body = request.dataToSend()!!.decodeToString()
        assertTrue(body, "\"continuation\":\"${RecordedLiveChat.NEXT_REPLAY_SEED}\"" in body)
        assertTrue(body, "\"currentPlayerState\":{\"playerOffsetMs\":\"0\"}" in body && "\"clientName\":\"WEB\"" in body)
    }

    @Test
    fun seekAsksAtItsOffset() = runTest {
        val chat = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.REPLAY_SEEK))

        replayOver(chat).replay(RecordedLiveChat.REPLAY_FIRST_SEEK, offsetMs = 6_545_000)

        assertTrue("\"playerOffsetMs\":\"6545000\"" in chat.requests.single().dataToSend()!!.decodeToString())
    }

    /** 101 items, 3 of them held-line placeholders (not drawn); the first is YouTube's notice. */
    @Test
    fun firstPage_readsItsLinesInOrder_itsTokensAndViews() {
        val page = page(RecordedLiveChat.REPLAY_FIRST)

        assertEquals(98, page.items.size)
        assertEquals(0L to ChatKind.SYSTEM, page.items.first().offsetMs to (page.items.first().action as ChatAction.Add).message.kind)
        assertEquals(65_577L to 65_577L, page.items.last().offsetMs to page.untilMs)
        assertTrue(page.items.all { it.action is ChatAction.Add })
        assertEquals(RecordedLiveChat.REPLAY_FIRST_NEXT to RecordedLiveChat.REPLAY_FIRST_SEEK, page.next to page.seek)
        assertEquals(ChatViews(RecordedLiveChat.REPLAY_TOP_TOKEN, RecordedLiveChat.REPLAY_LIVE_TOKEN), page.views)
    }

    /** A seek's page starts before the moment asked (6 545 000 ms) and runs past it. */
    @Test
    fun laterPages_spanTheirStretch_withoutViews() {
        val spans = listOf(RecordedLiveChat.REPLAY_MIDDLE, RecordedLiveChat.REPLAY_SEEK, RecordedLiveChat.REPLAY_LAST).map { name ->
            val page = page(name)
            assertNull(page.views)
            assertTrue(page.next != null && page.seek != null)
            Triple(page.items.size, page.items.first().offsetMs, page.items.last().offsetMs)
        }

        assertEquals(
            listOf(Triple(44, 1_533_317L, 1_626_940L), Triple(93, 6_319_693L, 6_691_460L), Triple(93, 12_822_800L, 13_095_694L)),
            spans,
        )
    }

    @Test
    fun answerWithoutChatContents_isTheEnd() {
        assertEquals(ReplayPage.END, pageOf("""{"responseContext":{}}"""))
    }

    /** YouTube answering no item with a position is the end, though it names a next page. */
    @Test
    fun answerWithoutAPositionedItem_isTheEnd_itsSeekKept() {
        val none = pageOf(answer(continuations = true))
        val unpositioned = pageOf(answer(item("c", null), continuations = true))

        assertEquals(Triple(0, null, "s"), Triple(none.items.size, none.next, none.seek))
        assertEquals(null to null, unpositioned.next to unpositioned.untilMs)
    }

    /** A stretch of held lines only: nothing to draw, yet the replay goes on, saying how far. */
    @Test
    fun pageOfKindsNotDrawn_goesOn() {
        val placeholder = """{"replayChatItemAction":{"actions":[{"addChatItemAction":{"item":{"liveChatPlaceholderItemRenderer":{"id":"h"}}}}],"videoOffsetTimeMsec":"5"}}"""

        val page = pageOf(answer(placeholder, continuations = true))

        assertEquals(Triple(0, "n", 5L), Triple(page.items.size, page.next, page.untilMs))
    }

    @Test
    fun itemsOutOfOrder_comeInOffsetOrder_oneWithoutAnOffsetSkipped() {
        val page = pageOf(answer(item("b", "\"2000\""), item("a", "1000"), item("c", null)))

        assertEquals(listOf(1_000L to "a", 2_000L to "b"), page.items.map { it.offsetMs to (it.action as ChatAction.Add).message.id })
    }

    @Test
    fun serverError_readsAsOffline_clientErrorAndNonChatAsUnreadable() {
        assertEquals(AccessFailure.OFFLINE, failureFor(503 to "{}"))
        assertEquals(AccessFailure.OFFLINE, failureFor(429 to "{}"))
        assertEquals(AccessFailure.UNREADABLE, failureFor(400 to """{"error":{"code":400}}"""))
        assertEquals(AccessFailure.UNREADABLE, failureFor(200 to """{"not":"chat"}"""))
        assertEquals(AccessFailure.UNREADABLE, failureFor(200 to "<html>not chat</html>"))
    }

    private fun page(name: String): ReplayPage = pageOf(RecordedLiveChat.text(name))

    private fun TestScope.replayOver(server: ChatServer): NewPipeLiveChat = NewPipeLiveChat(server, io = StandardTestDispatcher(testScheduler))

    private fun pageOf(body: String): ReplayPage {
        var page: ReplayPage? = null
        runTest { page = replayOver(ChatServer(200 to body)).replay("t", 0) }
        return page!!
    }

    private fun failureFor(answer: Pair<Int, String>): AccessFailure {
        var failure: Throwable? = null
        runTest { failure = runCatching { replayOver(ChatServer(answer)).replay("t", 0) }.exceptionOrNull() }
        return (failure as AccessException).failure
    }

    /** A replay answer holding [items], with a next token `n` and a seek token `s` when [continuations]. */
    private fun answer(vararg items: String, continuations: Boolean = false): String {
        val tokens = if (continuations) {
            ""","continuations":[{"liveChatReplayContinuationData":{"continuation":"n"}},{"playerSeekContinuationData":{"continuation":"s"}}]"""
        } else {
            ""
        }
        return """{"responseContext":{},"continuationContents":{"liveChatContinuation":{"actions":[${items.joinToString(",")}]$tokens}}}"""
    }

    private fun item(id: String, offset: String?): String {
        val line = """{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"$id","message":{"runs":[{"text":"hi"}]}}}}}"""
        val at = offset?.let { ""","videoOffsetTimeMsec":$it""" }.orEmpty()
        return """{"replayChatItemAction":{"actions":[$line]$at}}"""
    }
}
