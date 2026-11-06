package com.arshad.ayn.core.access.potoken

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.arshad.ayn.core.access.VideoId

/**
 * R2 smoke check: WebView BotGuard minting on a real Android runtime (device/emulator).
 * Opt-in only: `KE_SMOKE=1 ./gradlew :core:potoken:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class WebViewPoTokenSourceTest {

    @Test
    fun mintsWebClientPoToken() {
        assumeTrue(
            "set KE_SMOKE=1 to run network smoke tests",
            InstrumentationRegistry.getArguments().containsKey("KE_SMOKE"),
        )

        val source = WebViewPoTokenSource(ApplicationProvider.getApplicationContext())
        val token = source.webClientPoToken(VideoId(FIXED_VIDEO_ID))

        assertNotNull("expected a minted web po-token", token)
        assertTrue("player token should not be blank", token!!.playerRequestPoToken.isNotBlank())
        assertTrue("streaming token should not be blank", token.streamingDataPoToken.isNotBlank())
        assertTrue(
            "cached streaming token should match",
            source.cachedStreamingPoToken()!!.isNotEmpty(),
        )
    }

    private companion object {
        const val FIXED_VIDEO_ID = "dQw4w9WgXcQ"
    }
}
