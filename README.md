# Library

[![Android CI](https://github.com/garfbargle/library/actions/workflows/android.yml/badge.svg)](https://github.com/garfbargle/library/actions/workflows/android.yml)

**Apps, without the mall.**

Library is a private-first Android app store for software shipped from GitHub Releases. Public and private repositories can live in the same catalog, source links are optional, and every install carries a visible provenance trail.

## Product

- Native Kotlin + Jetpack Compose storefront with Discover, Library, Updates, search, app details, settings, and provenance UI.
- Automatically discovers stable GitHub Releases containing standalone Android APKs.
- Reads `versionCode`, `versionName`, SDK requirements, and ABIs from each APK instead of guessing from tags.
- Pins the exact APK SHA-256 and Android signing-certificate SHA-256.
- Refuses package, version, hash, or signer mismatches before install.
- Uses Android `PackageInstaller` for installs and updates.
- Detects installed versions and surfaces newer catalog versions.
- Supports private release assets using a device-local GitHub token encrypted with Android Keystore.
- Keeps Library's signing identity separate from every distributed app's signing identity.

## How apps enter Library

Publish a stable GitHub Release in one of the configured GitHub repositories with a standalone `.apk` asset. The rolling catalog job scans automatically, downloads the APK, verifies it with Android build tools, and publishes the resulting metadata.

A source-only repository never appears as installable until it has an actual APK release.

Optional `.library.json` metadata in an app repository can customize the storefront:

```json
{
  "name": "My App",
  "tagline": "A crisp one-line description.",
  "description": "Optional long-form storefront copy.",
  "category": "Utilities",
  "accent": "#7BA8FF",
  "featured": false,
  "sourceVisible": true,
  "provenance": "developer-signed"
}
```

See `.library.example.json`.

## Trust model

Library never re-signs developer releases. It installs the exact developer-signed APK attached to GitHub Releases and records its hash and signer certificate in the catalog.

Trust labels are explicit:

- **Developer signed** — original developer APK, unchanged.
- **Built by Library** — reserved for a future isolated build/signing service with a dedicated key per package.
- **Binary release** — distributed without a source claim, still hash/signer pinned.

The Library app signing key signs **only Library**.

## Public and private repositories

Public GitHub releases are discovered anonymously. To index private repositories, configure the optional Actions secret:

```text
LIBRARY_GITHUB_TOKEN
```

Use a fine-grained token with read-only access to only the repositories Library should index.

Private downloads on Android require GitHub access in Settings. The token is encrypted with an AES-GCM key held by Android Keystore. Library sends authorization only to `api.github.com` and does not forward it to GitHub's release CDN redirects.

## CI and releases

### Android CI

Every push and pull request validates the catalog, runs Android lint/tests, builds a debug APK, and uploads it as an Actions artifact.

### Rolling catalog

Every six hours, and on manual dispatch, the catalog workflow scans GitHub releases and publishes `catalog.json` to a rolling GitHub Release tagged `catalog`. Installed copies can refresh the catalog without shipping a new Library APK.

### Signed Library releases

A semantic-version tag such as `v1.0.0` builds a release-signed APK and publishes:

```text
library-1.0.0.apk
SHA256SUMS.txt
catalog.json
```

Release CI intentionally refuses to publish without a stable signing key. Configure:

```text
LIBRARY_KEYSTORE_BASE64
LIBRARY_SIGNING_STORE_PASSWORD
LIBRARY_SIGNING_KEY_ALIAS
LIBRARY_SIGNING_KEY_PASSWORD
```

Generate the key once, back it up offline, and never regenerate it for later versions. See `docs/RELEASES.md` and `docs/SIGNING.md`.

## Local build

Requirements:

- JDK 17
- Android SDK platform 37
- Android build-tools 36.0.0
- Gradle 9.5.0

```bash
python3 scripts/build_catalog.py
./gradlew assembleDebug
```

Full GitHub discovery:

```bash
export LIBRARY_GITHUB_TOKEN=github_pat_...   # optional; private repos only
python3 scripts/sync_github.py
python3 scripts/build_catalog.py
python3 scripts/validate_catalog.py catalog/library.json
```

## Project layout

```text
app/                         Android storefront + installer
catalog/apps/                manual/bootstrap entries
catalog/apps/generated/      generated GitHub-release entries
scripts/sync_github.py       GitHub release discovery + APK inspection
scripts/build_catalog.py     aggregate catalog generation
scripts/validate_catalog.py  deterministic validation
.github/workflows/           CI, rolling catalog, signed releases
docs/                        architecture, signing, release operations
```

## Security boundary

Distribution, authorship, source availability, build provenance, and APK signing are separate concepts. Library's job is to make those distinctions visible while preserving Android's existing package-signing trust model.
