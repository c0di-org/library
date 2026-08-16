# Library

[![Android CI](https://github.com/garfbargle/library/actions/workflows/android.yml/badge.svg)](https://github.com/garfbargle/library/actions/workflows/android.yml)

**Apps, without the mall.**

Library is a private-first Android app store for software shipped from GitHub. Public and private repositories can live in the same catalog, source links are optional, and every install carries a visible provenance trail.

## Product

- Native Kotlin + Jetpack Compose storefront with Discover, Library, Updates, search, app details, settings, and provenance UI.
- Automatically discovers stable GitHub Releases containing standalone Android APKs.
- Can preserve developer-signed releases unchanged or centrally sign explicitly allowlisted unsigned CI artifacts.
- Reads `versionCode`, `versionName`, SDK requirements, and ABIs from each APK instead of guessing from tags.
- Pins the exact APK SHA-256 and Android signing-certificate SHA-256.
- Refuses package, version, hash, or signer mismatches before install.
- Uses Android `PackageInstaller` for installs and updates.
- Detects installed versions and surfaces newer catalog versions.
- Supports private release assets using a device-local GitHub token encrypted with Android Keystore.
- Keeps Library's own app signing identity separate from its managed-app distribution identity.

## How apps enter Library

There are two supported paths.

### Developer-signed releases

Publish a stable GitHub Release containing a standalone signed `.apk`. Library indexes and installs that exact APK unchanged.

### Library-managed releases

An app repository's CI uploads an unsigned release APK as an Actions artifact named `library-unsigned-apk`. The repository is enrolled centrally in `config/managed-apps.json`, which pins its repository, package name, branch, and artifact name. Library downloads only that allowlisted artifact, validates it, aligns it, signs it with the managed-app distribution identity, verifies it, and publishes the final APK as a stable GitHub Release.

The signing allowlist lives in Library rather than the app repository. Compromising an app repository therefore cannot change which Android package Library is willing to sign without a separate change to Library itself.

A source-only repository never appears as installable until it has a signed APK release.

Optional `.library.json` metadata in the app repository customizes storefront presentation and provenance labels. See `.library.example.json` and `docs/RELEASES.md`.

## Trust model

Library keeps authorship, build provenance, distribution, and signing identity distinct.

Trust labels are explicit:

- **Developer signed** — original developer APK, unchanged.
- **Library managed** — unsigned CI artifact from a centrally allowlisted repository, validated and signed by Library's managed-app distribution identity.
- **Binary release** — distributed without a source claim, still hash/signer pinned.

The Library application signing key signs **only Library**. Library-managed apps use a separate distribution key. Managed apps currently share that distribution identity, which is an intentional operational tradeoff and should be limited to packages you control and want under one signer.

## Public and private repositories

Public GitHub releases are discovered anonymously. To index private repositories, configure `LIBRARY_GITHUB_TOKEN` with read-only access to the repositories Library should index.

Library-managed signing requires that token to have `Actions: read`, `Contents: write`, and `Metadata: read` on each repository listed in `config/managed-apps.json` so it can retrieve unsigned CI artifacts and publish signed Releases.

Private downloads on Android require GitHub access in Settings. The token is encrypted with an AES-GCM key held by Android Keystore. Library sends authorization only to `api.github.com` and does not forward it to GitHub's release CDN redirects.

## CI and releases

### Android CI

Every push and pull request validates helper scripts and the catalog, runs Android lint/tests, builds a debug APK, and uploads it as an Actions artifact.

### Managed APK signing

Hourly, and on manual dispatch, Library scans only the repositories in `config/managed-apps.json` for a new successful `library-unsigned-apk` artifact from the pinned branch. It signs and publishes qualifying builds. Signing secrets live only in Library's protected production environment.

### Rolling catalog

Every six hours, and on manual dispatch, the catalog workflow scans GitHub releases and publishes `catalog.json` to a rolling GitHub Release tagged `catalog`.

### Signed Library releases

Library itself uses an entirely separate stable signing key. A newer semantic version on `main` builds a release-signed APK and publishes its APK, checksum, and catalog.

See `docs/RELEASES.md` and `docs/SIGNING.md` for setup and operational details.

## Local build

Requirements:

- JDK 17
- Android SDK platform 36
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
app/                             Android storefront + installer
catalog/apps/                    manual/bootstrap entries
catalog/apps/generated/          generated GitHub-release entries
config/managed-apps.json         central repo/package/branch signing allowlist
scripts/sync_github.py           GitHub release discovery + APK inspection
scripts/manage_unsigned_apks.py  managed unsigned-artifact signing + publishing
scripts/build_catalog.py         aggregate catalog generation
scripts/validate_catalog.py      deterministic validation
.github/workflows/               CI, signing, catalog, and Library releases
docs/                            architecture, signing, release operations
```

## Security boundary

Managed signing is centrally allowlisted, not ambient. Library will not sign arbitrary APKs: each enrolled repository has a package name and branch pinned in this repository, and the signer rejects package mismatches and already-signed artifacts before the managed distribution key is used.
