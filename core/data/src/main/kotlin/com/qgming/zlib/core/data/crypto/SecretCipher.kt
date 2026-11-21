package com.qgming.zlib.core.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small abstraction so encryption can be tested without the Android Keystore.
 */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Returns null when the ciphertext or the key is unusable. */
    fun decrypt(ciphertext: ByteArray): ByteArray?
}

/**
 * AES/GCM key stored in the Android Keystore. The IV is prepended to the ciphertext:
 * [1 byte IV length][IV][ciphertext].
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        val buffer = ByteBuffer.allocate(1 + iv.size + encrypted.size)
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(encrypted)
        return buffer.array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray? = runCatching {
        if (ciphertext.size < 3) return null
        val buffer = ByteBuffer.wrap(ciphertext)
        val ivLength = buffer.get().toInt() and 0xff
        if (ivLength !in 12..16 || buffer.remaining() < ivLength) return null
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted)
    }.getOrNull()

    private fun key(): SecretKey {
        synchronized(lock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_ALIAS = "zlib.session.v1"
        private val lock = Any()
    }
}
