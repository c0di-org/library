# Library

[![Android CI](https://github.com/garfbargle/library/actions/workflows/android.yml/badge.svg)](https://github.com/garfbargle/library/actions/workflows/android.yml)

**Apps, without the mall.**

Library is a private-first Android app store for software shipped from GitHub. Public and private repositories can live in the same catalog, source links are optional, and every install carries a visible provenance trail.

## Product

- Native Kotlin + Jetpack Compose storefront with Discover, Library, Updates, search, app details, settings, and provenance UI.
- Automatically discovers stable GitHub Releases containing standalone Android APKs.
- Can preserve developer-signed releases unchanged or centrally sign explicitly enrolled unsigned release artifacts.
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

An app repository's release workflow uploads an unsigned release APK as an Actions artifact named `library-unsigned-apk`. For repositories owned by the configured Library source owner, the normal enrollment lives entirely in the app repository's `.library.json`:

```json
{
  "provenance": "library-managed",
  "managedSigning": {
    "packageName": "com.example.myapp",
    "tagPrefix": "android-v"
  }
}
```

`branch` and `artifact` may also be specified under `managedSigning`; they default to the repository's default branch and `library-unsigned-apk`. The webhook reads this declaration from the exact commit that produced the successful workflow artifact, then the protected signing workflow independently reads the same commit again before the distribution key is used.

`config/managed-apps.json` remains supported as a central hard-pinned enrollment/override path. A central entry wins over repository metadata. This is useful for repositories that should not be able to change their own signing package declaration, and for non-Tauri/legacy integrations.

For the Tauri app convention, app repositories use stable Android release tags of the form `android-vX.Y.Z`. Their automatic `main` workflow should emit `library-unsigned-apk` only when the committed stable version is newer than the latest published Android release. Manual CI/check workflows must never use that artifact name.

Library downloads the qualifying unsigned artifact, validates its package and version, aligns it, signs it with the managed-app distribution identity, verifies it, and publishes the final APK as a stable GitHub Release. A source-only repository never appears as installable until it has a signed APK release.

`.library.json` also customizes storefront presentation and provenance labels. See `.library.example.json` and `docs/RELEASES.md`.

## Trust model

Library keeps authorship, build provenance, distribution, and signing identity distinct.

Trust labels are explicit:

- **Developer signed** — original developer APK, unchanged.
- **Library managed** — unsigned release artifact from an enrolled repository, validated and signed by Library's managed-app distribution identity.
- **Binary release** — distributed without a source claim, still hash/signer pinned.

The Library application signing key signs **only Library**. Library-managed apps use a separate distribution key. Managed apps currently share that distribution identity, which is an intentional operational tradeoff and should be limited to repositories you control and want under one signer.

Repository-side enrollment is owner-scoped: the webhook only considers successful runs from the configured source owner, and the protected signer re-resolves `.library.json` from the exact source commit. The declared package still has to match the APK. For a stronger boundary where the app repository must not be able to change its package declaration, use a central `config/managed-apps.json` entry instead; central enrollment takes precedence.

## Public and private repositories

Public GitHub releases are discovered anonymously. To index private repositories, configure `LIBRARY_GITHUB_TOKEN` with read-only access to the repositories Library should index.

Library-managed signing requires the Library GitHub App/token to read Actions artifacts and repository contents for managed repositories, and the protected signing token to publish signed Releases. Repo-side enrollment specifically requires reading `.library.json` from the source commit.

Private downloads on Android require GitHub access in Settings. The token is encrypted with an AES-GCM key held by Android Keystore. Library sends authorization only to `api.github.com` and does not forward it to GitHub's release CDN redirects.

## CI and releases

### Android CI

Android verification is manual-only. Dispatch `.github/workflows/android.yml` when a human or agent needs remote validation; an optional ref can target the exact branch, tag, or SHA being checked. Ordinary pushes and pull requests do not automatically consume Android runners.

### Managed APK signing

Managed signing is event-driven. The GitHub App webhook inspects successful non-PR workflow runs from repositories owned by the configured source owner. It resolves enrollment centrally first and otherwise from `.library.json` at the run's exact commit. When the run is on the enrolled branch and contains the enrolled `library-unsigned-apk` artifact, the webhook dispatches Library's protected managed-signing workflow. The signer independently resolves enrollment again before using signing secrets. The signing workflow also remains manually dispatchable for operations/recovery.

### Rolling catalog

Catalog refresh is event-driven from GitHub release webhooks and remains manually dispatchable. A qualifying release event dispatches `.github/workflows/catalog.yml`, which scans GitHub releases and publishes `catalog.json` to the rolling GitHub Release tagged `catalog`. There is no periodic polling schedule.

### Signed Library releases

Library itself uses an entirely separate stable signing key. A newer semantic version on `main` builds a release-signed APK and publishes its APK, checksum, and catalog. Commits whose tracked version is not newer stop at the release gate rather than rebuilding.

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
config/managed-apps.json         optional central hard-pinned managed enrollment
scripts/resolve_managed_app.py   central/repo-side enrollment resolver
scripts/sync_github.py           GitHub release discovery + APK inspection
scripts/manage_unsigned_apks.py  managed unsigned-artifact signing + publishing
scripts/build_catalog.py         aggregate catalog generation
scripts/validate_catalog.py      deterministic validation
.github/workflows/               manual CI, signing, catalog, and Library releases
infra/catalog-webhook/           GitHub App webhook for release/signing dispatch
docs/                            architecture, signing, release operations
```

## Security boundary

Library does not sign arbitrary workflow output. A managed request must come from the configured source owner, resolve either a central enrollment or a `library-managed` `.library.json` declaration, run on the enrolled branch, and contain the enrolled artifact name. The protected signer repeats enrollment resolution and rejects package mismatches, split APKs, already-signed artifacts, duplicate source artifacts, and non-increasing published `versionCode` values before the managed distribution key is used.
