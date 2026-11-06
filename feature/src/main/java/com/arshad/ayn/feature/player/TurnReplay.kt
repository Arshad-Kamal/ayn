package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatRetryWait
import com.arshad.ayn.core.access.LiveChat
import com.arshad.ayn.core.access.ReplayItem
import com.arshad.ayn.core.access.ReplayPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * One turn's chat replay from [seed], an ended stream's kept chat (LIVE-D7), following the play
 * position the screen hands to [tick]. A line shows once the position reaches the moment it was
 * sent. While shown and playing it asks for the next page when the pages held end within
 * [AHEAD_MS] of the position; paused it asks for none. A jump backwards, or forward past the pages
 * held, empties the list and asks for the messages around the new position with the last page's
 * seek token ([ChatStatus.Finding]). A page without a next token is the end: nothing more is
 * asked until a jump back. Each show starts again at the next tick's position, as a live chat starts again
 * from its newest line. A dropped connection after the turn's first page retries the same request,
 * waiting as [ChatRetryWait] says; any other failure waits for [retry].
 */
internal class TurnReplay(
    private val seed: String,
    private val chat: LiveChat,
    parent: CoroutineScope,
    private val publish: (PlayerChat) -> Unit,
) : ChatTurn {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private var state = PlayerChat()
    private var visible = false

    /** The next tick starts the list again at its position (a show, a view switched, Try again). */
    private var restart = true
    private var fetch: Job? = null

    /** A page has answered in this turn: a dropped connection retries from then on. */
    private var answered = false

    /** Messages held but not yet reached, in offset order. */
    private val ahead = ArrayDeque<ReplayItem>()

    /** How far the pages held reach: the last offset a page named (drawn or not), or the position a
     *  seek asked at; [Long.MAX_VALUE] once the last page is held. */
    private var heldUntilMs = 0L
    private var positionMs = 0L
    private var nextToken: String? = null
    private var seekToken: String? = null

    override val off: Boolean get() = false

    override val isShown: Boolean get() = visible

    init {
        publish(state)
    }

    override fun shown(visible: Boolean) {
        this.visible = visible
        if (!visible) {
            fetch?.cancel()
            restart = true
        }
    }

    override fun chooseView(live: Boolean) {
        if (state.views == null || live == state.liveView || state.status is ChatStatus.Failed) return
        // A page of the old view still on its way would bring back that view's seek token.
        fetch?.cancel()
        update(state.copy(liveView = live))
        seekToken = null
        restart = true
    }

    /** Asks the view's own token, not a seek token that may be what failed. */
    override fun retry() {
        if (state.status !is ChatStatus.Failed) return
        seekToken = null
        restart = true
        update(state.copy(status = ChatStatus.Loading))
    }

    override fun close() {
        scope.cancel()
    }

    /** The screen's position tick while the replay is open: [playing] when the picture moves. */
    fun tick(positionMs: Long, playing: Boolean) {
        if (!visible || state.status is ChatStatus.Failed) return
        val jumpedBack = positionMs < this.positionMs - BACK_SLACK_MS
        // Subtracted, as heldUntilMs may be Long.MAX_VALUE.
        val pastHeld = positionMs - AHEAD_MS > heldUntilMs
        this.positionMs = positionMs
        when {
            restart -> reseed(ChatStatus.Loading)
            jumpedBack || pastHeld -> reseed(ChatStatus.Finding(positionMs))
            else -> {
                reveal()
                val token = nextToken
                if (playing && token != null && fetch?.isActive != true && heldUntilMs < positionMs + AHEAD_MS) {
                    ask(token, heldUntilMs)
                }
            }
        }
    }

    /** An empty list at the position, asked for with the last seek token, else the view's own. */
    private fun reseed(status: ChatStatus) {
        restart = false
        fetch?.cancel()
        ahead.clear()
        nextToken = null
        heldUntilMs = positionMs
        update(state.copy(lines = emptyList(), pinned = null, status = status))
        ask(seekToken ?: state.viewToken ?: seed, positionMs)
    }

    private fun ask(token: String, offsetMs: Long) {
        fetch = scope.launch {
            val retryWait = ChatRetryWait()
            while (true) {
                try {
                    return@launch receive(chat.replay(token, offsetMs))
                } catch (e: AccessException) {
                    // A cancelled ask whose blocked request failed meanwhile publishes nothing.
                    currentCoroutineContext().ensureActive()
                    if (!answered || e.failure != AccessFailure.OFFLINE) {
                        return@launch update(state.copy(status = ChatStatus.Failed(e.failure)))
                    }
                    update(state.copy(status = ChatStatus.Reconnecting))
                    delay(retryWait.next())
                }
            }
        }
    }

    private fun receive(page: ReplayPage) {
        answered = true
        seekToken = page.seek ?: seekToken
        nextToken = page.next
        ahead.addAll(page.items)
        // The last page holds everything to the end: no jump forward finds more.
        heldUntilMs = if (nextToken == null) Long.MAX_VALUE else maxOf(heldUntilMs, page.untilMs ?: heldUntilMs)
        update(state.copy(views = page.views ?: state.views, status = ChatStatus.Flowing))
        reveal()
    }

    /** Every held message the position has reached, in one update. */
    private fun reveal() {
        var shown = state
        while (ahead.firstOrNull()?.let { it.offsetMs <= positionMs } == true) {
            val item = ahead.removeFirst()
            shown = applied(shown, listOf(item.action), atMs = item.offsetMs)
        }
        if (shown !== state) update(shown)
    }

    private fun update(shown: PlayerChat) {
        state = shown
        publish(shown)
    }

    companion object {
        /** LIVE-D7: pages are asked for this far ahead of the play position. */
        const val AHEAD_MS = 60_000L

        /** A position this far behind the last is a jump back, not a tick's jitter. */
        const val BACK_SLACK_MS = 1_000L
    }
}
