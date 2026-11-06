package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The profile's filter on the live chat and its replay: blocked keywords (LIVE-D17), Restricted
 *  Mode (LIVE-D25). */
class FilteringLiveChatTest {

    @Test
    fun blockedLine_neverShows_anAllowedOneDoes() = runTest {
        assertEquals(listOf(add("a", "a calm view")), filtered(add("a", "a calm view"), add("b", "what a PRANK")))
    }

    @Test
    fun lineReplacedByBlockedWords_isRemoved_anAllowedReplacementPasses() = runTest {
        val blocked = ChatAction.Replace("a", line("a", "prank time"))
        val allowed = ChatAction.Replace("b", line("b", "calm time"))

        assertEquals(listOf(ChatAction.Remove("a"), allowed), filtered(blocked, allowed))
    }

    /** YouTube shows one pin: a blocked one replaces the last, so that one unpins. */
    @Test
    fun blockedPin_unpinsTheOneBefore_anAllowedPinPasses() = runTest {
        val allowed = ChatAction.Pin("p1", "Pinned by NASA", line("c", "calm"))
        val blocked = ChatAction.Pin("p2", "Pinned by NASA", line("d", "prank"))

        assertEquals(listOf(allowed, ChatAction.Unpin("p1")), filtered(allowed, blocked))
    }

    @Test
    fun moderationActions_passAsTheyCome() = runTest {
        val moderation = listOf(ChatAction.Remove("d"), ChatAction.RemoveAuthor("UCa"), ChatAction.Unpin("p"))

        assertEquals(moderation, filtered(*moderation.toTypedArray()))
    }

    /** A gift's words are its headline. */
    @Test
    fun headlineIsChecked() = runTest {
        assertEquals(emptyList<ChatAction>(), filtered(ChatAction.Add(line("g", null).copy(kind = ChatKind.GIFT, headline = "sent prank"))))
    }

    @Test
    fun customEmojiBetweenTwoWords_keepsThemApart() = runTest {
        val split = line("s", null).copy(runs = listOf(ChatRun.Text("calm"), ChatRun.CustomEmoji(":x:", "https://x"), ChatRun.Text("prank")))

        assertEquals(emptyList<ChatAction>(), filtered(ChatAction.Add(split)))
    }

    @Test
    fun reconnecting_passes() = runTest {
        val chat = FilteringLiveChat(FakeLiveChat { flowOf(LiveChatUpdate.Reconnecting) }) { filterOf(Keyword("prank")) }

        assertEquals(listOf(LiveChatUpdate.Reconnecting), chat.live("t").toList())
    }

    /** A profile that turned Restricted Mode on since the page resolved: no request, the chat ends. */
    @Test
    fun underRestrictedMode_theChatEndsWithoutAsking() = runTest {
        val source = FakeLiveChat { flowOf(LiveChatUpdate.Batch(listOf(add("a", "calm")), null)) }

        val updates = FilteringLiveChat(source) { strictFilter() }.live("t").toList()

        assertEquals(emptyList<LiveChatUpdate>() to emptyList<String>(), updates to source.tokens)
    }

    @Test
    fun replayPage_blockedLineNeverShows_theRestKeepTheirOffsetsAndTokens() = runTest {
        val page = ReplayPage(listOf(ReplayItem(1_000, add("a", "calm")), ReplayItem(2_000, add("b", "prank"))), "next", "seek", null)
        val source = FakeLiveChat(page = { _, _ -> page })

        val filtered = FilteringLiveChat(source) { filterOf(Keyword("prank")) }.replay("t", 500)

        assertEquals(page.copy(items = page.items.take(1)) to listOf("t" to 500L), filtered to source.replays)
    }

    @Test
    fun replayUnderRestrictedMode_isTheEnd_withoutAsking() = runTest {
        val source = FakeLiveChat(page = { _, _ -> error("asked") })

        val page = FilteringLiveChat(source) { strictFilter() }.replay("t", 0)

        assertEquals(ReplayPage.END to emptyList<Pair<String, Long>>(), page to source.replays)
    }

    /** Not the end: YouTube named a next page. */
    @Test
    fun replayPageWhollyBlocked_keepsItsTokens() = runTest {
        val page = ReplayPage(listOf(ReplayItem(1_000, add("b", "prank"))), "next", "seek", null, untilMs = 1_000)

        val filtered = FilteringLiveChat(FakeLiveChat(page = { _, _ -> page })) { filterOf(Keyword("prank")) }.replay("t", 0)

        assertEquals(page.copy(items = emptyList()), filtered)
    }

    private suspend fun filtered(vararg actions: ChatAction): List<ChatAction> {
        val chat = FilteringLiveChat(FakeLiveChat { flowOf(LiveChatUpdate.Batch(actions.toList(), null)) }) { filterOf(Keyword("prank")) }
        return (chat.live("t").toList().single() as LiveChatUpdate.Batch).actions
    }

    private fun add(id: String, text: String): ChatAction = ChatAction.Add(line(id, text))

    private fun line(id: String, text: String?): ChatMessage =
        ChatMessage(id, ChatKind.TEXT, "author", null, null, emptySet(), listOfNotNull(text?.let(ChatRun::Text)))

    private fun strictFilter(): ContentFilter = ContentFilter.of(
        ProfileSettings(restrictedMode = RestrictedMode.Strict),
        ProfileRules(),
    )
}
