package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.LiveChatUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat request as sent and its answers as read, on the LIVE.S recording; everything but the
 *  chat endpoint replays the recorded bootstrap (client version). */
class NewPipeLiveChatTest {

    @Test
    fun firstRequest_postsThePlainSeedAsWeb_andReadsTheRecordedAnswer() = runBlocking {
        val chat = ChatServer(200 to RecordedLiveChat.text(RecordedLiveChat.FIRST))

        val batch = NewPipeLiveChat(chat).live(RecordedLiveChat.NEXT_LIVE_SEED).first() as LiveChatUpdate.Batch

        val request = chat.requests.single()
        assertEquals("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?prettyPrint=false", request.url())
        val body = request.dataToSend()!!.decodeToString()
        assertTrue(body, "\"continuation\":\"${RecordedLiveChat.NEXT_LIVE_SEED}\"" in body && "\"clientName\":\"WEB\"" in body)
        assertEquals(RecordedLiveChat.FIRST_ACTION_COUNT, batch.actions.size)
        assertTrue(batch.actions.first() is ChatAction.Pin)
    }

    @Test
    fun serverErrorAndRateLimit_readAsOffline() {
        assertEquals(AccessFailure.OFFLINE, failureFor(503 to "{}"))
        assertEquals(AccessFailure.OFFLINE, failureFor(429 to "{}"))
    }

    @Test
    fun clientError_readsAsUnreadable() {
        assertEquals(AccessFailure.UNREADABLE, failureFor(400 to """{"error":{"code":400,"status":"INVALID_ARGUMENT"}}"""))
    }

    /** KT-JSON-03: the parser recurses, so depth is checked before it runs; parsed, this answer
     *  would read as the chat's end. */
    @Test
    fun answerNestedTooDeep_readsAsUnreadable() {
        val deep = """{"responseContext":{},"x":${"[".repeat(64)}${"]".repeat(64)}}"""

        assertEquals(AccessFailure.UNREADABLE, failureFor(200 to deep))
    }

    /** Shallow and valid, it would read as the chat's end but for the size cap. */
    @Test
    fun answerOverTheSizeCap_readsAsUnreadable() {
        val large = """{"responseContext":{},"x":"${"a".repeat(4_000_000)}"}"""

        assertEquals(AccessFailure.UNREADABLE, failureFor(200 to large))
    }

    @Test
    fun answerThatIsntJson_readsAsUnreadable() {
        assertEquals(AccessFailure.UNREADABLE, failureFor(200 to "<html>not chat</html>"))
    }

    private fun failureFor(answer: Pair<Int, String>): AccessFailure = runBlocking {
        val failure = runCatching { NewPipeLiveChat(ChatServer(answer)).live("seed").first() }.exceptionOrNull()
        (failure as AccessException).failure
    }
}
