package com.arshad.ayn.core.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Salted PBKDF2-HMAC-SHA256 over the platform provider (M5-D10/D15). The try limits are the
 * lock's real defence; the hash only keeps a PIN reused elsewhere out of a read database.
 * Each hash keeps its own iteration count, so a retuned [iterations] still verifies old ones.
 */
class LockHasher(
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = LOCK_ITERATIONS,
) {
    internal suspend fun hash(secret: LockSecret): StoredLock = withContext(compute) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        StoredLock(secret.kind, pbkdf2(secret.material(), salt, iterations, KEY_BYTES), salt, iterations)
    }

    internal suspend fun matches(secret: LockSecret, stored: StoredLock): Boolean = withContext(compute) {
        val attempt = pbkdf2(secret.material(), stored.salt, stored.iterations, stored.hash.size)
        MessageDigest.isEqual(attempt, stored.hash)
    }
}

/** A lock as kept on a profile row. */
internal class StoredLock(val kind: LockKind, val hash: ByteArray, val salt: ByteArray, val iterations: Int)

/** Derives [keyBytes] bytes from [password], then wipes [password]. */
internal fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    val spec = PBEKeySpec(password, salt, iterations, keyBytes * Byte.SIZE_BITS)
    try {
        return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
        password.fill('\u0000')
    }
}

/** Until the Shield pass measures the largest count under 200 ms (M5-D10, M5.10). */
internal const val LOCK_ITERATIONS = 10_000
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val SALT_BYTES = 16
private const val KEY_BYTES = 32
