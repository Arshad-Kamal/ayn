package com.arshad.ayn.core.lock

import java.security.SecureRandom
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lock secrets and their PBKDF2-HMAC-SHA256 hashes (M5-D10/D15). */
class LockHasherTest {

    @Test
    fun pbkdf2GivesTheRfc7914Vectors() {
        assertEquals(RFC_7914_PASSWD_SALT_1, pbkdf2("passwd".toCharArray(), "salt".encodeToByteArray(), 1, 64).toHexString())
        assertEquals(RFC_7914_PASSWORD_NACL_80000, pbkdf2("Password".toCharArray(), "NaCl".encodeToByteArray(), 80_000, 64).toHexString())
    }

    @Test
    fun aPinHashesAsItsKindAndDigits() = runTest {
        val hasher = LockHasher(StandardTestDispatcher(testScheduler), CountingRandom())

        val stored = hasher.hash(LockSecret.Pin("4071"))

        // hashlib.pbkdf2_hmac('sha256', b'pin:4071', bytes(range(16)), 10000, 32), Python 3.
        assertEquals("3bd395ca43d174e1e36760364da20814a76e6d02a903c3cf7b0105352d1ef6af", stored.hash.toHexString())
        assertEquals(LOCK_ITERATIONS, stored.iterations)
        assertEquals(LockKind.Pin, stored.kind)
    }

    @Test
    fun aHashMatchesItsOwnSecretOnly() = runTest {
        val hasher = LockHasher(StandardTestDispatcher(testScheduler), iterations = FAST)
        val stored = hasher.hash(LockSecret.Pin("4071"))

        assertTrue(hasher.matches(LockSecret.Pin("4071"), stored))
        assertFalse(hasher.matches(LockSecret.Pin("4072"), stored))
    }

    @Test
    fun aSequenceNeverMatchesAPinOfTheSameSymbols() = runTest {
        val hasher = LockHasher(StandardTestDispatcher(testScheduler), iterations = FAST)
        val stored = hasher.hash(LockSecret.Sequence(listOf(Press.Up, Press.Up, Press.Down, Press.Down, Press.Left, Press.Right)))

        assertFalse(hasher.matches(LockSecret.Pin("1234"), stored))
        assertTrue(hasher.matches(LockSecret.Sequence(listOf(Press.Up, Press.Up, Press.Down, Press.Down, Press.Left, Press.Right)), stored))
    }

    @Test
    fun eachHashHasItsOwnSalt() = runTest {
        val hasher = LockHasher(StandardTestDispatcher(testScheduler), iterations = FAST)

        val first = hasher.hash(LockSecret.Pin("4071"))
        val second = hasher.hash(LockSecret.Pin("4071"))

        assertNotEquals(first.salt.toHexString(), second.salt.toHexString())
        assertNotEquals(first.hash.toHexString(), second.hash.toHexString())
    }

    @Test
    fun aHashStillMatchesAfterTheIterationCountChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stored = LockHasher(dispatcher, iterations = FAST).hash(LockSecret.Pin("4071"))

        assertEquals(FAST, stored.iterations)
        assertTrue(LockHasher(dispatcher, iterations = FAST * 2).matches(LockSecret.Pin("4071"), stored))
    }

    @Test
    fun aSequenceTakesSixToEightPresses() {
        assertThrows(IllegalArgumentException::class.java) { LockSecret.Sequence(List(5) { Press.Up }) }
        assertThrows(IllegalArgumentException::class.java) { LockSecret.Sequence(List(9) { Press.Up }) }
        LockSecret.Sequence(List(6) { Press.Up })
        LockSecret.Sequence(List(8) { Press.Up })
    }

    @Test
    fun aPinIsFourAsciiDigits() {
        listOf("123", "12345", "12a4", "\u0661\u0662\u0663\u0664").forEach { digits ->
            assertThrows(digits, IllegalArgumentException::class.java) { LockSecret.Pin(digits) }
        }
        LockSecret.Pin("0000")
    }

    @Test
    fun aSecretNamesOnlyItsKindWhenPrinted() {
        assertEquals("LockSecret(pin)", LockSecret.Pin("4071").toString())
    }

    /** Fills each requested salt with 0, 1, 2 …, so a hash can be checked against a known value. */
    private class CountingRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = it.toByte() }
        }
    }
}

private const val FAST = 1_000

// RFC 7914 § 11, PBKDF2-HMAC-SHA256 test vectors (also hashlib.pbkdf2_hmac in Python 3).
private const val RFC_7914_PASSWD_SALT_1 =
    "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
private const val RFC_7914_PASSWORD_NACL_80000 =
    "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"
