package com.arshad.ayn.core.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seals small secrets for storage on this device (M8-D7). */
internal interface SecretBox {
    /** Throws [GeneralSecurityException], [IOException] or [ProviderException] when the box can't
     *  seal. */
    fun seal(plaintext: ByteArray): ByteArray

    /** `null` when [sealed] can't be opened: altered, or sealed under a key the box no longer has. */
    fun open(sealed: ByteArray): ByteArray?

    /** Drops the box's key, so the next seal makes a new one; false when it couldn't. */
    fun forget(): Boolean
}

/**
 * [SecretBox] under an AES-256-GCM key the Android Keystore makes and never releases ([alias]);
 * a sealed secret is the cipher's IV followed by its ciphertext. The Keystore picks each IV.
 */
internal class KeystoreSecretBox(private val alias: String = SYNC_KEY_ALIAS) : SecretBox {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray? = try {
        openOrNull(sealed)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: ProviderException) {
        null
    }

    override fun forget(): Boolean = try {
        keyStore().deleteEntry(alias)
        true
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: ProviderException) {
        false
    }

    private fun openOrNull(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        val key = existingKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /** Synchronized: two first seals at once would each make a key under [alias], and the second
     *  would leave the first's secret unopenable. */
    @Synchronized
    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }
}

/**
 * The sync key and the cloud service's credential (an app password or refresh token), each sealed
 * by [box] in the app's private `sync` preferences (M8-D7), read and written on [io]. A value the
 * box can no longer open, or that doesn't read as one, reads as absent: the device must link again.
 */
internal class SyncSecrets(
    context: Context,
    private val box: SecretBox = KeystoreSecretBox(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE) }

    /** False when the preferences file wasn't written. Throws what [SecretBox.seal] throws. */
    suspend fun saveKey(key: SyncKey): Boolean =
        save(KEY_ENTRY, ByteBuffer.allocate(STORED_KEY_BYTES).putInt(key.iterations).put(key.salt).put(key.keyBytes).array())

    /** The count, salt and key as [saveKey] stored them. */
    suspend fun key(): SyncKey? {
        val bytes = read(KEY_ENTRY)?.takeIf { it.size == STORED_KEY_BYTES } ?: return null
        val buffer = ByteBuffer.wrap(bytes)
        val iterations = buffer.getInt()
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val keyBytes = ByteArray(KEY_BYTES).also(buffer::get)
        return if (iterations in ITERATION_RANGE) SyncKey(keyBytes, salt, iterations) else null
    }

    /** As [saveKey]. */
    suspend fun saveCredential(credential: ByteArray): Boolean = save(CREDENTIAL_ENTRY, credential)

    suspend fun credential(): ByteArray? = read(CREDENTIAL_ENTRY)

    /** Unlinking: both gone, and the box's key with them, so a broken Keystore key can't stop the
     *  next link. False when either step failed. */
    suspend fun clear(): Boolean = withContext(io) { preferences.edit().clear().commit() and box.forget() }

    private suspend fun save(entry: String, value: ByteArray): Boolean = withContext(io) {
        preferences.edit().putString(entry, Base64.encode(box.seal(value))).commit()
    }

    private suspend fun read(entry: String): ByteArray? = withContext(io) {
        val stored = preferences.getString(entry, null) ?: return@withContext null
        val sealed = try {
            Base64.decode(stored)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        box.open(sealed)
    }
}

private const val SYNC_PREFERENCES = "sync"
private const val SYNC_KEY_ALIAS = "ayn-sync"
private const val KEY_ENTRY = "key"
private const val CREDENTIAL_ENTRY = "credential"
private const val KEYSTORE = "AndroidKeyStore"
private const val IV_BYTES = 12
private val STORED_KEY_BYTES = Int.SIZE_BYTES + SALT_BYTES + KEY_BYTES
