# Library

[![Android CI](https://github.com/garfbargle/library/actions/workflows/android.yml/badge.svg)](https://github.com/garfbargle/library/actions/workflows/android.yml)

**Apps, without the mall.**

Android app store for APKs shipped through GitHub Releases.

<p align="center">
  <img src="docs/screenshots/library-list.jpg" width="260" alt="Library app list">
  <img src="docs/screenshots/app-details.jpg" width="260" alt="Library app details">
</p>

## Features

- Public and private GitHub repos
- Install and update APKs
- Search, app details, release notes, and source links
- Verifies package, version, APK SHA-256, and signer
- Optional managed signing for allowlisted CI artifacts

## Add an app

**Signed release:** publish a stable GitHub Release with a standalone signed `.apk`.

**Managed release:** upload an unsigned Actions artifact named `library-unsigned-apk` and add the repo/package/branch to `config/managed-apps.json`.

Optional storefront metadata: `.library.json`

## Build

```bash
python3 scripts/build_catalog.py
./gradlew assembleDebug
```

## Docs

[Releases](docs/RELEASES.md) · [Signing](docs/SIGNING.md) · [Architecture](docs/ARCHITECTURE.md)
