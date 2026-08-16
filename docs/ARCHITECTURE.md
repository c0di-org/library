# Architecture

Library keeps four identities separate:

1. **Store identity** — the stable release-signing key for the Library Android client.
2. **App identity** — the signing certificate belonging to each Android package.
3. **Build identity** — an isolated builder, only relevant for future Library-built apps.
4. **Distribution identity** — GitHub Releases/catalog infrastructure serving the bytes.

Library never uses one universal signing key for every app.

## Flow

```text
GitHub stable Releases (*.apk)
        │
        ▼
scripts/sync_github.py
        ├─ downloads exact APK
        ├─ aapt2: package/version/sdk/ABI metadata
        ├─ apksigner: signer certificate
        ├─ SHA-256: exact bytes
        └─ optional .library.json metadata
        │
        ▼
catalog/apps/generated/*.json
        │
        ▼
scripts/build_catalog.py
        │
        ├─ catalog/library.json
        └─ app/src/main/assets/catalog.json
        │
        ▼
GitHub Release tag: catalog
        │
        ▼
Android Library client
        ├─ compare installed versionCode
        ├─ choose compatible artifact
        ├─ download over HTTPS
        ├─ verify SHA-256
        ├─ verify package + versionCode
        ├─ verify APK signing certificate
        └─ stage PackageInstaller session
```

## Public and private apps

Public releases work anonymously. For private repositories, CI uses `LIBRARY_GITHUB_TOKEN` with read-only access to the repositories being indexed. On Android, the user can provide a fine-grained GitHub token; Library encrypts it with an AES-GCM key generated inside Android Keystore. Authorization is only sent to `api.github.com` and is deliberately not forwarded to release-CDN redirects.

The bundled catalog lets the app start offline. Live refresh reads the rolling `catalog` GitHub Release.

## Release selection

Discovery intentionally ignores drafts, prereleases, repos with no APK release, and split APK fragments. All accepted APKs in one release must agree on package name, versionCode, and signer. Version metadata comes from the APK manifest rather than tag naming.

## Update safety

Library compares catalog `versionCode` with the installed package. Before staging an update it checks the installed signer against the pinned signer, then verifies the downloaded SHA-256, package ID, versionCode, and certificate. Android performs its own signature/update verification again when the PackageInstaller session commits.

## Future source builds

A future build service should use:

```text
source ref -> isolated builder -> unsigned digest -> signing service -> signed APK
```

The signing service must map one Android package to one dedicated key. Build workers must never receive private signing keys.
