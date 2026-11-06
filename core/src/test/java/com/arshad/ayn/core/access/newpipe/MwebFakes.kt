package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.NChallengeSolver
import com.arshad.ayn.core.access.PoToken
import com.arshad.ayn.core.access.PoTokenSource
import com.arshad.ayn.core.access.VideoId

/** Mints [token] for every video, or nothing when it is `null` (a failed mint). */
internal class FixedWebPoTokenSource(private val token: PoToken?) : PoTokenSource {
    override fun webClientPoToken(videoId: VideoId): PoToken? = token
    override fun webEmbedClientPoToken(videoId: VideoId): PoToken? = null
    override fun androidClientPoToken(videoId: VideoId): PoToken? = null
    override fun iosClientPoToken(videoId: VideoId): PoToken? = null
}

/** The placeholder token the MWEB flow was recorded with, as a mint returns it. */
internal val PLACEHOLDER_WEB_PO_TOKEN = PoToken(
    visitorData = "CgtwbGFjZWhvbGRlcg%3D%3D",
    playerRequestPoToken = MWEB_PLACEHOLDER_PO_TOKEN,
    streamingDataPoToken = MWEB_PLACEHOLDER_PO_TOKEN,
)

/** Answers every challenge with [answer], or throws [solveFailure] / [prepareFailure]; counts its
 *  prepares and remembers the challenges. */
internal class FixedNChallengeSolver(
    private val answer: String = SOLVED_N,
    private val solveFailure: Exception? = null,
    private val prepareFailure: Exception? = null,
) : NChallengeSolver {
    @Volatile
    var prepares = 0
        private set
    val challenges: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun prepare(playerCode: String) {
        prepares++
        prepareFailure?.let { throw it }
    }

    override fun solve(playerCode: String, challenge: String): String {
        challenges += challenge
        solveFailure?.let { throw it }
        return answer
    }

    companion object {
        const val SOLVED_N = "solvedNvalue0"
    }
}
