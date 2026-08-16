package com.garfbargle.library.data

import android.os.Build

enum class TrustKind {
    DEVELOPER_SIGNED,
    LIBRARY_BUILT,
    BINARY
}

enum class AppVisibility {
    PUBLIC,
    PRIVATE
}

data class Artifact(
    val name: String,
    val downloadUrl: String?,
    val apiUrl: String?,
    val sha256: String,
    val sizeBytes: Long,
    val abis: List<String>,
    val authRequired: Boolean
) {
    fun supportsDevice(deviceAbis: Array<String> = Build.SUPPORTED_ABIS): Boolean =
        abis.isEmpty() || deviceAbis.any { it in abis }
}

data class ReleaseHistoryEntry(
    val tag: String,
    val versionName: String?,
    val publishedAt: String?,
    val releaseUrl: String?,
    val notes: String?
)

data class AppEntry(
    val id: String,
    val name: String,
    val packageName: String,
    val developer: String,
    val tagline: String,
    val description: String,
    val versionName: String,
    val versionCode: Long,
    val category: String,
    val minSdk: Int,
    val targetSdk: Int,
    val featured: Boolean,
    val accent: Long,
    val trust: TrustKind,
    val visibility: AppVisibility,
    val repository: String?,
    val sourceUrl: String?,
    val releaseUrl: String?,
    val releaseTag: String?,
    val publishedAt: String?,
    val signingCertSha256: String?,
    val artifacts: List<Artifact>,
    val changelog: List<String>,
    val history: List<ReleaseHistoryEntry>
) {
    val sizeBytes: Long get() = preferredArtifact()?.sizeBytes ?: artifacts.maxOfOrNull { it.sizeBytes } ?: 0L
    val requiresGitHubAuth: Boolean get() = visibility == AppVisibility.PRIVATE || artifacts.any { it.authRequired }

    fun preferredArtifact(deviceAbis: Array<String> = Build.SUPPORTED_ABIS): Artifact? =
        artifacts.firstOrNull { it.abis.isEmpty() }
            ?: artifacts.firstOrNull { artifact -> artifact.supportsDevice(deviceAbis) }
}

data class Catalog(
    val schemaVersion: Int,
    val name: String,
    val updatedAt: String,
    val generatedAt: String?,
    val apps: List<AppEntry>
)

data class CatalogLoadResult(
    val catalog: Catalog,
    val source: CatalogSource,
    val warning: String? = null
)

enum class CatalogSource { BUNDLED, REMOTE }

data class InstalledState(
    val installed: Boolean,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val signingCertSha256: String? = null
) {
    fun hasUpdate(entry: AppEntry): Boolean = installed && versionCode != null && versionCode < entry.versionCode

    fun signerMatches(entry: AppEntry): Boolean {
        if (!installed) return true
        val expected = entry.signingCertSha256?.normalizeFingerprint() ?: return true
        val actual = signingCertSha256?.normalizeFingerprint() ?: return false
        return expected.equals(actual, ignoreCase = true)
    }
}

internal fun String.normalizeFingerprint(): String = replace(":", "").trim().lowercase()
