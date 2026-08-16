package com.garfbargle.library.data

import android.content.Context
import com.garfbargle.library.BuildConfig
import com.garfbargle.library.auth.GitHubTokenStore
import com.garfbargle.library.network.GitHubHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CatalogRepository(
    private val context: Context,
    private val tokenStore: GitHubTokenStore
) {
    suspend fun load(preferRemote: Boolean = true): CatalogLoadResult = withContext(Dispatchers.IO) {
        val bundled = parse(readBundledCatalog())
        if (!preferRemote) return@withContext CatalogLoadResult(bundled, CatalogSource.BUNDLED)

        runCatching { parse(fetchRemoteCatalog()) }
            .fold(
                onSuccess = { CatalogLoadResult(it, CatalogSource.REMOTE) },
                onFailure = {
                    CatalogLoadResult(
                        catalog = bundled,
                        source = CatalogSource.BUNDLED,
                        warning = it.message ?: "Live catalog is unavailable. Showing the bundled snapshot."
                    )
                }
            )
    }

    private fun readBundledCatalog(): String =
        context.assets.open("catalog.json").bufferedReader().use { it.readText() }

    private fun fetchRemoteCatalog(): String {
        if (BuildConfig.CATALOG_URL.isNotBlank()) {
            return GitHubHttp.getText(BuildConfig.CATALOG_URL, tokenStore.token(), accept = "application/json")
        }

        val repo = BuildConfig.CATALOG_REPOSITORY
        check(repo.isNotBlank()) { "No remote catalog is configured." }
        val token = tokenStore.token()
        val releaseJson = GitHubHttp.getText(
            "https://api.github.com/repos/$repo/releases/tags/catalog",
            token
        )
        val release = JSONObject(releaseJson)
        val assets = release.optJSONArray("assets") ?: JSONArray()
        var apiUrl: String? = null
        var browserUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == "catalog.json") {
                apiUrl = asset.optString("url").takeIf { it.isNotBlank() }
                browserUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                break
            }
        }
        val selected = (
            if (!token.isNullOrBlank()) apiUrl ?: browserUrl
            else browserUrl ?: apiUrl
        ) ?: error("The catalog release does not contain catalog.json.")

        return if (selected.startsWith("https://api.github.com/")) {
            GitHubHttp.openBinary(selected, token).use { response ->
                response.stream.bufferedReader().use { it.readText() }
            }
        } else {
            GitHubHttp.getText(selected, null, accept = "application/json")
        }
    }

    internal fun parse(raw: String): Catalog {
        val root = JSONObject(raw)
        val schemaVersion = root.optInt("schemaVersion", 1)
        val appsJson = root.optJSONArray("apps") ?: JSONArray()
        val apps = buildList {
            for (index in 0 until appsJson.length()) {
                add(appsJson.getJSONObject(index).toAppEntry(schemaVersion))
            }
        }
        return Catalog(
            schemaVersion = schemaVersion,
            name = root.optString("name", "Library"),
            updatedAt = root.optString("updatedAt", ""),
            generatedAt = root.optNullableString("generatedAt"),
            apps = apps.sortedWith(compareByDescending<AppEntry> { it.featured }.thenByDescending { it.publishedAt.orEmpty() }.thenBy { it.name.lowercase() })
        )
    }

    private fun JSONObject.toAppEntry(schemaVersion: Int): AppEntry {
        val release = optJSONObject("release") ?: JSONObject()
        val provenance = optJSONObject("provenance") ?: JSONObject()
        val changelogJson = optJSONArray("changelog") ?: JSONArray()
        val historyJson = optJSONArray("history") ?: JSONArray()
        val changelog = buildList {
            for (index in 0 until changelogJson.length()) add(changelogJson.optString(index))
        }.filter { it.isNotBlank() }
        val history = buildList {
            for (index in 0 until historyJson.length()) {
                val item = historyJson.optJSONObject(index) ?: continue
                add(
                    ReleaseHistoryEntry(
                        tag = item.optString("tag"),
                        versionName = item.optNullableString("versionName"),
                        publishedAt = item.optNullableString("publishedAt"),
                        releaseUrl = item.optNullableString("releaseUrl"),
                        notes = item.optNullableString("notes")
                    )
                )
            }
        }
        val trust = when (provenance.optString("kind", "binary")) {
            "developer-signed" -> TrustKind.DEVELOPER_SIGNED
            "library-built", "store-built" -> TrustKind.LIBRARY_BUILT
            else -> TrustKind.BINARY
        }
        val visibility = when (optString("visibility", "public")) {
            "private" -> AppVisibility.PRIVATE
            else -> AppVisibility.PUBLIC
        }
        val accentString = optString("accent", "#A9FF68").removePrefix("#")
        val accent = runCatching {
            val rgb = accentString.toLong(16)
            if (accentString.length == 6) 0xFF000000L or rgb else rgb
        }.getOrDefault(0xFFA9FF68L)

        val artifacts = if (schemaVersion >= 2) {
            release.optJSONArray("artifacts").toArtifacts(visibility)
        } else {
            listOfNotNull(
                release.optNullableString("apkUrl")?.let { url ->
                    Artifact(
                        name = url.substringAfterLast('/'),
                        downloadUrl = url,
                        apiUrl = null,
                        sha256 = release.optString("sha256"),
                        sizeBytes = release.optLong("sizeBytes", 0),
                        abis = emptyList(),
                        authRequired = visibility == AppVisibility.PRIVATE
                    )
                }
            )
        }

        if (schemaVersion >= 2 && artifacts.isNotEmpty()) {
            require(artifacts.all { it.sha256.length == 64 }) { "Installable catalog entries must pin every APK SHA-256." }
            require(!provenance.optNullableString("signingCertSha256").isNullOrBlank()) {
                "Installable catalog entries must pin a signing certificate."
            }
            require(release.optLong("versionCode", 0) > 0L) { "Installable catalog entries need a positive versionCode." }
        }

        return AppEntry(
            id = getString("id"),
            name = getString("name"),
            packageName = getString("packageName"),
            developer = optString("developer", "Unknown developer"),
            tagline = optString("tagline", ""),
            description = optString("description", ""),
            versionName = release.optString("versionName", "0"),
            versionCode = release.optLong("versionCode", 0),
            category = optString("category", "Apps"),
            minSdk = release.optInt("minSdk", 28),
            targetSdk = release.optInt("targetSdk", 36),
            featured = optBoolean("featured", false),
            accent = accent,
            trust = trust,
            visibility = visibility,
            repository = optNullableString("repository"),
            sourceUrl = optNullableString("sourceUrl"),
            releaseUrl = release.optNullableString("releaseUrl"),
            releaseTag = release.optNullableString("tag"),
            publishedAt = release.optNullableString("publishedAt"),
            signingCertSha256 = provenance.optNullableString("signingCertSha256"),
            artifacts = artifacts,
            changelog = changelog,
            history = history
        )
    }

    private fun JSONArray?.toArtifacts(visibility: AppVisibility): List<Artifact> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val abiJson = item.optJSONArray("abis") ?: JSONArray()
                val abis = buildList {
                    for (abiIndex in 0 until abiJson.length()) {
                        abiJson.optString(abiIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                add(
                    Artifact(
                        name = item.optString("name", "app.apk"),
                        downloadUrl = item.optNullableString("downloadUrl"),
                        apiUrl = item.optNullableString("apiUrl"),
                        sha256 = item.optString("sha256"),
                        sizeBytes = item.optLong("sizeBytes", 0),
                        abis = abis,
                        authRequired = item.optBoolean("authRequired", visibility == AppVisibility.PRIVATE)
                    )
                )
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
