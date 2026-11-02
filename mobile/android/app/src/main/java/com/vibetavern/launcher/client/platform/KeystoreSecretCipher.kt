package com.vibetavern.launcher.client.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vibetavern.client.data.SecretCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts access tokens with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves it. Stored form: `v1:<base64 iv>:<base64 ciphertext>`.
 *
 * If the key is gone (app data restored on another device, keystore reset) [decrypt]
 * returns null and the profile simply asks for its token again.
 */
class KeystoreSecretCipher(private val alias: String = "vibe_tavern_profile_tokens") : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(encrypted: String): String? = try {
        val parts = encrypted.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
