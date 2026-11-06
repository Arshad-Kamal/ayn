package com.arshad.ayn.core.access

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A page's chat as its `next` answer offers it (LIVE-D6): [token] opens Top chat, YouTube's
 * default (LIVE-D14); [replay] marks an ended stream's kept chat.
 */
data class LiveChatSeed(val token: String, val replay: Boolean)

/**
 * Read-only live chat (LIVE-D6). The caller collects [live] only while the chat shows and the app
 * is started: collecting is what polls YouTube.
 */
interface LiveChat {
    /**
     * Polls a live chat from [token] (a live [LiveChatSeed.token], never a replay's, or one of
     * [ChatViews]) until the chat ends, which completes the flow. Each answer is one
     * [LiveChatUpdate.Batch], in YouTube's order, a message YouTube sends again dropped. A dropped
     * connection after the first answer emits [LiveChatUpdate.Reconnecting] and retries the last
     * good token, waiting as [ChatRetryWait] says; a first request that fails, or an answer that
     * can't be read, fails the flow with an [AccessException].
     */
    fun live(token: String): Flow<LiveChatUpdate>

    /**
     * One page of an ended stream's kept chat (LIVE-D7): from a replay [LiveChatSeed.token] or one
     * of its [ChatViews], a page's [ReplayPage.next] for the page after it, or its [ReplayPage.seek]
     * for the messages around another moment; [offsetMs] is the play position the page is asked
     * at (with [ReplayPage.next], how far the pages held reach). Throws an [AccessException]:
     * [AccessFailure.OFFLINE] for no connection, a 5xx or a 429, anything else for an answer that
     * isn't one.
     */
    suspend fun replay(token: String, offsetMs: Long): ReplayPage
}

/**
 * A replay page: its [items] in offset order, the [next] page's token and the [seek] token for a
 * jump (`null` when the answer names none), the [views] when its header names them, and [untilMs],
 * the last offset the answer named, drawn or not. No [next] is the replay's end; a page may have no
 * items yet a next (every line not drawn, or filtered out), its [untilMs] saying how far it reached.
 */
data class ReplayPage(
    val items: List<ReplayItem>,
    val next: String?,
    val seek: String?,
    val views: ChatViews?,
    val untilMs: Long? = null,
) {
    companion object {
        /** YouTube has no more: an answer without chat, or a profile that may not see it. */
        val END: ReplayPage = ReplayPage(emptyList(), next = null, seek = null, views = null)
    }
}

/** A replayed [action] and the play position it belongs at. */
data class ReplayItem(val offsetMs: Long, val action: ChatAction)

sealed interface LiveChatUpdate {
    /** One answer's actions, to apply in order; [views] when the answer names the two views. */
    data class Batch(val actions: List<ChatAction>, val views: ChatViews?) : LiveChatUpdate

    data object Reconnecting : LiveChatUpdate
}

/** The chat's two views, as a chat answer's own header names them (LIVE-D14; the watch page's
 *  tokens answer 400). */
data class ChatViews(val top: String, val live: String)

/** What one chat answer does to the list, applied in order. */
sealed interface ChatAction {
    data class Add(val message: ChatMessage) : ChatAction

    /** A moderator removed or retracted the message, or replaced it with a placeholder. */
    data class Remove(val id: String) : ChatAction

    /** Every message of the author (a ban or timeout). */
    data class RemoveAuthor(val channelId: String) : ChatAction

    /** The message under [id] is now [message] (YouTube's edit of a held or reworded line). */
    data class Replace(val id: String, val message: ChatMessage) : ChatAction

    /** The channel pinned [message] above the list; [title] is YouTube's "Pinned by …". */
    data class Pin(val bannerId: String, val title: String, val message: ChatMessage) : ChatAction

    data class Unpin(val bannerId: String) : ChatAction
}

/**
 * One chat line as the panel draws it (LIVE-D16).
 *
 * @property author the name as YouTube shows it; `null` for a [ChatKind.SYSTEM] line.
 * @property authorChannelId the author's channel id, the key of [ChatAction.RemoveAuthor].
 * @property authorPhotoUrl the largest author photo YouTube sends (64 px).
 * @property runs the message: text and emoji in order; empty when the line has no body.
 * @property headline what a membership or gift line says above or instead of a body ("New member",
 *   "Welcome to Crew!", "Member for 6 months", "sent Star").
 * @property amount a Super Chat's or Super Sticker's amount as YouTube writes it ("$2.00").
 * @property tierColor the amount's tier colour (ARGB), for its pill.
 * @property stickerUrl a Super Sticker's picture.
 */
data class ChatMessage(
    val id: String,
    val kind: ChatKind,
    val author: String?,
    val authorChannelId: String?,
    val authorPhotoUrl: String?,
    val badges: Set<ChatBadge>,
    val runs: List<ChatRun>,
    val headline: String? = null,
    val amount: String? = null,
    val tierColor: Int? = null,
    val stickerUrl: String? = null,
)

enum class ChatKind { TEXT, SUPER_CHAT, SUPER_STICKER, MEMBERSHIP, GIFT, SYSTEM }

enum class ChatBadge { OWNER, MODERATOR, VERIFIED, MEMBER }

sealed interface ChatRun {
    /** Text, Unicode emoji included. */
    data class Text(val text: String) : ChatRun

    /** A channel's own emoji: its picture, [label] (its shortcut) where none shows. */
    data class CustomEmoji(val label: String, val imageUrl: String) : ChatRun
}

/** One chat answer as read: its actions, the next request, and the views its header names. */
internal data class LiveChatReply(
    val actions: List<ChatAction>,
    val next: ChatContinuation?,
    val views: ChatViews?,
)

/** The next token and how long YouTube asks to wait for it; `null` when it names no wait. */
internal data class ChatContinuation(val token: String, val timeoutMs: Long?)

/**
 * The poll loop behind [LiveChat.live], on [fetch]'s answers. [fetch] throws an [AccessException]:
 * [AccessFailure.OFFLINE] retries after the first answer, anything else ends the flow.
 */
internal fun liveChatUpdates(
    start: String,
    fetch: suspend (token: String) -> LiveChatReply,
): Flow<LiveChatUpdate> = flow {
    var token = start
    var answered = false
    val retryWait = ChatRetryWait()
    val seen = SeenIds(SEEN_IDS)
    while (true) {
        val reply = try {
            fetch(token)
        } catch (e: AccessException) {
            if (!answered || e.failure != AccessFailure.OFFLINE) throw e
            emit(LiveChatUpdate.Reconnecting)
            delay(retryWait.next())
            continue
        }
        answered = true
        retryWait.reset()
        val actions = reply.actions.filter { it !is ChatAction.Add || seen.add(it.message.id) }
        emit(LiveChatUpdate.Batch(actions, reply.views))
        val next = reply.next ?: return@flow
        token = next.token
        delay((next.timeoutMs ?: 0L).milliseconds.coerceIn(POLL_MIN, POLL_MAX))
    }
}

/** LIVE-D6/D7: how long a chat (live or replay) waits before asking again after a dropped
 *  connection: 5 s, doubling each time to at most 60 s, until [reset]. One instance per caller
 *  (not thread-safe). */
class ChatRetryWait {
    private var wait = FIRST

    fun next(): Duration = wait.also { wait = (wait * 2).coerceAtMost(MAX) }

    fun reset() {
        wait = FIRST
    }

    private companion object {
        val FIRST: Duration = 5.seconds
        val MAX: Duration = 60.seconds
    }
}

/** The last [capacity] ids seen; [add] is false for one already among them. */
private class SeenIds(private val capacity: Int) {
    private val ids = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/** Under 1 s hammers YouTube; over 8 s a busy chat outruns one answer (~150–200 messages). */
private val POLL_MIN: Duration = 1.seconds
private val POLL_MAX: Duration = 8.seconds

/** Several answers' worth: a resent message is a recent one. */
private const val SEEN_IDS = 2_000
