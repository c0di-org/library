#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch target: {label}")
    return text.replace(old, new, 1)


# Android shell + Settings wiring.
path = Path("app/src/main/java/com/garfbargle/library/ui/LibraryApp.kt")
text = path.read_text()
text = replace_once(
    text,
    "import com.garfbargle.library.auth.GitHubTokenStore\n",
    "import com.garfbargle.library.auth.GitHubAuthSession\nimport com.garfbargle.library.auth.GitHubTokenStore\n",
    "GitHubAuthSession import",
)
text = replace_once(
    text,
    """            onSaveToken = {
                tokenStore.saveToken(it)
                hasToken = true
                refreshKey++
            },
""",
    """            onAuthorizeGitHub = { session ->
                tokenStore.saveSession(session)
                hasToken = true
                refreshKey++
            },
""",
    "MainShell authorize callback",
)
text = replace_once(
    text,
    "    onSaveToken: (String) -> Unit,\n",
    "    onAuthorizeGitHub: (GitHubAuthSession) -> Unit,\n",
    "MainShell signature",
)
text = replace_once(
    text,
    """                                onSaveToken,
                                onClearToken,
""",
    """                                onAuthorizeGitHub,
                                onClearToken,
""",
    "SettingsScreen call",
)

start = text.index("@Composable\nprivate fun SettingsScreen(")
end = text.index("\n@Composable\nprivate fun Notice(", start)
settings = dedent("""\
@Composable
private fun SettingsScreen(
    hasToken: Boolean,
    warning: String?,
    onAuthorize: (GitHubAuthSession) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    padding: PaddingValues,
    wideLayout: Boolean
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val sidePadding = if (wideLayout) {
            ((maxWidth - 760.dp) / 2f).coerceAtLeast(32.dp)
        } else {
            20.dp
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Settings", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("GitHub App access stays on this device.", color = TextSecondary)
            }
            item {
                SectionCard("GitHub") {
                    GitHubAuthContent(
                        connected = hasToken,
                        clientId = BuildConfig.GITHUB_APP_CLIENT_ID,
                        onAuthorized = onAuthorize,
                        onClear = onClear
                    )
                }
            }
            item {
                SectionCard("Catalog") {
                    Text(BuildConfig.CATALOG_REPOSITORY, color = TextSecondary, fontSize = 12.sp)
                    Text("Public catalog · no sign-in required", color = Color(0xFF777A82), fontSize = 10.sp)
                    warning?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh")
                    }
                }
            }
            item {
                SectionCard("Library ${BuildConfig.VERSION_NAME}") {
                    Text(
                        "Library verifies every APK before installation and keeps each app's signing identity intact.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
""")
path.write_text(text[:start] + settings + text[end:])

# Public catalog refresh never depends on user GitHub credentials.
path = Path("app/src/main/java/com/garfbargle/library/data/CatalogRepository.kt")
text = path.read_text()
start = text.index("    private fun fetchRemoteCatalog(): String {")
end = text.index("\n    internal fun parse(raw: String): Catalog {", start)
fetch = dedent("""\
    private fun fetchRemoteCatalog(): String {
        if (BuildConfig.CATALOG_URL.isNotBlank()) {
            return GitHubHttp.getText(BuildConfig.CATALOG_URL, null, accept = "application/json")
        }

        val repo = BuildConfig.CATALOG_REPOSITORY
        check(repo.isNotBlank()) { "No remote catalog is configured." }
        val releaseJson = GitHubHttp.getText(
            "https://api.github.com/repos/$repo/releases/tags/catalog",
            null
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
        val selected = browserUrl ?: apiUrl
            ?: error("The catalog release does not contain catalog.json.")

        return if (selected.startsWith("https://api.github.com/")) {
            GitHubHttp.openBinary(selected, null).use { response ->
                response.stream.bufferedReader().use { it.readText() }
            }
        } else {
            GitHubHttp.getText(selected, null, accept = "application/json")
        }
    }
""")
path.write_text(text[:start] + fetch + text[end:])

# Version and docs link.
path = Path("gradle.properties")
text = path.read_text()
text = replace_once(text, "LIBRARY_VERSION=1.0.11", "LIBRARY_VERSION=1.0.12", "version bump")
path.write_text(text)

path = Path("README.md")
text = path.read_text()
text = replace_once(
    text,
    "[Releases](docs/RELEASES.md) · [Signing](docs/SIGNING.md) · [Architecture](docs/ARCHITECTURE.md)",
    "[Releases](docs/RELEASES.md) · [Signing](docs/SIGNING.md) · [GitHub App](docs/GITHUB_APP.md) · [Architecture](docs/ARCHITECTURE.md)",
    "README docs link",
)
path.write_text(text)
