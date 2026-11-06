package com.arshad.ayn.core.access.potoken

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped EJS solver on a real player: YouTube's `cbcfbf2f` base.js as recorded in fixture
 * `video-made-for-kids-mweb`, whose `n` answers node gave (yt-dlp EJS 0.8.0 on the host; spec 12
 * ledger "yt-dlp EJS solver"). Needs a WebView, no network. Skips where the player isn't recorded
 * (YouTube's code stays out of the public source tree).
 */
@RunWith(AndroidJUnit4::class)
class EjsNChallengeSolverTest {

    @Test
    fun solvesNAsNodeDoes() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        // Any recorded player counts: a re-record under another hash then fails on the open below
        // (the node answers belong to cbcfbf2f) instead of skipping.
        assumeTrue("no recorded player code", assets.list("").orEmpty().any { it.endsWith("base.js.body") })
        val player = assets.open(PLAYER_ASSET).use { it.readBytes().decodeToString() }
        val solver = EjsNChallengeSolver(ApplicationProvider.getApplicationContext())
        try {
            NODE_ANSWERS.forEach { (challenge, answer) ->
                assertEquals(challenge, answer, solver.solve(player, challenge))
            }
        } finally {
            solver.close()
        }
    }

    private companion object {
        const val PLAYER_ASSET = "www.youtube.com_s_player_cbcfbf2f_player_ias.vflset_en_GB_base.js.body"
        val NODE_ANSWERS = mapOf(
            "BS_z_SpyMI4DoEvuhn2" to "T8lSpKFFE9YJ0A",
            "fqx2gK4ALUemOlykm62" to "sQrs6C6wgVLDqQ",
            "-KAdY2oThUgstR9xKyo" to "dLCEC_xzdXrO5g",
        )
    }
}
