package com.garfbargle.library.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GitHubTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasToken(): Boolean = token().isNullOrBlank().not()

    fun token(): String? {
        val encodedCiphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val encodedIv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun saveToken(value: String) {
        val clean = value.trim()
        require(clean.isNotBlank()) { "Token cannot be empty." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "github_auth"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "library-github-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
