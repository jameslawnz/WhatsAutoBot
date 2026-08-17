package com.whatsautobot.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by the Android Keystore. Used to protect stored
 * contact PII (names + numbers) and message templates at rest.
 *
 * Falls back to identity (plaintext passthrough) if the Keystore is unavailable
 * (some emulators / corner cases) so the app never hard-fails on cold start.
 */
object Crypto {
    private const val TAG = "WhatsAutoBotCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "whatsautobot_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val out = ByteBuffer.allocate(IV_LENGTH + ct.size)
                .put(iv)
                .put(ct)
                .array()
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt failed; storing plaintext", e)
            plain
        }
    }

    fun decrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val ct = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Either legacy plaintext or a lost/invalidated key. Return the raw
            // value; callers treat unparseable data as empty rather than crashing.
            payload
        }
    }

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}