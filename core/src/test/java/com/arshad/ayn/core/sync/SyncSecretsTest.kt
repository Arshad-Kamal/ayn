package com.arshad.ayn.core.sync

import android.content.Context
import java.nio.ByteBuffer
import java.security.KeyStoreException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [SyncSecrets] in real preferences over a fake [SecretBox] (the Keystore box runs on a device:
 *  `KeystoreSecretBoxDeviceTest`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSecretsTest {
    private val box = FakeBox()

    @Test
    fun theKeyAndTheCredentialReadBackAsSaved() = runTest {
        val secrets = secrets(StandardTestDispatcher(testScheduler))
        val key = SyncKey(ByteArray(32) { it.toByte() }, ByteArray(16) { (it + 100).toByte() }, SYNC_KEY_ITERATIONS)

        assertTrue(secrets.saveKey(key))
        assertTrue(secrets.saveCredential("app-password".encodeToByteArray()))

        val read = checkNotNull(secrets(StandardTestDispatcher(testScheduler)).key())
        assertArrayEquals(key.keyBytes, read.keyBytes)
        assertArrayEquals(key.salt, read.salt)
        assertEquals(key.iterations, read.iterations)
        assertArrayEquals("app-password".encodeToByteArray(), secrets.credential())
        assertEquals(2, box.sealed)
    }

    /** The Keystore key gone (Android cleared it) or a stored value altered: link again. */
    @Test
    fun whatTheBoxCantOpenReadsAsAbsent() = runTest {
        val secrets = secrets(StandardTestDispatcher(testScheduler))
        secrets.saveKey(SyncKey(ByteArray(32), ByteArray(16), SYNC_KEY_ITERATIONS))
        secrets.saveCredential(byteArrayOf(1))

        box.lost = true

        assertNull(secrets.key())
        assertNull(secrets.credential())
    }

    @Test
    fun clearingForgetsBoth() = runTest {
        val secrets = secrets(StandardTestDispatcher(testScheduler))
        secrets.saveKey(SyncKey(ByteArray(32), ByteArray(16), SYNC_KEY_ITERATIONS))
        secrets.saveCredential(byteArrayOf(1))

        assertTrue(secrets.clear())

        assertNull(secrets.key())
        assertNull(secrets.credential())
        assertTrue("a broken Keystore key mustn't outlive an unlink", box.forgot)
    }

    /** A stored key that doesn't read as one (not Base64, cut short, a count no key has) is absent,
     *  never an exception. */
    @Test
    fun aStoredKeyThatDoesntReadAsOneIsAbsent() = runTest {
        val secrets = secrets(StandardTestDispatcher(testScheduler))
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("sync", Context.MODE_PRIVATE)
        val full = ByteBuffer.allocate(52).putInt(SYNC_KEY_ITERATIONS).put(ByteArray(48)).array()
        val outOfRange = ByteBuffer.allocate(52).putInt(5).put(ByteArray(48)).array()

        listOf("not base64 !", Base64.encode(box.seal(full.copyOf(51))), Base64.encode(box.seal(outOfRange))).forEach { value ->
            stored.edit().putString("key", value).commit()

            assertNull(value, secrets.key())
        }
        stored.edit().putString("key", Base64.encode(box.seal(full))).commit()
        assertEquals(SYNC_KEY_ITERATIONS, secrets.key()?.iterations)
    }

    private fun secrets(io: CoroutineDispatcher): SyncSecrets = SyncSecrets(RuntimeEnvironment.getApplication(), box, io)
}

/** Seals by reversing behind a marker; [lost] makes it open nothing, as a box whose key is gone.
 *  Shared with [SyncRunnerTest]. */
internal class FakeBox : SecretBox {
    var lost = false
    var sealFails = false
    var sealed = 0
    var forgot = false

    override fun forget(): Boolean {
        forgot = true
        return true
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        if (sealFails) throw KeyStoreException("no Keystore key")
        return (MARKER + plaintext.reversedArray()).also { sealed++ }
    }

    override fun open(sealed: ByteArray): ByteArray? =
        sealed.takeIf { !lost && it.size >= MARKER.size && it.copyOf(MARKER.size).contentEquals(MARKER) }
            ?.copyOfRange(MARKER.size, sealed.size)?.reversedArray()
}

private val MARKER = "box:".encodeToByteArray()
