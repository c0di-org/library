package com.garfbargle.library.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/** OAuth device-flow authentication for the Library GitHub App. */
data class GitHubAuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
    val refreshExpiresAtMillis: Long? = null
)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Long
)

class GitHubDeviceAuth(private val clientId: String) {
    suspend fun begin(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        requireConfigured()
        val now = System.currentTimeMillis()
        val response = post(
            DEVICE_CODE_URL,
            mapOf("client_id" to clientId)
        )
        throwIfOAuthError(response, "GitHub authorization failed.")

        val deviceCode = response.optString("device_code")
        val userCode = response.optString("user_code")
        val verificationUri = response.optString("verification_uri")
        val expiresIn = response.optLong("expires_in", 0L)
        check(
            deviceCode.isNotBlank() &&
                userCode.isNotBlank() &&
                verificationUri.isNotBlank() &&
                expiresIn > 0L
        ) { "GitHub returned an incomplete device sign-in response. Try again." }

        GitHubDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtMillis = now + expiresIn * 1_000L,
            intervalSeconds = response.optLong("interval", 5L).coerceAtLeast(1L)
        )
    }

    suspend fun awaitAuthorization(code: GitHubDeviceCode): GitHubAuthSession = withContext(Dispatchers.IO) {
        requireConfigured()
        var intervalMillis = code.intervalSeconds * 1_000L
        while (System.currentTimeMillis() < code.expiresAtMillis) {
            delay(intervalMillis)
            val response = post(
                TOKEN_URL,
                mapOf(
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                    "grant_type" to DEVICE_GRANT
                )
            )
            response.optString("access_token").takeIf { it.isNotBlank() }?.let {
                return@withContext sessionFrom(response)
            }

            when (response.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMillis += 5_000L
                "access_denied" -> error("GitHub authorization was cancelled.")
                "expired_token", "token_expired" -> error("The GitHub sign-in code expired. Try again.")
                "device_flow_disabled" -> error("Device Flow is not enabled for the Library GitHub App.")
                "incorrect_client_credentials" -> error("This build has an invalid GitHub App client ID.")
                else -> error(response.optString("error_description", "GitHub authorization failed."))
            }
        }
        error("The GitHub sign-in code expired. Try again.")
    }

    internal fun refreshBlocking(refreshToken: String): GitHubAuthSession {
        requireConfigured()
        val response = post(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            )
        )
        val error = response.optString("error")
        if (error.isNotBlank()) {
            error(response.optString("error_description", "GitHub session refresh failed."))
        }
        return sessionFrom(response)
    }

    private fun requireConfigured() {
        check(clientId.isNotBlank()) { "GitHub App authentication is not configured in this build." }
    }

    private fun throwIfOAuthError(response: JSONObject, fallback: String) {
        val errorCode = response.optString("error")
        if (errorCode.isBlank()) return

        val message = when (errorCode) {
            "device_flow_disabled" ->
                "Device Flow is not enabled for the Library GitHub App. Enable Device Flow in the GitHub App settings."
            "incorrect_client_credentials" ->
                "This build has an invalid GitHub App client ID. Check the Library GitHub App configuration."
            else -> response.optString("error_description").takeIf { it.isNotBlank() }
                ?: "$fallback ($errorCode)"
        }
        error(message)
    }

    private fun sessionFrom(response: JSONObject): GitHubAuthSession {
        val now = System.currentTimeMillis()
        val expiresIn = response.optLong("expires_in", 0L)
        val refreshExpiresIn = response.optLong("refresh_token_expires_in", 0L)
        return GitHubAuthSession(
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAtMillis = expiresIn.takeIf { it > 0L }?.let { now + it * 1_000L },
            refreshExpiresAtMillis = refreshExpiresIn.takeIf { it > 0L }?.let { now + it * 1_000L }
        )
    }

    private fun post(url: String, values: Map<String, String>): JSONObject {
        var lastDnsError: UnknownHostException? = null
        repeat(DNS_RETRY_ATTEMPTS) { attempt ->
            try {
                return postOnce(url, values)
            } catch (error: UnknownHostException) {
                lastDnsError = error
                if (attempt < DNS_RETRY_ATTEMPTS - 1) {
                    Thread.sleep(DNS_RETRY_DELAY_MILLIS * (attempt + 1L))
                }
            } catch (error: IOException) {
                throw IllegalStateException(
                    "Can't connect to GitHub right now. Check your connection and try again.",
                    error
                )
            }
        }
        throw IllegalStateException(
            "Can't reach GitHub. Check your connection, VPN, or Private DNS and try again.",
            lastDnsError
        )
    }

    private fun postOnce(url: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "garfbargle/library-android")
            setRequestProperty("Content-Length", body.size.toString())
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) error("GitHub returned HTTP ${connection.responseCode}.")
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val DNS_RETRY_ATTEMPTS = 3
        const val DNS_RETRY_DELAY_MILLIS = 500L
    }
}
