package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatAction
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatViews
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.LiveChatUpdate
import com.arshad.ayn.core.store.ChatPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** The turn's live chat or chat replay (LIVE-D6, D7, D14): what a chat panel or overlay shows. */
data class PlayerChat(
    /** Oldest first, the newest [TurnChat.MAX_LINES] at most. */
    val lines: List<ChatLine> = emptyList(),
    /** The channel's pinned line, above the list. */
    val pinned: ChatPinned? = null,
    /** The two views the chat offers; `null` until an answer names them (the switch hidden). */
    val views: ChatViews? = null,
    /** Live chat shows (every line), else Top chat, YouTube's default. */
    val liveView: Boolean = false,
    val status: ChatStatus = ChatStatus.Loading,
) {
    /** The token of the view shown, once an answer names the views. */
    internal val viewToken: String?
        get() = views?.let { if (liveView) it.live else it.top }
}

/** Where this stream's chat shows (LIVE-D21, D22, D26). */
data class ChatPlace(
    /** The stream's view: the profile's default until a pick in the player, which holds for this
     *  stream only. */
    val placement: ChatPlacement = ChatPlacement.Beside,
    /** Hide chat: off screen for this stream until Chat is pressed again. */
    val hidden: Boolean = false,
    /** Scroll chat: the Beside panel for now, until BACK puts chat back over the picture. */
    val scrolling: Boolean = false,
) {
    /** The view chat shows over the picture in, or `null`: Beside, hidden, or scrolled in Beside
     *  for now. Steps aside under the chrome or a sheet without leaving the screen (it polls on). */
    val overlay: ChatPlacement?
        get() = placement.takeUnless { it == ChatPlacement.Beside || hidden || scrolling }

    /** An unpicked stream's view moved to the profile's new default [placement]; Scroll chat ends
     *  when that is Beside (there is no picture to return to). */
    internal fun following(placement: ChatPlacement): ChatPlace =
        copy(placement = placement, scrolling = scrolling && placement != ChatPlacement.Beside)
}

/** A line, or the place of one YouTube's moderators [removed] (`chat-removed`: marked, so the eye
 *  doesn't jump); a replayed line carries the play position it was sent at ([atMs]). */
data class ChatLine(val message: ChatMessage, val removed: Boolean = false, val atMs: Long? = null) {
    /** Drawn as a paid card: a Super Chat, Super Sticker or membership not removed. */
    val paidCard: Boolean get() = message.isPaid && !removed
}

/** A Super Chat, Super Sticker or membership. */
val ChatMessage.isPaid: Boolean
    get() = kind == ChatKind.SUPER_CHAT || kind == ChatKind.SUPER_STICKER || kind == ChatKind.MEMBERSHIP

data class ChatPinned(val bannerId: String, val title: String, val message: ChatMessage)

sealed interface ChatStatus {
    /** Lines show: flowing, or kept under a reconnecting or ended row. */
    val listed: Boolean get() = this == Flowing || this == Reconnecting || this == Ended

    /** A first answer is on its way to an empty list: [Loading], or a replay's jump ([Finding]). */
    val loading: Boolean get() = this == Loading || this is Finding

    /** The first answer is on its way (opened, retried, or the view switched). */
    data object Loading : ChatStatus

    /** A replay's list emptied by a jump: the messages around [atMs] are on their way. */
    data class Finding(val atMs: Long) : ChatStatus

    /** Lines arrive. */
    data object Flowing : ChatStatus

    /** The connection dropped; the chat tries again by itself, the lines staying. */
    data object Reconnecting : ChatStatus

    /** The chat didn't load, or an answer couldn't be read: Try again ([TurnChat.retry]). */
    data class Failed(val failure: AccessFailure) : ChatStatus

    /** YouTube ended the live chat (the stream ended). */
    data object Ended : ChatStatus

    /** The page offers no chat: turned off, members only, or the profile can't see it. */
    data object Off : ChatStatus
}

/** A turn's chat as the player drives it: [TurnChat] live, [TurnReplay] on an ended stream. */
internal interface ChatTurn {
    /** The chat is off: no seed. */
    val off: Boolean

    /** Last told on screen by [shown]. */
    val isShown: Boolean

    /** The chat came on screen ([visible]) or left it, or the app stopped or started. */
    fun shown(visible: Boolean)

    /** Top chat or Live chat (LIVE-D14): the list starts again. */
    fun chooseView(live: Boolean)

    /** Try again on a failed chat. */
    fun retry()

    fun close()
}

/**
 * One turn's live chat from [seed] (a live page's; `null` = [ChatStatus.Off]). It polls only while
 * [shown] says the chat is on screen and the app started (LIVE-D6). A show starts the list again
 * from the view's newest lines: what YouTube removed or changed while it was hidden never reaches
 * a kept list. Each answer is one state update. Runs in a child scope of [parent]; [close] cancels
 * it with the turn.
 */
internal class TurnChat(
    private val seed: String?,
    private val chat: LiveChat,
    parent: CoroutineScope,
    private val publish: (PlayerChat) -> Unit,
) : ChatTurn {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private var state = PlayerChat(status = if (seed == null) ChatStatus.Off else ChatStatus.Loading)
    private var polling: Job? = null
    private var visible = false

    /** A chat that is off, ended or failed waits: off and ended for good, failed for [retry]. */
    private val pollable: Boolean
        get() = state.status != ChatStatus.Off && state.status != ChatStatus.Ended && state.status !is ChatStatus.Failed

    override val off: Boolean get() = seed == null

    override val isShown: Boolean get() = visible

    init {
        publish(state)
    }

    override fun shown(visible: Boolean) {
        this.visible = visible
        if (!visible) {
            polling?.cancel()
            polling = null
        } else if (polling == null && pollable) {
            restart(state.liveView)
        }
    }

    /** The list starts again from the view's newest line. */
    override fun chooseView(live: Boolean) {
        if (state.views == null || live == state.liveView || !pollable) return
        restart(live)
    }

    override fun retry() {
        if (state.status !is ChatStatus.Failed) return
        restart(state.liveView)
    }

    override fun close() {
        scope.cancel()
    }

    /** An empty list on [live]'s view, polled from its token while shown. */
    private fun restart(live: Boolean) {
        polling?.cancel()
        polling = null
        update(state.copy(lines = emptyList(), pinned = null, liveView = live, status = ChatStatus.Loading))
        if (visible) poll()
    }

    private fun poll() {
        val token = state.viewToken ?: seed ?: return
        polling = scope.launch {
            try {
                chat.live(token).collect(::receive)
                update(state.copy(status = ChatStatus.Ended))
            } catch (e: AccessException) {
                // A cancelled poll whose blocked request failed meanwhile publishes nothing.
                currentCoroutineContext().ensureActive()
                update(state.copy(status = ChatStatus.Failed(e.failure)))
            }
            polling = null
        }
    }

    private fun receive(answer: LiveChatUpdate) {
        when (answer) {
            LiveChatUpdate.Reconnecting -> update(state.copy(status = ChatStatus.Reconnecting))
            is LiveChatUpdate.Batch -> update(
                applied(state, answer.actions).copy(views = answer.views ?: state.views, status = ChatStatus.Flowing),
            )
        }
    }

    private fun update(next: PlayerChat) {
        state = next
        publish(next)
    }

    companion object {
        /** The panel keeps the newest ~300 (LIVE-D6): a busy chat sends 150–200 an answer. */
        const val MAX_LINES = 300
    }
}

/** A picture a chat line names (an author photo, a channel's emoji, a sticker), loaded only over
 *  https: the image loader also opens files and content URIs, and these come from YouTube's answer. */
fun chatPicture(url: String?): String? = url?.takeIf { it.startsWith("https://", ignoreCase = true) }

/** The count on the new-messages pill while a reader is up the list: "1 new message", "14 new messages". */
fun newChatLinesText(count: Int): String = if (count == 1) "1 new message" else "$count new messages"

/** Whether the player's Chat leads anywhere: until the page resolves ([dressing] `null`) the chat
 *  may yet come; then any seed counts, live or a kept replay (under Restricted Mode there is
 *  none, LIVE-D25). */
fun liveChatOffered(dressing: PlaybackDressing?): Boolean = dressing == null || dressing.liveChat != null

/** Where chat shows over the picture: [placement] (the stream's view, `null` for Beside) once
 *  [chat] has opened and isn't off. */
fun overPicture(placement: ChatPlacement?, chat: PlayerChat?): ChatPlacement? =
    placement?.takeIf { chat != null && chat.status != ChatStatus.Off }

/** The words under the newest line: the connection dropped, the chat ended, or a replay finds the
 *  chat at a new moment; over the picture also that it didn't load (a panel says so in a statement
 *  with Try again instead). */
fun chatTailText(status: ChatStatus): String? = when (status) {
    ChatStatus.Reconnecting -> "Reconnecting to chat"
    ChatStatus.Ended -> "Chat has ended"
    is ChatStatus.Failed -> "Chat didn’t load"
    is ChatStatus.Finding -> "Finding the chat at ${timeText(status.atMs)}"
    ChatStatus.Loading, ChatStatus.Flowing, ChatStatus.Off -> null
}

/** The ids a chat list showed last, so each update can tell its new lines (they rise in); the first
 *  batch arrives with the list itself. A cache read and written only by its list's `remember(lines)`;
 *  a list that empties to load afresh (any [ChatStatus.loading]) takes a new one, so its next lines
 *  arrive with it as the first did. */
class Arrivals {
    private var known: Set<String>? = null

    fun next(lines: List<ChatLine>): Set<String> {
        val ids = lines.mapTo(HashSet()) { it.message.id }
        val fresh = known?.let { ids - it }.orEmpty()
        known = ids
        return fresh
    }
}

/** [actions] applied in order to [chat]: a line YouTube sends twice isn't listed twice (a
 *  replacement naming a listed line's id takes the replaced line out); the oldest past
 *  [TurnChat.MAX_LINES] go. Lines added or replaced carry [atMs] (a replay's position). */
internal fun applied(chat: PlayerChat, actions: List<ChatAction>, atMs: Long? = null): PlayerChat {
    val lines = chat.lines.toMutableList()
    val ids = lines.mapTo(HashSet()) { it.message.id }
    var pinned = chat.pinned
    for (action in actions) {
        when (action) {
            is ChatAction.Add -> if (ids.add(action.message.id)) lines += ChatLine(action.message, atMs = atMs)
            is ChatAction.Remove -> lines.replaceAll { if (it.message.id == action.id) it.copy(removed = true) else it }
            is ChatAction.RemoveAuthor ->
                lines.replaceAll { if (it.message.authorChannelId == action.channelId) it.copy(removed = true) else it }
            is ChatAction.Replace -> {
                val at = lines.indexOfFirst { it.message.id == action.id }
                val newId = action.message.id
                when {
                    at < 0 -> Unit
                    newId != action.id && newId in ids -> lines.removeAt(at).also { ids.remove(action.id) }
                    else -> {
                        lines[at] = ChatLine(action.message, atMs = atMs ?: lines[at].atMs)
                        ids.remove(action.id)
                        ids.add(newId)
                    }
                }
            }
            is ChatAction.Pin -> pinned = ChatPinned(action.bannerId, action.title, action.message)
            is ChatAction.Unpin -> if (pinned?.bannerId == action.bannerId) pinned = null
        }
    }
    return chat.copy(lines = lines.takeLast(TurnChat.MAX_LINES), pinned = pinned)
}
