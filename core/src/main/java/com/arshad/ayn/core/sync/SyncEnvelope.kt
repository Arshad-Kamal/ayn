package com.arshad.ayn.core.sync

import com.arshad.ayn.core.backup.MAX_BACKUP_BYTES
import com.arshad.ayn.core.lock.pbkdf2
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The folder's sync key (M8-D7): derived from the sync passphrase with [salt] over [iterations],
 * the same on every device of a folder. The first device picks the salt; a joining one reads it
 * and the count from any file there ([envelopeKeyParameters]).
 */
internal class SyncKey(internal val keyBytes: ByteArray, val salt: ByteArray, val iterations: Int) {
    init {
        require(keyBytes.size == KEY_BYTES && salt.size == SALT_BYTES && iterations in ITERATION_RANGE) { "not a sync key" }
    }

    fun sameParameters(parameters: KeyParameters): Boolean =
        parameters.iterations == iterations && MessageDigest.isEqual(parameters.salt, salt)
}

/** A file's key parameters, from its header. */
internal class KeyParameters(val salt: ByteArray, val iterations: Int)

/** PBKDF2-HMAC-SHA256, as the profile locks (M5-D10), at the count OWASP's Password Storage Cheat
 *  Sheet gives for it (checked 2026-10-30): the key guards a file an attacker can copy and test
 *  offline, with no try limit. Runs once per device, when the passphrase is entered. */
internal const val SYNC_KEY_ITERATIONS = 600_000

/** What a file's header may ask for: below it a key is too cheap to guess; above it, deriving
 *  would hang the device. */
internal val ITERATION_RANGE: IntRange = 100_000..10_000_000

/** Derives the key from [passphrase] (wiped) on [compute]; parameters a sync key can't have are
 *  refused before any work. */
internal suspend fun deriveSyncKey(
    passphrase: CharArray,
    salt: ByteArray,
    iterations: Int = SYNC_KEY_ITERATIONS,
    compute: CoroutineDispatcher = Dispatchers.Default,
): SyncKey {
    if (salt.size != SALT_BYTES || iterations !in ITERATION_RANGE) {
        passphrase.fill('\u0000')
        throw IllegalArgumentException("not a sync key's parameters")
    }
    return withContext(compute) { SyncKey(pbkdf2(passphrase, salt, iterations, KEY_BYTES), salt, iterations) }
}

internal fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

/**
 * [plaintext] sealed with AES-256-GCM under [key] (M8-D7): a header (magic, [ENVELOPE_VERSION],
 * the key's iteration count and salt, a fresh nonce) authenticated with the ciphertext, so a
 * changed byte anywhere fails to open. [plaintext] is at most [MAX_BACKUP_BYTES], as every reader
 * expects.
 */
internal fun sealEnvelope(key: SyncKey, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
    require(plaintext.size <= MAX_BACKUP_BYTES) { "a snapshot larger than any reader takes" }
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val header = ByteBuffer.allocate(HEADER_BYTES)
        .put(MAGIC).put(ENVELOPE_VERSION).putInt(key.iterations).put(key.salt).put(nonce)
        .array()
    val cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(header)
    }
    return header + cipher.doFinal(plaintext)
}

internal sealed interface EnvelopeRead {
    class Opened(val plaintext: ByteArray) : EnvelopeRead

    /** Sealed under another key (a wrong passphrase, or the folder's passphrase changed), or
     *  altered since: GCM can't tell the two apart. */
    data object WrongKey : EnvelopeRead

    /** Written by a newer Ayn. */
    data object NewerVersion : EnvelopeRead

    /** Not an Ayn sync file, cut short, or larger than any snapshot. */
    data object NotAnEnvelope : EnvelopeRead
}

internal fun openEnvelope(key: SyncKey, envelope: ByteArray): EnvelopeRead {
    val header = when (val read = readHeader(envelope)) {
        is HeaderRead.Valid -> read
        HeaderRead.Newer -> return EnvelopeRead.NewerVersion
        HeaderRead.Invalid -> return EnvelopeRead.NotAnEnvelope
    }
    if (!key.sameParameters(header.parameters)) return EnvelopeRead.WrongKey
    return try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.keyBytes, "AES"), GCMParameterSpec(TAG_BITS, header.nonce))
            updateAAD(envelope, 0, HEADER_BYTES)
        }
        EnvelopeRead.Opened(cipher.doFinal(envelope, HEADER_BYTES, envelope.size - HEADER_BYTES))
    } catch (_: GeneralSecurityException) {
        EnvelopeRead.WrongKey
    }
}

/** The salt and count [envelope] was sealed with, for a device joining the folder; `null` when it
 *  isn't an envelope this build reads. */
internal fun envelopeKeyParameters(envelope: ByteArray): KeyParameters? = (readHeader(envelope) as? HeaderRead.Valid)?.parameters

private sealed interface HeaderRead {
    class Valid(val parameters: KeyParameters, val nonce: ByteArray) : HeaderRead

    data object Newer : HeaderRead

    data object Invalid : HeaderRead
}

/** Magic and version first, so a newer version of any size or layout reads as newer. */
private fun readHeader(envelope: ByteArray): HeaderRead {
    if (envelope.size <= VERSION_OFFSET || !envelope.copyOf(MAGIC.size).contentEquals(MAGIC)) return HeaderRead.Invalid
    val version = envelope[VERSION_OFFSET].toInt() and 0xFF
    if (version > ENVELOPE_VERSION) return HeaderRead.Newer
    if (version != ENVELOPE_VERSION.toInt()) return HeaderRead.Invalid
    if (envelope.size !in HEADER_BYTES + TAG_BYTES..MAX_ENVELOPE_BYTES) return HeaderRead.Invalid
    val buffer = ByteBuffer.wrap(envelope, ITERATIONS_OFFSET, HEADER_BYTES - ITERATIONS_OFFSET)
    val iterations = buffer.getInt()
    if (iterations !in ITERATION_RANGE) return HeaderRead.Invalid
    val salt = ByteArray(SALT_BYTES).also(buffer::get)
    val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
    return HeaderRead.Valid(KeyParameters(salt, iterations), nonce)
}

private val MAGIC = "AYNS".encodeToByteArray()
internal const val ENVELOPE_VERSION: Byte = 1
internal const val TRANSFORMATION = "AES/GCM/NoPadding"
internal const val KEY_BYTES = 32
internal const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
internal const val TAG_BITS = 128
private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
internal val VERSION_OFFSET: Int = MAGIC.size
internal val ITERATIONS_OFFSET: Int = VERSION_OFFSET + Byte.SIZE_BYTES
private val HEADER_BYTES = ITERATIONS_OFFSET + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES

/** The largest file an envelope can be: a snapshot of [MAX_BACKUP_BYTES], sealed. */
internal val MAX_ENVELOPE_BYTES: Int = HEADER_BYTES + MAX_BACKUP_BYTES + TAG_BYTES
