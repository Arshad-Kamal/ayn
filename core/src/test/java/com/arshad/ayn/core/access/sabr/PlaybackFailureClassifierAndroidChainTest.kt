package com.arshad.ayn.core.access.sabr

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.arshad.ayn.core.access.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * The classifier on the chain a phone with no network delivered: the
 * SABR data source's wrapper around `UnknownHostException`, whose cause is the platform's
 * `android.system.GaiException`, a hidden `RuntimeException`. Robolectric supplies the real
 * class; it is not in the compile SDK, so it is built reflectively.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFailureClassifierAndroidChainTest {

    @Test
    fun lostNetworkClassifiesAsRecoverable() {
        val gai = Class.forName("android.system.GaiException")
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("android_getaddrinfo", EAI_NODATA) as Throwable
        assertTrue(gai is RuntimeException)
        val dns = UnknownHostException("Unable to resolve host").apply { initCause(gai) }
        val error = sabrSourceError(dns)

        assertEquals(PlaybackFailure.Recoverable("IOException"), classifyPlaybackFailure(error))
    }

    private companion object {
        const val EAI_NODATA = 7
    }
}
