package com.garfbargle.library.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.garfbargle.library.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GitHubTokenStore(
    context: Context,
    private val clientId: String = BuildConfig.GITHUB_APP_CLIENT_ID
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True for a GitHub App user session, including one that can be refreshed. Legacy PATs are ignored. */
    fun hasToken(): Boolean =
        prefs.getString(KEY_KIND, null) == KIND_GITHUB_APP &&
            (readEncrypted(KEY_ACCESS_CIPHERTEXT, KEY_ACCESS_IV) != null ||
                readEncrypted(KEY_REFRESH_CIPHERTEXT, KEY_REFRESH_IV) != null)

    /**
     * Returns a usable GitHub App user access token.
     *
     * GitHub device-flow sessions can be refreshed without shipping a client secret, so an expired
     * access token is renewed transparently when a refresh token is available.
     */
    fun token(): String? {
        if (prefs.getString(KEY_KIND, null) != KIND_GITHUB_APP) return null

        val access = readEncrypted(KEY_ACCESS_CIPHERTEXT, KEY_ACCESS_IV)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis()
        if (!access.isNullOrBlank() && (expiresAt == 0L || now < expiresAt - REFRESH_SKEW_MILLIS)) {
            return access
        }

        val refresh = readEncrypted(KEY_REFRESH_CIPHERTEXT, KEY_REFRESH_IV) ?: return null
        val refreshExpiresAt = prefs.getLong(KEY_REFRESH_EXPIRES_AT, 0L)
        if (refreshExpiresAt > 0L && now >= refreshExpiresAt) return null
        if (clientId.isBlank()) return null

        return runCatching {
            GitHubDeviceAuth(clientId).refreshBlocking(refresh).also(::saveSession).accessToken
        }.getOrNull()
    }

    fun saveSession(session: GitHubAuthSession) {
        require(session.accessToken.isNotBlank()) { "GitHub access token cannot be empty." }
        val access = encrypt(session.accessToken.trim())
        val refresh = session.refreshToken?.trim()?.takeIf { it.isNotBlank() }?.let(::encrypt)

        prefs.edit()
            .putString(KEY_KIND, KIND_GITHUB_APP)
            .putString(KEY_ACCESS_IV, access.iv)
            .putString(KEY_ACCESS_CIPHERTEXT, access.ciphertext)
            .apply {
                if (refresh != null) {
                    putString(KEY_REFRESH_IV, refresh.iv)
                    putString(KEY_REFRESH_CIPHERTEXT, refresh.ciphertext)
                } else {
                    remove(KEY_REFRESH_IV)
                    remove(KEY_REFRESH_CIPHERTEXT)
                }
                session.expiresAtMillis?.let { putLong(KEY_EXPIRES_AT, it) } ?: remove(KEY_EXPIRES_AT)
                session.refreshExpiresAtMillis?.let { putLong(KEY_REFRESH_EXPIRES_AT, it) }
                    ?: remove(KEY_REFRESH_EXPIRES_AT)
                // Remove the old PAT-era storage keys as part of the migration.
                remove(LEGACY_KEY_IV)
                remove(LEGACY_KEY_CIPHERTEXT)
            }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readEncrypted(ciphertextKey: String, ivKey: String): String? {
        val encodedCiphertext = prefs.getString(ciphertextKey, null) ?: return null
        val encodedIv = prefs.getString(ivKey, null) ?: return null
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

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return EncryptedValue(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        )
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

    private data class EncryptedValue(val iv: String, val ciphertext: String)

    private companion object {
        const val PREFS = "github_auth"
        const val KEY_KIND = "kind"
        const val KIND_GITHUB_APP = "github_app_user"
        const val KEY_ACCESS_IV = "access_iv"
        const val KEY_ACCESS_CIPHERTEXT = "access_ciphertext"
        const val KEY_REFRESH_IV = "refresh_iv"
        const val KEY_REFRESH_CIPHERTEXT = "refresh_ciphertext"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val LEGACY_KEY_IV = "iv"
        const val LEGACY_KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "library-github-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val REFRESH_SKEW_MILLIS = 60_000L
    }
}
