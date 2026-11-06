package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.access.ReplayPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The session profile's filter on the live chat and its replay, read once when the chat starts and
 * once per replay page. Its blocked keywords (LIVE-D17): a line whose words they match never shows;
 * one replaced by such words is removed; a pin of such a line unpins the one before it (YouTube
 * shows one pin; a replay page knows only its own pins). Under Restricted Mode (LIVE-D25) the chat
 * ends without a request, as comments are off per call: a page resolved under it carries no chat
 * ([FilteringExtractor.video]), and this covers a profile that turned it on since.
 */
class FilteringLiveChat(
    private val delegate: LiveChat,
    private val currentFilter: suspend () -> ContentFilter,
) : LiveChat {
    override fun live(token: String): Flow<LiveChatUpdate> = flow {
        val filter = currentFilter()
        if (!filter.allowsComments) return@flow
        val lines = LineFilter(filter)
        delegate.live(token).collect { update ->
            if (update !is LiveChatUpdate.Batch) return@collect emit(update)
            emit(update.copy(actions = update.actions.mapNotNull(lines::pass)))
        }
    }

    /** Under Restricted Mode the replay's end without a request; a page whose every line is
     *  blocked keeps its tokens, so the replay goes on. */
    override suspend fun replay(token: String, offsetMs: Long): ReplayPage {
        val filter = currentFilter()
        if (!filter.allowsComments) return ReplayPage.END
        val page = delegate.replay(token, offsetMs)
        val lines = LineFilter(filter)
        return page.copy(items = page.items.mapNotNull { item -> lines.pass(item.action)?.let { item.copy(action = it) } })
    }

    /** One chat's actions through [filter] in order, remembering the pin it let through. */
    private class LineFilter(private val filter: ContentFilter) {
        private var pinned: String? = null

        fun pass(action: ChatAction): ChatAction? = when (action) {
            is ChatAction.Add if !allows(action.message) -> null
            is ChatAction.Replace if !allows(action.message) -> ChatAction.Remove(action.id)
            is ChatAction.Pin if !allows(action.message) -> pinned?.let(ChatAction::Unpin).also { pinned = null }
            is ChatAction.Pin -> action.also { pinned = it.bannerId }
            is ChatAction.Add, is ChatAction.Replace, is ChatAction.Remove, is ChatAction.RemoveAuthor, is ChatAction.Unpin -> action
        }

        /** The words a line shows (its text, its headline), checked as a title is. */
        private fun allows(message: ChatMessage): Boolean {
            val words = buildString {
                message.runs.forEach { run ->
                    when (run) {
                        is ChatRun.Text -> append(run.text)
                        // A picture between two words keeps them apart.
                        is ChatRun.CustomEmoji -> append(' ')
                    }
                }
                message.headline?.let { append(' ').append(it) }
            }
            return words.isBlank() || filter.allowsTitle(words)
        }
    }
}
