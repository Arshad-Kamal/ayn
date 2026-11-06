package com.arshad.ayn.core.sync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sync secrets and envelope on the device's own providers (M8-D7, M8-D15; the Shield's Keystore
 * is spec 20's open risk). Run with `./gradlew :core:connectedDebugAndroidTest`; the timing line in
 * logcat (tag `Ayn-sync`) is the 600 000-iteration derive on that device.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxDeviceTest {
    private val box = KeystoreSecretBox(ALIAS)
    private val other = KeystoreSecretBox(OTHER_ALIAS)

    @After
    fun deleteKeys() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            deleteEntry(ALIAS)
            deleteEntry(OTHER_ALIAS)
        }
    }

    @Test
    fun aSealedSecretOpensWithItsKey() {
        val sealed = box.seal("app-password".encodeToByteArray())

        assertArrayEquals("app-password".encodeToByteArray(), box.open(sealed))
    }

    @Test
    fun anotherKeyOpensNothing() {
        other.seal(byteArrayOf(0))
        val sealed = box.seal("app-password".encodeToByteArray())

        assertNull(other.open(sealed))
    }

    @Test
    fun anAlteredSecretOpensNothing() {
        val sealed = box.seal(byteArrayOf(1, 2, 3))
        val altered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertNull(box.open(altered))
    }

    @Test
    fun aForgottenKeyOpensNothing() {
        val sealed = box.seal(byteArrayOf(1, 2, 3))

        assertTrue(box.forget())

        assertNull(box.open(sealed))
    }

    @Test
    fun aSealAfterForgettingOpens() {
        box.seal(byteArrayOf(1, 2, 3))
        box.forget()

        val sealed = box.seal(byteArrayOf(4))

        assertArrayEquals(byteArrayOf(4), box.open(sealed))
    }

    /** The JVM tests' golden file (`src/test/resources/sync/envelope-v1.bin`, from Python's
     *  `cryptography`) opens with the device's PBKDF2 and AES-GCM: every device derives one key. */
    @Test
    fun theGoldenEnvelopeOpensWithTheDevicesProviders() {
        val golden = GOLDEN_ENVELOPE_HEX.hexToByteArray()
        val (key, derive) = measureTimedValue {
            runBlocking { deriveSyncKey("correct horse battery staple".toCharArray(), ByteArray(16) { it.toByte() }) }
        }
        Log.i("Ayn-sync", "PBKDF2 derive at $SYNC_KEY_ITERATIONS iterations: ${derive.inWholeMilliseconds} ms")

        assertArrayEquals("{\"hello\":\"Ayn\"}".encodeToByteArray(), (openEnvelope(key, golden) as EnvelopeRead.Opened).plaintext)
    }
}

private const val ALIAS = "ayn-sync-device-test"
private const val OTHER_ALIAS = "ayn-sync-device-test-other"
private const val GOLDEN_ENVELOPE_HEX =
    "41594e5301000927c0000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a7a8a9aaab" +
        "d04cdbe51759aaca0d0853cb6ab770d2b6775bdd7d8d6407f2954c635b75f3"
