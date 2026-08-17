package com.garfbargle.library.network

import com.garfbargle.library.BuildConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object GitHubHttp {
    private const val MAX_REDIRECTS = 6

    fun getText(url: String, token: String? = null, accept: String = "application/vnd.github+json"): String =
        open(url, token, accept).use { response -> response.stream.bufferedReader().use { it.readText() } }

    fun openBinary(url: String, token: String? = null): Response =
        open(url, token, "application/octet-stream")

    private fun open(initialUrl: String, token: String?, accept: String): Response {
        var current = initialUrl
        var activeToken = token
        var redirectCount = 0

        while (true) {
            require(URI(current).scheme.equals("https", ignoreCase = true)) { "Library only downloads over HTTPS." }
            val parsed = URL(current)
            val sendToken = !activeToken.isNullOrBlank() && isGitHubApiHost(parsed.host)
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Library/${BuildConfig.VERSION_NAME}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (sendToken) setRequestProperty("Authorization", "Bearer $activeToken")
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        error("Download redirect did not include a destination.")
                    }
                connection.disconnect()
                check(++redirectCount <= MAX_REDIRECTS) { "Too many download redirects." }
                current = URL(parsed, location).toString()
                continue
            }

            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                connection.disconnect()

                // Fine-grained PATs can be valid for private apps while lacking access to a public
                // catalog repository. Retry GitHub API access anonymously before surfacing the error.
                if (sendToken && code in listOf(401, 403, 404)) {
                    activeToken = null
                    current = initialUrl
                    redirectCount = 0
                    continue
                }

                val hint = if (code == 401 || code == 403 || code == 404) " GitHub access may be required." else ""
                error("Request failed: HTTP $code.$hint${detail?.take(160)?.let { " $it" } ?: ""}")
            }

            return Response(connection, connection.inputStream)
        }
    }

    private fun isGitHubApiHost(host: String): Boolean = host.equals("api.github.com", ignoreCase = true)

    class Response internal constructor(
        private val connection: HttpURLConnection,
        val stream: InputStream
    ) : AutoCloseable {
        val contentLength: Long? = connection.contentLengthLong.takeIf { it > 0L }
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }
}
