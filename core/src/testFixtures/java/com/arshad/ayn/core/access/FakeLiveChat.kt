package com.arshad.ayn.core.access

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/** A [LiveChat] answering each [live] call with [answer]'s flow (by default one that never
 *  answers) and each [replay] call with [page] (by default never answering), noting the live
 *  tokens it was started with in [tokens] and the replay requests in [replays]. */
class FakeLiveChat(
    private val page: suspend (token: String, offsetMs: Long) -> ReplayPage = { _, _ -> awaitCancellation() },
    private val answer: (token: String) -> Flow<LiveChatUpdate> = { flow { awaitCancellation() } },
) : LiveChat {
    val tokens: MutableList<String> = mutableListOf()
    val replays: MutableList<Pair<String, Long>> = mutableListOf()

    override fun live(token: String): Flow<LiveChatUpdate> = answer(token).onStart { tokens += token }

    override suspend fun replay(token: String, offsetMs: Long): ReplayPage {
        replays += token to offsetMs
        return page(token, offsetMs)
    }
}
