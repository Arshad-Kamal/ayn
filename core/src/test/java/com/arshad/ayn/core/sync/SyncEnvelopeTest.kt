package com.arshad.ayn.core.sync

import com.arshad.ayn.core.backup.MAX_BACKUP_BYTES
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync envelope (M8-D7) against `/sync/envelope-v1.bin`, written by Python's `cryptography`
 * (an independent AES-GCM and PBKDF2): passphrase `correct horse battery staple`, salt bytes 0–15,
 * 600 000 iterations, nonce bytes A0–AB, plaintext `{"hello":"Ayn"}`, the header as associated data.
 */
class SyncEnvelopeTest {
    private val golden = checkNotNull(javaClass.getResourceAsStream("/sync/envelope-v1.bin")).use { it.readBytes() }

    @Test
    fun theGoldenFileOpens() {
        assertArrayEquals(PLAINTEXT, (openEnvelope(GOLDEN_KEY, golden) as EnvelopeRead.Opened).plaintext)
    }

    @Test
    fun sealingTheGoldenInputsWritesTheGoldenFileByteForByte() {
        assertArrayEquals(golden, sealEnvelope(GOLDEN_KEY, PLAINTEXT, FixedRandom(GOLDEN_NONCE)))
    }

    @Test
    fun aJoiningDeviceReadsTheFoldersSaltAndCountFromAnyFile() {
        val parameters = checkNotNull(envelopeKeyParameters(golden))

        assertArrayEquals(GOLDEN_SALT, parameters.salt)
        assertEquals(SYNC_KEY_ITERATIONS, parameters.iterations)
    }

    @Test
    fun aWrongPassphraseIsTheWrongKey() {
        val wrong = runBlocking { deriveSyncKey("correct horse battery stapler".toCharArray(), GOLDEN_SALT) }

        assertEquals(EnvelopeRead.WrongKey, openEnvelope(wrong, golden))
    }

    @Test
    fun derivingWipesThePassphrase() {
        val passphrase = "correct horse battery stapler".toCharArray()

        runBlocking { deriveSyncKey(passphrase, GOLDEN_SALT) }

        assertTrue(passphrase.all { it == '\u0000' })
    }

    /** The writer fails loudly rather than upload what no reader opens. */
    @Test
    fun sealingMoreThanAnySnapshotIsRefused() {
        val refused = runCatching { sealEnvelope(GOLDEN_KEY, ByteArray(MAX_BACKUP_BYTES + 1)) }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    /** A count no file may ask for is refused before 600 000-odd rounds of work, and wiped. */
    @Test
    fun parametersNoSyncKeyHasAreRefusedBeforeDeriving() {
        val passphrase = "correct horse".toCharArray()

        val refused = runCatching { runBlocking { deriveSyncKey(passphrase, GOLDEN_SALT, iterations = Int.MAX_VALUE) } }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertTrue(passphrase.all { it == '\u0000' })
    }

    /** A key with other parameters (the folder's passphrase set again elsewhere) is refused before
     *  any decryption. */
    @Test
    fun aKeyMadeWithAnotherSaltIsTheWrongKey() {
        val other = SyncKey(GOLDEN_KEY.keyBytes, ByteArray(16) { 7 }, SYNC_KEY_ITERATIONS)

        assertEquals(EnvelopeRead.WrongKey, openEnvelope(other, golden))
    }

    /** From the nonce on (nonce, ciphertext, tag) an altered byte reads as the wrong key; before it
     *  (magic, version, count, salt) it reads as no envelope or a key of other parameters. */
    @Test
    fun everyAlteredByteFailsToOpen() {
        val nonceOffset = ITERATIONS_OFFSET + Int.SIZE_BYTES + 16
        golden.indices.forEach { index ->
            val altered = golden.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

            val read = openEnvelope(GOLDEN_KEY, altered)

            if (index >= nonceOffset) assertEquals("byte $index", EnvelopeRead.WrongKey, read) else assertFalse("byte $index", read is EnvelopeRead.Opened)
        }
    }

    @Test
    fun aFileCutShortOrAnotherFileIsNoEnvelope() {
        listOf(golden.copyOf(golden.size - 17), ByteArray(0), "{\"bundleVersion\":6}".encodeToByteArray()).forEach {
            assertEquals(EnvelopeRead.NotAnEnvelope, openEnvelope(GOLDEN_KEY, it))
        }
    }

    /** The cap keeps a hostile file's size from deciding what is decrypted into memory. */
    @Test
    fun aFileLargerThanAnySnapshotIsNoEnvelope() {
        val tooLarge = golden.copyInto(ByteArray(golden.size - PLAINTEXT.size + MAX_BACKUP_BYTES + 1), endIndex = golden.size - PLAINTEXT.size - 16)

        assertEquals(EnvelopeRead.NotAnEnvelope, openEnvelope(GOLDEN_KEY, tooLarge))
    }

    /** Read before any size or layout check, and unsigned. */
    @Test
    fun aNewerVersionIsRefusedAsNewerWhateverItsSize() {
        listOf(2, 0x80).forEach { version ->
            val newer = golden.copyOf().also { it[VERSION_OFFSET] = version.toByte() }

            assertEquals("$version", EnvelopeRead.NewerVersion, openEnvelope(GOLDEN_KEY, newer))
            assertEquals("$version", EnvelopeRead.NewerVersion, openEnvelope(GOLDEN_KEY, newer.copyOf(VERSION_OFFSET + 1)))
            assertNull(envelopeKeyParameters(newer))
        }
    }

    /** A count that would make a weak key or hang the device makes the file unreadable (M8-D15:
     *  100 000..10 000 000 taken). */
    @Test
    fun aHeaderAskingForTooFewOrTooManyIterationsIsNoEnvelope() {
        listOf(99_999, 10_000_001, Int.MAX_VALUE, -1).forEach { iterations ->
            val asking = withIterations(iterations)

            assertNull("$iterations", envelopeKeyParameters(asking))
            assertEquals(EnvelopeRead.NotAnEnvelope, openEnvelope(GOLDEN_KEY, asking))
        }
        listOf(100_000, 10_000_000).forEach { iterations ->
            assertEquals(iterations, envelopeKeyParameters(withIterations(iterations))?.iterations)
        }
    }

    private fun withIterations(iterations: Int): ByteArray = golden.copyOf().also { ByteBuffer.wrap(it).putInt(ITERATIONS_OFFSET, iterations) }

    @Test
    fun eachSealTakesAFreshNonce() {
        val one = sealEnvelope(GOLDEN_KEY, PLAINTEXT)
        val other = sealEnvelope(GOLDEN_KEY, PLAINTEXT)

        assertFalse(one.contentEquals(other))
        listOf(one, other).forEach { assertTrue(openEnvelope(GOLDEN_KEY, it) is EnvelopeRead.Opened) }
    }
}

private val PLAINTEXT = "{\"hello\":\"Ayn\"}".encodeToByteArray()
private val GOLDEN_SALT = ByteArray(16) { it.toByte() }
private val GOLDEN_NONCE = ByteArray(12) { (0xA0 + it).toByte() }

/** Derived once: 600 000 iterations take a moment. */
private val GOLDEN_KEY: SyncKey by lazy { runBlocking { deriveSyncKey("correct horse battery staple".toCharArray(), GOLDEN_SALT) } }

/** Gives [bytes] for the nonce. */
private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
    override fun nextBytes(out: ByteArray) {
        bytes.copyInto(out, endIndex = out.size)
    }
}
