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

    suspend fun readmeFor(entry: AppEntry): String? = withContext(Dispatchers.IO) {
        val repo = entry.repository ?: return@withContext null
        val url = "https://api.github.com/repos/$repo/readme"
        val token = tokenStore.token()
        val authenticated = runCatching {
            GitHubHttp.getText(url, token, accept = "application/vnd.github.html+json")
        }.getOrNull()
        if (!authenticated.isNullOrBlank()) return@withContext authenticated

        if (!token.isNullOrBlank() && entry.visibility == AppVisibility.PUBLIC) {
            return@withContext runCatching {
                GitHubHttp.getText(url, null, accept = "application/vnd.github.html+json")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        null
    }

    private fun readBundledCatalog(): String =
        context.assets.open("catalog.json").bufferedReader().use { it.readText() }

    private fun fetchRemoteCatalog(): String {
        if (BuildConfig.CATALOG_URL.isNotBlank()) {
            return GitHubHttp.getText(BuildConfig.CATALOG_URL, null, accept = "application/json")
        }

        val repo = BuildConfig.CATALOG_REPOSITORY
        check(repo.isNotBlank()) { "No remote catalog is configured." }
        val release = latestCatalogRelease(repo)
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
        val selected = browserUrl ?: apiUrl
            ?: error("The latest catalog release does not contain catalog.json.")

        return if (selected.startsWith("https://api.github.com/")) {
            GitHubHttp.openBinary(selected, null).use { response ->
                response.stream.bufferedReader().use { it.readText() }
            }
        } else {
            GitHubHttp.getText(selected, null, accept = "application/json")
        }
    }

    private fun latestCatalogRelease(repo: String): JSONObject {
        val releasesJson = GitHubHttp.getText(
            "https://api.github.com/repos/$repo/releases?per_page=100",
            null
        )
        val releases = JSONArray(releasesJson)
        var latest: JSONObject? = null
        var latestPublishedAt = ""

        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            if (!release.optString("tag_name").startsWith("catalog-")) continue

            val publishedAt = release.optString("published_at")
                .ifBlank { release.optString("created_at") }
            if (latest == null || publishedAt > latestPublishedAt) {
                latest = release
                latestPublishedAt = publishedAt
            }
        }

        if (latest != null) return latest

        val legacyReleaseJson = GitHubHttp.getText(
            "https://api.github.com/repos/$repo/releases/tags/catalog",
            null
        )
        return JSONObject(legacyReleaseJson)
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
        val releaseJson = optJSONObject("release") ?: JSONObject()
        val provenance = optJSONObject("provenance") ?: JSONObject()
        val iconJson = optJSONObject("icon")
        val changelogJson = optJSONArray("changelog") ?: JSONArray()
        val historyJson = optJSONArray("history") ?: JSONArray()
        val changelog = changelogJson.toStrings()
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
        val icon = iconJson?.optNullableString("dataBase64")?.let { data ->
            AppIcon(
                mimeType = iconJson.optString("mimeType", "image/png"),
                dataBase64 = data
            )
        }
        val trust = when (provenance.optString("kind", "binary")) {
            "developer-signed" -> TrustKind.DEVELOPER_SIGNED
            "library-built", "library-managed", "store-built" -> TrustKind.LIBRARY_BUILT
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
        val currentSigner = provenance.optNullableString("signingCertSha256")
        val currentRelease = releaseJson.toAppRelease(
            schemaVersion = schemaVersion,
            visibility = visibility,
            fallbackSigner = currentSigner,
            fallbackChangelog = changelog
        )
        val releasesJson = optJSONArray("releases") ?: JSONArray()
        val releases = buildList {
            for (index in 0 until releasesJson.length()) {
                val item = releasesJson.optJSONObject(index) ?: continue
                add(
                    item.toAppRelease(
                        schemaVersion = schemaVersion,
                        visibility = visibility,
                        fallbackSigner = currentSigner,
                        fallbackChangelog = emptyList()
                    )
                )
            }
        }.distinctBy { it.versionCode to it.tag }

        return AppEntry(
            id = getString("id"),
            name = getString("name"),
            packageName = getString("packageName"),
            developer = optString("developer", "Unknown developer"),
            tagline = optString("tagline", ""),
            description = optString("description", ""),
            icon = icon,
            versionName = currentRelease.versionName,
            versionCode = currentRelease.versionCode,
            category = optString("category", "Apps"),
            minSdk = currentRelease.minSdk,
            targetSdk = currentRelease.targetSdk,
            featured = optBoolean("featured", false),
            accent = accent,
            trust = trust,
            visibility = visibility,
            repository = optNullableString("repository"),
            sourceUrl = optNullableString("sourceUrl"),
            releaseUrl = currentRelease.releaseUrl,
            releaseTag = currentRelease.tag,
            publishedAt = currentRelease.publishedAt,
            signingCertSha256 = currentRelease.signingCertSha256,
            artifacts = currentRelease.artifacts,
            changelog = changelog,
            history = history,
            releases = releases
        )
    }

    private fun JSONObject.toAppRelease(
        schemaVersion: Int,
        visibility: AppVisibility,
        fallbackSigner: String?,
        fallbackChangelog: List<String>
    ): AppRelease {
        val artifacts = if (schemaVersion >= 2) {
            optJSONArray("artifacts").toArtifacts(visibility)
        } else {
            listOfNotNull(
                optNullableString("apkUrl")?.let { url ->
                    Artifact(
                        name = url.substringAfterLast('/'),
                        downloadUrl = url,
                        apiUrl = null,
                        sha256 = optString("sha256"),
                        sizeBytes = optLong("sizeBytes", 0),
                        abis = emptyList(),
                        authRequired = visibility == AppVisibility.PRIVATE
                    )
                }
            )
        }
        val signer = optNullableString("signingCertSha256") ?: fallbackSigner
        val releaseChangelog = optJSONArray("changelog")?.toStrings().orEmpty().ifEmpty { fallbackChangelog }

        if (schemaVersion >= 2 && artifacts.isNotEmpty()) {
            require(artifacts.all { it.sha256.length == 64 }) { "Installable catalog releases must pin every APK SHA-256." }
            require(!signer.isNullOrBlank()) { "Installable catalog releases must pin a signing certificate." }
            require(optLong("versionCode", 0) > 0L) { "Installable catalog releases need a positive versionCode." }
        }

        return AppRelease(
            tag = optNullableString("tag"),
            versionName = optString("versionName", "0"),
            versionCode = optLong("versionCode", 0),
            minSdk = optInt("minSdk", 28),
            targetSdk = optInt("targetSdk", 36),
            publishedAt = optNullableString("publishedAt"),
            releaseUrl = optNullableString("releaseUrl"),
            signingCertSha256 = signer,
            artifacts = artifacts,
            changelog = releaseChangelog
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

    private fun JSONArray.toStrings(): List<String> = buildList {
        for (index in 0 until length()) add(optString(index))
    }.filter { it.isNotBlank() }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
