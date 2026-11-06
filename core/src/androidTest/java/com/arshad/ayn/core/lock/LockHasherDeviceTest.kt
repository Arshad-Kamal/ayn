package com.arshad.ayn.core.lock

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PBKDF2-HMAC-SHA256 from the device's own security provider (M5-D10/D15): the JVM tests run
 * the JDK's. Run with `./gradlew :core:connectedDebugAndroidTest`; the timing line in logcat
 * (tag `KE-lock`) is the device pass's iteration measurement (M5.10).
 */
@RunWith(AndroidJUnit4::class)
class LockHasherDeviceTest {

    @Test
    fun thePlatformProviderGivesTheRfc7914Vector() {
        // RFC 7914 § 11: P = "passwd", S = "salt", c = 1, dkLen = 64.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            pbkdf2("passwd".toCharArray(), "salt".encodeToByteArray(), 1, 64).toHexString(),
        )
    }

    @Test
    fun aLockHashedOnTheDeviceOpensWithItsSecret(): Unit = runBlocking {
        val hasher = LockHasher()
        val stored = hasher.hash(LockSecret.Pin("4071"))

        val compare = measureTime { assertTrue(hasher.matches(LockSecret.Pin("4071"), stored)) }

        Log.i("KE-lock", "PBKDF2 compare at ${stored.iterations} iterations: ${compare.inWholeMilliseconds} ms")
    }
}
